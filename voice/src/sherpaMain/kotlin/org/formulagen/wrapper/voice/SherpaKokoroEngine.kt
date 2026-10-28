package org.formulagen.wrapper.voice

import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Kokoro 82M v1.0 through sherpa-onnx 2.0.0. Lexicon and tokens do the
 * grapheme mapping. espeak-ng data and lang are ignored on this runtime, so
 * they are not set. One [synthesize] call is one sentence. The frame ceiling
 * is a PocketTTS limit and does not apply here.
 */
class SherpaKokoroEngine(private val config: VoiceConfig) : PieceEngine {
    private val tts: OfflineTts
    private val released = AtomicBoolean(false)
    private val cancel = AtomicBoolean(false)
    override val sampleRate: Int

    init {
        LibraryUtils.load()
        val dir = File(config.kokoroModelDir)
        refuseNonCommercialVoice(dir.path)
        val required = listOf("model.onnx", "voices-blends.bin", "tokens.txt", "lexicon-us-en.txt")
        val missing = required.filter { !File(dir, it).isFile }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("Kokoro files missing in ${dir.path}: ${missing.joinToString(", ")}")
        }
        val chosen = voice()
        if (chosen.sid !in 0 until kokoroCatalog().speakerCount) {
            throw IllegalStateException("Kokoro voice ${chosen.id} sid ${chosen.sid} is outside the voice table")
        }
        val kokoro = OfflineTtsKokoroModelConfig.builder()
            .setModel(File(dir, "model.onnx").path)
            .setVoices(File(dir, "voices-blends.bin").path)
            .setTokens(File(dir, "tokens.txt").path)
            .setLexicon(File(dir, "lexicon-us-en.txt").path)
            .build()
        val model = OfflineTtsModelConfig.builder()
            .setKokoro(kokoro)
            .setNumThreads(config.numThreads)
            .setProvider("cpu")
            .build()
        tts = OfflineTts(OfflineTtsConfig.builder().setModel(model).build())
        sampleRate = tts.sampleRate
    }

    override fun usesFrameCeiling(): Boolean = false

    override fun requestCancel() {
        cancel.set(true)
    }

    override fun clearCancel() {
        cancel.set(false)
    }

    override fun isCancelled(): Boolean = cancel.get()

    override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
        if (released.get()) throw IllegalStateException("voice engine was released")
        if (cancel.get()) throw SpeechCancelled()
        val chosen = voice()
        val ttfaNs = AtomicLong(-1L)
        val start = System.nanoTime()
        val callback = object : OfflineTtsCallback {
            override fun invoke(samples: FloatArray): Int {
                if (samples.isNotEmpty()) ttfaNs.compareAndSet(-1L, System.nanoTime() - start)
                return if (cancel.get()) 0 else 1
            }
        }
        val audio = tts.generateWithCallback(text, chosen.sid, chosen.speed, callback)
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        val ttfaMs = if (ttfaNs.get() >= 0) ttfaNs.get() / 1_000_000.0 else totalMs
        if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
            if (cancel.get()) throw SpeechCancelled()
            throw IllegalStateException("no audio for \"$text\"")
        }
        return PieceAudio(audio.samples, audio.sampleRate, ttfaMs, totalMs)
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        tts.release()
    }

    private fun voice(): KokoroVoice {
        val id = config.kokoroVoiceId.ifEmpty { kokoroCatalog().defaultId }
        return kokoroVoice(id) ?: throw IllegalStateException("Unknown Kokoro voice '$id'.")
    }
}

internal fun createKokoroEngine(config: VoiceConfig): PieceEngine = SherpaKokoroEngine(config)
