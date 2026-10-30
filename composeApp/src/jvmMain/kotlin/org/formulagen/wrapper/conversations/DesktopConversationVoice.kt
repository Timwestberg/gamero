package org.formulagen.wrapper.conversations

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.formulagen.wrapper.bridge.ConversationDictationArgs
import org.formulagen.wrapper.bridge.ConversationHearingArgs
import org.formulagen.wrapper.bridge.ConversationVoiceCapability
import org.formulagen.wrapper.bridge.ConversationVoiceStatus
import org.formulagen.wrapper.bridge.DictationAction
import org.formulagen.wrapper.bridge.HearingAction
import org.formulagen.wrapper.chat.ChatSpeech
import org.formulagen.wrapper.chat.SharedHearing
import org.formulagen.wrapper.chat.TalkSettings
import org.formulagen.wrapper.chat.VoicePickerModel
import org.formulagen.wrapper.chat.VoicePickerState
import org.formulagen.wrapper.chat.loggingDaemonThreads
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/**
 * `conversationVoice.*` on the Mac, named like `DesktopProjects` and `DesktopChat`: the microphone and speaker serve
 * one conversation at a time, its [VoiceConversation], each with its own share of [microphone] and the [settings]
 * team chat shares too.
 *
 * - A page's action names the conversation and moves the voice to it, unless Talk or dictation is on in another,
 *   which keeps it (the answer then names that conversation).
 * - A claimed message of the served conversation is spoken by it; one of another conversation whose `speak` is on
 *   moves the voice there when nothing is in progress, so the first reply keeps the speaker.
 * - A heard turn given up on before its claim never runs: its id is kept here, checked before any routing, and
 *   journaled as a stop on claim ([HeardTurns.abandon]), which holds across a move, the page going away and a restart.
 */
