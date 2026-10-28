package org.formulagen.wrapper.voice

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Speaks the fixed 3-turn conversation and prints the acceptance metrics.
 * Playback time-to-first-audio is not measured here. That is the desktop line.
 */
fun main(args: Array<String>) {
    val model = args.getOrNull(0)
        ?: "../spikes/ember-voice/mac/models/sherpa-onnx-pocket-tts-int8-2026-01-26"
    val wav = args.getOrNull(1)
        ?: "../spikes/ember-voice/mac/models/voices/0a67_enhanced-24k.wav"
    val out = args.getOrNull(2)
    val turns = listOf(
        "I finished the task. Three files changed and all tests are green.",
        "I refactored the session code, moved the socket into the bridge, and the build is green.",
        "Should I rerun the suite, or is this ready for you to merge?",
    )
    val config = VoiceConfig(modelDir = model, voiceWav = wav)
    val capture = Capture(SherpaPocketEngine(config))
    val voice = EmberVoice(capture, config)
    println("steps=${config.numSteps} silenceMs=${config.silenceMs} seed=${config.seed}")
    val turnAudio = ArrayList<FloatArray>()
    var synthMs = 0.0
    var audioSeconds = 0.0
    turns.forEachIndexed { index, text ->
        val pieces = speechPieces(text, config.firstPieceTargetChars, config.laterPieceMaxChars)
        println("turn $index pieces=${pieces.map { it.length }}")
        val started = System.nanoTime()
        val spoken = voice.speak(text)
        synthMs += (System.nanoTime() - started) / 1_000_000.0
        audioSeconds += spoken.audioSeconds
        turnAudio.add(spoken.samples)
        val zeros = zeroRunsMs(spoken.samples, spoken.sampleRate)
        println("  ttfaMs=${fmt(spoken.ttfaMs)} audioS=${fmt(spoken.audioSeconds)} zerosMs=$zeros")
    }
    val hello = "Hello, this is Ember."
    val firstHello = voice.speak(hello).samples
    val secondHello = voice.speak(hello).samples
    voice.release()
    val helloCos = cosine(spectrum(firstHello), spectrum(secondHello))
    val turnSpec = turnAudio.map { spectrum(it) }
    val pairs = listOf(
        cosine(turnSpec[0], turnSpec[1]),
        cosine(turnSpec[0], turnSpec[2]),
        cosine(turnSpec[1], turnSpec[2]),
    )
    val pitches = capture.sentences.map { pitchHz(it.samples, it.rate) }
    val finite = pitches.filterNotNull()
    val f0Range = if (finite.isEmpty()) 0.0 else finite.max() - finite.min()
    val rtf = if (synthMs > 0.0) audioSeconds / (synthMs / 1000.0) else 0.0
    val minPair = pairs.min()
    val seeds = capture.seeds
    println("helloCosine=${fmt(helloCos)}")
    println("turnCosine=${pairs.map { fmt(it) }} min=${fmt(minPair)}")
    println("f0Hz=${pitches.map { if (it == null) "n/a" else fmt(it) }} range=${fmt(f0Range)}")
    println("rtf=${fmt(rtf)} seeds=$seeds")
    println("bars hello>=0.99 ${helloCos >= 0.99} cosine>=0.75 ${minPair >= 0.75} f0Range<=15 ${f0Range <= 15.0} seed1 ${seeds.all { it == config.seed }}")
    if (out != null) {
        val file = File(out)
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {
              "num_steps": ${config.numSteps},
              "silence_ms": ${config.silenceMs},
              "hello_cosine": ${fmt(helloCos)},
              "turn_cosine_min": ${fmt(minPair)},
              "f0_hz": [${finite.joinToString(", ") { fmt(it) }}],
              "f0_range_hz": ${fmt(f0Range)},
              "rtf": ${fmt(rtf)},
              "synth_ms": ${fmt(synthMs)},
              "audio_s": ${fmt(audioSeconds)},
              "seeds": [${seeds.joinToString(", ")}]
            }
            """.trimIndent() + "\n",
        )
        println("wrote ${file.path}")
    }
}

private class Capture(private val inner: PieceEngine) : PieceEngine {
    val seeds = ArrayList<Int>()
    val sentences = ArrayList<Take>()
    override val sampleRate: Int get() = inner.sampleRate
    override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
        seeds.add(seed)
        val audio = inner.synthesize(text, maxFrames, seed)
        sentences.add(Take(text, audio.samples, audio.sampleRate))
        return audio
    }
    override fun release() = inner.release()
}

private class Take(val text: String, val samples: FloatArray, val rate: Int)

private fun fmt(value: Double): String = "%.4f".format(value)

private fun zeroRunsMs(samples: FloatArray, rate: Int): List<Double> {
    val minSamples = rate * 5 / 1000
    val runs = ArrayList<Double>()
    var count = 0
    fun flush() {
        if (count >= minSamples) runs.add(count * 1000.0 / rate)
        count = 0
    }
    for (sample in samples) {
        if (sample == 0f) count += 1 else flush()
    }
    flush()
    return runs
}

private fun spectrum(samples: FloatArray): DoubleArray {
    val rate = 24000
    val nfft = 1024
    val hop = 512
    val limit = minOf(samples.size, rate * 3)
    val acc = DoubleArray(nfft / 2)
    var frames = 0
    var start = 0
    val window = DoubleArray(nfft) { 0.5 - 0.5 * cos(2.0 * PI * it / (nfft - 1)) }
    while (start + nfft <= limit) {
        val frame = DoubleArray(nfft) { samples[start + it].toDouble() * window[it] }
        val mag = fftMagnitude(frame)
        for (i in 1 until nfft / 2) acc[i] += mag[i]
        frames += 1
        start += hop
    }
    if (frames == 0) return DoubleArray(nfft / 2 - 1) { 0.0 }
    var energy = 0.0
    val out = DoubleArray(nfft / 2 - 1)
    for (i in out.indices) {
        out[i] = ln(1.0 + acc[i + 1] / frames)
        energy += out[i] * out[i]
    }
    val norm = sqrt(energy)
    if (norm > 0.0) for (i in out.indices) out[i] /= norm
    return out
}

private fun cosine(left: DoubleArray, right: DoubleArray): Double {
    var sum = 0.0
    val n = minOf(left.size, right.size)
    for (i in 0 until n) sum += left[i] * right[i]
    return sum
}

private fun pitchHz(samples: FloatArray, rate: Int): Double? {
    if (samples.size < rate / 2) return null
    val from = (samples.size - rate) / 2
    val count = minOf(rate, samples.size - from)
    var mean = 0.0
    for (i in 0 until count) mean += samples[from + i]
    mean /= count
    val lo = rate / 350
    val hi = minOf(rate / 70, count - 1)
    if (hi <= lo) return null
    var bestLag = lo
    var best = Double.NEGATIVE_INFINITY
    var lag = lo
    while (lag <= hi) {
        var corr = 0.0
        var i = 0
        while (i + lag < count) {
            corr += (samples[from + i] - mean) * (samples[from + i + lag] - mean)
            i += 1
        }
        if (corr > best) {
            best = corr
            bestLag = lag
        }
        lag += 1
    }
    return rate.toDouble() / bestLag
}

private fun fftMagnitude(frame: DoubleArray): DoubleArray {
    val n = frame.size
    val real = frame.copyOf()
    val imag = DoubleArray(n)
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val swap = real[i]
            real[i] = real[j]
            real[j] = swap
        }
    }
    var length = 2
    while (length <= n) {
        val angle = -2.0 * PI / length
        val wReal = cos(angle)
        val wImag = sin(angle)
        var start = 0
        while (start < n) {
            var wr = 1.0
            var wi = 0.0
            for (k in 0 until length / 2) {
                val even = start + k
                val odd = even + length / 2
                val tr = wr * real[odd] - wi * imag[odd]
                val ti = wr * imag[odd] + wi * real[odd]
                real[odd] = real[even] - tr
                imag[odd] = imag[even] - ti
                real[even] += tr
                imag[even] += ti
                val nextWr = wr * wReal - wi * wImag
                wi = wr * wImag + wi * wReal
                wr = nextWr
            }
            start += length
        }
        length = length shl 1
    }
    return DoubleArray(n / 2) { sqrt(real[it] * real[it] + imag[it] * imag[it]) }
}
