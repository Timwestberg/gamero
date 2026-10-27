@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.formulagen.wrapper.voice

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.sqrt
import kotlin.time.TimeSource

const val SPEECH_START = "speech_start"
const val SPEECH_LEVEL = "speech_level"
const val SPEECH_END = "speech_end"

data class SpeechEvent(
    val name: String,
    val rms: Float = 0f,
    val audioSeconds: Double = 0.0,
)

fun interface SpeechListener {
    fun onEvent(event: SpeechEvent)
}

/**
 * One synthesized piece. [ttfaMs] is the engine's first audio callback.
 * [synthMs] is the whole call, which is what a buffered retry has to wait for.
 */
data class PieceAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val ttfaMs: Double,
    val synthMs: Double = ttfaMs,
)

/** No retry: the engine's first callback. A retry is buffered, so both takes count. */
fun firstAudibleMs(callbackMs: Double, retried: Boolean, spentMs: Double): Double {
    return if (retried) spentMs else callbackMs
}

/**
 * Platform PocketTTS. Implementations must not claim frame-streaming decode
 * unless the runtime really emits audio while the language model is still
 * generating. sherpa-onnx 2.0.0 does not.
 */
interface PieceEngine {
    val sampleRate: Int
    fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio
    fun release()
}

data class SpeakResult(
    val samples: FloatArray,
    val sampleRate: Int,
    val ttfaMs: Double,
    val totalMs: Double,
    val audioSeconds: Double,
    val rtf: Double,
    val events: List<SpeechEvent>,
)

/**
 * Shared speak path: split, cap frames, drop a runaway take, then emit
 * speech_start, one speech_level per piece, and speech_end.
 * The first piece is buffered so a long take can be replaced before it is heard.
 */
@OptIn(ExperimentalAtomicApi::class)
class EmberVoice(
    private val engine: PieceEngine,
    private val config: VoiceConfig = VoiceConfig(),
) {
    private val state = AtomicInt(IDLE)

    fun speak(text: String, listener: SpeechListener? = null): SpeakResult {
        if (!state.compareAndSet(IDLE, SPEAKING)) {
            throw IllegalStateException(
                if (state.load() == RELEASED) "EmberVoice was released" else "EmberVoice is already speaking",
            )
        }
        try {
            return speakLocked(text, listener)
        } finally {
            state.compareAndSet(SPEAKING, IDLE)
        }
    }

    fun release() {
        if (!state.compareAndSet(IDLE, RELEASED)) {
            if (state.load() == RELEASED) return
            throw IllegalStateException("EmberVoice is speaking")
        }
        engine.release()
    }

    private fun speakLocked(text: String, listener: SpeechListener?): SpeakResult {
        val events = ArrayList<SpeechEvent>()
        fun emit(event: SpeechEvent) {
            events.add(event)
            listener?.onEvent(event)
        }
        emit(SpeechEvent(SPEECH_START))
        val started = nowMs()
        val pieces = speechPieces(text, config.firstPieceTargetChars, config.laterPieceMaxChars)
        if (pieces.isEmpty()) {
            emit(SpeechEvent(SPEECH_END, audioSeconds = 0.0))
            return SpeakResult(FloatArray(0), engine.sampleRate, 0.0, 0.0, 0.0, 0.0, events)
        }
        val parts = ArrayList<FloatArray>()
        var ttfaMs = 0.0
        var sampleRate = engine.sampleRate
        for ((index, piece) in pieces.withIndex()) {
            val frames = config.framesFor(piece.length)
            var take = engine.synthesize(piece, frames, config.seed)
            var spentMs = take.synthMs
            var retried = false
            if (frames > 0 && ranLong(config, piece, take)) {
                val second = engine.synthesize(piece, frames, config.seed + 1)
                retried = true
                spentMs += second.synthMs
                if (second.samples.size <= take.samples.size) take = second
            }
            if (index == 0) ttfaMs = firstAudibleMs(take.ttfaMs, retried, spentMs)
            sampleRate = take.sampleRate
            emit(SpeechEvent(SPEECH_LEVEL, rms = rms(take.samples)))
            parts.add(take.samples)
        }
        val gap = sampleRate * config.silenceMs / 1000
        val samples = concat(parts, gap)
        val totalMs = nowMs() - started
        val audioSeconds = if (sampleRate > 0) samples.size / sampleRate.toDouble() else 0.0
        val rtf = if (totalMs > 0.0) audioSeconds / (totalMs / 1000.0) else 0.0
        emit(SpeechEvent(SPEECH_END, audioSeconds = audioSeconds))
        return SpeakResult(samples, sampleRate, ttfaMs, totalMs, audioSeconds, rtf, events)
    }
}

internal fun ranLong(config: VoiceConfig, piece: String, audio: PieceAudio): Boolean {
    if (audio.sampleRate <= 0) return false
    val seconds = audio.samples.size / audio.sampleRate.toDouble()
    val expected = expectedAudioSeconds(piece.length)
    return seconds > config.maxDurationRatio * expected
}

fun rms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (sample in samples) sum += sample * sample
    return sqrt(sum / samples.size).toFloat()
}

internal fun concat(parts: List<FloatArray>, gap: Int): FloatArray {
    if (parts.isEmpty()) return FloatArray(0)
    val silence = if (gap > 0) gap else 0
    var total = 0
    for (part in parts) total += part.size
    if (parts.size > 1) total += silence * (parts.size - 1)
    val out = FloatArray(total)
    var pos = 0
    for ((index, part) in parts.withIndex()) {
        part.copyInto(out, pos)
        pos += part.size
        if (index < parts.lastIndex) pos += silence
    }
    return out
}

private const val IDLE = 0
private const val SPEAKING = 1
private const val RELEASED = 2

private val clockOrigin = TimeSource.Monotonic.markNow()

internal fun nowMs(): Double = clockOrigin.elapsedNow().inWholeMilliseconds.toDouble()
