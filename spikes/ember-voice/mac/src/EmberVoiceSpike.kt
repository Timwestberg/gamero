import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/**
 * Times sherpa-onnx PocketTTS the way the Ember desktop companion would call it:
 * the JVM binding, a reference-audio voice prompt, and the streaming callback.
 *
 * Cold start is model load plus the first line. Warm numbers are the median and
 * nearest-rank p90 of the measured runs that follow one discarded warmup per line.
 * RTF is audio seconds divided by synthesis seconds (higher is faster).
 */
class HarnessException(message: String) : RuntimeException(message)

data class LineSpec(val id: String, val text: String)

data class Config(
    val path: File,
    val modelDir: File,
    val voice: File,
    val voiceUrl: String,
    val voiceLicense: String,
    val voiceSource: String,
    val numThreads: Int,
    val numSteps: Int,
    val temperature: String,
    val chunkSize: String,
    val warmRuns: Int,
    val passTtfaMs: Double,
    val passRtf: Double,
    val lines: List<LineSpec>,
)

data class RunResult(
    val kind: String,
    val ttfaMs: Double,
    val ttfaFromCallback: Boolean,
    val synthMs: Double,
    val audioSeconds: Double,
    val rtf: Double,
    val sampleRate: Int,
    val callbackCount: Int,
    val firstChunkSamples: Int,
    val chunkSamples: List<Int>,
)

data class LineSummary(
    val line: LineSpec,
    val runs: List<RunResult>,
    val ttfaMedian: Double,
    val ttfaP90: Double,
    val synthMedian: Double,
    val synthP90: Double,
    val audioMedian: Double,
    val rtfMedian: Double,
    val rtfP10: Double,
    val ttfaPass: Boolean,
    val rtfPass: Boolean,
)

class RssSampler {
    private val sampledPeakKb = AtomicLong(0)
    @Volatile private var running = true
    private var thread: Thread? = null

