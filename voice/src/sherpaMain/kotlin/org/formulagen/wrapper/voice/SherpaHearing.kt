package org.formulagen.wrapper.voice

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.math.min

/**
 * Silero VAD v5 plus the streaming Zipformer, both through sherpa-onnx 2.0.0.
 * Push-to-talk finishes the stream on release. The recognizer stays loaded
 * across holds.
 */
class SherpaHearingEngine(modelDir: String) : HearingEngine {
    private val recognizer: OnlineRecognizer
    private val vad: Vad?
    private var stream: OnlineStream? = null
    private var voiced = false
    private var heardMs = 0
    private val vadWindow = FloatArray(VAD_WINDOW)
    private var vadFill = 0

    init {
        LibraryUtils.load()
        val root = File(modelDir)
        val missing = HEARING_MODEL_FILES.filter { !File(root, it).isFile }
        if (missing.isNotEmpty()) {
            throw HearingUnavailable(
                "Hearing models are missing (${missing.joinToString(", ")}). Run scripts/fetch-voice.sh, then rebuild.",
            )
        }
        val transducer = OnlineTransducerModelConfig.builder()
            .setEncoder(File(root, HEARING_ENCODER).path)
            .setDecoder(File(root, HEARING_DECODER).path)
            .setJoiner(File(root, HEARING_JOINER).path)
            .build()
        val model = OnlineModelConfig.builder()
            .setTransducer(transducer)
            .setTokens(File(root, HEARING_TOKENS).path)
            .setNumThreads(2)
            .setProvider("cpu")
            .build()
        val endpoint = EndpointConfig.builder()
            .setRule1(rule(mustSpeak = true, trailing = 1.2f, length = 0f))
            .setRule2(rule(mustSpeak = true, trailing = 0.32f, length = 0f))
            .setRule3(rule(mustSpeak = false, trailing = 0f, length = 30f))
            .build()
        val config = OnlineRecognizerConfig.builder()
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(HEARING_SAMPLE_RATE).setFeatureDim(80).build())
            .setOnlineModelConfig(model)
            .setEndpointConfig(endpoint)
            .setEnableEndpoint(true)
            .setDecodingMethod("greedy_search")
            .build()
        recognizer = OnlineRecognizer(config)
        vad = openVad(File(root, HEARING_VAD))
    }

    override fun startUtterance() {
        stream?.release()
        stream = recognizer.createStream()
        voiced = false
        heardMs = 0
        vadFill = 0
        vad?.reset()
    }

    override fun accept(samples: FloatArray) {
        val current = stream ?: return
        if (samples.isEmpty()) return
        heardMs += samples.size * 1000 / HEARING_SAMPLE_RATE
        current.acceptWaveform(samples, HEARING_SAMPLE_RATE)
        while (recognizer.isReady(current)) recognizer.decode(current)
        noteSpeech(samples)
    }

    override fun partial(): String = stream?.let { recognizer.getResult(it).text }.orEmpty()

    override fun finish(): HearingResult {
        val current = stream
        if (current == null) return HearingResult("", 0, 0.0)
        val started = System.nanoTime()
        current.inputFinished()
        while (recognizer.isReady(current)) recognizer.decode(current)
        val text = recognizer.getResult(current).text.trim()
        val decodeMs = (System.nanoTime() - started) / 1_000_000.0
        recognizer.reset(current)
        val speechMs = if (voiced) heardMs else 0
        return HearingResult(text, speechMs, decodeMs)
    }

    override fun close() {
        stream?.release()
        stream = null
        recognizer.release()
        vad?.release()
    }

    private fun noteSpeech(samples: FloatArray) {
        val detector = vad ?: run {
            voiced = true
            return
        }
        var offset = 0
        while (offset < samples.size) {
            val n = min(VAD_WINDOW - vadFill, samples.size - offset)
            samples.copyInto(vadWindow, vadFill, offset, offset + n)
            vadFill += n
            offset += n
            if (vadFill == VAD_WINDOW) {
                detector.acceptWaveform(vadWindow)
                if (detector.isSpeechDetected()) voiced = true
                vadFill = 0
            }
        }
    }
}

internal fun createHearingEngine(modelDir: String): HearingEngine {
    val root = File(modelDir)
    val missing = modelDir.isEmpty() || HEARING_MODEL_FILES.any { !File(root, it).isFile }
    if (missing) {
        return createMissingHearing(
            "Hearing models are missing. Run scripts/fetch-voice.sh, then rebuild.",
        )
    }
    return LazyHearingEngine(modelDir)
}