internal class DesktopConversationVoice(
    private val speech: ChatSpeech,
    private val microphone: SharedHearing?,
    private val settings: TalkSettings,
    private val picker: VoicePickerModel?,
    private val product: String,
    /** How long a posted heard turn waits for its claim before Talk listens again. */
    private val claimWaitMs: Long = HEARD_CLAIM_WAIT_MS,
    private val now: () -> Long = System::nanoTime,
    /** Waits between a heard turn's post attempts. */
    private val sleep: (Long) -> Unit = Thread::sleep,
    /** Runs the voice's timers: the resume guard, fillers and the claim wait. */
    private val timer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-conversation-voice-timer")),
) : ConversationVoiceCapability, ConversationVoiceLink, AutoCloseable {
    private val lock = Any()
    @Volatile private var current: VoiceConversation? = null
    @Volatile private var turns: HeardTurns? = null
    @Volatile private var closed = false
    /** Heard turns given up on before their claim, by message id: the newest few; the journal holds every one. */
    private val abandoned = LinkedHashSet<String>()
    private val worker = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-conversation-voice"))

    init {
        speech.onVoice { current?.refresh() }
    }

    /** The conversation the voice serves now. */
    internal val served: VoiceConversation? get() = current

    /**
     * The account's runner started ([turns]) or stopped (null, at sign-out or quit). Before the link goes, the served
     * conversation's heard turn not claimed yet is given up through it, so its stop is journaled. A turn Talk said is
     * waiting runs.
     */
    override fun connected(turns: HeardTurns?) {
        if (turns == null) current?.releaseHeardTurns()
        this.turns = turns
    }

    override fun forClaim(claim: ClaimResult, stop: () -> Unit): ReplyListener? {
        // An abandoned heard turn stops on arrival, whichever conversation the voice serves now.
        if (synchronized(abandoned) { abandoned.remove(claim.message.id) }) {
            stop()
            return null
        }
        // A thread reply is never a reply in progress here, so it never moves the voice.
        if (claim.message.threadId != null) return null
        return synchronized(lock) {
            if (closed) return@synchronized null
            val id = claim.conversation.id
            val voice = current
            when {
                voice?.conversationId == id -> voice.forClaim(claim, stop)
                !claim.conversation.speak || voice?.busy == true -> null
                else -> serveLocked(id).forClaim(claim, stop)
            }
        }
    }

    override suspend fun dictation(args: ConversationDictationArgs): ConversationVoiceStatus {
        val voice = forPage(args.conversationId, starts = args.action == DictationAction.START) ?: return voiceStatus()
        return voice.dictation(args.action)
    }

    override suspend fun hearing(args: ConversationHearingArgs): ConversationVoiceStatus {
        // Only hands-free starts Talk, so only it may move the voice here.
        val voice = forPage(args.conversationId, starts = args.action == HearingAction.HANDS_FREE) ?: return voiceStatus()
        return voice.hearing(args.action)
    }

    override suspend fun voiceStatus(): ConversationVoiceStatus =
        current?.status() ?: ConversationVoiceStatus(null, voice = speech.status())

    /** The voice row's voice choice: the next spoken sentence uses [id], and a short line plays now. */
    internal fun chooseSpokenVoice(id: String) {
        speech.previewVoice(id)
        current?.refresh()
    }

    /** The voice row's Talk icon, for the conversation the voice serves; it also ends a session the page started. */
    internal fun converseClick() { current?.talk?.click() }

    internal fun toggleFillers() { current?.talk?.toggleFillers() }

    /** Escape from the native window: interrupts or ends Talk in the served conversation. False when Talk is off. */
    internal fun escape(): Boolean = current?.talk?.escape() == true

    /**
     * The page's document context ended: the voice lets go of the microphone and stops speaking, and serves no
     * conversation until a page asks again. The conversation's messages keep running.
     */
    fun pageEnded() = synchronized(lock) {
        current?.close()
        current = null
        picker?.update(VoicePickerState.Hidden)
    }

    /**
     * The voice for [conversationId], moved there when nothing holds it elsewhere. Null when Talk or dictation is
     * on in another conversation and [starts], or when an action that only ends something names another conversation.
     */
    private fun forPage(conversationId: String, starts: Boolean): VoiceConversation? = synchronized(lock) {
        check(!closed)
        val voice = current
        when {
            voice?.conversationId == conversationId -> voice
            !starts || voice?.engaged == true -> null
            else -> serveLocked(conversationId)
        }
    }

    /** Serve [conversationId] from now on; the voice it served lets go. Kokoro warms when Talk starts, not here. */
    private fun serveLocked(conversationId: String): VoiceConversation {
        current?.close()
        val voice = VoiceConversation(conversationId, speech, microphone?.user(), settings, timer, { turns }, worker,
            abandon = { messageId -> abandon(conversationId, messageId) }, onChange = ::publish, claimWaitMs = claimWaitMs,
            now = now, sleep = sleep)
        current = voice
        voice.refresh()
        return voice
    }

    /** Give up on heard turn [messageId] of [conversationId]: it stops on arrival, here and in the journal. */
    private fun abandon(conversationId: String, messageId: String) {
        synchronized(abandoned) {
            abandoned += messageId
            while (abandoned.size > ABANDONED_KEPT) abandoned.remove(abandoned.first())
        }
        val link = turns
        if (link == null) {
            log(LogLevel.Warn, "hearing", "A heard turn given up with no account link is stopped on this run only")
            return
        }
        runCatching { link.abandon(conversationId, messageId) }
            .onFailure { log(LogLevel.Warn, "hearing", "A heard turn's stop on claim was not journaled", it) }
    }

    /** The voice row shows the conversation the voice serves; any other is hidden. Called under [voice]'s lock. */
    private fun publish(voice: VoiceConversation) {
        val model = picker ?: return
        if (current !== voice) return
        model.update(if (closed) VoicePickerState.Hidden else voice.pickerLocked(product))
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            current?.close()
            current = null
            picker?.update(VoicePickerState.Hidden)
        }
        timer.shutdownNow()
        worker.shutdownNow()
        speech.close()
    }

    private companion object {
        /** In-memory abandons kept; one older than these still stops from the journal. */
        const val ABANDONED_KEPT = 64
    }
}
