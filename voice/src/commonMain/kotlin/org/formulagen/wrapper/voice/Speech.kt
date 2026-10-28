@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.formulagen.wrapper.voice

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
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

    /** PocketTTS retries when a take fills [maxFrames]. Kokoro does not use that cap. */
    fun usesFrameCeiling(): Boolean = true
    fun requestCancel() {}
    fun clearCancel() {}
    fun isCancelled(): Boolean = false
}

class SpeechCancelled : RuntimeException("speech cancelled")

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
        return speakStreaming(text, listener) { _, _ -> true }
    }

    /**
     * Synthesizes one sentence at a time. [onSentence] is the raw sentence,
     * before the crossfade. Return false to drop the rest. Playback can start
     * from the first sentence while the next one is still being generated.
     */
    fun speakStreaming(
        text: String,
        listener: SpeechListener? = null,
        onSentence: (FloatArray, Int) -> Boolean,
    ): SpeakResult {
        if (!state.compareAndSet(IDLE, SPEAKING)) {
            throw IllegalStateException(
                if (state.load() == RELEASED) "EmberVoice was released" else "EmberVoice is already speaking",
            )
        }
        try {
            engine.clearCancel()
            return speakLocked(text, listener, onSentence)
        } finally {
            state.compareAndSet(SPEAKING, IDLE)
        }
    }

    fun cancel() {
        engine.requestCancel()
    }

    fun release() {
        if (!state.compareAndSet(IDLE, RELEASED)) {
            if (state.load() == RELEASED) return
            throw IllegalStateException("EmberVoice is speaking")
        }
        engine.release()
    }

    private fun speakLocked(
        text: String,
        listener: SpeechListener?,
        onSentence: (FloatArray, Int) -> Boolean,
    ): SpeakResult {
        val events = ArrayList<SpeechEvent>()
        fun emit(event: SpeechEvent) {
            events.add(event)
            listener?.onEvent(event)
        }
        emit(SpeechEvent(SPEECH_START))
        val started = nowMs()
        val pieces = speechPieces(text, config.longSentenceMaxChars)
        if (pieces.isEmpty()) {
            emit(SpeechEvent(SPEECH_END, audioSeconds = 0.0))
            return SpeakResult(FloatArray(0), engine.sampleRate, 0.0, 0.0, 0.0, 0.0, events)
        }
        val parts = ArrayList<FloatArray>()
        var ttfaMs = 0.0
        var sampleRate = engine.sampleRate
        for ((index, piece) in pieces.withIndex()) {
            if (engine.isCancelled()) break
            val take = try {
                synthesizePiece(engine, config, piece)
            } catch (cancelled: SpeechCancelled) {
                break
            }
            if (take.samples.isEmpty() || engine.isCancelled()) break
            if (index == 0) ttfaMs = take.ttfaMs
            sampleRate = take.sampleRate
            emit(SpeechEvent(SPEECH_LEVEL, rms = rms(take.samples)))
            parts.add(take.samples)
            if (!onSentence(take.samples, take.sampleRate)) break
        }
        val gap = sampleRate * config.silenceBetweenPiecesMs / 1000
        val fade = if (gap == 0) sampleRate * config.crossfadeMs / 1000 else 0
        val samples = concat(parts, gap, fade, config.crossfadeEdgeThreshold.toFloat())
        val totalMs = nowMs() - started
        val audioSeconds = if (sampleRate > 0) samples.size / sampleRate.toDouble() else 0.0
        val rtf = if (totalMs > 0.0) audioSeconds / (totalMs / 1000.0) else 0.0
        emit(SpeechEvent(SPEECH_END, audioSeconds = audioSeconds))
        return SpeakResult(samples, sampleRate, ttfaMs, totalMs, audioSeconds, rtf, events)
    }
}

internal const val POCKET_FRAME_MS = 80

/** PocketTTS emits one frame every 80 ms. At 24 kHz that is 1920 samples. */
internal fun frameSamples(sampleRate: Int): Int = sampleRate * POCKET_FRAME_MS / 1000

