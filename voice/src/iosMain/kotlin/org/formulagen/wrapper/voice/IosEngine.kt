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
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction

/**
 * iOS calls ember_voice_bridge.h. Swift includes the sherpa-onnx xcframework
 * headers, or FluidAudio, so this file does not guess sherpa's struct layout.
 * sherpa-onnx 2.0.0 still finishes the language model for a piece before the
 * first callback. FluidAudio can emit an 80 ms frame at a time; that path is
 * optional and selected with backend "fluid-audio".
 */
@OptIn(ExperimentalForeignApi::class)
actual fun openPieceEngine(config: VoiceConfig): PieceEngine {
    return if (config.backend == VoiceConfig.FLUID_AUDIO) {
        FluidAudioPieceEngine(config)
    } else {
        SherpaIosPieceEngine(config)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class SherpaIosPieceEngine(private val config: VoiceConfig) : PieceEngine {
    private val handle = ember_voice_sherpa_create(config.modelDir, config.voiceWav, config.numThreads)
        ?: throw IllegalStateException("sherpa-onnx iOS engine did not load. Link the xcframework and the bridge.")
    override val sampleRate: Int = ember_voice_sherpa_sample_rate(handle)

    override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
        return collect(text) { callback, arg ->
            ember_voice_sherpa_speak(
                handle,
                text,
                maxFrames,
                config.numSteps,
                config.temperature.toFloat(),
                seed,
                config.chunkSize,
                callback,
                arg,
            )
        }
    }

    override fun release() {
        ember_voice_sherpa_destroy(handle)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class FluidAudioPieceEngine(config: VoiceConfig) : PieceEngine {
    private val handle = ember_voice_fluid_create(config.voiceWav, config.temperature.toFloat(), config.seed)
        ?: throw IllegalStateException("FluidAudio is not linked. It is an optional iOS backend.")
    override val sampleRate: Int = ember_voice_fluid_sample_rate(handle)

    override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
        return collect(text) { callback, arg ->
            ember_voice_fluid_speak(handle, text, maxFrames, callback, arg)
        }
    }

    override fun release() {
        ember_voice_fluid_destroy(handle)
    }
}

private class SampleBox {
    val samples = ArrayList<Float>()
    var firstCallbackNs: Long = -1L
    val startedNs: Long = kotlin.system.getTimeNanos()
}

@OptIn(ExperimentalForeignApi::class)
private fun collect(
    text: String,
    call: (CPointer<CFunction<(CPointer<FloatVar>?, Int, COpaquePointer?) -> Int>>?, COpaquePointer?) -> Int,
): PieceAudio {
    val box = SampleBox()
    val ref = StableRef.create(box)
    try {
        val code = call(sampleCallback, ref.asCPointer())
        if (code == 0) throw IllegalStateException("no audio for \"$text\"")
    } finally {
        ref.dispose()
    }
    val ttfaMs = if (box.firstCallbackNs >= 0) {
        (box.firstCallbackNs - box.startedNs) / 1_000_000.0
    } else {
        (kotlin.system.getTimeNanos() - box.startedNs) / 1_000_000.0
    }
    return PieceAudio(box.samples.toFloatArray(), 24000, ttfaMs)
}

@OptIn(ExperimentalForeignApi::class)
private val sampleCallback = staticCFunction<CPointer<FloatVar>?, Int, COpaquePointer?, Int> { samples, n, arg ->
    if (arg == null || samples == null || n <= 0) return@staticCFunction 1
    val target = arg.asStableRef<SampleBox>().get()
    if (target.firstCallbackNs < 0) target.firstCallbackNs = kotlin.system.getTimeNanos()
    var i = 0
    while (i < n) {
        target.samples.add(samples[i])
        i += 1
    }
    1
}
