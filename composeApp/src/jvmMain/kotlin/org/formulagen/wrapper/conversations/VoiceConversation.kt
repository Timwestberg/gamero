package org.formulagen.wrapper.conversations

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
import org.formulagen.wrapper.chat.TALK_WHILE_DICTATING
import org.formulagen.wrapper.chat.TalkHost
import org.formulagen.wrapper.chat.TalkLoop
import org.formulagen.wrapper.chat.TalkReply
import org.formulagen.wrapper.chat.TalkSettings
import org.formulagen.wrapper.chat.VoicePickerState
import org.formulagen.wrapper.chat.decideHeard
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.hovel.HovelStatusException
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.HearingResult

/** A heard voice turn's way into its conversation: posted as the owner's message, then claimed at once. */
internal interface HeardTurns {
    /** Post [text] as message [messageId] in [conversationId]. Throws when the server refuses it or cannot be reached. */
    fun send(conversationId: String, messageId: String, text: String)

    /** Claim [conversationId]'s next message now, without waiting for the server's `work.available`. */
    fun claimNow(conversationId: String)

    /** Stop [messageId] as soon as it is claimed, durably: a heard turn given up on before its claim landed. */
    fun abandon(conversationId: String, messageId: String)
}

/** [HeardTurns] through the native `messageSend` route, claimed and stopped by [runner]. */
internal fun heardTurns(api: ConversationApi, runner: ConversationRunner) = object : HeardTurns {
    override fun send(conversationId: String, messageId: String, text: String) {
        api.send(conversationId, MessageSend(messageId, emptyList(), text, null))
    }

    override fun claimNow(conversationId: String) = runner.wake(conversationId)

    override fun abandon(conversationId: String, messageId: String) = runner.stopOnClaim(conversationId, messageId)
}

/** How long a posted heard turn may wait for this Mac to claim it before Talk listens again. */
internal const val HEARD_CLAIM_WAIT_MS = 30_000L

internal const val TALK_NOT_CONNECTED = "Connect this Mac to your Hovel account to talk in its conversations."
internal const val TALK_NOT_THIS_MAC = "Talk works in conversations whose project runs on this Mac."
internal const val TALK_NOT_SENT = "What you said could not be sent. Try again in a moment."
internal const val TALK_QUEUED = "What you said is waiting in the conversation. It runs when this Mac is free."
internal const val TALK_AGENTS_WORKING = "Agents are working on this. Talk listens again when they finish; Stop interrupts them."

/** For the last heard turn, from its endpoint: to the server storing it (null when the claim came first), and to its claim here. */
internal data class HeardLatency(val endpointToPostedMs: Long?, val endpointToClaimedMs: Long)

/**
 * Voice in one conversation on this Mac ([conversationId]): Talk with its coordinator, dictation into the page's
 * draft, and the coordinator's replies read aloud. It hosts a [TalkLoop]:
 *
 * - **A heard turn** is posted off the lock as the owner's message through [turns], and claimed at once.
 * - **The reply in progress** is the conversation's claimed main message ([forClaim]); its coordinator replies are
 *   spoken as they stream when it was heard, Talk is on, or the conversation's `speak` is on, and never while the
 *   page dictates. A thread reply's claim is not a reply in progress here.
 * - **Barge-in** (VAD, a press or Escape) flushes the speaker. It also stops the message, as Stop does, only while
 *   that message is this Talk session's own heard turn and has not delegated; once a thread has started, the run
 *   continues and only that reply stays silent. A heard turn not claimed yet is abandoned: [abandon] stops it on
 *   arrival, durably.
 * - **Ending Talk**, a microphone failure included, flushes the speaker, abandons an unclaimed heard turn and
 *   silences the rest of the run. It never stops a claimed message.
 * - **While agents work** on a run Talk is waiting for, Talk says so ([TALK_AGENTS_WORKING]): the microphone stays
 *   closed (half duplex) or what is heard is not sent (full duplex) until the run ends.
 * - **The page's actions**, in `ember-hearing.v1`'s words: `handsFree` arms Talk (or interrupts a reply when it is on);
 *   `press` interrupts a reply and never arms (Escape); `release` quiets the speaker and nothing else (Speak replies
 *   off), leaving Talk and dictation on; `off` ends Talk, and with Talk off only quiets; `mute` and `unmute` hold it.
 *
 * Threading: [gate] is the loop's lock. Posting runs on [worker]; a stop runs where it is decided.
 */