/** The ceiling stopped this take when the audio fills the frame budget. */
internal fun hitFrameCeiling(sampleCount: Int, sampleRate: Int, maxFrames: Int): Boolean {
    if (maxFrames <= 0 || sampleRate <= 0) return false
    val frame = frameSamples(sampleRate)
    if (frame <= 0) return false
    return sampleCount >= maxFrames * frame - frame
}

/** One retry, same seed. Cap is min(firstCap * factor, maxFrames). */
internal fun retryFrames(firstCap: Int, factor: Int, maxFrames: Int): Int = minOf(firstCap * factor, maxFrames)

/**
 * Same seed on every call. A second pass happens only when the frame ceiling
 * truncated the take, and that pass keeps the raised cap.
 */
internal fun synthesizePiece(engine: PieceEngine, config: VoiceConfig, piece: String): PieceAudio {
    val frames = config.framesFor(piece.length)
    val first = engine.synthesize(piece, frames, config.seed)
    if (!engine.usesFrameCeiling() || !hitFrameCeiling(first.samples.size, first.sampleRate, frames)) {
        return first.copy(ttfaMs = firstAudibleMs(first.ttfaMs, false, first.synthMs))
    }
    val second = engine.synthesize(
        piece,
        retryFrames(frames, config.raisedCapFactor, config.raisedCapMaxFrames),
        config.seed,
    )
    val spent = first.synthMs + second.synthMs
    return second.copy(ttfaMs = firstAudibleMs(second.ttfaMs, true, spent), synthMs = spent)
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

/**
 * Joins pieces. A positive [gap] inserts that many zero samples and does not
 * crossfade. With no gap, a boundary whose edge peaks at or above
 * [edgeThreshold] over [crossfadeSamples] is mixed for that many samples.
 */
internal fun concat(
    parts: List<FloatArray>,
    gap: Int,
    crossfadeSamples: Int = 0,
    edgeThreshold: Float = 0.02f,
): FloatArray {
    if (parts.isEmpty()) return FloatArray(0)
    var acc = parts[0]
    for (index in 1 until parts.size) {
        val next = parts[index]
        if (gap > 0) {
            val silence = FloatArray(gap)
            acc = joinRaw(acc, silence, next)
            continue
        }
        val fade = fadeLength(acc, next, crossfadeSamples, edgeThreshold)
        acc = if (fade > 0) crossfade(acc, next, fade) else joinRaw(acc, next)
    }
    return acc
}

private fun fadeLength(previous: FloatArray, next: FloatArray, requested: Int, edgeThreshold: Float): Int {
    if (requested <= 0 || previous.isEmpty() || next.isEmpty()) return 0
    val window = minOf(requested, previous.size, next.size)
    val tail = peak(previous, previous.size - window, window)
    val head = peak(next, 0, window)
    return if (tail >= edgeThreshold || head >= edgeThreshold) window else 0
}

private fun peak(samples: FloatArray, from: Int, count: Int): Float {
    var max = 0f
    val end = minOf(samples.size, from + count)
    var index = from.coerceAtLeast(0)
    while (index < end) {
        max = maxOf(max, abs(samples[index]))
        index += 1
    }
    return max
}

private fun crossfade(previous: FloatArray, next: FloatArray, fade: Int): FloatArray {
    val out = FloatArray(previous.size + next.size - fade)
    previous.copyInto(out)
    var index = 0
    while (index < fade) {
        val t = index.toFloat() / fade.toFloat()
        val at = previous.size - fade + index
        out[at] = previous[at] * (1f - t) + next[index] * t
        index += 1
    }
    next.copyInto(out, previous.size, fade)
    return out
}

private fun joinRaw(vararg parts: FloatArray): FloatArray {
    var total = 0
    for (part in parts) total += part.size
    val out = FloatArray(total)
    var pos = 0
    for (part in parts) {
        part.copyInto(out, pos)
        pos += part.size
    }
    return out
}

private const val IDLE = 0
private const val SPEAKING = 1
private const val RELEASED = 2

private val clockOrigin = TimeSource.Monotonic.markNow()

internal fun nowMs(): Double = clockOrigin.elapsedNow().inWholeMilliseconds.toDouble()
