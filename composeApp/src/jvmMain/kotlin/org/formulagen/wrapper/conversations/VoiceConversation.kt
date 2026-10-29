package org.formulagen.wrapper.conversations

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.bridge.ConversationDictation
import org.formulagen.wrapper.bridge.ConversationHearing
import org.formulagen.wrapper.bridge.ConversationVoiceStatus
import org.formulagen.wrapper.bridge.DictationAction
import org.formulagen.wrapper.bridge.DictationState
import org.formulagen.wrapper.bridge.HearingAction
import org.formulagen.wrapper.bridge.VoiceState
import org.formulagen.wrapper.chat.ChatSpeech
import org.formulagen.wrapper.chat.DictationStep
import org.formulagen.wrapper.chat.Hearing
import org.formulagen.wrapper.chat.HearingCommit
import org.formulagen.wrapper.chat.HearingRoute
import org.formulagen.wrapper.chat.TALK_WHILE_DICTATING
import org.formulagen.wrapper.chat.TalkHost
import org.formulagen.wrapper.chat.TalkLoop
import org.formulagen.wrapper.chat.TalkReply
import org.formulagen.wrapper.chat.VoicePickerState
import org.formulagen.wrapper.chat.decideHeard
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.hovel.HovelStatusException
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.DuplexChoice
import org.formulagen.wrapper.voice.FillerSettings
import org.formulagen.wrapper.voice.HearingResult

/** A heard voice turn's way into its conversation: posted as the owner's message, then claimed at once. */
internal interface HeardTurns {
    /** Post [text] as message [messageId] in [conversationId]. Throws when the server refuses it or cannot be reached. */
    fun send(conversationId: String, messageId: String, text: String)

    /** Claim [conversationId]'s next message now, without waiting for the server's `work.available`. */
    fun claimNow(conversationId: String)
}

/** [HeardTurns] through the native `messageSend` route, claimed by [runner] at once. */
internal fun heardTurns(api: ConversationApi, runner: ConversationRunner) = object : HeardTurns {
    override fun send(conversationId: String, messageId: String, text: String) {
        api.send(conversationId, MessageSend(messageId, emptyList(), text, null))
    }

    override fun claimNow(conversationId: String) = runner.wake(conversationId)
}

/** How long a sent heard turn may wait for this Mac to claim it before Talk listens again. */
internal const val HEARD_CLAIM_WAIT_MS = 30_000L

internal const val TALK_NOT_CONNECTED = "Connect this Mac to your Hovel account to talk in its conversations."
internal const val TALK_NOT_THIS_MAC = "Talk works in conversations whose project runs on this Mac."
internal const val TALK_NOT_SENT = "What you said could not be sent. Try again in a moment."
internal const val TALK_QUEUED = "What you said is waiting in the conversation. It runs when this Mac is free."

/** For the last heard turn, from its endpoint: to the server storing it (null when the claim came first), and to its claim here. */
internal data class HeardLatency(val endpointToPostedMs: Long?, val endpointToClaimedMs: Long)

/**
 * Voice in one conversation on this Mac ([conversationId]): Talk with its coordinator, dictation into the
 * page's draft, and the coordinator's replies read aloud. It hosts a [TalkLoop]: a heard turn is posted as
 * the owner's message through [turns] and claimed at once; each claimed message of the conversation is a
 * reply in progress ([forClaim]) whose main-conversation replies are spoken as they stream, when it was
 * heard, Talk is on, or the conversation's `speak` is on, and never while the page dictates. Barge-in and
 * ending Talk stop that message as the person's Stop does. Posting and stopping run on [worker], never
 * under the lock.
 */