internal class VoiceConversation(
    val conversationId: String,
    private val speech: ChatSpeech,
    /** This conversation's own share of the Mac's microphone. */
    hearing: Hearing?,
    settings: TalkSettings,
    private val timer: ScheduledExecutorService,
    /** Where heard turns go; null while this Mac is not connected to the account. */
    private val turns: () -> HeardTurns?,
    private val worker: Executor,
    /** Give up on a heard turn not claimed yet: it must never run. Called under the lock; must not block on it. */
    private val abandon: (messageId: String) -> Unit,
    /** Something this conversation's voice shows changed; called under its lock. */
    private val onChange: (VoiceConversation) -> Unit,
    private val claimWaitMs: Long = HEARD_CLAIM_WAIT_MS,
    private val now: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : TalkHost {
    private val gate = Any()
    internal val talk = TalkLoop(gate, this, speech, hearing, settings, timer, CompanionLimits.MESSAGE, rowEndsPageTalk = true, now = now)
    /** The heard turn on its way: posted or posting, and not claimed yet. */
    private var pending: Pending? = null
    /**
     * Heard turns stored but not claimed in time, which Talk told the person are waiting: visible messages now, so
     * nothing here gives them up (Stop on the page still does). Talk's own when their claim lands in their session.
     */
    private val overdue = LinkedHashMap<String, Pending>()
    /** The conversation's main message running on this Mac. */
    private var running: Running? = null
    /** Each Talk session's number; a heard turn belongs to the session it was heard in. */
    private var session = 0
    private var lastReply = ""
    private var closed = false

    /** Stage times of the last heard turn. */
    @Volatile var heardLatency: HeardLatency? = null
        private set

    private class Pending(val messageId: String, val endpointNs: Long, val session: Int) {
        var postedNs = 0L
        /** Barge-in or the end of Talk gave up on it: no more posting, and it stops when claimed. */
        @Volatile var abandoned = false
        /** Its claim landed. */
        var claimed = false
    }

    /** One claimed message: whether its replies are spoken, whether it is Talk's own turn, and the reply being spoken. */
    private inner class Running(val messageId: String, var speak: Boolean, val own: Boolean, val stop: () -> Unit) {
        /** A thread has started: the run is the agents' work now, which a barge-in never stops. */
        var delegated = false
        private var replyId: String? = null
        private var spoken: TalkLoop.SpokenReply? = null
        /** The reply a barge-in silenced: the rest of it is not said. */
        private var silenced: String? = null
        private val text = StringBuilder()
        /** A reply was already spoken in this run, or a typed one may cut earlier audio: the next reply starts fresh. */
        private var cut = !own

        fun deltaLocked(id: String, piece: String) {
            if (id != replyId) beginLocked(id)
            text.append(piece)
            lastReply = text.toString()
            quietWhileDictatingLocked()
            spoken?.growLocked(lastReply)
        }

        fun appendedLocked(id: String, whole: String) {
            lastReply = whole
            if (id != replyId) beginLocked(id)
            quietWhileDictatingLocked()
            talk.replyArrivedLocked()
            spoken?.finishLocked(whole)
            replyId = null
            spoken = null
            text.clear()
        }

        /** The microphone is taking dictation, so this reply is not read aloud from here on. */
        private fun quietWhileDictatingLocked() {
            if (talk.isDictating && spoken != null) spoken = null
        }

        /** Barge-in: the reply being said goes quiet; a later reply in the run is spoken as usual. */
        fun silenceLocked() {
            silenced = replyId
            spoken = null
        }

        /** Talk ended: nothing more of this run is said. */
        fun muteLocked() {
            speak = false
            spoken = null
        }

        /** The run ended mid-reply: what was not said is not said. */
        fun endedLocked() {
            if (spoken != null) speech.stop()
            spoken = null
        }

        private fun beginLocked(id: String) {
            replyId = id
            text.clear()
            if (!speak || talk.isDictating || id == silenced) return
            if (cut) speech.stop()
            cut = true
            spoken = talk.spokenReplyLocked()
        }
    }

    // ---- The page ------------------------------------------------------------------------------

    /** The page's Talk action, with the meanings in the class doc: only `handsFree` ever arms Talk. */
    suspend fun hearing(action: HearingAction): ConversationVoiceStatus {
        val start = synchronized(gate) {
            when (action) {
                HearingAction.HANDS_FREE -> when {
                    talk.isDictating -> { talk.sayLocked(TALK_WHILE_DICTATING); false }
                    // Hands-free while Talk is on interrupts the reply, connected or not.
                    !talk.on && turns() == null -> { talk.sayLocked(TALK_NOT_CONNECTED); false }
                    else -> { talk.pageOwnsLocked(); talk.pressLocked() }
                }
                HearingAction.PRESS -> { talk.interruptLocked(); false }
                HearingAction.RELEASE -> { quietLocked(); false }
                HearingAction.OFF -> { if (talk.on) talk.endLocked() else quietLocked(); false }
                HearingAction.MUTE -> { talk.muteLocked(); false }
                HearingAction.UNMUTE -> { talk.unmuteLocked(); false }
            }
        }
        if (start) talk.startCapture()
        return status()
    }

    /** The page's dictation action: the host microphone fills its draft, and stop answers the text heard. */
    suspend fun dictation(action: DictationAction): ConversationVoiceStatus {
        val step = synchronized(gate) {
            talk.pageOwnsLocked()
            talk.dictateLocked(action)
        }
        return when (step) {
            DictationStep.Ready -> status()
            DictationStep.Start -> { talk.startDictation(); status() }
            is DictationStep.Finish -> {
                val heard = talk.awaitDictation()
                synchronized(gate) {
                    val text = talk.dictationEndedLocked(heard, step.keep)
                    val status = statusLocked()
                    if (step.keep) status.copy(dictation = ConversationDictation(DictationState.DONE, text = text)) else status
                }
            }
        }
    }

    /** What the microphone and speaker are doing for this conversation. */
    fun status(): ConversationVoiceStatus = synchronized(gate) { statusLocked() }

    private fun statusLocked(): ConversationVoiceStatus {
        val hearing = talk.hearingLocked()
        val dictation = talk.dictationLocked()
        return ConversationVoiceStatus(conversationId, ConversationHearing(hearing.state, hearing.partial, hearing.notice), speech.status(),
            dictation?.let { ConversationDictation(it.state, partial = it.partial, notice = it.notice) })
    }

    /** The native voice row for this conversation, shown in every product. */
    fun pickerLocked(product: String): VoicePickerState = talk.pickerLocked(open = !closed, product = product, owned = true)

    /** Talk, dictation, a heard turn or a reply is in progress, or the speaker is still saying one. */
    val busy: Boolean get() = synchronized(gate) {
        val voice = speech.status().state
        talk.on || talk.isDictating || pending != null || running != null || voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING
    }

    /** Talk is on, or the page dictates: only the page that started it may hand the microphone on. */
    val engaged: Boolean get() = synchronized(gate) { talk.on || talk.isDictating }

    // ---- The runner ----------------------------------------------------------------------------

    /**
     * A message of this conversation was claimed on this Mac. A main message is the reply in progress until it ends;
     * a thread reply is not. Answers who hears its replies.
     */
    fun forClaim(claim: ClaimResult, stop: () -> Unit): ReplyListener? = synchronized(gate) {
        if (closed || claim.message.threadId != null) return null
        val messageId = claim.message.id
        val heard = pending?.takeIf { it.messageId == messageId }?.also { pending = null } ?: overdue.remove(messageId)
        if (heard != null) {
            heard.claimed = true
            val claimedMs = (now() - heard.endpointNs) / 1_000_000
            val postedMs = heard.postedNs.takeIf { it != 0L }?.let { (it - heard.endpointNs) / 1_000_000 }
            heardLatency = HeardLatency(postedMs, claimedMs)
            log(LogLevel.Info, "hearing", "latency endpoint_to_claimed_ms=$claimedMs")
        }
        val own = heard != null && heard.session == session
        val run = Running(messageId, own || talk.on || claim.conversation.speak, own, stop)
        running = run
        changedLocked()
        listener(run)
    }

    /** [run]'s replies, applied only while it is still the reply in progress. */
    private fun listener(run: Running) = object : ReplyListener {
        override fun delta(replyId: String, text: String) = synchronized(gate) {
            if (running !== run) return@synchronized
            run.deltaLocked(replyId, text)
            changedLocked()
        }

        override fun appended(replyId: String, text: String) = synchronized(gate) {
            if (running !== run) return@synchronized
            run.appendedLocked(replyId, text)
            changedLocked()
        }

        override fun threadStarted() = synchronized(gate) {
            if (run.delegated) return@synchronized
            run.delegated = true
            if (running === run && talk.on) talk.sayLocked(TALK_AGENTS_WORKING)
        }

        override fun ended() = synchronized(gate) {
            if (running !== run) return@synchronized
            running = null
            run.endedLocked()
            talk.unsayLocked(TALK_AGENTS_WORKING)
            changedLocked()
        }
    }

    // ---- TalkHost --------------------------------------------------------------------------------

    override val open: Boolean get() = !closed

    override val reply: TalkReply get() = if (pending != null || running != null) TalkReply.RESPONDING else TalkReply.IDLE

    /** Post the heard text as the owner's message, off the lock; until it is claimed, it is the reply in progress. */
    override fun sendHeardLocked(result: HearingResult): HearingCommit {
        val decision = decideHeard(result, reply, CompanionLimits.MESSAGE)
        if (decision !is HearingCommit.Send) return decision
        val link = turns() ?: return HearingCommit.Notice(TALK_NOT_CONNECTED)
        val turn = Pending(UUID.randomUUID().toString(), now(), session)
        if (runCatching { worker.execute { post(link, turn, decision.text) } }.isFailure) return HearingCommit.Notice(TALK_NOT_SENT)
        pending = turn
        return decision
    }

    /**
     * Barge-in. The speaker is already flushed. Talk's own heard turn, before it delegates, stops as Stop stops it; a
     * delegated run continues with this reply silent, and Talk says the agents are working. With no run, a heard turn
     * not claimed yet is abandoned; a newer one waiting behind a run is left alone.
     */
    override fun interruptLocked() {
        val run = running
        when {
            run == null -> pending?.let(::abandonLocked)
            run.own && !run.delegated -> {
                running = null
                run.endedLocked()
                run.stop()
            }
            else -> {
                run.silenceLocked()
                if (run.delegated) talk.sayLocked(TALK_AGENTS_WORKING)
            }
        }
    }

    /** Talk ended: flush, abandon a heard turn not claimed yet, and say nothing more of the run, which goes on. */
    override fun endReplyLocked(): Boolean {
        session++
        val busy = pending != null || running != null
        speech.stop()
        releaseHeardTurnsLocked()
        running?.muteLocked()
        if (busy) changedLocked()
        return busy
    }

    /** Speak replies off: the speaker stops now and nothing more of the run is said. Talk and dictation go on. */
    private fun quietLocked() {
        speech.stop()
        running?.muteLocked()
        changedLocked()
    }

    /**
     * Give up on the heard turn not claimed yet: it stops on arrival, durably while this Mac's link is up. A turn Talk
     * said is waiting ([overdue]) is the person's message in the conversation now, and runs.
     */
    private fun releaseHeardTurnsLocked() {
        pending?.let(::abandonLocked)
    }

    /** This Mac is leaving the account (or quitting): a heard turn not claimed yet is given up while the link can journal it. */
    fun releaseHeardTurns() = synchronized(gate) { releaseHeardTurnsLocked() }

    override fun lastReplyLocked(): String = lastReply

    override fun changedLocked() {
        talk.observeLocked()
        onChange(this)
    }

    /** Publish this conversation's voice again, from outside its lock: the speaker's state or voice changed. */
    fun refresh() = synchronized(gate) { if (!closed) changedLocked() }

    /** Let go of the microphone and stop speaking for this conversation; its messages keep running. */
    fun close() = synchronized(gate) {
        if (closed) return@synchronized
        talk.resetLocked()
        closed = true
        releaseHeardTurnsLocked()
        running = null
        speech.stop()
        onChange(this)
    }

    private fun abandonLocked(turn: Pending) {
        if (pending === turn) pending = null
        turn.abandoned = true
        abandon(turn.messageId)
        log(LogLevel.Info, "hearing", "heard turn abandoned before its claim")
    }

    /** Post [text] for [turn], trying an unreachable server twice more with the same id, unless it is given up on. */
    private fun post(link: HeardTurns, turn: Pending, text: String) {
        var wait = 200L
        for (attempt in 1..POST_ATTEMPTS) {
            if (turn.abandoned) return
            try {
                link.send(conversationId, turn.messageId, text)
                posted(turn)
                link.claimNow(conversationId)
                return
            } catch (refused: HovelRejectedException) {
                return failed(turn, TALK_NOT_SENT, refused)
            } catch (final: HovelStatusException) {
                val elsewhere = final.status == 403 || final.status == 404
                if (final.final) return failed(turn, if (elsewhere) TALK_NOT_THIS_MAC else TALK_NOT_SENT, final)
                if (attempt == POST_ATTEMPTS) return failed(turn, TALK_NOT_SENT, final)
            } catch (error: Exception) {
                if (attempt == POST_ATTEMPTS) return failed(turn, TALK_NOT_SENT, error)
            }
            try { sleep(wait) } catch (_: InterruptedException) { return failed(turn, TALK_NOT_SENT, null) }
            wait *= 3
        }
    }

    /** The server stored [turn]: from now on, a claim that does not come lets Talk listen again. */
    private fun posted(turn: Pending) = synchronized(gate) {
        turn.postedNs = now()
        log(LogLevel.Info, "hearing", "latency endpoint_to_posted_ms=${(turn.postedNs - turn.endpointNs) / 1_000_000}")
        if (pending === turn) runCatching { timer.schedule({ claimOverdue(turn) }, claimWaitMs, TimeUnit.MILLISECONDS) }
    }

    /** [turn] never reached the conversation: Talk says why and listens again. */
    private fun failed(turn: Pending, notice: String, error: Exception?) = synchronized(gate) {
        log(LogLevel.Warn, "hearing", "A heard turn was not sent", error)
        if (turn.abandoned || turn.claimed || closed) return@synchronized
        if (pending === turn) pending = null
        talk.sayLocked(notice)
    }

    /**
     * [turn] was stored but not claimed in time (this Mac is busy): Talk listens again, and the turn runs when claimed,
     * still Talk's own. The few kept are the newest.
     */
    private fun claimOverdue(turn: Pending) = synchronized(gate) {
        if (pending !== turn) return@synchronized
        pending = null
        overdue[turn.messageId] = turn
        while (overdue.size > OVERDUE_KEPT) overdue.remove(overdue.keys.first())
        talk.sayLocked(TALK_QUEUED)
    }

    private companion object {
        const val POST_ATTEMPTS = 3
        const val OVERDUE_KEPT = 8
    }
}
