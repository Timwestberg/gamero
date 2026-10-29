package org.formulagen.wrapper.chat

import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.DictationAction
import org.formulagen.wrapper.bridge.DictationState
import org.formulagen.wrapper.bridge.HearingState
import org.formulagen.wrapper.bridge.VoiceState
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.BargeInGuard
import org.formulagen.wrapper.voice.DuplexChoice
import org.formulagen.wrapper.voice.FILLER_LINES
import org.formulagen.wrapper.voice.FillerRotation
import org.formulagen.wrapper.voice.FillerSettings
import org.formulagen.wrapper.voice.FillerTurn
import org.formulagen.wrapper.voice.HEARING_BARGE_TALK_GAP_MS
import org.formulagen.wrapper.voice.HEARING_MIN_SPEECH_MS
import org.formulagen.wrapper.voice.HearingDuplex
import org.formulagen.wrapper.voice.HearingResult
import org.formulagen.wrapper.voice.HearingStates
import org.formulagen.wrapper.voice.LatencyMetric
import org.formulagen.wrapper.voice.VoiceConversation as VoiceCopy
import org.formulagen.wrapper.voice.catalogHearingState
import org.formulagen.wrapper.voice.catalogOwnsConversationControls

/** Where the reply to a conversation's latest turn stands, as a [TalkLoop] reads it. */
internal enum class TalkReply {
    IDLE, RESPONDING, HANDING_OVER, AWAITING_YOU;

    /** A reply is being written, or waits on a dialog: a click or Escape interrupts it. */
    val live: Boolean get() = this == RESPONDING || this == AWAITING_YOU

    /** Anything in progress keeps the microphone closed in half duplex. */
    val busy: Boolean get() = this != IDLE
}

/** What a [TalkLoop] needs from the conversation it talks in. Every call holds the loop's lock. */
internal interface TalkHost {
    /** Whether there is a conversation to talk in. */
    val open: Boolean

    /** Where the conversation's current reply stands. */
    val reply: TalkReply

    /** A finished utterance: the host refuses it with a notice, ignores it, or sends it as a heard turn. */
    fun sendHeard(result: HearingResult): HearingCommit

    /** Barge-in: end the reply in progress with no notice, keeping what it said; the next heard turn starts another. */
    fun interrupt()

    /** Talk ended: stop the reply in progress as Stop does. False when none was in progress. */
    fun endReply(): Boolean

    /** The latest reply's text, which self-echo compares the next utterance with. */
    fun lastReply(): String

