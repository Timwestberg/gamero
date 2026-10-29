package org.formulagen.wrapper.voice

/** 16 kHz mono, which is what Silero v5 and the streaming Zipformer both take. */
const val HEARING_SAMPLE_RATE = 16_000

/** A tap shorter than this is not a turn. */
const val HEARING_MIN_SPEECH_MS = 240

/** Hands-free endpoint: this much quiet after speech sends the turn. */
const val HEARING_ENDPOINT_SILENCE_MS = 1000

/** After Ember stops, wait this long before opening the microphone again. */
const val HEARING_RESUME_GUARD_MS = 300

const val HEARING_VAD = "silero_vad.onnx"
const val HEARING_ENCODER = "zipformer/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
const val HEARING_DECODER = "zipformer/decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
const val HEARING_JOINER = "zipformer/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
const val HEARING_TOKENS = "zipformer/tokens.txt"

data class HearingResult(val text: String, val speechMs: Int, val decodeMs: Double)

/**
 * One utterance. [accept] may be called from the microphone thread.
 * [finish] is called after the microphone has stopped.
 */
interface HearingEngine {
    fun startUtterance()
    fun accept(samples: FloatArray)
    /** Voiced milliseconds inside the last [accept], from the VAD windows in that buffer. */
    fun voicedMillisOfLastAccept(): Int = 0
    fun partial(): String
    fun finish(): HearingResult
    fun close()
}

/** Recognizer files are not on disk, or this build has no sherpa-onnx jar. */
class HearingUnavailable(message: String) : IllegalStateException(message)

internal fun createMissingHearing(reason: String): HearingEngine = object : HearingEngine {
    override fun startUtterance() = throw HearingUnavailable(reason)
    override fun accept(samples: FloatArray) = Unit
    override fun voicedMillisOfLastAccept() = 0
    override fun partial() = ""
    override fun finish() = HearingResult("", 0, 0.0)
    override fun close() = Unit
}

enum class EndpointDecision { LISTEN, SEND, DROP }

/**
 * After speech, [silenceMs] of quiet ends the turn. A short burst is [DROP]
 * (noise) and the caller keeps listening. Silence before any speech stays [LISTEN].
 */
class SilenceEndpoint(
    val silenceMs: Int = HEARING_ENDPOINT_SILENCE_MS,
    val minSpeechMs: Int = HEARING_MIN_SPEECH_MS,
) {
    private var speechMs = 0
    private var trailingMs = 0
    private var heard = false

    fun reset() {
        speechMs = 0
        trailingMs = 0
        heard = false
    }

    fun frame(voiced: Boolean, frameMs: Int): EndpointDecision {
        if (frameMs <= 0) return EndpointDecision.LISTEN
        if (voiced) {
            heard = true
            speechMs += frameMs
            trailingMs = 0
            return EndpointDecision.LISTEN
        }
        if (!heard) return EndpointDecision.LISTEN
        trailingMs += frameMs
        if (trailingMs < silenceMs) return EndpointDecision.LISTEN
        val enough = speechMs >= minSpeechMs
        reset()
        return if (enough) EndpointDecision.SEND else EndpointDecision.DROP
    }
}