    fun start() {
        val worker = Thread(
            {
                while (running) {
                    note(currentRssKb())
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                note(currentRssKb())
            },
            "rss-sampler",
        )
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    fun stop(): Long {
        running = false
        thread?.interrupt()
        thread?.join(1000)
        note(currentRssKb())
        note(linuxPeakKb())
        return sampledPeakKb.get()
    }

    fun peakKb(): Long = sampledPeakKb.get()

    private fun note(kb: Long) {
        if (kb > 0) {
            sampledPeakKb.updateAndGet { current -> if (kb > current) kb else current }
        }
    }
}

fun main(args: Array<String>) {
    try {
        runHarness(args)
    } catch (e: HarnessException) {
        System.err.println("error: ${e.message}")
        System.exit(1)
    }
}

private data class Cli(
    val configPath: String,
    val play: Boolean,
    val selfCheck: Boolean,
    val warmRuns: Int?,
    val only: String?,
    val debug: Boolean,
    val help: Boolean,
)

private fun runHarness(args: Array<String>) {
    val cli = parseArgs(args)
    if (cli.help) {
        printHelp()
        return
    }
    val config = loadConfig(File(cli.configPath))
    if (cli.selfCheck) {
        selfCheck(config)
        println("self-check: ok (${config.lines.size} lines, say_limit is ${sayLimit(config).text.length} characters)")
        return
    }
    val warmRuns = cli.warmRuns ?: config.warmRuns
    if (warmRuns < 1) {
        throw HarnessException("--warm-runs must be at least 1")
    }
    val lines = selectLines(config.lines, cli.only)
    val sampler = RssSampler()
    sampler.start()
    var tts: OfflineTts? = null
    try {
        if (cli.debug) {
            LibraryUtils.enableDebug()
        }
        val nativeStart = System.nanoTime()
        try {
            LibraryUtils.load()
        } catch (e: UnsatisfiedLinkError) {
            throw HarnessException(
                "could not load the sherpa-onnx native library (${e.message}). Run ./setup.sh on this machine.",
            )
        }
        val nativeMs = nanosToMs(System.nanoTime() - nativeStart)
        log("native library loaded in ${fmtMs(nativeMs)} ms")

        val voiceWave = readVoice(config.voice)
        log(
            "voice ${config.voice.name}: ${voiceWave.sampleRate} Hz, " +
                "${fmt(voiceWave.samples.size / voiceWave.sampleRate.toDouble(), 2)} s, ${config.voiceLicense}",
        )

        val modelStart = System.nanoTime()
        tts = loadTts(config, cli.debug)
        val modelLoadMs = nanosToMs(System.nanoTime() - modelStart)
        val rssAfterLoad = currentRssKb()
        log("model loaded in ${fmtMs(modelLoadMs)} ms (sample rate ${tts.sampleRate} Hz)")

        val engine = tts
        val cold = speak(engine, lines[0], voiceWave, config, "cold")
        log(
            "cold ${lines[0].id}: TTFA ${fmtMs(cold.ttfaMs)} ms, synth ${fmtMs(cold.synthMs)} ms, " +
                "callbacks ${cold.callbackCount}",
        )

        val summaries = ArrayList<LineSummary>()
        for (line in lines) {
            val warmup = speak(engine, line, voiceWave, config, "warmup")
            log("warmup ${line.id}: synth ${fmtMs(warmup.synthMs)} ms (not counted)")
            val measured = ArrayList<RunResult>()
            val wav = File("results/wav/${safeId(line.id)}.wav")
            for (n in 1..warmRuns) {
                val run = speak(
                    tts = engine,
                    line = line,
                    voice = voiceWave,
                    config = config,
                    kind = "warm",
                    wav = if (n == warmRuns) wav else null,
                )
                measured.add(run)
                log(
                    "warm ${line.id} $n/$warmRuns: TTFA ${fmtMs(run.ttfaMs)} ms, " +
                        "synth ${fmtMs(run.synthMs)} ms, RTF ${fmt(run.rtf, 2)}x",
                )
            }
            log("wrote ${wav.path}")
            summaries.add(summarize(line, measured, config))
        }

        val peakKb = sampler.stop()
        val peakSource = if (linuxPeakKb() > 0) "max(VmHWM, samples)" else "ps rss samples"
        val streamingNote = streamingNote(cold, summaries)
        printReport(
            config = config,
            warmRuns = warmRuns,
            nativeMs = nativeMs,
            modelLoadMs = modelLoadMs,
            rssAfterLoadKb = rssAfterLoad,
            peakKb = peakKb,
            peakSource = peakSource,
            cold = cold,
            summaries = summaries,
            streamingNote = streamingNote,
        )
        val jsonFile = File("results/ember-voice-results.json")
        val csvFile = File("results/ember-voice-results.csv")
        jsonFile.parentFile?.mkdirs()
        jsonFile.writeText(
            renderJson(
                config = config,
                warmRuns = warmRuns,
                nativeMs = nativeMs,
                modelLoadMs = modelLoadMs,
                rssAfterLoadKb = rssAfterLoad,
                peakKb = peakKb,
                peakSource = peakSource,
                voiceSampleRate = voiceWave.sampleRate,
                voiceSeconds = voiceWave.samples.size / voiceWave.sampleRate.toDouble(),
                cold = cold,
                summaries = summaries,
                streamingNote = streamingNote,
            ),
        )
        csvFile.writeText(renderCsv(modelLoadMs, cold, peakKb, summaries))
        println("json: ${jsonFile.path}")
        println("csv:  ${csvFile.path}")
        println("wav:  results/wav/<line id>.wav")

        if (cli.play) {
            for (line in lines) {
                val wav = File("results/wav/${safeId(line.id)}.wav")
                playWav(wav)
            }
        }
    } finally {
        sampler.stop()
        tts?.release()
    }
}

private class VoiceWave(val samples: FloatArray, val sampleRate: Int)

private fun readVoice(path: File): VoiceWave {
    if (!path.isFile) {
        throw HarnessException(
            "voice file not found: ${path.path}. Run ./setup.sh. " +
                "PocketTTS needs a reference wav. Use a CC0 clip from kyutai/tts-voices " +
                "voice-donations/, not the Expresso files under the model's test_wavs/.",
        )
    }
    val reader = WaveReader(path.path)
    if (reader.samples.isEmpty() || reader.sampleRate <= 0) {
        throw HarnessException("voice wav has no samples: ${path.path}")
    }
    return VoiceWave(reader.samples, reader.sampleRate)
}

private fun loadTts(config: Config, debug: Boolean): OfflineTts {
    val dir = config.modelDir
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
        throw HarnessException(
            "model files missing in ${dir.path}: ${missing.joinToString(", ")}. Run ./setup.sh.",
        )
    }
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
        .setDebug(debug)
        .setProvider("cpu")
        .build()
    val ttsConfig = OfflineTtsConfig.builder().setModel(model).build()
    return OfflineTts(ttsConfig)
}

private fun generationConfig(config: Config, voice: VoiceWave): GenerationConfig {
    val extra = HashMap<String, String>()
    extra["temperature"] = config.temperature
    extra["chunk_size"] = config.chunkSize
    val gen = GenerationConfig()
    gen.referenceAudio = voice.samples
    gen.referenceSampleRate = voice.sampleRate
    gen.numSteps = config.numSteps
    gen.speed = 1.0f
    gen.extra = extra
    return gen
}

private fun speak(
    tts: OfflineTts,
    line: LineSpec,
    voice: VoiceWave,
    config: Config,
    kind: String,
    wav: File? = null,
): RunResult {
    val gen = generationConfig(config, voice)
    val callbackCount = AtomicInteger(0)
    val firstChunkSamples = AtomicInteger(0)
    val ttfaNs = AtomicLong(-1L)
    val chunks = Collections.synchronizedList(ArrayList<Int>())
    val start = System.nanoTime()
    val callback = object : OfflineTtsCallback {
        override fun invoke(samples: FloatArray): Int {
            val n = callbackCount.incrementAndGet()
            if (chunks.size < 64) {
                chunks.add(samples.size)
            }
            if (samples.isNotEmpty()) {
                val elapsed = System.nanoTime() - start
                if (ttfaNs.compareAndSet(-1L, elapsed)) {
                    firstChunkSamples.set(samples.size)
                }
            }
            if (n > 100000) {
                return 0
            }
            return 1
        }
    }
    val audio = tts.generateWithConfigAndCallback(line.text, gen, callback)
    val synthNs = System.nanoTime() - start
    if (wav != null) {
        wav.parentFile?.mkdirs()
        if (!audio.save(wav.path)) {
            throw HarnessException("could not write ${wav.path}")
        }
    }
    val samples = audio.samples
    val sampleRate = audio.sampleRate
    if (samples.isEmpty() || sampleRate <= 0) {
        throw HarnessException("no audio for ${line.id}")
    }
    val synthMs = nanosToMs(synthNs)
    val fromCallback = ttfaNs.get() >= 0
    val ttfaMs = if (fromCallback) nanosToMs(ttfaNs.get()) else synthMs
    val audioSeconds = samples.size / sampleRate.toDouble()
    val rtf = if (synthMs > 0.0) audioSeconds / (synthMs / 1000.0) else 0.0
    return RunResult(
        kind = kind,
        ttfaMs = ttfaMs,
        ttfaFromCallback = fromCallback,
        synthMs = synthMs,
        audioSeconds = audioSeconds,
        rtf = rtf,
        sampleRate = sampleRate,
        callbackCount = callbackCount.get(),
        firstChunkSamples = firstChunkSamples.get(),
        chunkSamples = ArrayList(chunks),
    )
}

private fun summarize(line: LineSpec, runs: List<RunResult>, config: Config): LineSummary {
    if (runs.isEmpty()) {
        throw HarnessException("no warm runs for ${line.id}")
    }
    val ttfaMedian = median(runs.map { it.ttfaMs })
    val rtfMedian = median(runs.map { it.rtf })
    return LineSummary(
        line = line,
        runs = runs,
        ttfaMedian = ttfaMedian,
        ttfaP90 = percentile(runs.map { it.ttfaMs }, 90.0),
        synthMedian = median(runs.map { it.synthMs }),
        synthP90 = percentile(runs.map { it.synthMs }, 90.0),
        audioMedian = median(runs.map { it.audioSeconds }),
        rtfMedian = rtfMedian,
        rtfP10 = percentile(runs.map { it.rtf }, 10.0),
        ttfaPass = ttfaMedian <= config.passTtfaMs,
        rtfPass = rtfMedian >= config.passRtf,
    )
}

private fun printReport(
    config: Config,
    warmRuns: Int,
    nativeMs: Double,
    modelLoadMs: Double,
    rssAfterLoadKb: Long,
    peakKb: Long,
    peakSource: String,
    cold: RunResult,
    summaries: List<LineSummary>,
    streamingNote: String,
) {
    val coldStart = modelLoadMs + cold.synthMs
    println()
    println("Ember voice  PocketTTS int8  sherpa-onnx JVM ${configPathVersion(config)}")
    println("host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}  ${cpuBrand()}")
    println("java: ${System.getProperty("java.version")}   heap max ${fmt(Runtime.getRuntime().maxMemory() / 1048576.0, 0)} MiB")
    println("voice: ${config.voice.path} (${config.voiceLicense}, ${config.voiceSource})")
    println("steps: ${config.numSteps}   threads: ${config.numThreads}   temperature: ${config.temperature}   chunk_size: ${config.chunkSize}")
    println("warm runs: $warmRuns per line, after one discarded warmup. Cold start is load plus the first line only.")
    println()
    println("native lib load:        ${fmtMs(nativeMs)} ms  (not part of cold start)")
    println("model load:             ${fmtMs(modelLoadMs)} ms")
    println("cold line:              ${summaries[0].line.id}")
    println("cold TTFA:              ${fmtMs(cold.ttfaMs)} ms   (${if (cold.ttfaFromCallback) "first callback" else "no callback; full utterance"})")
    println("cold synthesis:         ${fmtMs(cold.synthMs)} ms")
    println("cold audio:             ${fmt(cold.audioSeconds, 2)} s")
    println("cold RTF:               ${fmt(cold.rtf, 2)}x")
    println("cold start (load+line): ${fmtMs(coldStart)} ms")
    println("RSS after model load:   ${fmtKb(rssAfterLoadKb)}")
    println("peak RSS:               ${fmtKb(peakKb)}  ($peakSource)")
    println()
    println("Pass bar: warm median TTFA at or under ${fmt(config.passTtfaMs, 0)} ms, and warm median RTF at or above ${fmt(config.passRtf, 1)}x.")
    println("RTF = audio seconds / synthesis seconds. Higher is faster. sherpa-onnx's own examples print the inverse.")
    println("TTFA p90 is the slow tail. RTF p10 is the slow tail.")
    println()
    println(
        String.format(
            Locale.US,
            "%-16s %5s %8s %8s %9s %9s %8s %8s %8s %6s %6s",
            "line", "chars", "TTFAmed", "TTFAp90", "synthmed", "synthp90", "audio_s", "RTFmed", "RTFp10", "TTFA", "RTF",
        ),
    )
    for (summary in summaries) {
        println(
            String.format(
                Locale.US,
                "%-16s %5d %8.0f %8.0f %9.0f %9.0f %8.2f %8.2f %8.2f %6s %6s",
                summary.line.id,
                summary.line.text.length,
                summary.ttfaMedian,
                summary.ttfaP90,
                summary.synthMedian,
                summary.synthP90,
                summary.audioMedian,
                summary.rtfMedian,
                summary.rtfP10,
                passFail(summary.ttfaPass),
                passFail(summary.rtfPass),
            ),
        )
    }
    println()
    println("streaming: $streamingNote")
    val failed = summaries.filter { !it.ttfaPass || !it.rtfPass }
    if (failed.isEmpty()) {
        println("overall: PASS  every line met the warm TTFA and RTF bar")
    } else {
        val names = failed.joinToString(", ") { it.line.id }
        println("overall: FAIL  ${failed.size} of ${summaries.size} lines missed the bar ($names)")
    }
    println()
}

private fun streamingNote(cold: RunResult, summaries: List<LineSummary>): String {
    val runs = ArrayList<RunResult>()
    runs.add(cold)
    for (summary in summaries) {
        runs.addAll(summary.runs)
    }
    val multi = runs.filter { it.callbackCount > 1 && it.ttfaFromCallback && it.ttfaMs + 20.0 < it.synthMs }
    val withCallback = runs.count { it.ttfaFromCallback }
    val medianChunks = median(runs.map { it.callbackCount.toDouble() })
    val firstChunkMs = runs.filter { it.firstChunkSamples > 0 && it.sampleRate > 0 }
        .map { it.firstChunkSamples * 1000.0 / it.sampleRate }
    val firstChunkNote = if (firstChunkMs.isEmpty()) {
        "no audio samples were delivered to the callback"
    } else {
        "median first chunk is ${fmt(median(firstChunkMs), 0)} ms of audio"
    }
    return if (multi.isNotEmpty()) {
        "generateWithConfigAndCallback delivered partial audio before synthesis finished " +
            "(${multi.size} of ${runs.size} timed runs had more than one chunk, and the first arrived early). " +
            "TTFA is that first chunk. Median callback count ${fmt(medianChunks, 1)}. $firstChunkNote."
    } else if (withCallback > 0) {
        "the callback fired, but each timed run got a single chunk at the end of the utterance " +
            "($withCallback of ${runs.size} runs invoked the callback; median callback count ${fmt(medianChunks, 1)}). " +
            "sherpa-onnx exposes a streaming callback for PocketTTS, and this build did not use it to stream. " +
            "TTFA here is the time until the whole utterance was ready, same as total synthesis. $firstChunkNote."
    } else {
        "generateWithConfigAndCallback returned the whole utterance without invoking the callback. " +
            "TTFA was recorded as total synthesis time."
    }
}

private fun renderJson(
    config: Config,
    warmRuns: Int,
    nativeMs: Double,
    modelLoadMs: Double,
    rssAfterLoadKb: Long,
    peakKb: Long,
    peakSource: String,
    voiceSampleRate: Int,
    voiceSeconds: Double,
    cold: RunResult,
    summaries: List<LineSummary>,
    streamingNote: String,
): String {
    val failed = summaries.any { !it.ttfaPass || !it.rtfPass }
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"schema\": 1,\n")
    sb.append("  \"measured_at\": ${jsonString(Instant.now().toString())},\n")
    sb.append("  \"label\": \"Numbers in this file are from the machine that ran the harness.\",\n")
    sb.append("  \"host\": {\n")
    sb.append("    \"os_name\": ${jsonString(System.getProperty("os.name"))},\n")
    sb.append("    \"os_arch\": ${jsonString(System.getProperty("os.arch"))},\n")
    sb.append("    \"os_version\": ${jsonString(System.getProperty("os.version"))},\n")
    sb.append("    \"cpu\": ${jsonString(cpuBrand())},\n")
    sb.append("    \"java\": ${jsonString(System.getProperty("java.version"))}\n")
    sb.append("  },\n")
    sb.append("  \"engine\": {\n")
    sb.append("    \"binding\": \"sherpa-onnx Java/Kotlin\",\n")
    sb.append("    \"api\": \"OfflineTts.generateWithConfigAndCallback\",\n")
    sb.append("    \"sherpa_version\": ${jsonString(confValue(config, "sherpa_version"))},\n")
    sb.append("    \"model_dir\": ${jsonString(config.modelDir.path)},\n")
    sb.append("    \"provider\": \"cpu\",\n")
    sb.append("    \"num_threads\": ${config.numThreads},\n")
    sb.append("    \"num_steps\": ${config.numSteps},\n")
    sb.append("    \"temperature\": ${jsonString(config.temperature)},\n")
    sb.append("    \"chunk_size\": ${jsonString(config.chunkSize)},\n")
    sb.append("    \"warm_runs\": $warmRuns,\n")
    sb.append("    \"heap_max_bytes\": ${Runtime.getRuntime().maxMemory()}\n")
    sb.append("  },\n")
    sb.append("  \"voice\": {\n")
    sb.append("    \"path\": ${jsonString(config.voice.path)},\n")
    sb.append("    \"license\": ${jsonString(config.voiceLicense)},\n")
    sb.append("    \"source\": ${jsonString(config.voiceSource)},\n")
    sb.append("    \"url\": ${jsonString(config.voiceUrl)},\n")
    sb.append("    \"sample_rate\": $voiceSampleRate,\n")
    sb.append("    \"seconds\": ${num(voiceSeconds)}\n")
    sb.append("  },\n")
    sb.append("  \"criteria\": {\n")
    sb.append("    \"warm_ttfa_ms_at_or_under\": ${num(config.passTtfaMs)},\n")
    sb.append("    \"warm_rtf_at_or_above\": ${num(config.passRtf)},\n")
    sb.append("    \"rtf\": \"audio_seconds / synthesis_seconds\"\n")
    sb.append("  },\n")
    sb.append("  \"native_lib_load_ms\": ${num(nativeMs)},\n")
    sb.append("  \"model_load_ms\": ${num(modelLoadMs)},\n")
    sb.append("  \"cold\": ${runJson(cold, 1)},\n")
    sb.append("  \"cold_start_ms\": ${num(modelLoadMs + cold.synthMs)},\n")
    sb.append("  \"rss_after_model_load_kb\": $rssAfterLoadKb,\n")
    sb.append("  \"peak_rss_kb\": $peakKb,\n")
    sb.append("  \"peak_rss_source\": ${jsonString(peakSource)},\n")
    sb.append("  \"external_peak_rss_kb\": null,\n")
    sb.append("  \"streaming_note\": ${jsonString(streamingNote)},\n")
    sb.append("  \"lines\": [\n")
    summaries.forEachIndexed { index, summary ->
        sb.append("    {\n")
        sb.append("      \"id\": ${jsonString(summary.line.id)},\n")
        sb.append("      \"chars\": ${summary.line.text.length},\n")
        sb.append("      \"text\": ${jsonString(summary.line.text)},\n")
        sb.append("      \"warm_ttfa_median_ms\": ${num(summary.ttfaMedian)},\n")
        sb.append("      \"warm_ttfa_p90_ms\": ${num(summary.ttfaP90)},\n")
        sb.append("      \"warm_synth_median_ms\": ${num(summary.synthMedian)},\n")
        sb.append("      \"warm_synth_p90_ms\": ${num(summary.synthP90)},\n")
        sb.append("      \"warm_audio_median_s\": ${num(summary.audioMedian)},\n")
        sb.append("      \"warm_rtf_median\": ${num(summary.rtfMedian)},\n")
        sb.append("      \"warm_rtf_p10\": ${num(summary.rtfP10)},\n")
        sb.append("      \"ttfa_pass\": ${summary.ttfaPass},\n")
        sb.append("      \"rtf_pass\": ${summary.rtfPass},\n")
        sb.append("      \"wav\": ${jsonString("results/wav/${safeId(summary.line.id)}.wav")},\n")
        sb.append("      \"runs\": [\n")
        summary.runs.forEachIndexed { runIndex, run ->
            sb.append(runJson(run, 4))
            if (runIndex != summary.runs.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("      ]\n")
        sb.append("    }")
        if (index != summaries.lastIndex) sb.append(",")
        sb.append("\n")
    }
    sb.append("  ],\n")
    sb.append("  \"overall_pass\": ${!failed}\n")
    sb.append("}\n")
    return sb.toString()
}

private fun runJson(run: RunResult, indentLevel: Int): String {
    val pad = "  ".repeat(indentLevel)
    val chunks = run.chunkSamples.joinToString(", ")
    return """
        $pad{
        $pad  "kind": ${jsonString(run.kind)},
        $pad  "ttfa_ms": ${num(run.ttfaMs)},
        $pad  "ttfa_from_callback": ${run.ttfaFromCallback},
        $pad  "synth_ms": ${num(run.synthMs)},
        $pad  "audio_seconds": ${num(run.audioSeconds)},
        $pad  "rtf": ${num(run.rtf)},
        $pad  "sample_rate": ${run.sampleRate},
        $pad  "callback_count": ${run.callbackCount},
        $pad  "first_chunk_samples": ${run.firstChunkSamples},
        $pad  "chunk_samples": [$chunks]
        $pad}
    """.trimIndent()
}

private fun renderCsv(
    modelLoadMs: Double,
    cold: RunResult,
    peakKb: Long,
    summaries: List<LineSummary>,
): String {
    val sb = StringBuilder()
    sb.append(
        "line_id,chars,warm_ttfa_median_ms,warm_ttfa_p90_ms,warm_synth_median_ms,warm_synth_p90_ms," +
            "warm_audio_median_s,warm_rtf_median,warm_rtf_p10,ttfa_pass,rtf_pass," +
            "model_load_ms,cold_line_ttfa_ms,cold_line_synth_ms,cold_start_ms,peak_rss_kb\n",
    )
    val coldStart = modelLoadMs + cold.synthMs
    for (summary in summaries) {
        val coldTtfa = if (summary.line.id == summaries[0].line.id) num(cold.ttfaMs) else ""
        val coldSynth = if (summary.line.id == summaries[0].line.id) num(cold.synthMs) else ""
        sb.append(
            listOf(
                csv(summary.line.id),
                summary.line.text.length.toString(),
                num(summary.ttfaMedian),
                num(summary.ttfaP90),
                num(summary.synthMedian),
                num(summary.synthP90),
                num(summary.audioMedian),
                num(summary.rtfMedian),
                num(summary.rtfP10),
                summary.ttfaPass.toString(),
                summary.rtfPass.toString(),
                num(modelLoadMs),
                coldTtfa,
                coldSynth,
                num(coldStart),
                peakKb.toString(),
            ).joinToString(","),
        )
        sb.append('\n')
    }
    return sb.toString()
}

private fun playWav(wav: File) {
    if (!wav.isFile) {
        throw HarnessException("nothing to play at ${wav.path}")
    }
    val player = pickPlayer()
        ?: throw HarnessException(
            "no player found for ${wav.path}. On a Mac, afplay is built in. " +
                "Elsewhere install alsa-utils (aplay) or ffmpeg (ffplay).",
        )
    log("play ${wav.path} with ${player.first()}")
    val cmd = ArrayList<String>()
    cmd.addAll(player)
    cmd.add(wav.path)
    val proc = ProcessBuilder(cmd).inheritIO().start()
    val code = proc.waitFor()
    if (code != 0) {
        throw HarnessException("${player.first()} exited $code for ${wav.path}")
    }
}

private fun pickPlayer(): List<String>? {
    val os = System.getProperty("os.name").lowercase(Locale.US)
    if (os.contains("mac") || commandExists("afplay")) {
        return listOf("afplay")
    }
    if (commandExists("aplay")) return listOf("aplay")
    if (commandExists("paplay")) return listOf("paplay")
    if (commandExists("ffplay")) return listOf("ffplay", "-autoexit", "-nodisp")
    return null
}

private fun commandExists(name: String): Boolean {
    val proc = ProcessBuilder("bash", "-c", "command -v " + name).start()
    val code = proc.waitFor()
    return code == 0
}

private fun loadConfig(path: File): Config {
    if (!path.isFile) {
        throw HarnessException("config not found: ${path.path}")
    }
    val values = LinkedHashMap<String, String>()
    val lines = ArrayList<LineSpec>()
    var pendingId: String? = null
    for ((number, raw) in path.readLines(Charsets.UTF_8).withIndex()) {
        val text = raw.removeSuffix("\r")
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            continue
        }
        val eq = text.indexOf('=')
        if (eq <= 0) {
            throw HarnessException("${path.path}:${number + 1}: expected key=value")
        }
        val key = text.substring(0, eq).trim()
        val value = text.substring(eq + 1)
        when (key) {
            "line.id" -> {
                if (pendingId != null) {
                    throw HarnessException("${path.path}:${number + 1}: line.id without a line.text before it")
                }
                if (value.isEmpty()) {
                    throw HarnessException("${path.path}:${number + 1}: empty line id")
                }
                pendingId = value
            }
            "line.text" -> {
                val id = pendingId
                    ?: throw HarnessException("${path.path}:${number + 1}: line.text before line.id")
                lines.add(LineSpec(id, value))
                pendingId = null
            }
            else -> values[key] = value
        }
    }
    if (pendingId != null) {
        throw HarnessException("line.id $pendingId has no line.text")
    }
    if (lines.isEmpty()) {
        throw HarnessException("config has no lines")
    }
    fun req(key: String): String {
        return values[key] ?: throw HarnessException("config is missing $key")
    }
    return Config(
        path = path,
        modelDir = File(req("model_dir")),
        voice = File(req("voice")),
        voiceUrl = req("voice_url"),
        voiceLicense = values["voice_license"] ?: "",
        voiceSource = values["voice_source"] ?: "",
        numThreads = req("num_threads").toInt(),
        numSteps = req("num_steps").toInt(),
        temperature = req("temperature"),
        chunkSize = req("chunk_size"),
        warmRuns = req("warm_runs").toInt(),
        passTtfaMs = req("pass_warm_ttfa_ms").toDouble(),
        passRtf = req("pass_warm_rtf").toDouble(),
        lines = lines,
    )
}

private fun confValue(config: Config, key: String): String {
    for (raw in config.path.readLines(Charsets.UTF_8)) {
        val text = raw.removeSuffix("\r").trim()
        if (text.startsWith("#") || !text.startsWith("$key=")) continue
        return text.substring(key.length + 1)
    }
    return ""
}

private fun selectLines(lines: List<LineSpec>, only: String?): List<LineSpec> {
    if (only == null) return lines
    val match = lines.filter { it.id == only }
    if (match.isEmpty()) {
        throw HarnessException("no line id '$only'. Have: ${lines.joinToString(", ") { it.id }}")
    }
    return match
}

private fun selfCheck(config: Config) {
    val expected = listOf(
        "build_passed" to "Done! Your build passed.",
        "task_finished" to "I finished the task. Three files changed and all tests are green.",
        "sign_in" to "Heads up, Codex needs you to sign in before I can keep going on this one.",
        "deploy_blocked" to "The deploy is blocked: the API returned error 503 at 2:45 PM. I'll retry in 30 seconds, or you can check the logs.",
        "summary" to "Here's the summary: I refactored PetSession.kt, moved the socket code into PetBridge, updated the README, and added two tests. Everything builds, but the voice settings still need a final review from you before merge.",
    )
    expect(config.lines.size == 6, "expected 6 lines, found ${config.lines.size}")
    for ((index, pair) in expected.withIndex()) {
        val line = config.lines[index]
        expect(line.id == pair.first, "line ${index + 1} id is ${line.id}")
        expect(line.text == pair.second, "line ${line.id} text does not match the spec")
    }
    val limit = config.lines[5]
    expect(limit.id == "say_limit", "last line id is ${limit.id}")
    expect(limit.text.length == 236, "say_limit is ${limit.text.length} characters, expected 236")
    expect(!limit.text.contains("expresso", ignoreCase = true), "say_limit must not name Expresso")
    expect(config.voiceSource.contains("voice-donations"), "default voice must be a voice-donations clip")
    expect(!config.voice.path.contains("expresso"), "voice path must not point at Expresso")
    expect(!config.voice.path.contains("test_wavs"), "voice path must not point at the model test_wavs")
    expect(median(listOf(1.0, 2.0, 3.0, 4.0)) == 2.5, "median even")
    expect(median(listOf(1.0, 2.0, 3.0)) == 2.0, "median odd")
    val ten = (1..10).map { it.toDouble() }
    expect(percentile(ten, 90.0) == 9.0, "p90 of 1..10")
    expect(percentile(ten, 10.0) == 1.0, "p10 of 1..10")
    expect(jsonString("a\"b\\c") == "\"a\\\"b\\\\c\"", "json string")
    val pass = LineSummary(
        line = LineSpec("x", "hi"),
        runs = emptyList(),
        ttfaMedian = 500.0,
        ttfaP90 = 0.0,
        synthMedian = 0.0,
        synthP90 = 0.0,
        audioMedian = 0.0,
        rtfMedian = 1.5,
        rtfP10 = 0.0,
        ttfaPass = 500.0 <= config.passTtfaMs,
        rtfPass = 1.5 >= config.passRtf,
    )
    expect(pass.ttfaPass && pass.rtfPass, "pass bar is inclusive")
}

private fun sayLimit(config: Config): LineSpec = config.lines.first { it.id == "say_limit" }

private fun expect(condition: Boolean, message: String) {
    if (!condition) throw HarnessException("self-check failed: $message")
}

private fun parseArgs(args: Array<String>): Cli {
    var config = "config/harness.conf"
    var play = false
    var selfCheck = false
    var warmRuns: Int? = null
    var only: String? = null
    var debug = false
    var help = false
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--help", "-h" -> help = true
            "--play" -> play = true
            "--self-check" -> selfCheck = true
            "--debug" -> debug = true
            "--config" -> {
                i += 1
                config = argValue(args, i, arg)
            }
            "--warm-runs" -> {
                i += 1
                val raw = argValue(args, i, "--warm-runs")
                warmRuns = raw.toIntOrNull()
                    ?: throw HarnessException("--warm-runs needs an integer, got $raw")
            }
            "--only" -> {
                i += 1
                only = argValue(args, i, "--only")
            }
            else -> throw HarnessException("unknown argument $arg (try --help)")
        }
        i += 1
    }
    return Cli(config, play, selfCheck, warmRuns, only, debug, help)
}

