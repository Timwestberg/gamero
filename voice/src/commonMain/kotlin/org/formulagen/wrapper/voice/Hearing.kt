package org.formulagen.wrapper.voice

/** 16 kHz mono, which is what Silero v5 and the streaming Zipformer both take. */
const val HEARING_SAMPLE_RATE = 16_000

/** A tap shorter than this is not a turn. */
const val HEARING_MIN_SPEECH_MS = 240

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
    fun partial(): String
    fun finish(): HearingResult
    fun close()
}

/** Recognizer files are not on disk, or this build has no sherpa-onnx jar. */
class HearingUnavailable(message: String) : IllegalStateException(message)

internal fun createMissingHearing(reason: String): HearingEngine = object : HearingEngine {
    override fun startUtterance() = throw HearingUnavailable(reason)
    override fun accept(samples: FloatArray) = Unit
    override fun partial() = ""
    override fun finish() = HearingResult("", 0, 0.0)
    override fun close() = Unit
}
