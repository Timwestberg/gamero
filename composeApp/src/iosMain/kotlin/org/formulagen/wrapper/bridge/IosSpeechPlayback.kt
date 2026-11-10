package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

private const val TAG = "IosSpeechPlayback"

/** A sentence that arrives this soon after the last one ended keeps the audio session, so music is ducked once per reply. */
private const val SESSION_LINGER_MS = 1_500L
private const val SESSION_RETRY_MS = 500L
private const val SESSION_RETRIES = 4

/**
 * The companion's voice on iPhone. [LocalSpeechQueue] holds the rules and [speaker] makes the sound: Kokoro, the
 * Mac's voice, where the app carries its model, and Apple's synthesizer otherwise. The speaker reports each
 * utterance's start and end, so `status` never reads `done` in the gap before the first word. A queue that stops
 * reporting is dropped as `error` after [speechBudgetMs] instead of staying `speaking`.
 *
 * State lives on the main thread.
 */
internal class IosSpeechPlayback(
    private val audio: IosAudioSession,
    private val speaker: IosUtteranceSpeaker = iosSpeaker(),
) : VoicePlaybackCapability, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = LocalSpeechQueue()
    private var stall: Job? = null
    private var release: Job? = null

    override suspend fun speak(args: LocalSpeechArgs): LocalSpeechStatus = withContext(Dispatchers.Main) { say(args, replace = true) }
    override suspend fun append(args: LocalSpeechArgs): LocalSpeechStatus = withContext(Dispatchers.Main) { say(args, replace = false) }

    override suspend fun stopSpeech(args: VoiceInputScope): LocalSpeechStatus = withContext(Dispatchers.Main) {
        if (queue.stop(args.sessionId)) drop()
        LocalSpeechStatus(args.sessionId, queue.status(args.sessionId))
    }

    override suspend fun speechStatus(args: VoiceInputScope): LocalSpeechStatus =
        withContext(Dispatchers.Main) { LocalSpeechStatus(args.sessionId, queue.status(args.sessionId)) }

    /** Backgrounding, an audio interruption, a capture that needs the session, or the page ending: the speaker stops as `cancelled`. */
    fun interrupt() {
        if (queue.interrupt()) drop()
    }

    /** The person began to talk to the companion: its voice gets ready while they speak. */
    fun warm() = speaker.warm()

    private fun say(args: LocalSpeechArgs, replace: Boolean): LocalSpeechStatus {
        val id = args.sessionId
        when (queue.admit(id, replace)) {
            LocalSpeechQueue.Admission.CANCELLED -> return LocalSpeechStatus(id, "cancelled")
            LocalSpeechQueue.Admission.BUSY -> return LocalSpeechStatus(id, "error")
            LocalSpeechQueue.Admission.QUEUE -> Unit
            LocalSpeechQueue.Admission.START -> {
                speaker.flush()
                release?.cancel()
                if (!audio.begin(IosAudioSession.Use.SPEECH)) {
                    queue.fail(queue.ticket)
                    return LocalSpeechStatus(id, "error")
                }
                watch(speaker.startBudgetMs)
            }
        }
        val ticket = queue.ticket
        val length = args.text.length
        speaker.say(
            args.text,
            started = { if (ticket == queue.ticket) watch(speechBudgetMs(length)) },
            ended = { ended(ticket) },
        )
        return LocalSpeechStatus(id, queue.status(id))
    }

    private fun ended(ticket: Int) {
        if (ticket != queue.ticket) return
        if (queue.ended(ticket)) {
            stall?.cancel()
            releaseSession(SESSION_LINGER_MS)
        } else {
            watch(speaker.startBudgetMs)
        }
    }

    /** The live queue is over before its end: silence the speaker and give the session back, now if iOS lets it go. */
    private fun drop() {
        speaker.flush()
        stall?.cancel()
        release?.cancel()
        if (audio.end(IosAudioSession.Use.SPEECH)) speaker.rest() else releaseSession(SESSION_RETRY_MS)
    }

    /** Drops the queue as `error` when the speaker reports nothing for [budgetMs]. */
    private fun watch(budgetMs: Long) {
        val ticket = queue.ticket
        stall?.cancel()
        stall = scope.launch {
            delay(budgetMs)
            if (queue.fail(ticket)) {
                log(LogLevel.Warn, TAG, "The speaker stopped reporting; the reply's voice was dropped")
                drop()
            }
        }
    }

    /**
     * Deactivates the audio session once nothing is being said, after [delayMs], so other apps' audio comes back.
     * iOS refuses while the last buffer drains, so it tries a few times.
     */
    private fun releaseSession(delayMs: Long) {
        release?.cancel()
        release = scope.launch {
            delay(delayMs)
            repeat(SESSION_RETRIES) {
                if (queue.speaking) return@launch
                if (audio.end(IosAudioSession.Use.SPEECH)) {
                    speaker.rest()
                    return@launch
                }
                delay(SESSION_RETRY_MS)
            }
        }
    }

    override fun close() {
        interrupt()
        scope.cancel()
        speaker.close()
        audio.end(IosAudioSession.Use.SPEECH)
    }
}