internal class VoiceConversation(
    val conversationId: String,
    private val speech: ChatSpeech,
    hearing: Hearing?,
    route: HearingRoute?,
    duplex: DuplexChoice,
    private val timer: ScheduledExecutorService,
    fillers: FillerSettings,
    fillerFile: Path?,
    silenceMs: Int,
    guardMs: Int,
    bargeMs: Int,
    /** Where heard turns go; null while this Mac is not connected to the account. */
    private val turns: () -> HeardTurns?,
    private val worker: Executor,
    /** Something this conversation's voice shows changed; called under its lock. */
    private val onChange: (VoiceConversation) -> Unit,
    private val claimWaitMs: Long = HEARD_CLAIM_WAIT_MS,
) : TalkHost {
    private val gate = Any()
    internal val talk = TalkLoop(gate, this, speech, hearing, route, duplex, timer, fillers, fillerFile, silenceMs, guardMs, bargeMs,
        CompanionLimits.MESSAGE)
    /** The heard turn on its way: posted or posting, and not claimed yet. */
    private var pending: Pending? = null
    /** The conversation's message running on this Mac. */
    private var running: Running? = null
    /** Heard turns barge-in abandoned before they were claimed: each stops as soon as its claim lands. */
    private val abandoned = mutableSetOf<String>()
    private var lastReply = ""
    private var closed = false

    /** Stage times of the last heard turn. */
    @Volatile var heardLatency: HeardLatency? = null
        private set

    private class Pending(val messageId: String, val endpointNs: Long, var postedNs: Long = 0L)

    /** One claimed message: whether its replies are spoken, how to stop it, and the reply being spoken. */
    private inner class Running(val messageId: String, val speak: Boolean, val heard: Boolean, val stop: () -> Unit) {
        private var replyId: String? = null
        private var spoken: TalkLoop.SpokenReply? = null
        private val text = StringBuilder()
        /** A reply was already spoken in this run, or a typed one may cut earlier audio: the next reply starts fresh. */
        private var cut = !heard

        fun delta(id: String, piece: String) {
            if (id != replyId) begin(id)
            text.append(piece)
            lastReply = text.toString()
            silenceWhileDictating()
            spoken?.grow(lastReply)
        }

        fun appended(id: String, whole: String) {
            lastReply = whole
            if (id != replyId) begin(id)
            silenceWhileDictating()
            talk.replyArrived()
            spoken?.finish(whole)
            replyId = null
            spoken = null
            text.clear()
        }

        /** The microphone is taking dictation, so this reply is not read aloud from here on. */
        private fun silenceWhileDictating() {
            if (talk.isDictating && spoken != null) spoken = null
        }

        /** The run ended mid-reply: what was not said is not said. */
        fun ended() {
            if (spoken != null) speech.stop()
            spoken = null
        }

        private fun begin(id: String) {
            replyId = id
            text.clear()
            if (!speak || talk.isDictating) return
            if (cut) speech.stop()
            cut = true
            spoken = talk.spokenReply()
        }
    }

    // ---- The page ------------------------------------------------------------------------------

    /** The page's Talk action. Press and hands-free arm Talk (or interrupt a reply); release and off end it. */
    suspend fun hearing(action: HearingAction): ConversationVoiceStatus {
        val start = synchronized(gate) {
            talk.pageOwns()
            when (action) {
                HearingAction.PRESS, HearingAction.HANDS_FREE -> when {
                    talk.isDictating -> { talk.say(TALK_WHILE_DICTATING); false }
                    // A press while Talk is on interrupts the reply, connected or not.
                    !talk.on && turns() == null -> { talk.say(TALK_NOT_CONNECTED); false }
                    else -> talk.press()
                }
                HearingAction.RELEASE, HearingAction.OFF -> { talk.end(); false }
                HearingAction.MUTE -> { talk.mute(); false }
                HearingAction.UNMUTE -> { talk.unmute(); false }
            }
        }
        if (start) talk.startCapture()
        return status()
    }

    /** The page's dictation action: the host microphone fills its draft, and stop answers the text heard. */
    suspend fun dictation(action: DictationAction): ConversationVoiceStatus {
        val step = synchronized(gate) {
            talk.pageOwns()
            talk.dictate(action)
        }
        return when (step) {
            DictationStep.Ready -> status()
            DictationStep.Start -> { talk.startDictation(); status() }
            is DictationStep.Finish -> {
                val heard = talk.awaitDictation()
                synchronized(gate) {
                    val text = talk.dictationEnded(heard, step.keep)
                    val status = statusLocked()
                    if (step.keep) status.copy(dictation = ConversationDictation(DictationState.DONE, text = text)) else status
                }
            }
        }
    }

    /** What the microphone and speaker are doing for this conversation. */
    fun status(): ConversationVoiceStatus = synchronized(gate) { statusLocked() }

    private fun statusLocked(): ConversationVoiceStatus {
        val hearing = talk.hearing()
        val dictation = talk.dictation()
        return ConversationVoiceStatus(conversationId, ConversationHearing(hearing.state, hearing.partial, hearing.notice), speech.status(),
            dictation?.let { ConversationDictation(it.state, partial = it.partial, notice = it.notice) })
    }

    /** The native voice row for this conversation, shown in every product. */
    fun picker(product: String): VoicePickerState = synchronized(gate) { talk.picker(open = !closed, product = product, owned = true) }

    /** Talk, dictation, a heard turn or a reply is in progress, or the speaker is still saying one. */
    val busy: Boolean get() = synchronized(gate) {
        val voice = speech.status().state
        talk.on || talk.isDictating || pending != null || running != null || voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING
    }

    /** Talk is on, or the page dictates: only the page that started it may hand the microphone on. */
    val engaged: Boolean get() = synchronized(gate) { talk.on || talk.isDictating }

    // ---- The runner ----------------------------------------------------------------------------

    /**
     * A message of this conversation was claimed on this Mac: it is the reply in progress until it ends. A heard
     * turn barge-in abandoned is stopped at once instead. Answers who hears its replies.
     */
    fun forClaim(claim: ClaimResult, stop: () -> Unit): ReplyListener? = synchronized(gate) {
        if (closed) return null
        val messageId = claim.message.id
        if (abandoned.remove(messageId)) {
            log(LogLevel.Info, "hearing", "abandoned heard turn stopped on claim")
            stop()
            return null
        }
        val heard = pending?.takeIf { it.messageId == messageId }
        if (heard != null) {
            pending = null
            val claimedMs = (System.nanoTime() - heard.endpointNs) / 1_000_000
            val postedMs = heard.postedNs.takeIf { it != 0L }?.let { (it - heard.endpointNs) / 1_000_000 }
            heardLatency = HeardLatency(postedMs, claimedMs)
            log(LogLevel.Info, "hearing", "latency endpoint_to_claimed_ms=$claimedMs")
        }
        val run = Running(messageId, heard != null || talk.on || claim.conversation.speak, heard != null, stop)
        running = run
        changed()
        listener(run)
    }

    /** [run]'s replies, applied only while it is still the reply in progress. */
    private fun listener(run: Running) = object : ReplyListener {
        override fun delta(replyId: String, text: String) = synchronized(gate) {
            if (running !== run) return@synchronized
            run.delta(replyId, text)
            changed()
        }

        override fun appended(replyId: String, text: String) = synchronized(gate) {
            if (running !== run) return@synchronized
            run.appended(replyId, text)
            changed()
        }

        override fun ended() = synchronized(gate) {
            if (running !== run) return@synchronized
            running = null
            run.ended()
            changed()
        }
    }

    // ---- TalkHost --------------------------------------------------------------------------------

    override val open: Boolean get() = !closed

    override val reply: TalkReply get() = if (pending != null || running != null) TalkReply.RESPONDING else TalkReply.IDLE

    /** Post the heard text as the owner's message, off the lock; until it is claimed, it is the reply in progress. */
    override fun sendHeard(result: HearingResult): HearingCommit {
        val decision = decideHeard(result, reply, CompanionLimits.MESSAGE)
        if (decision !is HearingCommit.Send) return decision
        val link = turns() ?: return HearingCommit.Notice(TALK_NOT_CONNECTED)
        val turn = Pending(UUID.randomUUID().toString(), System.nanoTime())
        if (runCatching { worker.execute { post(link, turn, decision.text) } }.isFailure) return HearingCommit.Notice(TALK_NOT_SENT)
        pending = turn
        runCatching { timer.schedule({ claimOverdue(turn) }, claimWaitMs, TimeUnit.MILLISECONDS) }
        return decision
    }

    /** The reply in progress stops as Stop stops it, and a heard turn not claimed yet is stopped when it is. */
    override fun interrupt() {
        running?.let { run ->
            running = null
            run.ended()
            runCatching { worker.execute { run.stop() } }.onFailure { run.stop() }
        }
        pending?.let { turn ->
            pending = null
            abandoned += turn.messageId
        }
    }

    override fun endReply(): Boolean {
        if (pending == null && running == null) return false
        interrupt()
        changed()
        return true
    }

    override fun lastReply(): String = lastReply

    override fun changed() {
        talk.observe()
        onChange(this)
    }

    /** Publish this conversation's voice again, from outside its lock: the speaker's state or voice changed. */
    fun refresh() = synchronized(gate) { if (!closed) changed() }

    /** Let go of the microphone and stop speaking for this conversation; its messages keep running. */
    fun close() = synchronized(gate) {
        if (closed) return@synchronized
        talk.reset()
        closed = true
        pending = null
        running = null
        speech.stop()
        onChange(this)
    }

    /** Post [text] for [turn], trying an unreachable server twice more. A refusal is final. */
    private fun post(link: HeardTurns, turn: Pending, text: String) {
        var wait = 200L
        repeat(POST_ATTEMPTS) { attempt ->
            try {
                link.send(conversationId, turn.messageId, text)
                synchronized(gate) {
                    turn.postedNs = System.nanoTime()
                    log(LogLevel.Info, "hearing", "latency endpoint_to_posted_ms=${(turn.postedNs - turn.endpointNs) / 1_000_000}")
                }
                link.claimNow(conversationId)
                return
            } catch (refused: HovelRejectedException) {
                return failed(turn, TALK_NOT_SENT, refused)
            } catch (final: HovelStatusException) {
                if (final.final) return failed(turn, if (final.status == 403 || final.status == 404) TALK_NOT_THIS_MAC else TALK_NOT_SENT, final)
                if (attempt == POST_ATTEMPTS - 1) return failed(turn, TALK_NOT_SENT, final)
            } catch (error: Exception) {
                if (attempt == POST_ATTEMPTS - 1) return failed(turn, TALK_NOT_SENT, error)
            }
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return failed(turn, TALK_NOT_SENT, null) }
            wait *= 3
        }
    }

    /** [turn] never reached the conversation: Talk says why and listens again. */
    private fun failed(turn: Pending, notice: String, error: Exception?) = synchronized(gate) {
        log(LogLevel.Warn, "hearing", "A heard turn was not sent", error)
        abandoned -= turn.messageId
        if (pending !== turn) return@synchronized
        pending = null
        talk.say(notice)
    }

    /** [turn] was sent but not claimed in time (this Mac is busy): Talk listens again, and the turn runs when claimed. */
    private fun claimOverdue(turn: Pending) = synchronized(gate) {
        if (pending !== turn) return@synchronized
        pending = null
        talk.say(TALK_QUEUED)
    }

    private companion object {
        const val POST_ATTEMPTS = 3
    }
}