private fun argValue(args: Array<String>, index: Int, flag: String): String {
    if (index >= args.size) throw HarnessException("$flag needs a value")
    return args[index]
}

private fun printHelp() {
    println(
        """
        Usage: ./run.sh [--play] [--only LINE_ID] [--warm-runs N] [--debug]
               ./run.sh --self-check

        Times the lines in config/harness.conf with sherpa-onnx PocketTTS.
        --play        play each saved wav (afplay on macOS)
        --only ID     time one line id
        --warm-runs N override the config warm-run count
        --self-check  check the fixed lines and the percentile math, then exit
        """.trimIndent(),
    )
}

/** Nearest-rank percentile. p is in 0..100. p90 of 10 values is the 9th. */
fun percentile(values: List<Double>, p: Double): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    var rank = ceil(p / 100.0 * sorted.size).toInt()
    if (rank < 1) rank = 1
    if (rank > sorted.size) rank = sorted.size
    return sorted[rank - 1]
}

fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

private fun currentRssKb(): Long {
    val os = System.getProperty("os.name").lowercase(Locale.US)
    if (os.contains("linux")) return procStatusKb("VmRSS:")
    if (os.contains("mac")) {
        val pid = ProcessHandle.current().pid()
        val text = capture(listOf("ps", "-o", "rss=", "-p", pid.toString()))
        return text.toLongOrNull() ?: -1L
    }
    return -1L
}