    /** Something the page or the voice row shows changed: publish it (the host calls [TalkLoop.observe] first). */
    fun changed()
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
 * One voice conversation's loop on the Mac, shared by team chat and project conversations: hands-free
 * Talk (listen, send a heard turn, think, speak, listen again), fillers, barge-in, the reply spoken
 * sentence by sentence through [SentenceCursor], dictation into the page's draft, the latency marks,
 * and the native voice row's state. [host] is the conversation: it sends heard turns and stops
 * replies. Everything runs under [lock], the host's own lock; the microphone thread, the timer and the
 * speaker call back into it. See docs/voice-conversation-plan.md for the turn-taking rules.
 */
internal class TalkLoop(
    private val lock: Any,
    private val host: TalkHost,
    private val speech: ChatSpeech,
    private val hearing: Hearing?,
    private val route: HearingRoute?,
    duplexChoice: DuplexChoice,
    private val timer: ScheduledExecutorService,
    fillers: FillerSettings,
    private val fillerFile: Path?,
    private val silenceMs: Int,
    private val guardMs: Int,
    bargeMs: Int,
    /** The longest heard or dictated text the conversation takes. */
    private val limit: Int,
) {
    private var duplexChoice = duplexChoice
    private val bargeGuard = BargeInGuard(sustainMs = bargeMs, gapResetMs = HEARING_BARGE_TALK_GAP_MS)
    /** Last playback state told to the hearing session. Preparing counts: Kokoro is audible before the device reports speaking. */
    private var playbackNoted = false
    private val fillerRotation = FillerRotation()
    private var fillerTurn = FillerTurn(fillers, fillerRotation)
    private var liveFillers = fillers
    private var talkOn = false
    private var talkPhase = TalkPhase.OFF
    private var endpointAt = 0L
    private var sentAt = 0L
    private var tokenAt = 0L
    /** Stage times of the last heard turn, once its first audio played. */
    var stageLatency: StageLatency? = null
        private set
    private var guardGeneration = 0
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

    /** A voice conversation is on. */
    val on: Boolean get() = talkOn

    /** The page is dictating. */
    val isDictating: Boolean get() = dictating

    /** The current talk phase, as the voice row shows it. */
    val phase: TalkPhase get() = talkPhase

    /** Warm the fillers for a conversation just opened. */
    fun warm() {
        if (liveFillers.enabled) speech.warmFillers(FILLER_LINES)
    }

    /** A typed send or a stop: no filler plays for it. */
    fun cancelFiller() = fillerTurn.cancel()

    /** The reply finished: a filler that has not started never will. */
    fun replyArrived() = fillerTurn.replyArrived()

    /** Bring the microphone in line with playback and the reply, and note first audio. The host calls this before publishing. */
    fun observe() {
        if (talkOn) notePlaybackLocked()
        noteAudio()
    }

    /** The page took over the controls: the native icon steps aside. */
    fun pageOwns() { pageOwnsCapture = true }

    /** The hearing field: Talk's state, the live transcript and its notice. */
    fun hearing(): TalkHearing {
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
        val notice = rowNotice()?.take(ChatLimits.NOTICE)?.takeIf { it.isNotEmpty() }
        return TalkHearing(state, partial, notice)
    }

    /** Present while dictating, or with the reason it stopped until the page starts again. */
    fun dictation(): TalkDictation? = when {
        dictating -> {
            val partial = dictationPartial.trim().take(limit).takeIf { it.isNotEmpty() }
            TalkDictation(if (partial == null) DictationState.LISTENING else DictationState.HEARING, partial, null)
        }
        dictationNotice != null -> TalkDictation(DictationState.UNAVAILABLE, null, dictationNotice!!.take(ChatLimits.NOTICE))
        else -> null
    }

    /** The native voice row for a conversation that is [open]; [owned] rows show in any product. */
    fun picker(open: Boolean, product: String, owned: Boolean = catalogOwnsConversationControls(product)): VoicePickerState =
        voicePickerState(
            chatOpen = open,
            kokoro = speech.kokoroActive(),
            voices = speech.voiceChoices(),
            selectedId = speech.currentVoiceId(),
            phase = talkPhase,
            fillersOn = liveFillers.enabled,
            partial = hearingPartial,
            hearingNotice = rowNotice(),
            product = product,
            pageOwnsTalk = pageOwnsCapture,
            duplexMode = duplexChoice.mode,
            duplexReason = duplexChoice.reason,
            owned = owned,
        )

    /** The permission sentence until the first arm. After that, only a real notice. */
    private fun rowNotice(): String? = hearingNotice ?: if (!explained && !talkOn && !hearingUnavailable) VoiceCopy.PERMISSION_COPY else null

    /**
     * Click on the voice row's icon. The first click listens until a second of quiet, then keeps going.
     * The next click, Escape, or stop ends the session. The icon stays clickable while Ember speaks.
     */
    fun click() {
        synchronized(lock) {
            if (pageOwnsCapture || !host.open) return
            if (talkOn && replyLiveLocked()) {
                bargeInLocked("press")
                return
            }
            if (talkOn) {
                end()
                return
            }
            if (refusedBusyLocked()) return
            armLocked()
        }
        launchCapture(wait = false)
    }

    /**
     * A page's press or hands-free: interrupts a reply in progress, keeps a session that is on, or arms
     * one. True when the caller must [startCapture] outside the lock.
     */
    fun press(): Boolean {
        if (talkOn && replyLiveLocked()) {
            bargeInLocked("press")
            return false
        }
        if (talkOn) {
            host.changed()
            return false
        }
        if (refusedBusyLocked()) return false
        armLocked()
        return true
    }

    /** Open the microphone for the session [press] armed. Call outside the lock. */
    fun startCapture() = launchCapture(wait = true)

    fun mute() {
        userMuted = true
        if (talkOn) hearing?.setPaused(true)
        host.changed()
    }

    fun unmute() {
        userMuted = false
        host.changed()
    }

    /**
     * Escape. While a reply is in progress or audible, this interrupts it and keeps listening.
     * While the session is only listening, this ends it. False when no voice session is up, so the page keeps the key.
     */
    fun escape(): Boolean = synchronized(lock) {
        if (!talkOn) return@synchronized false
        if (replyLiveLocked()) bargeInLocked("press") else end()
        true
    }

    /** End the session; a reply in progress stops as Stop stops it. */
    fun end() {
        talkOn = false
        userMuted = false
        resumePending = false
        guardGeneration++
        hearingPartial = ""
        setPhase(TalkPhase.OFF)
        speech.stop()
        hearing?.endSession()
        if (!host.endReply()) {
            speech.stop()
            host.changed()
        }
    }

    /** The voice row's filler toggle. The env var wins at the next launch; this choice is what the file remembers. */
    fun toggleFillers() = synchronized(lock) {
        val enabled = !liveFillers.enabled
        liveFillers = liveFillers.copy(enabled = enabled)
        fillerTurn.cancel()
        fillerTurn = FillerTurn(liveFillers, fillerRotation)
        fillerFile?.let { writeSavedFillers(it, enabled) }
        speech.setFillersEnabled(enabled)
        log(LogLevel.Info, "hearing", "fillers enabled=$enabled")
        host.changed()
    }

    /** The conversation went away: every voice state resets and the microphone closes. */
    fun reset() {
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
        setPhase(TalkPhase.OFF)
        hearing?.endSession()
    }

    /** Let go of the microphone for good. */
    fun close() {
        hearing?.close()
    }

    /** A test session with no microphone. Real clicks go through [click]. */
    fun testingSession() = synchronized(lock) {
        check(host.open)
        talkOn = true
        hearingPartial = ""
        hearingNotice = null
        setPhase(TalkPhase.LISTENING)
        host.changed()
    }

    /** A finished hands-free turn, as the microphone thread would deliver it. */
    fun testingUtterance(result: HearingResult) = synchronized(lock) { onUtteranceLocked(result) }

    /** A test, or a finished hold, handing the recognizer result to the conversation. */
    fun commit(result: HearingResult) = synchronized(lock) {
        commitLocked(result)
        host.changed()
    }

    /** Show [notice] on the hearing field until the next arm: a refused action, or a heard turn that went nowhere. */
    fun say(notice: String) {
        hearingNotice = notice
        fillerTurn.cancel()
        host.changed()
    }

    // ---- Dictation ---------------------------------------------------------------------------

    /**
     * Dictation is one hold on the hearing engine: [Hearing.down] streams the transcript into the field, and
     * stop waits for the last decode, outside the lock, and answers the text once. Nothing is sent or spoken.
     * Starting stops reply audio so the microphone does not hear Ember. It works while a dialog waits.
     */
    fun dictate(action: DictationAction): DictationStep = when (action) {
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
                if (playbackAudible()) speech.stop()
                log(LogLevel.Info, "dictation", "start")
                host.changed()
                DictationStep.Start
            }
        }
        DictationAction.STOP, DictationAction.CANCEL ->
            if (dictating) DictationStep.Finish(keep = action == DictationAction.STOP) else DictationStep.Ready
    }

    /** Open the hold [dictate] started. Call outside the lock. */
    fun startDictation() {
        val ear = hearing ?: return dictationUnavailable(DICTATION_NEEDS_MODELS)
        // A voice conversation that just ended may still be letting go of the microphone.
        ear.awaitStopped(MICROPHONE_HANDOFF_MS)
        val started = ear.down(
            onPartial = { text ->
                synchronized(lock) {
                    if (dictating && text != dictationPartial) {
                        dictationPartial = text
                        host.changed()
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
    fun dictationEnded(heard: String?, keep: Boolean): String? {
        dictating = false
        dictationPartial = ""
        host.changed()
        val text = heard?.trim()?.take(limit)?.takeIf { keep && it.isNotEmpty() }
        log(LogLevel.Info, "dictation", "${if (keep) "stop" else "cancel"} chars=${text?.length ?: 0}")
        return text
    }

    private fun dictationUnavailable(notice: String) = synchronized(lock) {
        dictating = false
        dictationPartial = ""
        dictationNotice = notice
        log(LogLevel.Info, "dictation", "unavailable")
        host.changed()
    }

    // ---- The reply, spoken ---------------------------------------------------------------------

    /** A reply to speak as it grows. One per reply; a new reply gets a new one. */
    fun spokenReply() = SpokenReply()

    /** One reply's speech: closed sentences as the text grows ([grow]), and the tail once it ends ([finish]). */
    inner class SpokenReply internal constructor() {
        private val cursor = SentenceCursor()
        private var streamed = false

        /** The reply so far, the whole text each time. */
        fun grow(text: String) = speak(text, finish = false)

        /** The finished reply: speak the rest and close the stream. */
        fun finish(text: String) = speak(text, finish = true)

        private fun speak(text: String, finish: Boolean) {
            noteToken()
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
        host.changed()
        return true
    }

    /** Opens one session. */
    private fun armLocked() {
        route?.arm()?.let { duplexChoice = it }
        talkOn = true
        explained = true
        hearingUnavailable = false
        hearingPartial = ""
        hearingNotice = null
        bargeGuard.reset()
        setPhase(TalkPhase.LISTENING)
        log(LogLevel.Info, "hearing", "duplex=${duplexChoice.mode.name.lowercase()} reason=${duplexChoice.reason}")
        host.changed()
    }

    /** Start the armed session's capture. A page's arm first waits briefly for a capture that is still letting go. */
    private fun launchCapture(wait: Boolean) {
        val ear = hearing
        if (ear == null) {
            synchronized(lock) {
                talkOn = false
                hearingUnavailable = true
                setPhase(TalkPhase.OFF)
                hearingNotice = TALK_NEEDS_MODELS
                host.changed()
            }
            return
        }
        if (wait) ear.awaitStopped(MICROPHONE_HANDOFF_MS)
        val started = ear.startSession(
            silenceMs,
            onPartial = { text ->
                synchronized(lock) {
                    if (talkOn && talkPhase == TalkPhase.LISTENING && text != hearingPartial) {
                        hearingPartial = text
                        host.changed()
                    }
                }
            },
            onUtterance = { result -> synchronized(lock) { onUtteranceLocked(result) } },
            onFailed = { message ->
                synchronized(lock) {
                    hearingNotice = if (message.contains("denied", ignoreCase = true)) VoiceCopy.MIC_SETTINGS_NOTICE else message
                    hearingUnavailable = true
                    if (talkOn) end() else host.changed()
                }
            },
            onFrame = { voicedMs, chunkMs, prob -> synchronized(lock) { onHearingFrame(voicedMs, chunkMs, prob) } },
        )
        if (!started) synchronized(lock) {
            if (ear.busyElsewhere) {
                talkOn = false
                hearingNotice = MICROPHONE_IN_USE
                setPhase(TalkPhase.OFF)
                log(LogLevel.Info, "hearing", "microphone busy elsewhere")
                host.changed()
                return@synchronized
            }
            if (pageOwnsCapture && talkOn) return@synchronized
            talkOn = false
            setPhase(TalkPhase.OFF)
            log(LogLevel.Info, "hearing", "button busy")
            host.changed()
        }
    }

    private fun onUtteranceLocked(result: HearingResult) {
        if (!talkOn) return
        if (duplexChoice.mode == HearingDuplex.FULL && playbackAudible()) {
            bargeInLocked("vad")
            if (result.text.isNotBlank() && result.speechMs >= HEARING_MIN_SPEECH_MS) {
                noteEndpoint()
                commitLocked(result)
                if (talkOn && host.reply == TalkReply.RESPONDING) noteSend()
            }
            host.changed()
            return
        }
        if (talkPhase != TalkPhase.LISTENING) return
        noteEndpoint()
        log(LogLevel.Info, "hearing", "asr_final decodeMs=${result.decodeMs.toInt()} speechMs=${result.speechMs} chars=${result.text.length}")
        val text = result.text.trim()
        if (text.isEmpty() || result.speechMs < HEARING_MIN_SPEECH_MS) {
            log(LogLevel.Info, "hearing", "drop chars=${text.length} speechMs=${result.speechMs}")
            hearingPartial = ""
            host.changed()
            return
        }
        hearingPartial = ""
        commitLocked(result)
        if (talkOn && host.reply == TalkReply.RESPONDING) {
            noteSend()
            holdMicLocked(TalkPhase.THINKING)
        } else {
            endpointAt = 0L
        }
        host.changed()
    }

    /** A reply or a filler is in progress. A click or Escape interrupts it and keeps the session. */
    private fun replyLiveLocked(): Boolean {
        if (host.reply.live) return true
        if (fillerTurn.playing) return true
        val voice = speech.status().state
        return voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING
    }

    /**
     * Flush playback before the reply is cancelled, keep its partial text, and listen again.
     * The microphone stays paused for [guardMs] so the speaker tail is not transcribed.
     */
    private fun bargeInLocked(kind: String) {
        if (!host.open) return
        val started = System.nanoTime()
        speech.stop()
        val flushMs = (System.nanoTime() - started) / 1_000_000
        log(LogLevel.Info, "hearing", "barge-in $kind ${LatencyMetric.FLUSH_MS}=$flushMs")
        fillerTurn.cancel()
        if (host.reply.live) host.interrupt()
        hearingPartial = ""
        bargeGuard.reset()
        setPhase(TalkPhase.LISTENING)
        if (duplexChoice.mode == HearingDuplex.FULL) {
            hearing?.setPaused(false)
            hearing?.restartUtterance()
        } else {
            hearing?.setPaused(true)
            resumePending = false
            scheduleResumeLocked()
        }
        host.changed()
    }

    /**
     * Full duplex: 250 ms of frames at or above probability 0.5 while she is preparing or playing
     * flushes playback. A single quiet mic chunk must not wipe that run. Half duplex ignores the mic here.
     */
    private fun onHearingFrame(voicedMs: Int, chunkMs: Int, prob: Float) {
        if (!talkOn || duplexChoice.mode != HearingDuplex.FULL) return
        syncPlaybackToHearing()
        if (!playbackAudible()) {
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

    private fun noteEndpoint() {
        endpointAt = System.nanoTime()
        sentAt = 0L
        tokenAt = 0L
        stageLatency = null
    }

    private fun noteSend() {
        if (endpointAt == 0L || sentAt != 0L) return
        sentAt = System.nanoTime()
        log(LogLevel.Info, "hearing", "latency ${LatencyMetric.ENDPOINT_TO_SEND_MS}=${(sentAt - endpointAt) / 1_000_000}")
    }

    private fun noteToken() {
        if (endpointAt == 0L || tokenAt != 0L) return
        if (sentAt == 0L) noteSend()
        tokenAt = System.nanoTime()
        log(LogLevel.Info, "hearing", "latency ${LatencyMetric.SEND_TO_FIRST_TOKEN_MS}=${(tokenAt - sentAt) / 1_000_000}")
    }

    private fun noteAudio() {
        if (tokenAt == 0L || stageLatency != null) return
        if (speech.status().state != VoiceState.SPEAKING) return
        val audioAt = System.nanoTime()
        val latency = StageLatency(
            endpointToSendMs = (sentAt - endpointAt) / 1_000_000,
            sendToFirstTokenMs = (tokenAt - sentAt) / 1_000_000,
            firstTokenToFirstAudioMs = (audioAt - tokenAt) / 1_000_000,
        )
        stageLatency = latency
        log(
            LogLevel.Info,
            "hearing",
            "latency ${LatencyMetric.ENDPOINT_TO_SEND_MS}=${latency.endpointToSendMs} ${LatencyMetric.SEND_TO_FIRST_TOKEN_MS}=${latency.sendToFirstTokenMs} ${LatencyMetric.FIRST_TOKEN_TO_FIRST_AUDIO_MS}=${latency.firstTokenToFirstAudioMs}",
        )
    }

    /** Preparing is already playback: Kokoro is producing audio before the device reports speaking. */
    private fun playbackAudible(): Boolean {
        val voice = speech.status().state
        return voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING || fillerTurn.playing
    }

    /** Tell the hearing session when playback starts and stops, including the preparing state. */
    private fun syncPlaybackToHearing() {
        val audible = playbackAudible()
        if (audible == playbackNoted) return
        playbackNoted = audible
        if (audible) hearing?.noteEmberSpeaking(host.lastReply()) else hearing?.noteEmberSilent(host.lastReply())
    }

    /** Half-duplex: the mic stays closed while Ember is speaking or a reply is still in flight. */
    private fun notePlaybackLocked() {
        if (!talkOn) return
        syncPlaybackToHearing()
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
                    setPhase(TalkPhase.THINKING)
                    coverMicLocked()
                }
            talkPhase != TalkPhase.LISTENING && talkPhase != TalkPhase.OFF -> scheduleResumeLocked()
        }
    }

    private fun holdMicLocked(phase: TalkPhase) {
        resumePending = false
        guardGeneration++
        setPhase(phase)
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
        val wait = guardMs.coerceAtLeast(0).toLong()
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
        setPhase(TalkPhase.LISTENING)
        hearing?.setPaused(false)
        if (publish) host.changed()
    }

    private fun setPhase(next: TalkPhase) {
        if (talkPhase == next) return
        val leavingSpeech = talkPhase == TalkPhase.SPEAKING && next != TalkPhase.SPEAKING
        val enteringSpeech = next == TalkPhase.SPEAKING
        talkPhase = next
        log(LogLevel.Info, "hearing", "session=${next.name.lowercase()} reply=${host.reply.name.lowercase()} voice=${speech.status().state}")
        if (enteringSpeech || leavingSpeech) syncPlaybackToHearing()
    }

    private fun commitLocked(result: HearingResult) {
        if (!host.open) return
        when (val decision = host.sendHeard(result)) {
            HearingCommit.Ignore -> Unit
            is HearingCommit.Notice -> hearingNotice = decision.text
            is HearingCommit.Send -> {
                hearingNotice = null
                fillerTurn.begin()
                if (host.reply == TalkReply.RESPONDING && fillerTurn.arm(System.nanoTime())) {
                    log(LogLevel.Info, "hearing", "send chars=${decision.text.length}")
                    timer.schedule({ fillerDue() }, liveFillers.thresholdMs.toLong(), TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun fillerDue() = synchronized(lock) {
        if (!host.open) return@synchronized
        val cue = fillerTurn.poll(System.nanoTime()) ?: return@synchronized
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
/** The microphone is held by another capture, such as the native chat row's or another conversation's. */
internal const val MICROPHONE_IN_USE = "The microphone is in use. Try again in a moment."
