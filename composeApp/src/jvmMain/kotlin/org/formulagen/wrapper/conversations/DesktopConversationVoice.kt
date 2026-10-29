package org.formulagen.wrapper.conversations

import java.nio.file.Path
import java.util.concurrent.Executors
import org.formulagen.wrapper.bridge.ConversationDictationArgs
import org.formulagen.wrapper.bridge.ConversationHearingArgs
import org.formulagen.wrapper.bridge.ConversationVoiceCapability
import org.formulagen.wrapper.bridge.ConversationVoiceStatus
import org.formulagen.wrapper.bridge.DictationAction
import org.formulagen.wrapper.bridge.HearingAction
import org.formulagen.wrapper.chat.ChatSpeech
import org.formulagen.wrapper.chat.Hearing
import org.formulagen.wrapper.chat.HearingRoute
import org.formulagen.wrapper.chat.VoicePickerModel
import org.formulagen.wrapper.chat.VoicePickerState
import org.formulagen.wrapper.chat.loggingDaemonThreads
import org.formulagen.wrapper.voice.DuplexChoice
import org.formulagen.wrapper.voice.FillerSettings

/** The settings every conversation's voice shares on this Mac. */
internal class ConversationVoiceSettings(
    val duplex: DuplexChoice,
    val fillers: FillerSettings,
    val fillerFile: Path?,
    val silenceMs: Int,
    val guardMs: Int,
    val bargeMs: Int,
)

/**
 * `conversationVoice.*` on the Mac: the microphone and speaker serve one conversation at a time, its
 * [VoiceConversation]. A page's action names the conversation and moves the voice to it, unless Talk or
 * dictation is on in another, which keeps it (the answer then names that conversation). A claimed
 * message of the served conversation is spoken by it; one of another conversation whose `speak` is on
 * moves the voice there when nothing is in progress, so the first reply keeps the speaker. [hearing] is
 * the conversations' share of the Mac's microphone, which team chat shares too.
 */
internal class DesktopConversationVoice(
    private val speech: ChatSpeech,
    private val hearing: Hearing?,
    private val route: HearingRoute?,
    private val settings: ConversationVoiceSettings,
    private val picker: VoicePickerModel?,
    private val product: String,
) : ConversationVoiceCapability, ConversationVoiceLink, AutoCloseable {
    private val lock = Any()
    @Volatile private var current: VoiceConversation? = null
    @Volatile private var turns: HeardTurns? = null
    @Volatile private var closed = false
    private val timer = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-conversation-voice-timer"))
    private val worker = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-conversation-voice"))

    init {
        speech.onVoice { current?.refresh() }
    }

    /** The conversation the voice serves now. */
    internal val served: VoiceConversation? get() = current

    override fun connected(turns: HeardTurns?) {
        this.turns = turns
    }

    override fun forClaim(claim: ClaimResult, stop: () -> Unit): ReplyListener? = synchronized(lock) {
        if (closed) return null
        val id = claim.conversation.id
        val voice = current
        if (voice?.conversationId == id) return voice.forClaim(claim, stop)
        if (!claim.conversation.speak || voice?.busy == true) return null
        serve(id).forClaim(claim, stop)
    }

    override suspend fun dictation(args: ConversationDictationArgs): ConversationVoiceStatus {
        val voice = forPage(args.conversationId, starts = args.action == DictationAction.START) ?: return voiceStatus()
        return voice.dictation(args.action)
    }

    override suspend fun hearing(args: ConversationHearingArgs): ConversationVoiceStatus {
        val starts = args.action == HearingAction.PRESS || args.action == HearingAction.HANDS_FREE
        val voice = forPage(args.conversationId, starts) ?: return voiceStatus()
        return voice.hearing(args.action)
    }

    override suspend fun voiceStatus(): ConversationVoiceStatus = current?.status() ?: ConversationVoiceStatus(null, voice = speech.status())

    /** The voice row's voice choice: the next spoken sentence uses [id], and a short line plays now. */
    internal fun chooseSpokenVoice(id: String) {
        speech.previewVoice(id)
        current?.refresh()
    }

    /** The voice row's Talk icon, for the conversation the voice serves. */
    internal fun converseClick() { current?.talk?.click() }

    internal fun toggleFillers() { current?.talk?.toggleFillers() }

    /** Escape: interrupts or ends Talk in the served conversation. False when Talk is off, so the page keeps the key. */
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
            else -> serve(conversationId)
        }
    }

    /** Serve [conversationId] from now on; the voice it served lets go. Holds [lock]. */
    private fun serve(conversationId: String): VoiceConversation {
        current?.close()
        val voice = VoiceConversation(conversationId, speech, hearing, route, settings.duplex, timer, settings.fillers, settings.fillerFile,
            settings.silenceMs, settings.guardMs, settings.bargeMs, { turns }, worker, ::publish)
        current = voice
        voice.talk.warm()
        voice.refresh()
        return voice
    }

    /** The voice row shows the conversation the voice serves; any other is hidden. */
    private fun publish(voice: VoiceConversation) {
        val model = picker ?: return
        if (current !== voice) return
        model.update(if (closed) VoicePickerState.Hidden else voice.picker(product))
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
        hearing?.close()
        speech.close()
    }
}
