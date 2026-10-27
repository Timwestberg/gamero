package org.formulagen.wrapper.voice

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * sherpa-onnx 2.0.0 PocketTTS on the JVM and, with the same Kotlin API, on Android.
 * The first callback fires after the language model has finished the piece.
 * There is no per-frame decode while the model is still generating.
 */
class SherpaPocketEngine(private val config: VoiceConfig) : PieceEngine {
    private val tts: OfflineTts
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    private val voiceSamples: FloatArray
    private val voiceRate: Int
    override val sampleRate: Int

    init {
        LibraryUtils.load()
        val dir = File(config.modelDir)
        val required = listOf(
            "lm_flow.int8.onnx",
            "lm_main.int8.onnx",
            "encoder.onnx",
            "decoder.int8.onnx",
            "text_conditioner.onnx",
            "vocab.json",
            "token_scores.json",
        )
        val missing = required.filter { !File(dir, it).isFile }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("model files missing in ${dir.path}: ${missing.joinToString(", ")}")
        }
        val voice = File(config.voiceWav)
        if (!voice.isFile) throw IllegalStateException("voice file not found: ${voice.path}")
        refuseNonCommercialVoice(voice.path)
        val clip = prepareReferenceClip(voice.readBytes())
        voiceSamples = clip.samples
        voiceRate = clip.sampleRate
        fun modelPath(name: String) = File(dir, name).path
        val pocket = OfflineTtsPocketModelConfig.builder()
            .setLmFlow(modelPath("lm_flow.int8.onnx"))
            .setLmMain(modelPath("lm_main.int8.onnx"))
            .setEncoder(modelPath("encoder.onnx"))
            .setDecoder(modelPath("decoder.int8.onnx"))
            .setTextConditioner(modelPath("text_conditioner.onnx"))
            .setVocabJson(modelPath("vocab.json"))
            .setTokenScoresJson(modelPath("token_scores.json"))
            .build()
        val model = OfflineTtsModelConfig.builder()
            .setPocket(pocket)
            .setNumThreads(config.numThreads)
            .setProvider("cpu")
            .build()
        tts = OfflineTts(OfflineTtsConfig.builder().setModel(model).build())
        sampleRate = tts.sampleRate
    }

    override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
        if (released.get()) throw IllegalStateException("voice engine was released")
        val extra = HashMap<String, String>()
        extra["temperature"] = config.temperature.toString()
        extra["chunk_size"] = config.chunkSize.toString()
        extra["max_char_in_sentence"] = maxOf(120, text.length).toString()
        extra["seed"] = seed.toString()
        extra["max_reference_audio_len"] = (voiceSamples.size / voiceRate.toDouble()).toString()
        if (maxFrames > 0) extra["max_frames"] = maxFrames.toString()
        val gen = GenerationConfig()
        gen.referenceAudio = voiceSamples
        gen.referenceSampleRate = voiceRate
        gen.numSteps = config.numSteps
        gen.speed = 1.0f
        gen.extra = extra
        val callbackCount = java.util.concurrent.atomic.AtomicInteger(0)
        val ttfaNs = AtomicLong(-1L)
        val start = System.nanoTime()
        val callback = object : OfflineTtsCallback {
            override fun invoke(samples: FloatArray): Int {
                val n = callbackCount.incrementAndGet()
                if (samples.isNotEmpty()) ttfaNs.compareAndSet(-1L, System.nanoTime() - start)
                val limit = if (maxFrames > 0) maxFrames * 8 else 8192
                return if (n > limit) 0 else 1
            }
        }
        val audio = tts.generateWithConfigAndCallback(text, gen, callback)
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        val ttfaMs = if (ttfaNs.get() >= 0) ttfaNs.get() / 1_000_000.0 else totalMs
        if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
            throw IllegalStateException("no audio for \"$text\"")
        }
        return PieceAudio(audio.samples, audio.sampleRate, ttfaMs, totalMs)
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        tts.release()
    }
}

internal const val POCKET_ENGINE_LINKED = true

internal fun createPocketEngine(config: VoiceConfig): PieceEngine = SherpaPocketEngine(config)