/** The Zipformer stays unloaded until the first hold, so opening the app does not pay for it. */
private class LazyHearingEngine(private val modelDir: String) : HearingEngine {
    private var opened: SherpaHearingEngine? = null

    private fun live(): SherpaHearingEngine = opened ?: SherpaHearingEngine(modelDir).also { opened = it }

    override fun startUtterance() = live().startUtterance()
    override fun accept(samples: FloatArray) = live().accept(samples)
    override fun partial(): String = opened?.partial().orEmpty()
    override fun finish(): HearingResult = opened?.finish() ?: HearingResult("", 0, 0.0)
    override fun close() {
        opened?.close()
        opened = null
    }
}

/** Decode one wav when the models are installed. Null when a file is missing. */
fun transcribeHearingFile(modelDir: String, wavPath: String): HearingResult? {
    val wav = File(wavPath)
    val root = File(modelDir)
    if (!wav.isFile || HEARING_MODEL_FILES.any { !File(root, it).isFile }) return null
    val engine = SherpaHearingEngine(modelDir)
    try {
        val samples = readPcm16Wav(wav.readBytes())
        engine.startUtterance()
        var offset = 0
        while (offset < samples.size) {
            val n = min(1600, samples.size - offset)
            engine.accept(samples.copyOfRange(offset, offset + n))
            offset += n
        }
        return engine.finish()
    } finally {
        engine.close()
    }
}

private val HEARING_MODEL_FILES = listOf(HEARING_VAD, HEARING_ENCODER, HEARING_DECODER, HEARING_JOINER, HEARING_TOKENS)

private const val VAD_WINDOW = 512

private fun rule(mustSpeak: Boolean, trailing: Float, length: Float): EndpointRule =
    EndpointRule.builder()
        .setMustContainNonSilence(mustSpeak)
        .setMinTrailingSilence(trailing)
        .setMinUtteranceLength(length)
        .build()

private fun openVad(model: File): Vad? {
    if (!model.isFile) return null
    val silero = SileroVadModelConfig.builder()
        .setModel(model.path)
        .setThreshold(0.5f)
        .setMinSilenceDuration(0.032f)
        .setMinSpeechDuration(0.032f)
        .setWindowSize(VAD_WINDOW)
        .setMaxSpeechDuration(30f)
        .build()
    val config = VadModelConfig.builder()
        .setSileroVadModelConfig(silero)
        .setSampleRate(HEARING_SAMPLE_RATE)
        .setNumThreads(1)
        .setProvider("cpu")
        .build()
    return Vad(config)
}

/** 16-bit little-endian PCM WAV, mixed to mono. The hearing sample is 16 kHz. */
internal fun readPcm16Wav(bytes: ByteArray): FloatArray {
    require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
        "hearing clip is not a WAV"
    }
    var channels = 1
    var rate = HEARING_SAMPLE_RATE
    var bits = 16
    var dataAt = -1
    var dataBytes = 0
    var cursor = 12
    while (cursor + 8 <= bytes.size) {
        val tag = String(bytes, cursor, 4)
        val size = le32(bytes, cursor + 4)
        val body = cursor + 8
        if (tag == "fmt " && size >= 16) {
            channels = le16(bytes, body + 2)
            rate = le32(bytes, body + 4)
            bits = le16(bytes, body + 14)
        } else if (tag == "data") {
            dataAt = body
            dataBytes = size
            break
        }
        cursor = body + size + (size and 1)
    }
    require(dataAt >= 0 && bits == 16 && rate == HEARING_SAMPLE_RATE && channels in 1..2) {
        "hearing clip must be 16 kHz 16-bit PCM"
    }
    val end = min(bytes.size, dataAt + dataBytes)
    val frames = (end - dataAt) / 2 / channels
    val out = FloatArray(frames)
    var frame = 0
    var pos = dataAt
    while (frame < frames) {
        var sum = 0
        var channel = 0
        while (channel < channels) {
            val lo = bytes[pos].toInt() and 0xff
            val hi = bytes[pos + 1].toInt()
            sum += (hi shl 8) or lo
            pos += 2
            channel += 1
        }
        out[frame] = (sum / channels) / 32768f
        frame += 1
    }
    return out
}

private fun le16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xff) or (bytes[at + 1].toInt() shl 8)

private fun le32(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xff) or
        ((bytes[at + 1].toInt() and 0xff) shl 8) or
        ((bytes[at + 2].toInt() and 0xff) shl 16) or
        (bytes[at + 3].toInt() shl 24)
