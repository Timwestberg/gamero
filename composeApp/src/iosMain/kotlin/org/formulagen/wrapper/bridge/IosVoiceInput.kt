@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.formulagen.wrapper.bridge

import kotlinx.coroutines.*
import platform.AVFAudio.*
import platform.Foundation.*
import platform.Speech.*
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import kotlin.coroutines.resume

/** iPhone speech capture stays on the phone and is cancelled even when JavaScript is suspended. */
internal class IosVoiceInput : VoiceInputCapability, VoicePlaybackCapability, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val speaker = AVSpeechSynthesizer()
    private var playback: String? = null
    private var speechDeadline: Job? = null
    private val speechStops = linkedSetOf<String>()
    private val sessions = VoiceInputSessions()
    private var engine: AVAudioEngine? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var deadline: Job? = null
    private val background = NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue,
    ) { invalidate() }
    private val interrupted = NSNotificationCenter.defaultCenter.addObserverForName(
        AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue,
    ) { invalidate() }

    override suspend fun availability(): VoiceInputAvailability = withContext(Dispatchers.Main) {
        val recognizer = SFSpeechRecognizer()
        val authorization = SFSpeechRecognizer.authorizationStatus()
        VoiceInputAvailability(
            when {
                authorization == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied || authorization == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusRestricted -> "unavailable"
                authorization == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined -> "permission-required"
                recognizer?.available != true -> "unavailable"
                else -> "ready"
            }, recognizer?.supportsOnDeviceRecognition == true,
            if (authorization == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied) "denied" else null,
        )
    }

    override suspend fun start(args: VoiceInputStart): VoiceInputSnapshot = withContext(Dispatchers.Main) {
        val id = args.sessionId
        if (sessions.contains(id)) return@withContext sessions.read(id)
        val initial = sessions.begin(id)
        if (initial.state != "starting") return@withContext initial
        // Reply immediately; permission work remains cancellable.
        scope.launch {
            val speech = suspendCancellableCoroutine<Boolean> { continuation ->
                SFSpeechRecognizer.requestAuthorization { status -> if (continuation.isActive) continuation.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) }
            }
            if (!sessions.owns(id)) return@launch
            val microphone = if (speech) suspendCancellableCoroutine<Boolean> { continuation ->
                AVAudioSession.sharedInstance().requestRecordPermission { granted -> if (continuation.isActive) continuation.resume(granted) }
            } else false
            if (!sessions.owns(id)) return@launch
            if (!speech || !microphone) { sessions.update(id, "error", reason = "denied"); return@launch }
            try {
                val recognizer = args.locale?.let { SFSpeechRecognizer(NSLocale(it)) } ?: SFSpeechRecognizer()
                if (recognizer?.available != true) { sessions.update(id, "error", reason = "unavailable"); return@launch }
                val audio = AVAudioSession.sharedInstance()
                check(audio.setCategory(AVAudioSessionCategoryRecord, mode = AVAudioSessionModeMeasurement, options = AVAudioSessionCategoryOptionDuckOthers, error = null))
                check(audio.setActive(true, error = null))
                val capture = AVAudioEngine()
                val buffer = SFSpeechAudioBufferRecognitionRequest()
                buffer.shouldReportPartialResults = true
                if (recognizer.supportsOnDeviceRecognition) buffer.requiresOnDeviceRecognition = true
                engine = capture
                request = buffer
                task = recognizer.recognitionTaskWithRequest(buffer) { result, error ->
                    scope.launch {
                        if (!sessions.owns(id)) return@launch
                        val words = result?.bestTranscription?.formattedString
                        if (result?.final == true) {
                            releaseAudio()
                            sessions.update(id, "done", final = words ?: "")
                        } else if (error != null) {
                            releaseAudio()
                            sessions.update(id, "error", reason = "unavailable")
                        } else if (words != null) sessions.update(id, sessions.read(id).state, partial = words)
                    }
                }
                val node = capture.inputNode
                node.installTapOnBus(0u, bufferSize = 1024u, format = node.outputFormatForBus(0u)) { pcm, _ -> if (pcm != null) buffer.appendAudioPCMBuffer(pcm) }
                capture.prepare()
                check(capture.startAndReturnError(null))
                sessions.update(id, "listening")
                deadline?.cancel()
                deadline = scope.launch { delay(120_000); stop(VoiceInputStop(id, "keep")) }
            } catch (_: Exception) {
                releaseAudio()
                sessions.update(id, "error", reason = "unavailable")
            }
        }
        deadline?.cancel()
        deadline = scope.launch { delay(30_000); if (sessions.read(id).state == "starting") stop(VoiceInputStop(id, "discard")) }
        initial
    }

    override suspend fun stop(args: VoiceInputStop): VoiceInputSnapshot = withContext(Dispatchers.Main) {
        val id = args.sessionId
        if (!sessions.owns(id)) return@withContext sessions.update(id, "cancelled")
        if (sessions.read(id).state == "stopping") return@withContext sessions.read(id)
        val keep = args.disposition == "keep" && sessions.read(id).state == "listening"
        releaseAudio()
        if (!keep) {
            task?.cancel(); task = null; request = null
            return@withContext sessions.update(id, "cancelled")
        }
        sessions.update(id, "stopping")
        request?.endAudio()
        deadline = scope.launch {
            delay(5_000)
            if (sessions.owns(id)) { task?.cancel(); sessions.update(id, "error", reason = "timeout") }
        }
        sessions.read(id)
    }
    override suspend fun status(args: VoiceInputScope) = withContext(Dispatchers.Main) { sessions.read(args.sessionId) }
    override suspend fun speak(args: LocalSpeechArgs): LocalSpeechStatus = withContext(Dispatchers.Main) {
        if (args.sessionId in speechStops) return@withContext LocalSpeechStatus(args.sessionId, "cancelled")
        if (playback != null && playback != args.sessionId && speaker.speaking) return@withContext LocalSpeechStatus(args.sessionId, "error")
        if (playback == args.sessionId) return@withContext speechStatus(VoiceInputScope(args.sessionId))
        val audio = AVAudioSession.sharedInstance()
        audio.setCategory(AVAudioSessionCategoryPlayback, error = null)
        audio.setActive(true, error = null)
        playback = args.sessionId
        speaker.speakUtterance(AVSpeechUtterance.speechUtteranceWithString(args.text))
        speechDeadline?.cancel()
        speechDeadline = scope.launch { delay(120_000); stopSpeech(VoiceInputScope(args.sessionId)) }
        LocalSpeechStatus(args.sessionId, "speaking")
    }
    override suspend fun stopSpeech(args: VoiceInputScope): LocalSpeechStatus = withContext(Dispatchers.Main) {
        speechStops.add(args.sessionId)
        while (speechStops.size > 32) speechStops.remove(speechStops.first())
        if (playback == args.sessionId) {
            speaker.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
            speechDeadline?.cancel()
            AVAudioSession.sharedInstance().setActive(false, error = null)
        }
        LocalSpeechStatus(args.sessionId, "cancelled")
    }
    override suspend fun speechStatus(args: VoiceInputScope): LocalSpeechStatus = withContext(Dispatchers.Main) {
        LocalSpeechStatus(args.sessionId, when {
            args.sessionId in speechStops -> "cancelled"
            playback != args.sessionId -> "error"
            speaker.speaking -> "speaking"
            else -> "done"
        })
    }
    /** Release recording immediately; recognition may finish after capture ends. */
    private fun releaseAudio() {
        deadline?.cancel(); deadline = null
        engine?.let { it.stop(); it.inputNode.removeTapOnBus(0u) }
        engine = null
        AVAudioSession.sharedInstance().setActive(false, error = null)
    }
    /** Called for backgrounding, audio interruption and document replacement. */
    fun invalidate() {
        playback?.let { speechStops.add(it) }; speaker.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate); speechDeadline?.cancel()
        sessions.cancelAll(); releaseAudio(); task?.cancel(); task = null; request = null
    }
    override fun close() {
        invalidate(); scope.cancel()
        NSNotificationCenter.defaultCenter.removeObserver(background)
        NSNotificationCenter.defaultCenter.removeObserver(interrupted)
    }
}
