@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionUndetermined
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.Foundation.NSLocale
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import kotlin.coroutines.resume

/** A capture that has not started listening this long after its permissions were settled is given up. */
private const val START_LIMIT_MS = 30_000L
private const val LISTEN_LIMIT_MS = 120_000L
private const val FINISH_LIMIT_MS = 5_000L
private const val FINISH_WITH_WORDS_LIMIT_MS = 1_500L
private const val TAG = "IosVoiceInput"
private const val VOICE_SAY_ENV = "GAMERO_VOICE_SAY"
private const val VOICE_SAY_SESSION = "00000000-0000-4000-8000-00000000cafe"
private const val VOICE_SAY_AFTER_MS = 2_000L
private const val VOICE_SAY_POLL_MS = 200L
private const val VOICE_SAY_REPLIES = " || "

/**
 * The recognizer's "no speech" errors (`kAFAssistantErrorDomain`): 1110 is "No speech detected", and 203 is what it
 * answers when asked to finish with nothing to report.
 */
private val NO_SPEECH_CODES = setOf(1110L, 203L)

/**
 * Dictation and the companion's voice on iPhone, which share one audio session. iOS events that end either one
 * arrive here: the app going to the background, an audio interruption (a call, Siri) and the page going away stop
 * both; headphones coming off stop the voice, so a reply does not continue out loud.
 */
internal class IosVoice : AutoCloseable {
    val audio = IosAudioSession()
    val playback = IosSpeechPlayback(audio)
    // The person starting to talk silences the companion, and gets its voice ready for the reply.
    val input = IosVoiceInput(audio, bargeIn = {
        playback.interrupt()
        playback.warm()
    })
    private val center = NSNotificationCenter.defaultCenter
    private val observers = listOf(
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { invalidate() },
        center.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { note ->
            if (number(note, AVAudioSessionInterruptionTypeKey) == AVAudioSessionInterruptionTypeBegan) {
                audio.lost()
                invalidate()
            }
        },
        center.addObserverForName(AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue) { note ->
            if (number(note, AVAudioSessionRouteChangeReasonKey) == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) playback.interrupt()
        },
    )

    init {
        // A development check: `GAMERO_VOICE_SAY="…"` at launch speaks the text once, sentence by sentence as a reply
        // arrives, so a build's voice can be heard, and its speed read from the log, without a conversation. Texts
        // separated by " || " are spoken as replies of their own, each after the one before it has ended and the
        // audio session has gone back.
        (NSProcessInfo.processInfo.environment[VOICE_SAY_ENV] as? String)?.takeIf { it.isNotBlank() }?.let { text ->
            CoroutineScope(Dispatchers.Main).launch {
                delay(VOICE_SAY_AFTER_MS)
                text.split(VOICE_SAY_REPLIES).filter { it.isNotBlank() }.forEachIndexed { reply, words ->
                    val session = VOICE_SAY_SESSION.dropLast(1) + reply % 10
                    val sentences = words.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                    sentences.forEachIndexed { index, sentence ->
                        val args = LocalSpeechArgs(session, sentence)
                        if (index == 0) playback.speak(args) else playback.append(args)
                    }
                    while (playback.speechStatus(VoiceInputScope(session)).state == "speaking") delay(VOICE_SAY_POLL_MS)
                    delay(VOICE_SAY_AFTER_MS)
                }
            }
        }
    }

    /** Called for backgrounding, an audio interruption and document replacement. */
    fun invalidate() {
        playback.interrupt()
        input.invalidate()
    }

    override fun close() {
        observers.forEach(center::removeObserver)
        input.close()
        playback.close()
    }

    private fun number(note: NSNotification?, key: String?): ULong? = (note?.userInfo?.get(key) as? NSNumber)?.unsignedLongValue
}

/**
 * iPhone speech capture with Apple's recognizer. Words are recognized on the phone when iOS has an on-device model
 * for the language, and `availability().offline` is then true. For a language without one, iOS sends the audio to
 * Apple's speech service, and `offline` is false. Capture is cancelled natively, even when JavaScript is suspended.
 *
 * State lives on the main thread.
 */
