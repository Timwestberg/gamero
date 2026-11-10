@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.formulagen.wrapper.bridge

import kotlinx.cinterop.ObjCSignatureOverride
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
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisAvailableVoicesDidChangeNotification
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityEnhanced
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityPremium
import platform.AVFAudio.AVSpeechSynthesisVoiceTraitIsNoveltyVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObject

private const val TAG = "IosSpeechPlayback"

/** A sentence that arrives this soon after the last one ended keeps the audio session, so music is ducked once per reply. */
private const val SESSION_LINGER_MS = 1_500L
private const val SESSION_RETRY_MS = 500L
private const val SESSION_RETRIES = 4

/**
 * The companion's voice on iPhone: Apple's on-device synthesizer with the best voice the person has installed.
 * [LocalSpeechQueue] holds the rules; the synthesizer's own queue holds the utterances, and its delegate reports
 * each one's start and end, so `status` never reads `done` in the gap before the first word. A queue that stops
 * reporting is dropped as `error` after [speechBudgetMs] instead of staying `speaking`.
 *
 * State lives on the main thread.
 */
internal class IosSpeechPlayback(private val audio: IosAudioSession) : VoicePlaybackCapability, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = LocalSpeechQueue()
    /** The utterances of the live queue the synthesizer has not finished. A flush empties it, so late reports find nothing. */
    private val live = mutableListOf<AVSpeechUtterance>()
    private var stall: Job? = null
    private var release: Job? = null
    /** The chosen voice for a language. An entry with a null voice means "use the system's default". */
    private var voice: Pair<String, AVSpeechSynthesisVoice?>? = null

    private val reports = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) {
            scope.launch { started(didStartSpeechUtterance) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            scope.launch { ended(didFinishSpeechUtterance) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            scope.launch { ended(didCancelSpeechUtterance) }
        }
    }
    // The synthesizer keeps its delegate weakly; `reports` lives as long as this object.
    private val speaker = AVSpeechSynthesizer().also { it.delegate = reports }
    private val voicesChanged = NSNotificationCenter.defaultCenter.addObserverForName(
        AVSpeechSynthesisAvailableVoicesDidChangeNotification, null, NSOperationQueue.mainQueue,
    ) { voice = null }

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

    private fun say(args: LocalSpeechArgs, replace: Boolean): LocalSpeechStatus {
        val id = args.sessionId
        when (queue.admit(id, replace)) {
            LocalSpeechQueue.Admission.CANCELLED -> return LocalSpeechStatus(id, "cancelled")
            LocalSpeechQueue.Admission.BUSY -> return LocalSpeechStatus(id, "error")
            LocalSpeechQueue.Admission.QUEUE -> Unit
            LocalSpeechQueue.Admission.START -> {
                flush()
                release?.cancel()
                if (!audio.begin(IosAudioSession.Use.SPEECH)) {
                    queue.fail(queue.ticket)
                    return LocalSpeechStatus(id, "error")
                }
                watch(SPEECH_START_BUDGET_MS)
            }
        }
        val utterance = AVSpeechUtterance.speechUtteranceWithString(args.text)
        chosenVoice()?.let { utterance.voice = it }
        live += utterance
        speaker.speakUtterance(utterance)
        return LocalSpeechStatus(id, queue.status(id))
    }

    private fun started(utterance: AVSpeechUtterance) {
        if (live.any { it == utterance }) watch(speechBudgetMs(utterance.speechString.length))
    }

    private fun ended(utterance: AVSpeechUtterance) {
        val index = live.indexOfFirst { it == utterance }
        if (index < 0) return
        live.removeAt(index)
        if (queue.ended(queue.ticket)) {
            stall?.cancel()
            releaseSession(SESSION_LINGER_MS)
        } else {
            watch(SPEECH_START_BUDGET_MS)
        }
    }

    /** The live queue is over before its end: silence the synthesizer and give the session back, now if iOS lets it go. */
    private fun drop() {
        flush()
        stall?.cancel()
        release?.cancel()
        if (!audio.end(IosAudioSession.Use.SPEECH)) releaseSession(SESSION_RETRY_MS)
    }

    private fun flush() {
        live.clear()
        if (speaker.speaking || speaker.paused) speaker.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    /** Drops the queue as `error` when the synthesizer reports nothing for [budgetMs]. */
    private fun watch(budgetMs: Long) {
        val ticket = queue.ticket
        stall?.cancel()
        stall = scope.launch {
            delay(budgetMs)
            if (queue.fail(ticket)) {
                log(LogLevel.Warn, TAG, "The synthesizer stopped reporting; the reply's voice was dropped")
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
                if (queue.speaking || audio.end(IosAudioSession.Use.SPEECH)) return@launch
                delay(SESSION_RETRY_MS)
            }
        }
    }

    private fun chosenVoice(): AVSpeechSynthesisVoice? {
        val language = AVSpeechSynthesisVoice.currentLanguageCode()
        voice?.takeIf { it.first == language }?.let { return it.second }
        val installed = AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>()
        val best = bestInstalledVoice(installed.map(::installedVoice), language)
        val chosen = best?.let { AVSpeechSynthesisVoice.voiceWithIdentifier(it) } ?: AVSpeechSynthesisVoice.voiceWithLanguage(language)
        log(LogLevel.Info, TAG, "Voice for $language: ${chosen?.name ?: "system default"}")
        voice = language to chosen
        return chosen
    }

    override fun close() {
        interrupt()
        scope.cancel()
        speaker.delegate = null
        NSNotificationCenter.defaultCenter.removeObserver(voicesChanged)
        audio.end(IosAudioSession.Use.SPEECH)
    }
}

/** One of Apple's voices as [bestInstalledVoice] ranks it. */
internal fun installedVoice(voice: AVSpeechSynthesisVoice): InstalledVoice = InstalledVoice(
    id = voice.identifier,
    language = voice.language,
    quality = when (voice.quality) {
        AVSpeechSynthesisVoiceQualityPremium -> 3
        AVSpeechSynthesisVoiceQualityEnhanced -> 2
        else -> 1
    },
    novelty = (voice.voiceTraits and AVSpeechSynthesisVoiceTraitIsNoveltyVoice) != 0uL,
)
