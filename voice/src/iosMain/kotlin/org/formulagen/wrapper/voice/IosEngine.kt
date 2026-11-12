package org.formulagen.wrapper.voice

import ember.voice.bridge.ember_voice_fluid_create
import ember.voice.bridge.ember_voice_fluid_destroy
import ember.voice.bridge.ember_voice_fluid_sample_rate
import ember.voice.bridge.ember_voice_fluid_speak
import ember.voice.bridge.ember_voice_sherpa_create
import ember.voice.bridge.ember_voice_sherpa_destroy
import ember.voice.bridge.ember_voice_sherpa_sample_rate
import ember.voice.bridge.ember_voice_sherpa_speak
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.time.TimeSource

/**
 * iOS calls ember_voice_bridge.h. Swift includes the sherpa-onnx xcframework
 * headers, or FluidAudio, so this file does not guess sherpa's struct layout.
 * Kokoro, the chat voice, is [KokoroIosPieceEngine].
 * sherpa-onnx 2.0.0 still finishes the language model for a piece before the
 * first callback. FluidAudio can emit an 80 ms frame at a time; that path is
 * optional and selected with backend "fluid-audio".
 */
@OptIn(ExperimentalForeignApi::class)
actual fun openPieceEngine(config: VoiceConfig): PieceEngine {
    if (config.backend == VoiceConfig.KOKORO) return KokoroIosPieceEngine(config)
    return if (config.backend == VoiceConfig.FLUID_AUDIO) {
        FluidAudioPieceEngine(config)
    } else {
        SherpaIosPieceEngine(config)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class SherpaIosPieceEngine(private val config: VoiceConfig) : PieceEngine {
    private var handle: COpaquePointer?
    override val sampleRate: Int

    init {
        val wav = preparedVoiceWav(config.voiceWav)
        val created = ember_voice_sherpa_create(config.modelDir, wav, config.numThreads)
        removeFile(wav)
        if (created == null) {
            throw IllegalStateException("sherpa-onnx iOS engine did not load. Link the xcframework and the bridge.")
        }
        val rate = ember_voice_sherpa_sample_rate(created)
        if (rate <= 0) {
            ember_voice_sherpa_destroy(created)
            throw IllegalStateException("sherpa-onnx iOS engine reported no sample rate")
        }
        handle = created
        sampleRate = rate
    }

    override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
        val current = handle ?: throw IllegalStateException("voice engine was released")
        return collect(text, sampleRate, maxFrames) { callback, arg ->
            ember_voice_sherpa_speak(
                current,
                text,
                maxFrames,
                config.numSteps,
                config.temperature.toFloat(),
                seed,
                config.decoderFramesPerCallback,
                callback,
                arg,
            )
        }
    }

    override fun release() {
        val current = handle ?: return
        handle = null
        ember_voice_sherpa_destroy(current)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class FluidAudioPieceEngine(config: VoiceConfig) : PieceEngine {
    private val wav = preparedVoiceWav(config.voiceWav)
    private var handle: COpaquePointer?
    override val sampleRate: Int

    init {
        val created = ember_voice_fluid_create(wav, config.temperature.toFloat(), config.seed)
        if (created == null) {
            removeFile(wav)
            throw IllegalStateException("FluidAudio is not linked. It is an optional iOS backend.")
        }
        val rate = ember_voice_fluid_sample_rate(created)
        if (rate <= 0) {
            ember_voice_fluid_destroy(created)
            removeFile(wav)
            throw IllegalStateException("FluidAudio reported no sample rate")
        }
        handle = created
        sampleRate = rate
    }

    override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
        val current = handle ?: throw IllegalStateException("voice engine was released")
        return collect(text, sampleRate, maxFrames) { callback, arg ->
            ember_voice_fluid_speak(current, text, maxFrames, seed, callback, arg)
        }
    }

    override fun release() {
        val current = handle ?: return
        handle = null
        ember_voice_fluid_destroy(current)
        removeFile(wav)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun preparedVoiceWav(path: String): String {
    refuseNonCommercialVoice(path)
    val data = NSData.dataWithContentsOfFile(path) ?: throw VoiceClipException("voice file not found: $path")
    if (data.length > Int.MAX_VALUE.toULong()) throw VoiceClipException("voice file is too large: $path")
    val count = data.length.toInt()
    val bytes = ByteArray(count)
    if (count > 0) {
        val raw = data.bytes ?: throw VoiceClipException("voice file is empty: $path")
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), raw, data.length)
        }
    }
    val clip = prepareReferenceClip(bytes)
    val wav = referenceClipWavBytes(clip)
    val dir = NSTemporaryDirectory() ?: "/tmp"
    val out = dir.trimEnd('/') + "/ember-voice-" + nowNs() + ".wav"
    val wrote = wav.usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), wav.size.toULong()).writeToFile(out, true)
    }
    if (!wrote) throw VoiceClipException("could not write the prepared voice clip")
    return out
}

