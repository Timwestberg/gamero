package org.formulagen.wrapper.conversation.voice

import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.conversation.ChatLimits
import org.formulagen.wrapper.conversation.DictationAction
import org.formulagen.wrapper.conversation.DictationState
import org.formulagen.wrapper.conversation.HearingState
import org.formulagen.wrapper.conversation.VoiceState
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.BargeInGuard
import org.formulagen.wrapper.voice.DuplexChoice
import org.formulagen.wrapper.voice.FILLER_LINES
import org.formulagen.wrapper.voice.FillerRotation
import org.formulagen.wrapper.voice.FillerSettings
import org.formulagen.wrapper.voice.FillerTurn
import org.formulagen.wrapper.voice.HEARING_BARGE_SUSTAIN_MS
import org.formulagen.wrapper.voice.HEARING_BARGE_TALK_GAP_MS
import org.formulagen.wrapper.voice.HEARING_ENDPOINT_SILENCE_MS
import org.formulagen.wrapper.voice.HEARING_MIN_SPEECH_MS
import org.formulagen.wrapper.voice.HEARING_RESUME_GUARD_MS
import org.formulagen.wrapper.voice.HearingDuplex
import org.formulagen.wrapper.voice.HearingResult
import org.formulagen.wrapper.voice.HearingStates
import org.formulagen.wrapper.voice.LatencyMetric
import org.formulagen.wrapper.voice.VoiceConversation as VoiceCopy
import org.formulagen.wrapper.voice.catalogHearingState

/** Where the reply to a conversation's latest turn stands, as a [TalkLoop] reads it. */
internal enum class TalkReply {
    IDLE, RESPONDING;

    /** A reply is being written: a click or Escape interrupts it. */
    val live: Boolean get() = this == RESPONDING

    /** A reply in progress keeps the microphone closed in half duplex. */
    val busy: Boolean get() = this != IDLE
}

/**
 * What a [TalkLoop] needs from the conversation it talks in. The loop calls every member with its lock held:
 * [open] and [reply] are read there, and each `…Locked` method runs there.
 */
internal interface TalkHost {
    /** Whether there is a conversation to talk in. */
    val open: Boolean

    /** Where the conversation's current reply stands. */
    val reply: TalkReply

    /** A finished utterance: the host refuses it with a notice, ignores it, or sends it as a heard turn. */
    fun sendHeardLocked(result: HearingResult): HearingCommit

    /** Barge-in on a reply in progress: the host stops it, or only lets the speaker be flushed, by its own rules. */
    fun interruptLocked()

    /** Talk ended: the host ends what Talk started, by its own rules. False when nothing was in progress. */
    fun endReplyLocked(): Boolean

    /** The latest reply's text, which self-echo compares the next utterance with. */
    fun lastReplyLocked(): String

    /** Something the page or the voice row shows changed: publish it (the host calls [TalkLoop.observeLocked] first). */
    fun changedLocked()
}

/**
 * The Mac's voice settings every talk loop shares, so a choice made in one conversation holds in the next: the filler
 * choice (remembered in [fillerFile]), the duplex mode (the [route]'s, which falls back to
 * half duplex when the voice-processing graph fails), and the turn-taking timings.
 */
internal class TalkSettings(
    fillers: FillerSettings = FillerSettings(),
    private val fillerFile: Path? = null,
    val route: HearingRoute? = null,
    private val duplex: DuplexChoice = DuplexChoice(HearingDuplex.HALF, "default"),
    val silenceMs: Int = HEARING_ENDPOINT_SILENCE_MS,
    val guardMs: Int = HEARING_RESUME_GUARD_MS,
    val bargeMs: Int = HEARING_BARGE_SUSTAIN_MS,
) {
    /** The filler choice now. */
    @Volatile var fillers: FillerSettings = fillers
        private set

    /** The duplex mode the next session uses. */
    val duplexChoice: DuplexChoice get() = route?.choice ?: duplex

    /** Turn fillers on or off for every loop, and remember the choice. Answers the new choice. */
    @Synchronized fun toggleFillers(): FillerSettings {
        fillers = fillers.copy(enabled = !fillers.enabled)
        fillerFile?.let { writeSavedFillers(it, fillers.enabled) }
        return fillers
    }
}

/** The hearing field a page reads, bounded by the loop's limits. */
internal data class TalkHearing(val state: HearingState, val partial: String?, val notice: String?)