private fun linuxPeakKb(): Long {
    val os = System.getProperty("os.name").lowercase(Locale.US)
    if (!os.contains("linux")) return -1L
    return procStatusKb("VmHWM:")
}

private fun procStatusKb(prefix: String): Long {
    val file = File("/proc/self/status")
    if (!file.isFile) return -1L
    for (line in file.readLines()) {
        if (line.startsWith(prefix)) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) return parts[1].toLongOrNull() ?: -1L
        }
    }
    return -1L
}

private fun cpuBrand(): String {
    val os = System.getProperty("os.name").lowercase(Locale.US)
    if (os.contains("linux")) {
        val cpuinfo = File("/proc/cpuinfo")
        if (cpuinfo.isFile) {
            for (line in cpuinfo.readLines()) {
                if (line.startsWith("model name")) {
                    val idx = line.indexOf(':')
                    if (idx >= 0) return line.substring(idx + 1).trim()
                }
            }
        }
    }
    if (os.contains("mac")) {
        return capture(listOf("sysctl", "-n", "machdep.cpu.brand_string"))
    }
    return ""
}

private fun capture(command: List<String>): String {
    return try {
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        text
    } catch (_: Exception) {
        ""
    }
}

private fun safeId(id: String): String {
    val cleaned = id.map { ch ->
        if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
    }.joinToString("")
    if (cleaned.isEmpty()) throw HarnessException("line id is empty")
    return cleaned
}

private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

private fun fmt(value: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", value)

private fun fmtMs(value: Double): String = fmt(value, 0)

private fun fmtKb(kb: Long): String {
    if (kb < 0) return "unknown"
    return "${kb} KiB (${fmt(kb / 1024.0, 1)} MiB)"
}

private fun num(value: Double): String = String.format(Locale.US, "%.3f", value)

private fun passFail(ok: Boolean): String = if (ok) "PASS" else "FAIL"

private fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (ch.code < 0x20) {
                    sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append('"')
    return sb.toString()
}

private fun csv(value: String): String {
    if (value.contains(',') || value.contains('"') || value.contains('\n')) {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
    return value
}

private fun configPathVersion(config: Config): String {
    val version = confValue(config, "sherpa_version")
    return if (version.isEmpty()) "?" else version
}

private fun log(message: String) {
    System.err.println(message)
    System.err.flush()
}
