package org.formulagen.wrapper.voice

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Dev command. Plays [ECHO_SELF_TEST_PHRASE] through the voice-processing graph, records the mic,
 * and writes ~/.gamero/hearing-self-check. Stay quiet while it runs.
 */
fun main() {
    exitProcess(runEchoSelfTest(System.getenv()))
}

/** Runs in the catalog process so the microphone prompt belongs to Gamero Adventures. */
fun runEchoSelfTest(environment: Map<String, String> = System.getenv()): Int = echoSelfTestMain(environment)

internal fun echoSelfTestMain(environment: Map<String, String>): Int {
    val file = selfCheckFile(environment)
    if (!EmberAudio.tryLoad(environment)) {
        println("echo self-test: voice processing is unavailable. Build voice/native with scripts/build-ember-audio.sh and set HOVEL_EMBER_AUDIO.")
        println("duplex will stay half until that library loads. No self-check file written.")
        return 2
    }
    when (val code = EmberAudio.startCode(HEARING_SAMPLE_RATE)) {
        EmberAudio.START_OK -> Unit
        EmberAudio.START_MIC_DENIED -> {
            println("echo self-test: ${EmberAudio.lastError()}")
            println("echo self-test: microphone permission denied. duplex=half status=mic-denied")
            writeSelfCheck(file, status = "fail", erleDb = 0.0, note = "mic-denied")
            return 1
        }
        else -> {
            println("echo self-test: ${EmberAudio.lastError()}")
            println("echo self-test: the graph did not enable voice processing (code=$code). duplex=half status=voice-processing-off")
            writeSelfCheck(file, status = "fail", erleDb = 0.0, note = "voice-processing-off")
            return 1
        }
    }
    println("echo self-test: ${EmberAudio.lastError()}")
    val spoken = kokoroReference(environment)
    val playRate = spoken?.second ?: HEARING_SAMPLE_RATE
    val playSamples = spoken?.first ?: tone(HEARING_SAMPLE_RATE, milliseconds = 1200)
    if (spoken == null) println("echo self-test: Kokoro weights are missing, so this plays a 440 Hz tone. Put HOVEL_KOKORO_MODEL in the environment to play the phrase.")
    val reference = resample(playSamples, playRate, HEARING_SAMPLE_RATE)
    val recorded = FloatArray(reference.size)
    var filled = 0
    val pcm = ShortArray(playSamples.size) { (playSamples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    var played = 0
    val started = System.nanoTime()
    while (filled < recorded.size && (System.nanoTime() - started) < 4_000_000_000L) {
        if (played < pcm.size) {
            val n = minOf(1600, pcm.size - played)
            EmberAudio.play(pcm.copyOfRange(played, played + n), playRate)
            played += n
        }
        val buf = FloatArray(1600)
        val n = EmberAudio.read(buf)
        if (n > 0) {
            val copy = minOf(n, recorded.size - filled)
            buf.copyInto(recorded, filled, 0, copy)
            filled += copy
        } else {
            Thread.sleep(5)
        }
    }
    EmberAudio.flush()
    EmberAudio.stop()
    if (filled < reference.size / 4) {
        println("echo self-test: the microphone returned too little audio ($filled samples). duplex=half")
        writeSelfCheck(file, status = "fail", erleDb = 0.0, note = "no-audio")
        return 1
    }
    val heard = recorded.copyOf(filled)
    val transcript = transcribeIfPossible(environment, heard)
    val verdict = echoVerdict(reference, heard, transcript ?: "")
    val asr = transcript ?: "not-run"
    val status = when {
        transcript == null -> "incomplete"
        verdict.passed -> "pass"
        else -> "fail"
    }
    writeSelfCheck(file, status, verdict.erleDb, asr)
    println("echo self-test erle_db=${"%.1f".format(verdict.erleDb)} leaked=${verdict.leaked} asr=$asr status=$status")
    println("wrote ${file.toAbsolutePath()}")
    println("Stay quiet. A pass needs ${ECHO_ERLE_PASS_DB} dB and none of these words: $ECHO_SELF_TEST_PHRASE")
    return if (status == "pass") 0 else 1
}

fun selfCheckFile(environment: Map<String, String>): Path {
    environment["HOVEL_HEARING_SELF_CHECK"]?.let { return Path.of(it) }
    val home = environment["HOME"] ?: System.getProperty("user.home")
    return Path.of(home, ".gamero", "hearing-self-check")
}

fun writeSelfCheck(file: Path, status: String, erleDb: Double, note: String) {
    file.parent?.let { Files.createDirectories(it) }
    Files.writeString(file, status + "\n" + erleDb + "\n" + note + "\n")
}

private fun kokoroReference(environment: Map<String, String>): Pair<FloatArray, Int>? {
    val dir = environment["HOVEL_KOKORO_MODEL"]?.trim().orEmpty()
    if (dir.isEmpty() || !java.io.File(dir).isDirectory) return null
    return try {
        val config = VoiceConfig(backend = VoiceConfig.KOKORO, numThreads = 4, kokoroModelDir = dir, fillersEnabled = false)
        val engine = openPieceEngine(config)
        try {
            val audio = engine.synthesize(ECHO_SELF_TEST_PHRASE, config.framesFor(ECHO_SELF_TEST_PHRASE.length), config.seed)
            if (audio.samples.isEmpty()) null else audio.samples to audio.sampleRate
        } finally {
            runCatching { engine.release() }
        }
    } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        null
    }
}

private fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    if (fromRate <= 0 || toRate <= 0 || fromRate == toRate) return samples
    val outCount = (samples.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
    return FloatArray(outCount) { index ->
        val at = index.toDouble() * fromRate / toRate
        val left = at.toInt().coerceIn(0, samples.lastIndex)
        val right = (left + 1).coerceAtMost(samples.lastIndex)
        val frac = (at - left).toFloat()
        samples[left] * (1f - frac) + samples[right] * frac
    }
}

private fun tone(sampleRate: Int, milliseconds: Int): FloatArray {
    val count = sampleRate * milliseconds / 1000
    return FloatArray(count) { index ->
        val t = index.toDouble() / sampleRate
        (0.4 * kotlin.math.sin(2.0 * Math.PI * 440.0 * t)).toFloat()
    }
}

private fun transcribeIfPossible(environment: Map<String, String>, samples: FloatArray): String? {
    val model = environment["HOVEL_HEARING_MODEL"]?.trim().orEmpty()
    if (model.isEmpty()) return null
    val engine = try {
        openHearingEngine(model)
    } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        return null
    }
    return try {
        engine.startUtterance()
        engine.accept(samples)
        engine.finish().text
    } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        null
    } finally {
        runCatching { engine.close() }
    }
}