@OptIn(ExperimentalForeignApi::class)
private fun removeFile(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

private val clock = TimeSource.Monotonic.markNow()

/** Nanoseconds on a clock that only moves forward, for the timings a take reports. */
private fun nowNs(): Long = clock.elapsedNow().inWholeNanoseconds

private class SampleBox(val limit: Int, val stop: () -> Boolean) {
    val buf = SampleBuf()
    var callbacks = 0
    var firstCallbackNs: Long = -1L
    val startedNs: Long = nowNs()
}

@OptIn(ExperimentalForeignApi::class)
private class SampleBuf {
    var data = FloatArray(0)
    var size = 0

    fun append(src: CPointer<FloatVar>, n: Int) {
        if (n <= 0) return
        val need = size + n
        if (need > data.size) {
            var cap = if (data.isEmpty()) 4096 else data.size
            while (cap < need) cap *= 2
            data = data.copyOf(cap)
        }
        data.usePinned { pinned ->
            memcpy(pinned.addressOf(size), src, n.toULong() * 4u)
        }
        size += n
    }

    fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
}

/** Runs one generation and gathers its samples. [stop] is asked at each callback; true ends the generation early. */
@OptIn(ExperimentalForeignApi::class)
internal fun collect(
    text: String,
    sampleRate: Int,
    maxFrames: Int,
    stop: () -> Boolean = { false },
    call: (CPointer<CFunction<(CPointer<FloatVar>?, Int, COpaquePointer?) -> Int>>?, COpaquePointer?) -> Int,
): PieceAudio {
    if (sampleRate <= 0) throw IllegalStateException("voice engine has no sample rate")
    val limit = if (maxFrames > 0) maxFrames * 8 else 8192
    val box = SampleBox(limit, stop)
    val ref = StableRef.create(box)
    try {
        val code = call(sampleCallback, ref.asCPointer())
        if (stop()) throw SpeechCancelled()
        if (code == 0) throw IllegalStateException("no audio for \"$text\"")
    } finally {
        ref.dispose()
    }
    val synthMs = (nowNs() - box.startedNs) / 1_000_000.0
    val ttfaMs = if (box.firstCallbackNs >= 0) {
        (box.firstCallbackNs - box.startedNs) / 1_000_000.0
    } else {
        synthMs
    }
    return PieceAudio(box.buf.toArray(), sampleRate, ttfaMs, synthMs)
}

@OptIn(ExperimentalForeignApi::class)
private val sampleCallback = staticCFunction<CPointer<FloatVar>?, Int, COpaquePointer?, Int> { samples, n, arg ->
    if (arg == null) return@staticCFunction 1
    val target = arg.asStableRef<SampleBox>().get()
    target.callbacks += 1
    if (samples != null && n > 0) {
        if (target.firstCallbackNs < 0) target.firstCallbackNs = nowNs()
        target.buf.append(samples, n)
    }
    if (target.callbacks > target.limit || target.stop()) 0 else 1
}
