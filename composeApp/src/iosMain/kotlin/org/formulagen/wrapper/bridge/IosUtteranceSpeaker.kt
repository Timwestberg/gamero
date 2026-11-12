@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.formulagen.wrapper.bridge

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

private const val TAG = "IosUtteranceSpeaker"

/**
 * What says the phone's utterances and reports each one's start and end: Kokoro ([IosKokoroSpeaker]) where the app
 * carries it, Apple's synthesizer ([AppleSpeaker]) otherwise. [IosSpeechPlayback] holds the queue's rules and the
 * audio session; a speaker only makes the sound. Every call and every report is on the main thread.
 */
internal interface IosUtteranceSpeaker {
    /** How long an utterance handed over may take before its first sound. */
    val startBudgetMs: Long

    /**
     * Says [text] after what the speaker already holds. [started] runs when its first sound plays, and [ended] when
     * its last has, or when it could not be said at all. An utterance dropped by [flush] reports neither.
     */
    fun say(text: String, started: () -> Unit, ended: () -> Unit)

    /** Silences the speaker and forgets what it holds. */
    fun flush()

    /** Gets ready to speak, where that takes a while. */
    fun warm() = Unit

    /** Nothing is being said and the audio session is going back: lets go of what only speaking needs. */
    fun rest() = Unit

    /** The voices a person can choose between. None when the speaker has one voice it cannot change. */
    fun voices(): List<PlaybackVoice> = emptyList()

    /** The id of the voice in use, one of [voices]. Null when there are none. */
    val voiceId: String? get() = null

    /** Speaks with the voice [id] from the next utterance on, and remembers it. False when [id] is not one of [voices]. */
    fun choose(id: String): Boolean = false

    fun close()
}

/**
 * Apple's on-device synthesizer with the best voice the person has installed. The synthesizer's own queue holds the
 * utterances, and its delegate reports each one's start and end.
 */
internal class AppleSpeaker : IosUtteranceSpeaker {
    private class Report(val started: () -> Unit, val ended: () -> Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** The utterances the synthesizer has not finished. A flush empties it, so late reports find nothing. */
    private val live = mutableListOf<Pair<AVSpeechUtterance, Report>>()
    /** The chosen voice for a language. An entry with a null voice means "use the system's default". */
    private var voice: Pair<String, AVSpeechSynthesisVoice?>? = null

    private val reports = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) {
            scope.launch { live.firstOrNull { it.first == didStartSpeechUtterance }?.second?.started?.invoke() }
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

    override val startBudgetMs: Long = SPEECH_START_BUDGET_MS

    override fun say(text: String, started: () -> Unit, ended: () -> Unit) {
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        chosenVoice()?.let { utterance.voice = it }
        live += utterance to Report(started, ended)
        speaker.speakUtterance(utterance)
    }

    override fun flush() {
        live.clear()
        if (speaker.speaking || speaker.paused) speaker.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    private fun ended(utterance: AVSpeechUtterance) {
        val index = live.indexOfFirst { it.first == utterance }
        if (index >= 0) live.removeAt(index).second.ended()
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
        flush()
        scope.cancel()
        speaker.delegate = null
        NSNotificationCenter.defaultCenter.removeObserver(voicesChanged)
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