/** The dictation field a page reads while dictating, or with why it stopped. */
internal data class TalkDictation(val state: DictationState, val partial: String?, val notice: String?)

/** What a dictation action asks of the caller outside the lock. */
internal sealed interface DictationStep {
    data object Ready : DictationStep
    data object Start : DictationStep
    /** Close the hold; [keep] answers its text, a cancel drops it. */
    data class Finish(val keep: Boolean) : DictationStep
}

/**
 * One project conversation's voice loop on the Mac: hands-free Talk
 * (listen, send a heard turn, think, speak, listen again), fillers, barge-in, the reply spoken sentence by sentence
 * through [SentenceCursor], dictation into the page's draft, the latency marks, and the native voice row's state.
 * [host] is the conversation: it sends heard turns and decides what a barge-in or an end stops.
 *
 * Threading: everything runs under [lock], the host's own lock. A `…Locked` member needs it held; [click],
 * [escape], [toggleFillers], [commit] and the `testing…` members take it themselves; [startCapture],
 * [startDictation] and [awaitDictation] run outside it. The microphone thread, the timer and the speaker call back
 * into the loop under the lock. See docs/voice-conversation-plan.md for the turn-taking rules.
 */
internal class TalkLoop(
    private val lock: Any,
    private val host: TalkHost,
    private val speech: ChatSpeech,
    private val hearing: Hearing?,
    private val settings: TalkSettings,
    private val timer: ScheduledExecutorService,
    /** The longest heard or dictated text the conversation takes. */
    private val limit: Int,
    private val now: () -> Long = System::nanoTime,
) {
    private val duplexChoice: DuplexChoice get() = settings.duplexChoice
    private val bargeGuard = BargeInGuard(sustainMs = settings.bargeMs, gapResetMs = HEARING_BARGE_TALK_GAP_MS)
    /** Last playback state told to the hearing session. Preparing counts: Kokoro is audible before the device reports speaking. */
    private var playbackNoted = false
    private val fillerRotation = FillerRotation()
    /** The filler choice [fillerTurn] was made for; a choice made in another loop is picked up at the next heard turn. */
    private var fillersSeen = settings.fillers
    private var fillerTurn = FillerTurn(fillersSeen, fillerRotation)
    private var talkOn = false
    private var talkPhase = TalkPhase.OFF
    private var endpointAt = 0L
    private var sentAt = 0L
    private var tokenAt = 0L
    /** Stage times of the last heard turn, once its first audio played. */
    var stageLatency: StageLatency? = null
        private set
    private var guardGeneration = 0
    /** Counts every arm: a capture serves only the session of the arm it was launched for. */
    private var arms = 0
    /** The arm whose capture has not been launched yet, or 0: each arm's capture is launched once. */
    private var unlaunched = 0
    private var resumePending = false
    private var hearingPartial = ""
    private var hearingNotice: String? = null
    /** The page armed or dictated. The native icon stays hidden so only one capture can arm. */
    private var pageOwnsCapture = false
    private var userMuted = false
    private var hearingUnavailable = false
    /** The page is dictating into its draft: one hold on [hearing]. Never true while [talkOn]. */
    private var dictating = false
    private var dictationPartial = ""
    /** Why dictation is unavailable, until the page starts it again. */
    private var dictationNotice: String? = null
    /** The permission sentence is shown until the first arm, then the row stays quiet. */
    private var explained = false

    /** A voice conversation is on. Read under the lock. */
    val on: Boolean get() = talkOn

    /** The page is dictating. Read under the lock. */
    val isDictating: Boolean get() = dictating

    /** The current talk phase, as the voice row shows it. */
    val phase: TalkPhase get() = talkPhase

    /** Warm the fillers, when they are on, so the first one plays from the cache. */
    fun warmFillersLocked() {
        if (settings.fillers.enabled) speech.warmFillers(FILLER_LINES)
    }

    /** The reply finished: a filler that has not started never will. */
    fun replyArrivedLocked() = fillerTurn.replyArrived()

    /** Bring the microphone in line with playback and the reply, and note first audio. The host calls this before publishing. */
    fun observeLocked() {
        if (talkOn) notePlaybackLocked()
        noteAudioLocked()
    }

    /** The page took over the controls: the native icon steps aside. */
    fun pageOwnsLocked() { pageOwnsCapture = true }

    /** The hearing field: Talk's state, the live transcript and its notice. */
    fun hearingLocked(): TalkHearing {
        val wire = catalogHearingState(
            hostPresent = true,
            armed = talkOn,
            muted = userMuted && talkOn,
            phase = talkPhase.name.lowercase(),
            partial = hearingPartial,
            unavailable = hearingUnavailable,
        )
        val state = when (wire) {
            HearingStates.REQUESTING -> HearingState.REQUESTING
            HearingStates.LISTENING -> HearingState.LISTENING
            HearingStates.HEARING -> HearingState.HEARING
            HearingStates.THINKING -> HearingState.THINKING
            HearingStates.MUTED -> HearingState.MUTED
            HearingStates.UNAVAILABLE -> HearingState.UNAVAILABLE
            else -> HearingState.OFF
        }
        val partial = hearingPartial.trim().take(limit).takeIf { it.isNotEmpty() }
        val notice = rowNoticeLocked()?.take(ChatLimits.NOTICE)?.takeIf { it.isNotEmpty() }
        return TalkHearing(state, partial, notice)
    }

    /** Present while dictating, or with the reason it stopped until the page starts again. */
    fun dictationLocked(): TalkDictation? = when {
        dictating -> {
            val partial = dictationPartial.trim().take(limit).takeIf { it.isNotEmpty() }
            TalkDictation(if (partial == null) DictationState.LISTENING else DictationState.HEARING, partial, null)
        }
        dictationNotice != null -> TalkDictation(DictationState.UNAVAILABLE, null, dictationNotice!!.take(ChatLimits.NOTICE))
        else -> null
    }

    /** The native voice row for a conversation that is [open]. */
    fun pickerLocked(open: Boolean): VoicePickerState =
        voicePickerState(
            chatOpen = open,
            kokoro = speech.kokoroActive(),
            voices = speech.voiceChoices(),
            selectedId = speech.currentVoiceId(),
            phase = talkPhase,
            fillersOn = settings.fillers.enabled,
            partial = hearingPartial,
            hearingNotice = rowNoticeLocked(),
            pageOwnsTalk = pageOwnsCapture && !talkOn,
            duplexMode = duplexChoice.mode,
            duplexReason = duplexChoice.reason,
        )

    /** The permission sentence until the first arm. After that, only a real notice. */
    private fun rowNoticeLocked(): String? =
        hearingNotice ?: if (!explained && !talkOn && !hearingUnavailable) VoiceCopy.PERMISSION_COPY else null

    /**
     * Click on the voice row's icon. The first click listens until a second of quiet, then keeps going. The next
     * click, Escape, or stop ends the session. The icon stays clickable while Ember speaks. Once the page owns Talk,
     * the icon only ends (or interrupts) a session.
     */
    fun click() {
        synchronized(lock) {
            if (!host.open) return
            if (pageOwnsCapture && !talkOn) return
            if (talkOn && replyLiveLocked()) {
                bargeInLocked("press")
                return
            }
            if (talkOn) {
                endLocked()
                return
            }
            if (refusedBusyLocked()) return
            armLocked()
        }
        launchCapture(wait = false)
    }

    /**
     * A page's press or hands-free: interrupts a reply in progress, keeps a session that is on, or arms one. True when
     * the caller must [startCapture] outside the lock.
     */
    fun pressLocked(): Boolean {
        if (interruptLocked()) return false
        if (talkOn) {
            host.changedLocked()
            return false
        }
        if (refusedBusyLocked()) return false
        armLocked()
        return true
    }

    /** Interrupt the reply in progress, as a barge-in does, and never arm a session. True when there was one to interrupt. */
    fun interruptLocked(): Boolean {
        if (!talkOn || !replyLiveLocked()) return false
        bargeInLocked("press")
        return true
    }

    /** Open the microphone for the session [pressLocked] armed. Call outside the lock. */
    fun startCapture() = launchCapture(wait = true)

    fun muteLocked() {
        userMuted = true
        if (talkOn) hearing?.setPaused(true)
        host.changedLocked()
    }

    fun unmuteLocked() {
        userMuted = false
        host.changedLocked()
    }

    /**
     * Escape from the native window. While a reply is in progress or audible, this interrupts it and keeps listening.
     * While the session is only listening, this ends it. False when no voice session is up, so the key goes on.
     */
    fun escape(): Boolean = synchronized(lock) {
        if (!talkOn) return@synchronized false
        if (replyLiveLocked()) bargeInLocked("press") else endLocked()
        true
    }

    /**
     * End the session; the host decides what of a reply in progress ends with it. With no session on, the microphone is
     * left alone, so a dictation holding it goes on.
     */
    fun endLocked() {
        val wasOn = talkOn
        talkOn = false
        userMuted = false
        resumePending = false
        guardGeneration++
        hearingPartial = ""
        setPhaseLocked(TalkPhase.OFF)
        speech.stop()
        if (wasOn) hearing?.endSession()
        if (!host.endReplyLocked()) {
            speech.stop()
            host.changedLocked()
        }
    }

    /** The voice row's filler toggle, shared by every loop. The env var wins at the next launch; the file remembers this. */
    fun toggleFillers() = synchronized(lock) {
        val next = settings.toggleFillers()
        renewFillerTurnLocked(next)
        log(LogLevel.Info, "hearing", "fillers enabled=${next.enabled}")
        host.changedLocked()
    }

    /** The filler turn for [next], replacing one made for an older choice; the speaker warms it when it is on. */
    private fun renewFillerTurnLocked(next: FillerSettings) {
        fillerTurn.cancel()
        fillerTurn = FillerTurn(next, fillerRotation)
        fillersSeen = next
        speech.setFillersEnabled(next.enabled)
    }

    /** The conversation went away: every voice state resets and the microphone closes. */
    fun resetLocked() {
        talkOn = false
        playbackNoted = false
        pageOwnsCapture = false
        userMuted = false
        hearingUnavailable = false
        dictating = false
        dictationPartial = ""
        dictationNotice = null
        explained = false
        resumePending = false
        guardGeneration++
        setPhaseLocked(TalkPhase.OFF)
        hearing?.endSession()
    }

    /** Let go of the microphone for good. */
    fun close() {
        hearing?.close()
    }

    /** A test, or a finished hold, handing the recognizer result to the conversation. */
    fun commit(result: HearingResult) = synchronized(lock) {
        commitLocked(result)
        host.changedLocked()
    }

    /** Show [notice] on the hearing field until the next arm: a refused action, or a heard turn that went nowhere. */
    fun sayLocked(notice: String) {
        hearingNotice = notice
        fillerTurn.cancel()
        host.changedLocked()
    }

    /** Take [notice] off the hearing field, if it is still the one shown. */
    fun unsayLocked(notice: String) {
        if (hearingNotice != notice) return
        hearingNotice = null
        host.changedLocked()
    }

    // ---- Dictation ---------------------------------------------------------------------------

    /**
     * Dictation is one hold on the hearing engine: [Hearing.down] streams the transcript into the field, and
     * stop waits for the last decode, outside the lock, and answers the text once. Nothing is sent or spoken.
     * Starting stops reply audio so the microphone does not hear Ember. It works while a dialog waits.
     */
    fun dictateLocked(action: DictationAction): DictationStep = when (action) {
        DictationAction.START -> when {
            dictating -> DictationStep.Ready
            talkOn -> {
                dictationNotice = DICTATION_WHILE_TALKING
                DictationStep.Ready
            }
            else -> {
                dictating = true
                dictationPartial = ""
                dictationNotice = null
                explained = true
                if (playbackAudibleLocked()) speech.stop()
                log(LogLevel.Info, "dictation", "start")
                host.changedLocked()
                DictationStep.Start
            }
        }
        DictationAction.STOP, DictationAction.CANCEL ->
            if (dictating) DictationStep.Finish(keep = action == DictationAction.STOP) else DictationStep.Ready
    }

    /** Open the hold [dictateLocked] started. Call outside the lock. */
    fun startDictation() {
        val ear = hearing ?: return dictationUnavailable(DICTATION_NEEDS_MODELS)
        // A voice conversation that just ended may still be letting go of the microphone.
        ear.awaitStopped(MICROPHONE_HANDOFF_MS)
        val started = ear.down(
            onPartial = { text ->
                synchronized(lock) {
                    if (dictating && text != dictationPartial) {
                        dictationPartial = text
                        host.changedLocked()
                    }
                }
            },
            onFailed = { message ->
                dictationUnavailable(if (message.contains("denied", ignoreCase = true)) VoiceCopy.MIC_SETTINGS_NOTICE else message)
            },
        )
        if (!started) dictationUnavailable(MICROPHONE_IN_USE)
    }

    /** Close the hold and wait for its last decode, outside the lock. Null when it had already failed or ended. */
    fun awaitDictation(): String? {
        val ear = hearing ?: return null
        var text: String? = null
        ear.up { text = it.text }
        return text
    }

    /** The hold closed with [heard]: dictation ends, and a [keep] answers the text, bounded by the limit. */
    fun dictationEndedLocked(heard: String?, keep: Boolean): String? {
        dictating = false
        dictationPartial = ""
        host.changedLocked()
        val text = heard?.trim()?.take(limit)?.takeIf { keep && it.isNotEmpty() }
        log(LogLevel.Info, "dictation", "${if (keep) "stop" else "cancel"} chars=${text?.length ?: 0}")
        return text
    }

    private fun dictationUnavailable(notice: String) = synchronized(lock) {
        dictating = false
        dictationPartial = ""
        dictationNotice = notice
        log(LogLevel.Info, "dictation", "unavailable")
        host.changedLocked()
    }

    // ---- The reply, spoken ---------------------------------------------------------------------

    /** A reply to speak as it grows. One per reply; a new reply gets a new one. */
    fun spokenReplyLocked() = SpokenReply()

    /** One reply's speech, under the loop's lock: closed sentences as the text grows, and the tail once it ends. */
    inner class SpokenReply internal constructor() {
        private val cursor = SentenceCursor()
        private var streamed = false

        /** The reply so far, the whole text each time. */
        fun growLocked(text: String) = speakLocked(text, finish = false)

        /** The finished reply: speak the rest and close the stream. */
        fun finishLocked(text: String) = speakLocked(text, finish = true)

        private fun speakLocked(text: String, finish: Boolean) {
            noteTokenLocked()
            val update = cursor.feed(text, finish)
            if (update.replaced) speech.stop()
            if (update.pieces.isNotEmpty()) {
                val fillerWasPlaying = fillerTurn.playing
                fillerTurn.replyArrived()
                if (fillerWasPlaying) speech.stop()
                for (piece in update.pieces) {
                    if (speech.enqueue(piece)) streamed = true
                    log(LogLevel.Info, "hearing", "piece_enqueued chars=${piece.length}")
                }
            }
            if (!finish) return
            if (streamed) speech.endStream()
            else speech.speak(text)
        }
    }

    // ---- The session ---------------------------------------------------------------------------

    /** Another user holds the microphone: say so and stay off. */
    private fun refusedBusyLocked(): Boolean {
        if (hearing?.busyElsewhere != true) return false
        hearingNotice = MICROPHONE_IN_USE
        log(LogLevel.Info, "hearing", "microphone busy elsewhere")
        host.changedLocked()
        return true
    }

    /** Opens one session, however it was started, and warms the voice for its first sentence. */
    private fun armLocked() {
        unlaunched = ++arms
        speech.warm()
        warmFillersLocked()
        settings.route?.arm()
        talkOn = true
        explained = true
        hearingUnavailable = false
        hearingPartial = ""
        hearingNotice = null
        bargeGuard.reset()
        setPhaseLocked(TalkPhase.LISTENING)
        log(LogLevel.Info, "hearing", "duplex=${duplexChoice.mode.name.lowercase()} reason=${duplexChoice.reason}")
        host.changedLocked()
    }

    /**
     * Start the armed session's capture. A page's arm first waits briefly for a capture that is still letting go. A
     * capture that cannot start (the microphone is someone else's, or still letting go) ends the session as busy. One
     * that starts after the session ended (the row's icon, while a page's capture was starting) lets the microphone go.
     * Each arm launches one capture, which acts only for that arm's session: one still starting when a newer session
     * armed neither serves it nor turns it off.
     */
    private fun launchCapture(wait: Boolean) {
        // The arm this capture is for. A later arm, made while this one was still starting, has a capture of its own, so
        // this one then neither serves nor ends that newer session.
        val arm = synchronized(lock) { unlaunched.also { unlaunched = 0 } }
        if (arm == 0) return
        val ear = hearing
        if (ear == null) {
            synchronized(lock) {
                if (arm != arms) return@synchronized
                talkOn = false
                hearingUnavailable = true
                setPhaseLocked(TalkPhase.OFF)
                hearingNotice = TALK_NEEDS_MODELS
                host.changedLocked()
            }
            return
        }
        if (wait) ear.awaitStopped(MICROPHONE_HANDOFF_MS)
        val started = ear.startSession(
            settings.silenceMs,
            onPartial = { text ->
                synchronized(lock) {
                    if (arm == arms && talkOn && talkPhase == TalkPhase.LISTENING && text != hearingPartial) {
                        hearingPartial = text
                        host.changedLocked()
                    }
                }
            },
            onUtterance = { result -> synchronized(lock) { if (arm == arms) onUtteranceLocked(result) } },
            onFailed = { message ->
                synchronized(lock) {
                    if (arm != arms) return@synchronized
                    hearingNotice = if (message.contains("denied", ignoreCase = true)) VoiceCopy.MIC_SETTINGS_NOTICE else message
                    hearingUnavailable = true
                    if (talkOn) endLocked() else host.changedLocked()
                }
            },
            onFrame = { voicedMs, chunkMs, prob -> synchronized(lock) { if (arm == arms) onHearingFrameLocked(voicedMs, chunkMs, prob) } },
        )
        if (started) {
            synchronized(lock) { if (!talkOn || arm != arms) ear.endSession() }
            return
        }
        synchronized(lock) {
            if (!talkOn || arm != arms) return@synchronized
            talkOn = false
            hearingNotice = MICROPHONE_IN_USE
            setPhaseLocked(TalkPhase.OFF)
            log(LogLevel.Info, "hearing", "microphone busy")
            host.changedLocked()
        }
    }

    private fun onUtteranceLocked(result: HearingResult) {
        if (!talkOn) return
        if (duplexChoice.mode == HearingDuplex.FULL && playbackAudibleLocked()) {
            bargeInLocked("vad")
            if (result.text.isNotBlank() && result.speechMs >= HEARING_MIN_SPEECH_MS) {
                noteEndpointLocked()
                commitLocked(result)
                if (talkOn && host.reply == TalkReply.RESPONDING) noteSendLocked()
            }
            host.changedLocked()
            return
        }
        if (talkPhase != TalkPhase.LISTENING) return
        noteEndpointLocked()
        log(LogLevel.Info, "hearing",
            "asr_final decodeMs=${result.decodeMs.toInt()} speechMs=${result.speechMs} chars=${result.text.length}")
        val text = result.text.trim()
        if (text.isEmpty() || result.speechMs < HEARING_MIN_SPEECH_MS) {
            log(LogLevel.Info, "hearing", "drop chars=${text.length} speechMs=${result.speechMs}")
            hearingPartial = ""
            host.changedLocked()
            return
        }
        hearingPartial = ""
        commitLocked(result)
        if (talkOn && host.reply == TalkReply.RESPONDING) {
            noteSendLocked()
            holdMicLocked(TalkPhase.THINKING)
        } else {
            endpointAt = 0L
        }
        host.changedLocked()
    }

    /** A reply or a filler is in progress. A click or Escape interrupts it and keeps the session. */
    private fun replyLiveLocked(): Boolean {
        if (host.reply.live) return true
        if (fillerTurn.playing) return true
        val voice = speech.status().state
        return voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING
    }

    /**
     * Flush playback, let the host interrupt the reply by its rules, and listen again. The microphone stays paused for
     * the guard so the speaker tail is not transcribed.
     */
    private fun bargeInLocked(kind: String) {
        if (!host.open) return
        val started = now()
        speech.stop()
        val flushMs = (now() - started) / 1_000_000
        log(LogLevel.Info, "hearing", "barge-in $kind ${LatencyMetric.FLUSH_MS}=$flushMs")
        fillerTurn.cancel()
        if (host.reply.live) host.interruptLocked()
        hearingPartial = ""
        bargeGuard.reset()
        setPhaseLocked(TalkPhase.LISTENING)
        if (duplexChoice.mode == HearingDuplex.FULL) {
            hearing?.setPaused(false)
            hearing?.restartUtterance()
        } else {
            hearing?.setPaused(true)
            resumePending = false
            scheduleResumeLocked()
        }
        host.changedLocked()
    }

    /**
     * Full duplex: 250 ms of frames at or above probability 0.5 while she is preparing or playing
     * flushes playback. A single quiet mic chunk must not wipe that run. Half duplex ignores the mic here.
     */
    private fun onHearingFrameLocked(voicedMs: Int, chunkMs: Int, prob: Float) {
        if (!talkOn || duplexChoice.mode != HearingDuplex.FULL) return
        syncPlaybackToHearingLocked()
        if (!playbackAudibleLocked()) {
            bargeGuard.reset()
            return
        }
        val minProb = 0.5f
        val span = when {
            prob >= minProb -> maxOf(chunkMs, voicedMs)
            voicedMs > 0 -> voicedMs
            else -> 0
        }
        if (bargeGuard.frame(span > 0, if (span > 0) span else chunkMs)) bargeInLocked("vad")
    }

    private fun noteEndpointLocked() {
        endpointAt = now()
        sentAt = 0L
        tokenAt = 0L
        stageLatency = null
    }

    private fun noteSendLocked() {
        if (endpointAt == 0L || sentAt != 0L) return
        sentAt = now()
        log(LogLevel.Info, "hearing", "latency ${LatencyMetric.ENDPOINT_TO_SEND_MS}=${(sentAt - endpointAt) / 1_000_000}")
    }

    private fun noteTokenLocked() {
        if (endpointAt == 0L || tokenAt != 0L) return
        if (sentAt == 0L) noteSendLocked()
        tokenAt = now()
        log(LogLevel.Info, "hearing", "latency ${LatencyMetric.SEND_TO_FIRST_TOKEN_MS}=${(tokenAt - sentAt) / 1_000_000}")
    }

    private fun noteAudioLocked() {
        if (tokenAt == 0L || stageLatency != null) return
        if (speech.status().state != VoiceState.SPEAKING) return
        val audioAt = now()
        val latency = StageLatency(
            endpointToSendMs = (sentAt - endpointAt) / 1_000_000,
            sendToFirstTokenMs = (tokenAt - sentAt) / 1_000_000,
            firstTokenToFirstAudioMs = (audioAt - tokenAt) / 1_000_000,
        )
        stageLatency = latency
        log(
            LogLevel.Info,
            "hearing",
            "latency ${LatencyMetric.ENDPOINT_TO_SEND_MS}=${latency.endpointToSendMs} " +
                "${LatencyMetric.SEND_TO_FIRST_TOKEN_MS}=${latency.sendToFirstTokenMs} " +
                "${LatencyMetric.FIRST_TOKEN_TO_FIRST_AUDIO_MS}=${latency.firstTokenToFirstAudioMs}",
        )
    }

    /** Preparing is already playback: Kokoro is producing audio before the device reports speaking. */
    private fun playbackAudibleLocked(): Boolean {
        val voice = speech.status().state
        return voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING || fillerTurn.playing
    }

    /** Tell the hearing session when playback starts and stops, including the preparing state. */
    private fun syncPlaybackToHearingLocked() {
        val audible = playbackAudibleLocked()
        if (audible == playbackNoted) return
        playbackNoted = audible
        if (audible) hearing?.noteEmberSpeaking(host.lastReplyLocked()) else hearing?.noteEmberSilent(host.lastReplyLocked())
    }

    /** Half-duplex: the mic stays closed while Ember is speaking or a reply is still in flight. */
    private fun notePlaybackLocked() {
        if (!talkOn) return
        syncPlaybackToHearingLocked()
        if (userMuted) {
            hearing?.setPaused(true)
            return
        }
        val voice = speech.status().state
        val emberBusy = voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING
        when {
            emberBusy -> if (talkPhase != TalkPhase.SPEAKING) holdMicLocked(TalkPhase.SPEAKING)
            host.reply.busy -> if (talkPhase != TalkPhase.THINKING && talkPhase != TalkPhase.SPEAKING) holdMicLocked(TalkPhase.THINKING)
                else if (talkPhase == TalkPhase.SPEAKING) {
                    resumePending = false
                    guardGeneration++
                    setPhaseLocked(TalkPhase.THINKING)
                    coverMicLocked()
                }
            talkPhase != TalkPhase.LISTENING && talkPhase != TalkPhase.OFF -> scheduleResumeLocked()
        }
    }

    private fun holdMicLocked(phase: TalkPhase) {
        resumePending = false
        guardGeneration++
        setPhaseLocked(phase)
        coverMicLocked()
    }

    /** Half duplex closes the mic while Ember speaks. Full duplex leaves it open for the canceller. */
    private fun coverMicLocked() {
        if (duplexChoice.mode == HearingDuplex.HALF) hearing?.setPaused(true)
    }

    private fun scheduleResumeLocked() {
        if (resumePending || !talkOn) return
        resumePending = true
        val ticket = ++guardGeneration
        val wait = settings.guardMs.coerceAtLeast(0).toLong()
        if (wait == 0L) {
            resumeListeningLocked(ticket, publish = false)
            return
        }
        timer.schedule({ synchronized(lock) { resumeListeningLocked(ticket, publish = true) } }, wait, TimeUnit.MILLISECONDS)
    }

    private fun resumeListeningLocked(ticket: Int, publish: Boolean) {
        if (ticket != guardGeneration) return
        resumePending = false
        if (!talkOn || !host.open) return
        val voice = speech.status().state
        if (voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING) return
        if (host.reply.busy) return
        setPhaseLocked(TalkPhase.LISTENING)
        hearing?.setPaused(false)
        if (publish) host.changedLocked()
    }

    private fun setPhaseLocked(next: TalkPhase) {
        if (talkPhase == next) return
        val leavingSpeech = talkPhase == TalkPhase.SPEAKING && next != TalkPhase.SPEAKING
        val enteringSpeech = next == TalkPhase.SPEAKING
        talkPhase = next
        log(LogLevel.Info, "hearing",
            "session=${next.name.lowercase()} reply=${host.reply.name.lowercase()} voice=${speech.status().state}")
        if (enteringSpeech || leavingSpeech) syncPlaybackToHearingLocked()
    }

    private fun commitLocked(result: HearingResult) {
        if (!host.open) return
        when (val decision = host.sendHeardLocked(result)) {
            HearingCommit.Ignore -> Unit
            is HearingCommit.Notice -> hearingNotice = decision.text
            is HearingCommit.Send -> {
                hearingNotice = null
                // A filler choice another loop made is this turn's.
                settings.fillers.let { if (it != fillersSeen) renewFillerTurnLocked(it) }
                fillerTurn.begin()
                if (host.reply == TalkReply.RESPONDING && fillerTurn.arm(now())) {
                    log(LogLevel.Info, "hearing", "send chars=${decision.text.length}")
                    timer.schedule({ fillerDue() }, fillersSeen.thresholdMs.toLong(), TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun fillerDue() = synchronized(lock) {
        if (!host.open) return@synchronized
        val cue = fillerTurn.poll(now()) ?: return@synchronized
        val voiceId = speech.currentVoiceId().ifEmpty { "unknown" }
        val pcm = speech.fillerPcm(voiceId, cue.line)
        if (pcm == null) {
            fillerTurn.clipFinished()
            log(LogLevel.Info, "hearing", "filler skipped cache-cold voice=$voiceId")
            return@synchronized
        }
        log(LogLevel.Info, "hearing", "filler line=\"${cue.line}\" voice=$voiceId waitedMs=${cue.waitedMs}")
        speech.playClip(pcm.pcm, pcm.sampleRate) { synchronized(lock) { fillerTurn.clipFinished() } }
    }
}

/** Stage times for one heard turn, in milliseconds. */
internal data class StageLatency(
    val endpointToSendMs: Long,
    val sendToFirstTokenMs: Long,
    val firstTokenToFirstAudioMs: Long,
)

/** Talk and dictation share the microphone, so each waits for the other. */
internal const val DICTATION_WHILE_TALKING = "End the voice conversation to dictate."
internal const val TALK_WHILE_DICTATING = "Finish dictating to start a voice conversation."
internal const val DICTATION_NEEDS_MODELS = "Dictation needs the hearing models. Run scripts/fetch-voice.sh, then rebuild."
internal const val TALK_NEEDS_MODELS = "Talk needs the hearing models. Run scripts/fetch-voice.sh, then rebuild."
/** How long a new capture waits for one that is stopping to let go of the microphone. */
private const val MICROPHONE_HANDOFF_MS = 1_000L
/** The microphone is held by another capture, such as another conversation's. */
internal const val MICROPHONE_IN_USE = "The microphone is in use. Try again in a moment."