internal class IosVoiceInput(private val audio: IosAudioSession, private val bargeIn: () -> Unit = {}) : VoiceInputCapability, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessions = VoiceInputSessions()
    private var engine: AVAudioEngine? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    /** The words of the capture in progress, kept across the stretches iOS closes. */
    private var words: HeardWords? = null
    private var deadline: Job? = null
    /** iOS stops the engine when the input route changes (headphones on or off). Listen again on the new route. */
    private val rerouted = NSNotificationCenter.defaultCenter.addObserverForName(
        AVAudioEngineConfigurationChangeNotification, null, NSOperationQueue.mainQueue,
    ) { note -> if (engine != null && note?.`object` == engine) resume() }

    override suspend fun availability(): VoiceInputAvailability = withContext(Dispatchers.Main) {
        val recognizer = recognizerFor(null)
        val speech = SFSpeechRecognizer.authorizationStatus()
        val microphone = AVAudioApplication.sharedInstance.recordPermission
        val offline = recognizer?.supportsOnDeviceRecognition == true
        when {
            speech == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied ||
                microphone == AVAudioApplicationRecordPermissionDenied -> VoiceInputAvailability("unavailable", offline, "denied")
            speech == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusRestricted -> VoiceInputAvailability("unavailable", offline)
            recognizer == null -> VoiceInputAvailability("unavailable", false, "unsupported")
            audio.meeting() -> VoiceInputAvailability("unavailable", offline, "busy")
            promptPending() -> VoiceInputAvailability("permission-required", offline)
            // Without an on-device model the recognizer needs Apple's service, and it is unavailable when that is out of reach.
            !recognizer.available -> VoiceInputAvailability("unavailable", offline, if (offline) null else "offline")
            else -> VoiceInputAvailability("ready", offline)
        }
    }

    override suspend fun start(args: VoiceInputStart): VoiceInputSnapshot = withContext(Dispatchers.Main) {
        val id = args.sessionId
        if (sessions.contains(id)) return@withContext sessions.read(id)
        val initial = sessions.begin(id)
        if (initial.state != "starting") return@withContext initial
        // The person may read a system prompt for as long as they like; the start limit counts only the app's own work.
        val prompting = promptPending()
        if (!prompting) limit(START_LIMIT_MS) { if (sessions.read(id).state == "starting") stop(VoiceInputStop(id, "discard")) }
        // Reply immediately; permission work remains cancellable.
        scope.launch {
            val speech = suspendCancellableCoroutine<Boolean> { continuation ->
                SFSpeechRecognizer.requestAuthorization { status -> if (continuation.isActive) continuation.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) }
            }
            if (!sessions.owns(id)) return@launch
            val microphone = speech && suspendCancellableCoroutine<Boolean> { continuation ->
                AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted -> if (continuation.isActive) continuation.resume(granted) }
            }
            if (!sessions.owns(id)) return@launch
            if (!speech || !microphone) { sessions.update(id, "error", reason = "denied"); return@launch }
            if (prompting) limit(START_LIMIT_MS) { if (sessions.read(id).state == "starting") stop(VoiceInputStop(id, "discard")) }
            listen(id, args.locale)
        }
        initial
    }

    private fun listen(id: String, locale: String?) {
        try {
            val recognizer = recognizerFor(locale)
            if (recognizer?.available != true) { sessions.update(id, "error", reason = "unavailable"); return }
            if (!audio.begin(IosAudioSession.Use.CAPTURE)) { sessions.update(id, "error", reason = "busy"); return }
            // The companion stops talking when the person starts: the session now records, and its voice is cancelled.
            bargeIn()
            val capture = AVAudioEngine()
            val buffer = SFSpeechAudioBufferRecognitionRequest()
            buffer.shouldReportPartialResults = true
            if (recognizer.supportsOnDeviceRecognition) buffer.requiresOnDeviceRecognition = true
            engine = capture
            request = buffer
            val heard = HeardWords()
            words = heard
            task = recognizer.recognitionTaskWithRequest(buffer) { result, error ->
                scope.launch {
                    if (!sessions.owns(id)) return@launch
                    // iOS closes a stretch of speech after a pause (it then carries metadata) and starts the next from nothing.
                    val all = result?.let { heard.hear(it.bestTranscription.formattedString, it.final || it.speechRecognitionMetadata != null) } ?: heard.all
                    when {
                        result?.final == true -> finish(id, RecognitionEnd.HEARD, all, "final")
                        error != null -> finish(id, recognitionEnd(all, noSpeech = error.code in NO_SPEECH_CODES), all, "error ${error.domain} ${error.code}")
                        all.isNotBlank() -> sessions.update(id, sessions.read(id).state, partial = all)
                    }
                }
            }
            check(tap(capture, buffer))
            capture.prepare()
            check(capture.startAndReturnError(null))
            sessions.update(id, "listening")
            log(LogLevel.Info, TAG, "Capture listening (on-device: ${recognizer.supportsOnDeviceRecognition})")
            limit(LISTEN_LIMIT_MS) { stop(VoiceInputStop(id, "keep")) }
        } catch (failure: Exception) {
            log(LogLevel.Warn, TAG, "Capture could not start", failure)
            releaseCapture()
            sessions.update(id, "error", reason = "unavailable")
        }
    }

    /** Feeds [buffer] from the microphone. False when the input has no usable format; a tap on it would raise an Objective-C exception. */
    private fun tap(capture: AVAudioEngine, buffer: SFSpeechAudioBufferRecognitionRequest): Boolean {
        val node = capture.inputNode
        val format = node.outputFormatForBus(0u)
        if (format.sampleRate <= 0.0 || format.channelCount == 0u) return false
        node.installTapOnBus(0u, bufferSize = 1024u, format = format) { pcm, _ -> if (pcm != null) buffer.appendAudioPCMBuffer(pcm) }
        return true
    }

    /** Restarts a listening engine after a route change. If the new route cannot record, the words heard so far are kept. */
    private fun resume() {
        val id = sessions.owner ?: return
        val capture = engine ?: return
        val buffer = request ?: return
        if (sessions.read(id).state != "listening") return
        capture.inputNode.removeTapOnBus(0u)
        val listening = tap(capture, buffer) && run { capture.prepare(); capture.startAndReturnError(null) }
        log(LogLevel.Info, TAG, "The audio route changed while listening; listening again: $listening")
        if (!listening) scope.launch { stop(VoiceInputStop(id, "keep")) }
    }

    override suspend fun stop(args: VoiceInputStop): VoiceInputSnapshot = withContext(Dispatchers.Main) {
        val id = args.sessionId
        if (!sessions.owns(id)) return@withContext sessions.update(id, "cancelled")
        if (sessions.read(id).state == "stopping") return@withContext sessions.read(id)
        val keep = args.disposition == "keep" && sessions.read(id).state == "listening"
        releaseCapture()
        if (!keep) {
            task?.cancel(); task = null; request = null; words = null
            return@withContext sessions.update(id, "cancelled")
        }
        sessions.update(id, "stopping")
        request?.endAudio()
        // The recognizer usually answers at once. With words already heard there is little left for it to add, so the
        // wait for it is short; with none, it gets longer to produce its first.
        val heard = words?.all.orEmpty()
        limit(if (heard.isBlank()) FINISH_LIMIT_MS else FINISH_WITH_WORDS_LIMIT_MS) {
            if (sessions.owns(id)) {
                task?.cancel()
                val all = words?.all.orEmpty()
                if (all.isBlank()) sessions.update(id, "error", reason = "timeout").also { log(LogLevel.Warn, TAG, "Capture ended: the recognizer did not finish, and nothing was heard") }
                else finish(id, RecognitionEnd.HEARD, all, "finish limit")
            }
        }
        sessions.read(id)
    }

    /** Ends the capture with what the recognizer's ending means. The log names how it ended and how much was heard, never the words. */
    private fun finish(id: String, end: RecognitionEnd, all: String, how: String) {
        log(if (end == RecognitionEnd.FAILED) LogLevel.Warn else LogLevel.Info, TAG, "Capture ended: $end after ${sessions.read(id).state}, ${all.length} characters ($how)")
        releaseCapture()
        when (end) {
            RecognitionEnd.HEARD -> sessions.update(id, "done", final = all)
            // Nothing was said: an empty turn, which the page offers to try again, not a broken microphone.
            RecognitionEnd.SILENCE -> sessions.update(id, "done", final = "")
            RecognitionEnd.FAILED -> sessions.update(id, "error", reason = "unavailable")
        }
    }

    override suspend fun status(args: VoiceInputScope) = withContext(Dispatchers.Main) { sessions.read(args.sessionId) }

    /** Called for backgrounding, audio interruption and document replacement. */
    fun invalidate() {
        sessions.cancelAll(); releaseCapture(); task?.cancel(); task = null; request = null; words = null
    }

    override fun close() {
        invalidate(); scope.cancel()
        NSNotificationCenter.defaultCenter.removeObserver(rerouted)
    }

    /** True while iOS has yet to ask the person for speech recognition or the microphone, so a start will show a prompt. */
    private fun promptPending(): Boolean =
        SFSpeechRecognizer.authorizationStatus() == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined ||
            AVAudioApplication.sharedInstance.recordPermission == AVAudioApplicationRecordPermissionUndetermined

    /** The recognizer for the page's language, or the device's when the page named none or iOS has none for it. */
    private fun recognizerFor(locale: String?): SFSpeechRecognizer? {
        val supported = SFSpeechRecognizer.supportedLocales().filterIsInstance<NSLocale>().map { it.localeIdentifier }
        val device = NSLocale.preferredLanguages.filterIsInstance<String>() + NSLocale.currentLocale.localeIdentifier
        val chosen = bestSpeechLocale(locale, device, supported)
        return chosen?.let { SFSpeechRecognizer(NSLocale(it)) } ?: SFSpeechRecognizer()
    }

    /** One deadline at a time: starting, listening or finishing. */
    private fun limit(milliseconds: Long, expired: suspend () -> Unit) {
        deadline?.cancel()
        deadline = scope.launch { delay(milliseconds); expired() }
    }

    /** Release recording immediately; recognition may finish after capture ends. The session is given back only if capture took it. */
    private fun releaseCapture() {
        deadline?.cancel(); deadline = null
        engine?.let { it.stop(); it.inputNode.removeTapOnBus(0u) }
        engine = null
        audio.end(IosAudioSession.Use.CAPTURE)
    }
}
