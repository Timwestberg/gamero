package ember.voice.bench

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.k2fsa.sherpa.onnx.WaveWriter
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
class BenchException(message: String) : RuntimeException(message)

data class CorpusLine(val id: String, val categories: List<String>, val text: String)

data class BenchConfig(
    val corpusPath: File,
    val corpusId: String,
    val corpusVersion: String,
    val corpusSync: String,
    val modelDir: File,
    val modelName: String,
    val voice: File,
    val voiceLicense: String,
    val voiceSource: String,
    val asrDir: File,
    val asrEncoder: String,
    val asrDecoder: String,
    val asrTokens: String,
    val asrName: String,
    val numThreads: Int,
    val numSteps: Int,
    val temperature: String,
    val chunkSize: String,
    val maxCharInSentence: Int,
    val provider: String,
    val seed: String,
    val chunker: String,
    val maxFrames: Int,
    val firstPieceTargetChars: Int,
    val laterPieceMaxChars: Int,
    val maxFramesPerCharacter: Double,
    val silenceMs: Int,
    val warmup: Boolean,
    val warmRuns: Int,
    val passTtfaMs: Double,
    val passRtf: Double,
    val charsPerSecond: Double,
    val minExpectedAudioS: Double,
    val runawayFactor: Double,
    val lines: List<CorpusLine>,
)

data class SpeakResult(
    val ttfaMs: Double,
    val totalMs: Double,
    val audioSeconds: Double,
    val rtf: Double,
    val cpuPercent: Double,
    val sampleRate: Int,
    val callbackCount: Int,
    val firstChunkSamples: Int,
    val runaway: Boolean,
    val samples: FloatArray,
)

fun main(args: Array<String>) {
    try {
        val cli = parseArgs(args)
        if (cli.help) {
            printHelp()
            return
        }
        if (cli.compare != null) {
            val code = compareFiles(File(cli.compare.first), File(cli.compare.second))
            kotlin.system.exitProcess(code)
        }
        val config = loadConfig(File(cli.configPath))
        if (cli.selfCheck) {
            selfCheck(config)
            println("self-check: ok (${config.lines.size} lines, corpus ${config.corpusVersion})")
            return
        }
        val selected = selectLines(config, cli.only)
        if (cli.matrix.isNotEmpty()) {
            runMatrix(config, selected, cli)
        } else {
            val overridden = applyMap(config, cli.set)
            runOnce(overridden, selected, cli, cli.output)
        }
    } catch (e: BenchException) {
        System.err.println("error: ${e.message}")
        kotlin.system.exitProcess(1)
    } catch (e: JsonException) {
        System.err.println("error: ${e.message}")
        kotlin.system.exitProcess(2)
    }
}

private data class Cli(
    val configPath: String,
    val help: Boolean,
    val selfCheck: Boolean,
    val compare: Pair<String, String>?,
    val matrix: List<Pair<String, List<String>>>,
    val set: Map<String, String>,
    val only: String?,
    val warmRuns: Int?,
    val wer: Boolean,
    val output: String,
    val gitCommit: String,
)

private fun parseArgs(args: Array<String>): Cli {
    var config = "config/bench.conf"
    var help = false
    var selfCheck = false
    var compare: Pair<String, String>? = null
    val matrix = ArrayList<Pair<String, List<String>>>()
    val set = LinkedHashMap<String, String>()
    var only: String? = null
    var warmRuns: Int? = null
    var wer = true
    var output = ""
    var gitCommit = "unknown"
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--help", "-h" -> help = true
            "--self-check" -> selfCheck = true
            "--no-wer" -> wer = false
            "--wer" -> wer = true
            "--compare" -> {
                val a = need(args, i + 1, "--compare")
                val b = need(args, i + 2, "--compare")
                compare = a to b
                i += 2
            }
            "--matrix" -> {
                i += 1
                matrix.add(parseAxis(need(args, i, "--matrix")))
            }
            "--set" -> {
                i += 1
                val raw = need(args, i, "--set")
                val eq = raw.indexOf('=')
                if (eq <= 0) throw BenchException("--set needs key=value, got $raw")
                set[raw.substring(0, eq)] = raw.substring(eq + 1)
            }
            "--only" -> {
                i += 1
                only = need(args, i, "--only")
            }
            "--warm-runs" -> {
                i += 1
                warmRuns = need(args, i, "--warm-runs").toIntOrNull()
                    ?: throw BenchException("--warm-runs needs an integer")
            }
            "--output" -> {
                i += 1
                output = need(args, i, "--output")
            }
            "--config" -> {
                i += 1
                config = need(args, i, "--config")
            }
            "--git-commit" -> {
                i += 1
                gitCommit = need(args, i, "--git-commit")
            }
            else -> throw BenchException("unknown argument $arg (try --help)")
        }
        i += 1
    }
    if (output.isEmpty()) {
        output = if (matrix.isNotEmpty()) "results/matrix-summary.json" else "results/ember-voice-bench.json"
    }
    return Cli(config, help, selfCheck, compare, matrix, set, only, warmRuns, wer, output, gitCommit)
}

private fun need(args: Array<String>, index: Int, flag: String): String {
    if (index >= args.size) throw BenchException("$flag needs a value")
    return args[index]
}

private fun parseAxis(raw: String): Pair<String, List<String>> {
    val eq = raw.indexOf('=')
    if (eq <= 0) throw BenchException("--matrix needs key=a,b, got $raw")
    val key = raw.substring(0, eq)
    val values = raw.substring(eq + 1).split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (values.isEmpty()) throw BenchException("--matrix $key has no values")
    return key to values
}

private fun printHelp() {
    println(
        """
        Usage: ./run.sh [--output FILE] [--only ID] [--warm-runs N] [--no-wer]
               ./run.sh --set key=value [--set key=value]
               ./run.sh --matrix key=a,b [--matrix key=c,d]
               ./compare.sh baseline.json candidate.json
               ./run.sh --self-check

        Times corpus/ember-voice-corpus.json with sherpa-onnx PocketTTS.
        --matrix keys: chunk_size, max_char_in_sentence, num_threads, num_steps,
                       temperature, provider, model_dir, seed, chunker, warmup, max_frames,
                       first_piece_target_chars, max_frames_per_character, silence_ms
        --no-wer       skip the offline ASR intelligibility check
        Compare exits 1 when a line's warm TTFA median regresses by more than 10%
        and a one-sided Mann-Whitney test says that slowdown is unlikely to be noise (p < 0.05).
        """.trimIndent(),
    )
}

private fun runOnce(config: BenchConfig, lines: List<CorpusLine>, cli: Cli, output: String) {
    val doc = measure(config, lines, cli)
    val file = File(output)
    file.parentFile?.mkdirs()
    file.writeText(doc)
    println("json: ${file.path}")
}

private fun runMatrix(config: BenchConfig, lines: List<CorpusLine>, cli: Cli) {
    val combos = product(cli.matrix)
    println("matrix: ${combos.size} cells")
    val dir = File("results/matrix")
    dir.mkdirs()
    val summary = StringBuilder()
    summary.append("{\n")
    summary.append("  \"schema\": 1,\n")
    summary.append("  \"kind\": \"ember-voice-bench-matrix\",\n")
    summary.append("  \"label\": ${jsonString(hostLabel())},\n")
    summary.append("  \"measured_at\": ${jsonString(Instant.now().toString())},\n")
    summary.append("  \"git_commit\": ${jsonString(cli.gitCommit)},\n")
    summary.append("  \"external_peak_rss_kb\": null,\n")
    summary.append("  \"cells\": [\n")
    combos.forEachIndexed { index, overrides ->
        val cellConfig = applyMap(applyMap(config, cli.set), overrides)
        val slug = overrides.entries.joinToString("__") { "${it.key}-${it.value}" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val cellPath = "results/matrix/$slug.json"
        log("matrix ${index + 1}/${combos.size} $overrides")
        val cellCli = cli.copy(output = cellPath)
        runOnce(cellConfig, lines, cellCli, cellPath)
        val parsed = parseJson(File(cellPath).readText())
        if (index > 0) summary.append(",\n")
        summary.append("    {\n")
        summary.append("      \"config\": {")
        overrides.entries.forEachIndexed { entryIndex, entry ->
            if (entryIndex > 0) summary.append(", ")
            summary.append("${jsonString(entry.key)}: ${jsonString(entry.value)}")
        }
        summary.append("},\n")
        summary.append("      \"file\": ${jsonString(cellPath)},\n")
        summary.append("      \"lines\": [\n")
        val cellLines = parsed.field("lines").arr()
        cellLines.forEachIndexed { lineIndex, line ->
            val ttfa = line.field("ttfa_ms").field("median").num()
            val rtf = line.field("rtf").field("median").num()
            val runaway = line.field("runaway_count").num().toInt()
            val wer = line.optional("wer")
            summary.append("        { \"id\": ${jsonString(line.field("id").str())}, ")
            summary.append("\"ttfa_median_ms\": ${fmtNum(ttfa)}, \"rtf_median\": ${fmtNum(rtf)}, ")
            summary.append("\"runaway_count\": $runaway, ")
            if (wer == null || wer is Json.Null) summary.append("\"wer\": null")
            else summary.append("\"wer\": ${fmtNum(wer.field("wer").num())}")
            summary.append(" }")
            if (lineIndex != cellLines.lastIndex) summary.append(",")
            summary.append("\n")
        }
        summary.append("      ]\n")
        summary.append("    }")
    }
    summary.append("\n  ]\n}\n")
    val summaryFile = File(cli.output)
    summaryFile.parentFile?.mkdirs()
    summaryFile.writeText(summary.toString())
    println("matrix summary: ${summaryFile.path}")
}

private fun product(axes: List<Pair<String, List<String>>>): List<Map<String, String>> {
    var acc = listOf(emptyMap<String, String>())
    for ((key, values) in axes) {
        acc = acc.flatMap { base -> values.map { value -> base + (key to value) } }
    }
    return acc
}

private fun measure(config: BenchConfig, lines: List<CorpusLine>, cli: Cli): String {
    if (config.chunker != "sherpa" && config.chunker != "ours") {
        throw BenchException("chunker ${config.chunker} is not implemented in this harness yet")
    }
    val warmRuns = cli.warmRuns ?: config.warmRuns
    if (warmRuns < 1) throw BenchException("warm runs must be at least 1")
    val sampler = RssSampler()
    sampler.start()
    var tts: OfflineTts? = null
    var asr: OfflineRecognizer? = null
    try {
        val nativeStart = System.nanoTime()
        try {
            LibraryUtils.load()
        } catch (e: UnsatisfiedLinkError) {
            throw BenchException("could not load sherpa-onnx (${e.message}). Run ./setup.sh.")
        }
        val nativeMs = nanosToMs(System.nanoTime() - nativeStart)
        log("native library loaded in ${fmtMs(nativeMs)} ms")
        val voice = readVoice(config.voice)
        log("voice ${config.voice.path}: ${voice.sampleRate} Hz, ${config.voiceLicense}")
        val modelStart = System.nanoTime()
        tts = loadTts(config)
        val modelLoadMs = nanosToMs(System.nanoTime() - modelStart)
        log("model loaded in ${fmtMs(modelLoadMs)} ms")
        if (cli.wer) {
            val asrStart = System.nanoTime()
            asr = loadAsr(config)
            log("asr loaded in ${fmtMs(nanosToMs(System.nanoTime() - asrStart))} ms (${config.asrName})")
        }
        val engine = tts
        var warmupMs = 0.0
        if (config.warmup) {
            val warmStart = System.nanoTime()
            speak(engine, "Ready.", voice, config, "Ready.")
            warmupMs = nanosToMs(System.nanoTime() - warmStart)
            log("warmup dummy: ${fmtMs(warmupMs)} ms (not counted)")
        }
        printPieceNotes(config, lines)
        val cold = speak(engine, lines[0].text, voice, config, lines[0].id).withoutSamples()
        log("cold ${lines[0].id}: TTFA ${fmtMs(cold.ttfaMs)} ms, total ${fmtMs(cold.totalMs)} ms, RTF ${fmt(cold.rtf)}")
        println()
        println("Ember voice bench  ${hostLabel()}  ${config.modelName}")
        println(
            "steps ${config.numSteps}  threads ${config.numThreads}  temperature ${config.temperature}  " +
                "chunk ${config.chunkSize}  max_char ${config.maxCharInSentence}  seed ${config.seed}  " +
                "chunker ${config.chunker}  first_piece ${config.firstPieceTargetChars}  " +
                "frames_per_char ${config.maxFramesPerCharacter}",
        )
        println("cold ${lines[0].id}: TTFA ${fmtMs(cold.ttfaMs)} ms  total ${fmtMs(cold.totalMs)} ms  RTF ${fmt(cold.rtf)}")
        println(
            "Pass bar: warm median TTFA <= ${fmtMs(config.passTtfaMs)} ms and RTF >= ${fmt(config.passRtf)}. " +
                "Exit stays 0 if the bar is missed.",
        )
        println()
        println(
            String.format(
                Locale.US,
                "%-16s %5s %8s %8s %8s %8s %7s %6s %6s %5s %s",
                "line", "chars", "TTFAmed", "TTFAp90", "TTFAp99", "totalmed", "RTFmed", "TTFA", "RTF", "run", "wer",
            ),
        )
        val lineDocs = ArrayList<String>()
        for (line in lines) {
            val discard = speak(engine, line.text, voice, config, line.id).withoutSamples()
            log("discarded warmup ${line.id}: ${fmtMs(discard.totalMs)} ms")
            val runs = ArrayList<SpeakResult>()
            var kept: SpeakResult? = null
            val wav = File("results/wav/${safeId(line.id)}.wav")
            for (n in 1..warmRuns) {
                val run = speak(engine, line.text, voice, config, line.id)
                // Keep the waveform only for the saved take. The other runs
                // would otherwise sit in the list and inflate peak RSS.
                runs.add(if (n == warmRuns) run else run.withoutSamples())
                if (n == warmRuns) kept = run
                log(
                    "warm ${line.id} $n/$warmRuns: TTFA ${fmtMs(run.ttfaMs)} ms, " +
                        "total ${fmtMs(run.totalMs)} ms, RTF ${fmt(run.rtf)}, audio ${fmt(run.audioSeconds)} s" +
                        if (run.runaway) " RUNAWAY" else "",
                )
            }
            val last = kept ?: throw BenchException("no warm run for ${line.id}")
            wav.parentFile?.mkdirs()
            if (!WaveWriter.write(wav.path, last.samples, last.sampleRate)) {
                throw BenchException("could not write ${wav.path}")
            }
            val spoken = if (config.chunker == "ours") {
                speechPieces(line.text, config.firstPieceTargetChars, config.laterPieceMaxChars).joinToString(" ")
            } else {
                line.text
            }
            val hypothesis = if (asr != null) hypothesis(asr, last) else null
            val wer = if (hypothesis != null) wordErrorRate(line.text, hypothesis) else null
            val werSpoken = if (hypothesis != null) wordErrorRate(spoken, hypothesis) else null
            if (wer != null) log("wer ${line.id}: ${fmt(wer.wer)} spoken ${fmt(werSpoken!!.wer)} (${wer.hypothesis})")
            lineDocs.add(renderLine(config, line, runs, wav.path, currentRssKb(), wer, werSpoken, spoken))
        }
        val peak = sampler.stop()
        val peakSource = if (linuxPeakKb() > 0) "max(VmHWM, samples)" else "ps rss samples"
        return renderDocument(
            config = config,
            cli = cli,
            warmRuns = warmRuns,
            nativeMs = nativeMs,
            modelLoadMs = modelLoadMs,
            warmupMs = warmupMs,
            peakKb = peak,
            peakSource = peakSource,
            voiceRate = voice.sampleRate,
            cold = cold,
            coldLine = lines[0],
            lineDocs = lineDocs,
        )
    } finally {
        sampler.stop()
        asr?.release()
        tts?.release()
    }
}

private fun renderLine(
    config: BenchConfig,
    line: CorpusLine,
    runs: List<SpeakResult>,
    wav: String,
    rssKb: Long,
    wer: WerScore?,
    werSpoken: WerScore?,
    spoken: String,
): String {
    val ttfa = sampleStats(runs.map { it.ttfaMs })
    val total = sampleStats(runs.map { it.totalMs })
    val rtf = sampleStats(runs.map { it.rtf })
    val audio = sampleStats(runs.map { it.audioSeconds })
    val cpu = sampleStats(runs.map { it.cpuPercent })
    val expected = expectedAudioSeconds(line.text.length, config.charsPerSecond, config.minExpectedAudioS)
    val sb = StringBuilder()
    sb.append("    {\n")
    sb.append("      \"id\": ${jsonString(line.id)},\n")
    sb.append("      \"categories\": [${line.categories.joinToString(", ") { jsonString(it) }}],\n")
    sb.append("      \"chars\": ${line.text.length},\n")
    sb.append("      \"text\": ${jsonString(line.text)},\n")
    sb.append("      \"spoken\": ${jsonString(spoken)},\n")
    sb.append("      \"expected_audio_s\": ${fmtNum(expected)},\n")
    sb.append("      \"wav\": ${jsonString(wav)},\n")
    sb.append("      \"ttfa_ms\": ${renderStats(ttfa)},\n")
    sb.append("      \"total_ms\": ${renderStats(total)},\n")
    sb.append("      \"rtf\": ${renderStats(rtf)},\n")
    sb.append("      \"audio_s\": ${renderStats(audio)},\n")
    sb.append("      \"cpu_percent\": ${renderStats(cpu)},\n")
    sb.append("      \"runaway_count\": ${runs.count { it.runaway }},\n")
    sb.append("      \"rss_kb\": $rssKb,\n")
    sb.append("      \"wer\": ${renderWer(wer)},\n")
    sb.append("      \"wer_spoken\": ${renderWer(werSpoken)}\n")
    sb.append("    }")
    val ttfaPass = ttfa.median <= config.passTtfaMs
    val rtfPass = rtf.median >= config.passRtf
    println(
        String.format(
            Locale.US,
            "%-16s %5d %8.0f %8.0f %8.0f %8.0f %7.2f %6s %6s %5d %s",
            line.id,
            line.text.length,
            ttfa.median,
            ttfa.p90,
            ttfa.p99,
            total.median,
            rtf.median,
            if (ttfaPass) "PASS" else "FAIL",
            if (rtfPass) "PASS" else "FAIL",
            runs.count { it.runaway },
            if (wer == null) "" else "wer ${fmt(wer.wer)}",
        ),
    )
    return sb.toString()
}

private fun renderWer(wer: WerScore?): String {
    if (wer == null) return "null"
    return "{ \"wer\": ${fmtNum(wer.wer)}, \"reference_words\": ${wer.referenceWords}, " +
        "\"errors\": ${wer.errors}, \"hypothesis\": ${jsonString(wer.hypothesis)} }"
}

private fun renderStats(stats: SampleStats): String {
    return "{ \"median\": ${fmtNum(stats.median)}, \"p90\": ${fmtNum(stats.p90)}, " +
        "\"p99\": ${fmtNum(stats.p99)}, \"max\": ${fmtNum(stats.max)}, \"min\": ${fmtNum(stats.min)}, " +
        "\"samples\": [${stats.samples.joinToString(", ") { fmtNum(it) }}] }"
}

private fun renderDocument(
    config: BenchConfig,
    cli: Cli,
    warmRuns: Int,
    nativeMs: Double,
    modelLoadMs: Double,
    warmupMs: Double,
    peakKb: Long,
    peakSource: String,
    voiceRate: Int,
    cold: SpeakResult,
    coldLine: CorpusLine,
    lineDocs: List<String>,
): String {
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"schema\": 1,\n")
    sb.append("  \"kind\": \"ember-voice-bench\",\n")
    sb.append("  \"label\": ${jsonString(hostLabel())},\n")
    sb.append("  \"measured_at\": ${jsonString(Instant.now().toString())},\n")
    sb.append("  \"git_commit\": ${jsonString(cli.gitCommit)},\n")
    sb.append("  \"corpus\": {\n")
    sb.append("    \"id\": ${jsonString(config.corpusId)},\n")
    sb.append("    \"version\": ${jsonString(config.corpusVersion)},\n")
    sb.append("    \"path\": ${jsonString(config.corpusPath.path)}\n")
    sb.append("  },\n")
    sb.append("  \"device\": {\n")
    sb.append("    \"os_name\": ${jsonString(System.getProperty("os.name"))},\n")
    sb.append("    \"os_version\": ${jsonString(System.getProperty("os.version"))},\n")
    sb.append("    \"os_arch\": ${jsonString(System.getProperty("os.arch"))},\n")
    sb.append("    \"chip\": ${jsonString(cpuBrand())},\n")
    sb.append("    \"logical_cpus\": ${Runtime.getRuntime().availableProcessors()}\n")
    sb.append("  },\n")
    sb.append("  \"model\": {\n")
    sb.append("    \"name\": ${jsonString(config.modelName)},\n")
    sb.append("    \"dir\": ${jsonString(config.modelDir.path)},\n")
    sb.append("    \"provider\": ${jsonString(config.provider)}\n")
    sb.append("  },\n")
    sb.append("  \"voice\": {\n")
    sb.append("    \"path\": ${jsonString(config.voice.path)},\n")
    sb.append("    \"license\": ${jsonString(config.voiceLicense)},\n")
    sb.append("    \"source\": ${jsonString(config.voiceSource)},\n")
    sb.append("    \"sample_rate\": $voiceRate\n")
    sb.append("  },\n")
    sb.append("  \"asr\": {\n")
    sb.append("    \"name\": ${jsonString(config.asrName)},\n")
    sb.append("    \"used\": ${cli.wer}\n")
    sb.append("  },\n")
    sb.append("  \"config\": {\n")
    sb.append("    \"num_threads\": ${config.numThreads},\n")
    sb.append("    \"num_steps\": ${config.numSteps},\n")
    sb.append("    \"temperature\": ${jsonString(config.temperature)},\n")
    sb.append("    \"chunk_size\": ${jsonString(config.chunkSize)},\n")
    sb.append("    \"max_char_in_sentence\": ${config.maxCharInSentence},\n")
    sb.append("    \"seed\": ${jsonString(config.seed)},\n")
    sb.append("    \"chunker\": ${jsonString(config.chunker)},\n")
    sb.append("    \"max_frames\": ${config.maxFrames},\n")
    sb.append("    \"first_piece_target_chars\": ${config.firstPieceTargetChars},\n")
    sb.append("    \"later_piece_max_chars\": ${config.laterPieceMaxChars},\n")
    sb.append("    \"max_frames_per_character\": ${fmtNum(config.maxFramesPerCharacter)},\n")
    sb.append("    \"silence_ms\": ${config.silenceMs},\n")
    sb.append("    \"warmup\": ${config.warmup},\n")
    sb.append("    \"warm_runs\": $warmRuns,\n")
    sb.append("    \"chars_per_second\": ${fmtNum(config.charsPerSecond)},\n")
    sb.append("    \"min_expected_audio_s\": ${fmtNum(config.minExpectedAudioS)},\n")
    sb.append("    \"runaway_factor\": ${fmtNum(config.runawayFactor)}\n")
    sb.append("  },\n")
    sb.append("  \"native_lib_load_ms\": ${fmtNum(nativeMs)},\n")
    sb.append("  \"model_load_ms\": ${fmtNum(modelLoadMs)},\n")
    sb.append("  \"warmup_ms\": ${fmtNum(warmupMs)},\n")
    sb.append("  \"peak_rss_kb\": $peakKb,\n")
    sb.append("  \"peak_rss_source\": ${jsonString(peakSource)},\n")
    sb.append("  \"external_peak_rss_kb\": null,\n")
    sb.append("  \"cold\": {\n")
    sb.append("    \"line_id\": ${jsonString(coldLine.id)},\n")
    sb.append("    \"ttfa_ms\": ${fmtNum(cold.ttfaMs)},\n")
    sb.append("    \"total_ms\": ${fmtNum(cold.totalMs)},\n")
    sb.append("    \"audio_s\": ${fmtNum(cold.audioSeconds)},\n")
    sb.append("    \"rtf\": ${fmtNum(cold.rtf)},\n")
    sb.append("    \"cpu_percent\": ${fmtNum(cold.cpuPercent)},\n")
    sb.append("    \"runaway\": ${cold.runaway},\n")
    sb.append("    \"callback_count\": ${cold.callbackCount}\n")
    sb.append("  },\n")
    sb.append("  \"lines\": [\n")
    sb.append(lineDocs.joinToString(",\n"))
    sb.append("\n  ]\n}\n")
    return sb.toString()
}

private class VoiceWave(val samples: FloatArray, val sampleRate: Int)

private fun readVoice(path: File): VoiceWave {
    if (!path.isFile) throw BenchException("voice file not found: ${path.path}. Run ./setup.sh.")
    refuseNonCommercialVoice(path.path)
    val reader = WaveReader(path.path)
    if (reader.samples.isEmpty() || reader.sampleRate <= 0) throw BenchException("voice wav is empty: ${path.path}")
    return VoiceWave(reader.samples, reader.sampleRate)
}

private fun loadTts(config: BenchConfig): OfflineTts {
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
    if (missing.isNotEmpty()) throw BenchException("model files missing in ${dir.path}: ${missing.joinToString(", ")}. Run ./setup.sh.")
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
        .setProvider(config.provider)
        .build()
    return OfflineTts(OfflineTtsConfig.builder().setModel(model).build())
}

private fun generationConfig(
    config: BenchConfig,
    voice: VoiceWave,
    text: String,
    seedOverride: String? = null,
): GenerationConfig {
    val extra = HashMap<String, String>()
    extra["temperature"] = config.temperature
    extra["chunk_size"] = config.chunkSize
    val maxChar = if (config.chunker == "ours") maxOf(config.maxCharInSentence, text.length) else config.maxCharInSentence
    extra["max_char_in_sentence"] = maxChar.toString()
    extra["seed"] = seedOverride ?: config.seed
    val frames = framesFor(config, text)
    if (frames > 0) extra["max_frames"] = frames.toString()
    val gen = GenerationConfig()
    gen.referenceAudio = voice.samples
    gen.referenceSampleRate = voice.sampleRate
    gen.numSteps = config.numSteps
    gen.speed = 1.0f
    gen.extra = extra
    return gen
}

private fun framesFor(config: BenchConfig, text: String): Int {
    if (config.maxFrames > 0) return config.maxFrames
    if (config.chunker == "ours" && config.maxFramesPerCharacter > 0.0) {
        val frames = kotlin.math.ceil(text.length * config.maxFramesPerCharacter).toInt()
        return if (frames < 4) 4 else frames
    }
    return 0
}

private fun speak(tts: OfflineTts, text: String, voice: VoiceWave, config: BenchConfig, id: String): SpeakResult {
    if (config.chunker == "ours") return speakChunked(tts, text, voice, config, id)
    return speakSherpa(tts, text, voice, config, id)
}

private fun speakChunked(
    tts: OfflineTts,
    text: String,
    voice: VoiceWave,
    config: BenchConfig,
    id: String,
): SpeakResult {
    val pieces = speechPieces(text, config.firstPieceTargetChars, config.laterPieceMaxChars)
    if (pieces.isEmpty()) throw BenchException("chunker produced no pieces for $id")
    val cpuStart = processCpuNanos()
    val start = System.nanoTime()
    var ttfaMs = -1.0
    var callbackCount = 0
    var firstChunkSamples = 0
    val parts = ArrayList<FloatArray>()
    var sampleRate = voice.sampleRate
    for ((index, piece) in pieces.withIndex()) {
        var attempt = synthesizePiece(tts, piece, voice, config, "$id#$index", null)
        if (config.maxFramesPerCharacter > 0.0 && pieceRanLong(config, piece, attempt.audioSeconds)) {
            val retrySeed = nextSeed(config.seed)
            val second = synthesizePiece(tts, piece, voice, config, "$id#$index", retrySeed)
            log("regenerated $id piece $index (${fmt(attempt.audioSeconds)} s -> ${fmt(second.audioSeconds)} s)")
            if (second.audioSeconds <= attempt.audioSeconds) attempt = second
        }
        if (index == 0) {
            ttfaMs = attempt.ttfaMs
            firstChunkSamples = attempt.firstChunkSamples
        }
        callbackCount += attempt.callbackCount
        sampleRate = attempt.sampleRate
        parts.add(attempt.samples)
    }
    val totalNs = System.nanoTime() - start
    val cpuEnd = processCpuNanos()
    val gap = sampleRate * config.silenceMs / 1000
    val samples = concatAudio(parts, gap)
    return finishSpeak(config, text, samples, sampleRate, ttfaMs, totalNs, cpuStart, cpuEnd, callbackCount, firstChunkSamples)
}

private fun pieceRanLong(config: BenchConfig, piece: String, audioSeconds: Double): Boolean {
    val expected = expectedAudioSeconds(piece.length, config.charsPerSecond, config.minExpectedAudioS)
    return audioSeconds > config.runawayFactor * expected
}

private fun nextSeed(seed: String): String {
    val value = seed.toIntOrNull() ?: return seed + "1"
    return (value + 1).toString()
}

private fun concatAudio(parts: List<FloatArray>, gap: Int): FloatArray {
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

private data class PieceAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val ttfaMs: Double,
    val audioSeconds: Double,
    val callbackCount: Int,
    val firstChunkSamples: Int,
)

private fun synthesizePiece(
    tts: OfflineTts,
    text: String,
    voice: VoiceWave,
    config: BenchConfig,
    id: String,
    seedOverride: String?,
): PieceAudio {
    val gen = generationConfig(config, voice, text, seedOverride)
    val callbackCount = AtomicInteger(0)
    val firstChunkSamples = AtomicInteger(0)
    val ttfaNs = AtomicLong(-1L)
    val start = System.nanoTime()
    val callback = object : OfflineTtsCallback {
        override fun invoke(samples: FloatArray): Int {
            callbackCount.incrementAndGet()
            if (samples.isNotEmpty() && ttfaNs.compareAndSet(-1L, System.nanoTime() - start)) {
                firstChunkSamples.set(samples.size)
            }
            // Stop a callback loop if the engine ignores max_frames. A normal
            // line is a few hundred callbacks; 8192 is far past that.
            return if (callbackCount.get() > 8192) 0 else 1
        }
    }
    val audio = tts.generateWithConfigAndCallback(text, gen, callback)
    val totalMs = nanosToMs(System.nanoTime() - start)
    val samples = audio.samples
    val sampleRate = audio.sampleRate
    if (samples.isEmpty() || sampleRate <= 0) throw BenchException("no audio for $id")
    val ttfaMs = if (ttfaNs.get() >= 0) nanosToMs(ttfaNs.get()) else totalMs
    val audioSeconds = samples.size / sampleRate.toDouble()
    return PieceAudio(samples, sampleRate, ttfaMs, audioSeconds, callbackCount.get(), firstChunkSamples.get())
}

private fun speakSherpa(
    tts: OfflineTts,
    text: String,
    voice: VoiceWave,
    config: BenchConfig,
    id: String,
): SpeakResult {
    val cpuStart = processCpuNanos()
    val start = System.nanoTime()
    val piece = synthesizePiece(tts, text, voice, config, id, null)
    val totalNs = System.nanoTime() - start
    val cpuEnd = processCpuNanos()
    return finishSpeak(
        config,
        text,
        piece.samples,
        piece.sampleRate,
        piece.ttfaMs,
        totalNs,
        cpuStart,
        cpuEnd,
        piece.callbackCount,
        piece.firstChunkSamples,
    )
}

private fun finishSpeak(
    config: BenchConfig,
    text: String,
    samples: FloatArray,
    sampleRate: Int,
    ttfaMs: Double,
    totalNs: Long,
    cpuStart: Long,
    cpuEnd: Long,
    callbackCount: Int,
    firstChunkSamples: Int,
): SpeakResult {
    val totalMs = nanosToMs(totalNs)
    val audioSeconds = samples.size / sampleRate.toDouble()
    val rtf = if (totalMs > 0.0) audioSeconds / (totalMs / 1000.0) else 0.0
    val expected = expectedAudioSeconds(text.length, config.charsPerSecond, config.minExpectedAudioS)
    return SpeakResult(
        ttfaMs = ttfaMs,
        totalMs = totalMs,
        audioSeconds = audioSeconds,
        rtf = rtf,
        cpuPercent = cpuPercent(cpuStart, cpuEnd, totalNs),
        sampleRate = sampleRate,
        callbackCount = callbackCount,
        firstChunkSamples = firstChunkSamples,
        runaway = audioSeconds > config.runawayFactor * expected,
        samples = samples,
    )
}

private fun loadAsr(config: BenchConfig): OfflineRecognizer {
    val dir = config.asrDir
    val encoder = File(dir, config.asrEncoder)
    val decoder = File(dir, config.asrDecoder)
    val tokens = File(dir, config.asrTokens)
    if (!encoder.isFile || !decoder.isFile || !tokens.isFile) {
        throw BenchException("ASR model missing in ${dir.path}. Run ./setup.sh.")
    }
    val whisper = OfflineWhisperModelConfig.builder()
        .setEncoder(encoder.path)
        .setDecoder(decoder.path)
        .setLanguage("en")
        .setTask("transcribe")
        .build()
    val model = OfflineModelConfig.builder()
        .setWhisper(whisper)
        .setTokens(tokens.path)
        .setNumThreads(config.numThreads)
        .setDebug(false)
        .setProvider("cpu")
        .build()
    val recognizerConfig = OfflineRecognizerConfig.builder().setOfflineModelConfig(model).build()
    return OfflineRecognizer(recognizerConfig)
}

private fun hypothesis(asr: OfflineRecognizer, run: SpeakResult): String {
    val stream = asr.createStream()
    try {
        stream.acceptWaveform(run.samples, run.sampleRate)
        asr.decode(stream)
        return asr.getResult(stream).text ?: ""
    } finally {
        stream.release()
    }
}

private fun printPieceNotes(config: BenchConfig, lines: List<CorpusLine>) {
    if (config.chunker == "ours") {
        println(
            "our pieces, first target ${config.firstPieceTargetChars}, later max ${config.laterPieceMaxChars}: " +
                "the first piece is synthesized before the first callback.",
        )
    } else {
        println(
            "sherpa pieces at max_char ${config.maxCharInSentence}: " +
                "the language model finishes the first piece before the first callback.",
        )
    }
    for (line in lines) {
        val parts = if (config.chunker == "ours") {
            speechPieces(line.text, config.firstPieceTargetChars, config.laterPieceMaxChars)
        } else {
            sherpaPieces(line.text, config.maxCharInSentence, 30)
        }
        println("  ${line.id}: ${parts.joinToString(" + ") { it.length.toString() }} chars")
    }
}

private fun applyMap(config: BenchConfig, overrides: Map<String, String>): BenchConfig {
    var next = config
    for ((key, value) in overrides) {
        next = when (key) {
            "num_threads" -> next.copy(numThreads = positive(value, key))
            "num_steps" -> next.copy(numSteps = positive(value, key))
            "temperature" -> next.copy(temperature = value)
            "chunk_size" -> next.copy(chunkSize = positive(value, key).toString())
            "max_char_in_sentence" -> next.copy(maxCharInSentence = positive(value, key))
            "provider" -> next.copy(provider = value)
            "model_dir" -> next.copy(modelDir = File(value), modelName = File(value).name)
            "seed" -> next.copy(seed = value)
            "chunker" -> next.copy(chunker = value)
            "warmup" -> next.copy(warmup = value == "true" || value == "1")
            "max_frames" -> next.copy(maxFrames = value.toIntOrNull() ?: throw BenchException("max_frames needs an integer"))
            "first_piece_target_chars" -> next.copy(firstPieceTargetChars = positive(value, key))
            "later_piece_max_chars" -> next.copy(laterPieceMaxChars = positive(value, key))
            "max_frames_per_character" -> {
                val rate = value.toDoubleOrNull() ?: throw BenchException("max_frames_per_character needs a number")
                if (rate < 0.0) throw BenchException("max_frames_per_character must be >= 0")
                next.copy(maxFramesPerCharacter = rate)
            }
            "silence_ms" -> next.copy(silenceMs = value.toIntOrNull() ?: throw BenchException("silence_ms needs an integer"))
            "warm_runs" -> next.copy(warmRuns = positive(value, key))
            else -> throw BenchException("unknown config key $key")
        }
    }
    return next
}

private fun positive(raw: String, key: String): Int {
    val value = raw.toIntOrNull() ?: throw BenchException("$key needs an integer, got $raw")
    if (value < 1) throw BenchException("$key must be at least 1")
    return value
}

private fun selectLines(config: BenchConfig, only: String?): List<CorpusLine> {
    if (only == null) return config.lines
    val line = config.lines.firstOrNull { it.id == only } ?: throw BenchException("unknown line id $only")
    return listOf(line)
}

private fun loadConfig(path: File): BenchConfig {
    if (!path.isFile) throw BenchException("missing ${path.path}")
    val values = parseConf(path)
    fun req(key: String): String = values[key] ?: throw BenchException("${path.path} is missing $key")
    val corpusPath = File(req("corpus"))
    val corpus = loadCorpus(corpusPath)
    return BenchConfig(
        corpusPath = corpusPath,
        corpusId = corpus.first,
        corpusVersion = corpus.second,
        corpusSync = corpus.third,
        modelDir = File(req("model_dir")),
        modelName = req("model_name"),
        voice = File(req("voice")),
        voiceLicense = req("voice_license"),
        voiceSource = req("voice_source"),
        asrDir = File(req("asr_dir")),
        asrEncoder = req("asr_encoder"),
        asrDecoder = req("asr_decoder"),
        asrTokens = req("asr_tokens"),
        asrName = req("asr_name"),
        numThreads = req("num_threads").toInt(),
        numSteps = req("num_steps").toInt(),
        temperature = req("temperature"),
        chunkSize = req("chunk_size"),
        maxCharInSentence = req("max_char_in_sentence").toInt(),
        provider = req("provider"),
        seed = req("seed"),
        chunker = req("chunker"),
        maxFrames = req("max_frames").toInt(),
        firstPieceTargetChars = req("first_piece_target_chars").toInt(),
        laterPieceMaxChars = req("later_piece_max_chars").toInt(),
        maxFramesPerCharacter = req("max_frames_per_character").toDouble(),
        silenceMs = req("silence_ms").toInt(),
        warmup = req("warmup") == "true",
        warmRuns = req("warm_runs").toInt(),
        passTtfaMs = req("pass_warm_ttfa_ms").toDouble(),
        passRtf = req("pass_warm_rtf").toDouble(),
        charsPerSecond = req("chars_per_second").toDouble(),
        minExpectedAudioS = req("min_expected_audio_s").toDouble(),
        runawayFactor = req("runaway_factor").toDouble(),
        lines = corpus.fourth,
    )
}

private data class Quad(val first: String, val second: String, val third: String, val fourth: List<CorpusLine>)

private fun loadCorpus(path: File): Quad {
    if (!path.isFile) throw BenchException("missing corpus ${path.path}")
    val root = parseJson(path.readText())
    val lines = root.field("lines").arr().map { line ->
        CorpusLine(
            id = line.field("id").str(),
            categories = line.field("categories").arr().map { it.str() },
            text = line.field("text").str(),
        )
    }
    if (lines.isEmpty()) throw BenchException("corpus has no lines")
    return Quad(root.field("id").str(), root.field("version").str(), root.field("sync").str(), lines)
}

private fun parseConf(path: File): Map<String, String> {
    val values = LinkedHashMap<String, String>()
    for (raw in path.readLines()) {
        val line = raw.removeSuffix("\r")
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim()
        values[key] = line.substring(eq + 1).trim()
    }
    return values
}

fun compareFiles(baseline: File, candidate: File): Int {
    if (!baseline.isFile) throw BenchException("missing ${baseline.path}")
    if (!candidate.isFile) throw BenchException("missing ${candidate.path}")
    val base = parseJson(baseline.readText())
    val cand = parseJson(candidate.readText())
    val rows = ttfaRegressions(base, cand)
    println("compare ${baseline.path} -> ${candidate.path}")
    println(String.format(Locale.US, "%-16s %10s %10s %8s %8s %s", "line", "base_med", "cand_med", "ratio", "p", "flag"))
    var failed = 0
    for (row in rows) {
        val flag = if (row.regressed) "REGRESSION" else "ok"
        if (row.regressed) failed += 1
        println(
            String.format(
                Locale.US,
                "%-16s %10.1f %10.1f %8.3f %8.4f %s",
                row.lineId,
                row.baseMedian,
                row.candMedian,
                row.ratio,
                row.pValue,
                flag,
            ),
        )
    }
    if (failed == 0) {
        println("no TTFA regression over 10% that is also significant at p < 0.05")
        return 0
    }
    println("$failed line(s) regressed")
    return 1
}

private fun selfCheck(config: BenchConfig) {
    val expected = listOf(
        "build_passed" to "Done! Your build passed.",
        "task_finished" to "I finished the task. Three files changed and all tests are green.",
        "sign_in" to "Heads up, Codex needs you to sign in before I can keep going on this one.",
        "deploy_blocked" to "The deploy is blocked: the API returned error 503 at 2:45 PM. I'll retry in 30 seconds, or you can check the logs.",
        "summary" to "Here's the summary: I refactored PetSession.kt, moved the socket code into PetBridge, updated the README, and added two tests. Everything builds, but the voice settings still need a final review from you before merge.",
        "say_limit" to "This is the longest note the pet is allowed to say, just under the two hundred forty character cap, so we can hear a full status update: the build is green, two tests were added, and now the voice settings need your review before merge.",
    )
    for ((index, pair) in expected.withIndex()) {
        val line = config.lines[index]
        check(line.id == pair.first, "line ${index + 1} id is ${line.id}")
        check(line.text == pair.second, "line ${line.id} text drifted")
    }
    val say = config.lines.first { it.id == "say_limit" }
    check(say.text.length == 236, "say_limit length ${say.text.length}")
    val cap = config.lines.first { it.id == "say_cap" }
    check(cap.text.length == 240, "say_cap length ${cap.text.length}")
    check(cap.text.none { !it.isLetter() && it != ' ' }, "say_cap has punctuation")
    check(cap.categories.contains("punctuation_free"), "say_cap category")
    val question = config.lines.first { it.id == "question" }
    check(question.text.endsWith("?"), "question line")
    check(config.lines.any { it.categories.contains("very_short") }, "very_short")
    check(config.lines.any { it.categories.contains("numbers") && it.categories.contains("code") }, "numbers/code")
    check(config.lines.any { it.categories.contains("multi_sentence") }, "multi_sentence")
    check(config.corpusVersion.isNotEmpty() && config.corpusSync.contains("event-contracts"), "corpus version note")
    val ten = (1..10).map { it.toDouble() }
    check(percentile(ten, 90.0) == 9.0, "p90")
    check(percentile(ten, 99.0) == 10.0, "p99 of 10 is the max")
    check(median(listOf(1.0, 2.0, 3.0, 4.0)) == 2.5, "median")
    val same = wordErrorRate("The cat sat.", "the cat sat")
    check(same.wer == 0.0, "wer identical ${same.wer}")
    val one = wordErrorRate("the cat sat", "the dog sat")
    check(kotlin.math.abs(one.wer - 1.0 / 3.0) < 1e-9, "wer one sub ${one.wer}")
    val slowP = mannWhitneyGreaterP(listOf(1.0, 2.0, 3.0, 4.0, 5.0), listOf(6.0, 7.0, 8.0, 9.0, 10.0))
    check(slowP < 0.01, "separated samples p=$slowP")
    val fastP = mannWhitneyGreaterP(listOf(6.0, 7.0, 8.0, 9.0, 10.0), listOf(1.0, 2.0, 3.0, 4.0, 5.0))
    check(fastP > 0.5, "faster candidate p=$fastP")
    val pieces = sherpaPieces(say.text, 120, 30)
    check(pieces.size == 2 && pieces[0].length == 116, "say_limit split ${pieces.map { it.length }}")
    val doc = parseJson(
        """
        {"lines":[
          {"id":"a","ttfa_ms":{"samples":[1,2,3,4,5]}},
          {"id":"b","ttfa_ms":{"samples":[6,7,8,9,10]}}
        ]}
        """.trimIndent(),
    )
    val shifted = parseJson(
        """
        {"lines":[
          {"id":"a","ttfa_ms":{"samples":[6,7,8,9,10]}},
          {"id":"b","ttfa_ms":{"samples":[1,2,3,4,5]}}
        ]}
        """.trimIndent(),
    )
    val rows = ttfaRegressions(doc, shifted)
    check(rows.first { it.lineId == "a" }.regressed, "a should regress")
    check(!rows.first { it.lineId == "b" }.regressed, "b should not regress")
    val roundTrip = parseJson(jsonString("a\"b\\c"))
    check(roundTrip.str() == "a\"b\\c", "json string")
    check(expectedAudioSeconds(24, 15.0, 0.8) == 1.6, "expected audio")
    check(expectedAudioSeconds(4, 15.0, 0.8) == 0.8, "expected floor")
    check(werTokens("It's the A1.") == listOf("it's", "the", "a1"), "wer tokens ${werTokens("It's the A1.")}")
    check(werTokens("don\u2019t stop") == listOf("don", "t", "stop"), "curly apostrophe splits")
    check(wordErrorRate("", "").wer == 0.0, "empty reference and hypothesis")
    check(wordErrorRate("", "hello").wer == 1.0, "empty reference scores 1")
    check(compareVersions("2.0.0-rc1", "2.0.0") < 0, "pre-release is lower than the release")
    check(compareVersions("2.0.0", "2.0.0-rc1") > 0, "release is higher than its pre-release")
    check(compareVersions("2.0.0-rc1", "2.0.0-rc2") < 0, "rc1 before rc2")
    check(compareVersions("2.0.0+build", "2.0.0") == 0, "build metadata is not part of the order")
    check(normalizeForSpeech("503") == "five hundred three", "503 ${normalizeForSpeech("503")}")
    check(normalizeForSpeech("30") == "thirty", "30 ${normalizeForSpeech("30")}")
    check(normalizeForSpeech("2:45 PM") == "two forty-five PM", "time ${normalizeForSpeech("2:45 PM")}")
    check(normalizeForSpeech("PetBridge.kt") == "Pet Bridge dot kt", "file ${normalizeForSpeech("PetBridge.kt")}")
    check(normalizeForSpeech("PetBridge") == "PetBridge", "bare camel")
    val passed = speechPieces("Done! Your build passed.", 60, 140)
    check(passed == listOf("Done!", "Your build passed."), "build pieces $passed")
    val numbers = speechPieces(
        "PetBridge.kt returned error 503 at 2:45 PM after 30 seconds.",
        60,
        140,
    )
    check(numbers.any { it.contains("five hundred three") }, "number split $numbers")
    check(numbers.any { it.contains("two forty-five") }, "time split $numbers")
    check(numbers.any { it.contains("thirty") }, "thirty $numbers")
    val limit = speechPieces(say.text, 60, 140)
    check(limit.first().endsWith("say,"), "say_limit first ${limit.first()}")
    check(limit.any { it.contains("two hundred forty") }, "say_limit number $limit")
    val questionPieces = speechPieces(question.text, 60, 140)
    check(questionPieces.size == 1, "question pieces ${questionPieces.size}")
    refuseNonCommercialVoice(config.voice.path)
    checkFails("expresso") { refuseNonCommercialVoice("voices/expresso/ex03.wav") }
    checkFails("test_wavs") { refuseNonCommercialVoice("models/test_wavs/bria.wav") }
}

private fun SpeakResult.withoutSamples(): SpeakResult = copy(samples = FloatArray(0))

private fun refuseNonCommercialVoice(path: String) {
    val lowered = path.replace('\\', '/').lowercase()
    if (lowered.contains("expresso") || lowered.contains("test_wavs")) {
        throw BenchException(
            "voice must be CC0 or another permissive license. " +
                "Expresso and test_wavs are CC-BY-NC and cannot be used ($path).",
        )
    }
}

private fun checkFails(label: String, block: () -> Unit) {
    try {
        block()
        throw BenchException("self-check failed: $label should have failed")
    } catch (e: BenchException) {
        if (e.message?.contains("should have failed") == true) throw e
    }
}

private fun check(condition: Boolean, message: String) {
    if (!condition) throw BenchException("self-check failed: $message")
}

fun sherpaPieces(text: String, maxChars: Int, minChars: Int): List<String> {
    val merged = mergeShort(splitPunct(text), minChars)
    val pieces = ArrayList<String>()
    for (sentence in merged) pieces.addAll(splitLong(sentence, maxChars))
    return pieces
}

private fun splitPunct(text: String): List<String> {
    val sentences = ArrayList<String>()
    val cur = StringBuilder()
    fun flush() {
        val piece = cur.toString().trim()
        if (piece.isNotEmpty()) sentences.add(piece)
        cur.setLength(0)
    }
    for (ch in text) {
        cur.append(ch)
        if (ch == '.' || ch == '!' || ch == '?' || ch == '。' || ch == '！' || ch == '？') flush()
    }
    flush()
    return sentences
}

private fun boundary(ch: Char): Boolean {
    return ch == '.' || ch == '!' || ch == '?' || ch == '。' || ch == '！' || ch == '？' ||
        ch == ',' || ch == ';' || ch == ':' || ch == '，' || ch == '；' || ch == '：'
}

private fun mergeShort(sentences: List<String>, minChars: Int): List<String> {
    val merged = ArrayList<String>()
    val buffer = StringBuilder()
    for (raw in sentences) {
        val piece = raw.trim()
        if (piece.isEmpty()) continue
        if (buffer.isNotEmpty() && needSpace(buffer.toString(), piece)) buffer.append(' ')
        buffer.append(piece)
        if (buffer.length >= minChars) {
            merged.add(buffer.toString().trim())
            buffer.setLength(0)
        }
    }
    if (buffer.isNotEmpty()) merged.add(buffer.toString().trim())
    return merged
}

private fun needSpace(left: String, right: String): Boolean {
    if (left.isEmpty() || right.isEmpty()) return false
    val last = left.last()
    val first = right.first()
    if (last.isWhitespace() || first.isWhitespace()) return false
    if (boundary(last) || boundary(first)) return false
    return true
}

private fun splitLong(sentence: String, maxChars: Int): List<String> {
    val chunks = ArrayList<String>()
    val chars = sentence.trim().toCharArray()
    if (chars.isEmpty() || maxChars <= 0) return chunks
    var start = 0
    while (start < chars.size) {
        val end = minOf(start + maxChars, chars.size)
        if (end >= chars.size) {
            val piece = String(chars, start, chars.size - start).trim()
            if (piece.isNotEmpty()) chunks.add(piece)
            break
        }
        var splitPos = end
        var found = false
        var i = end
        while (i > start) {
            val ch = chars[i - 1]
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                splitPos = i - 1
                found = true
                break
            }
            if (boundary(ch)) {
                splitPos = i
                found = true
                break
            }
            i -= 1
        }
        if (!found || splitPos <= start) splitPos = end
        val piece = String(chars, start, splitPos - start).trim()
        if (piece.isNotEmpty()) chunks.add(piece)
        start = splitPos
        while (start < chars.size && (chars[start] == ' ' || chars[start] == '\t')) start += 1
    }
    return chunks
}

private class RssSampler {
    private val sampledPeakKb = AtomicLong(0)
    @Volatile private var running = true
    private var thread: Thread? = null

    fun start() {
        val worker = Thread({
            while (running) {
                note(currentRssKb())
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
            note(currentRssKb())
        }, "rss-sampler")
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

    private fun note(kb: Long) {
        if (kb > 0) sampledPeakKb.updateAndGet { current -> if (kb > current) kb else current }
    }
}

private fun currentRssKb(): Long {
    val os = System.getProperty("os.name").lowercase(Locale.US)
    if (os.contains("linux")) return procStatusKb("VmRSS:")
    if (os.contains("mac")) return psRssKb()
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

private fun psRssKb(): Long {
    val text = capture(listOf("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()))
    return text.toLongOrNull() ?: -1L
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
    if (os.contains("mac")) return capture(listOf("sysctl", "-n", "machdep.cpu.brand_string"))
    return ""
}

private fun hostLabel(): String {
    val os = System.getProperty("os.name").lowercase(Locale.US)
    return if (os.contains("linux")) "Linux VM" else System.getProperty("os.name")
}

private fun processCpuNanos(): Long {
    return try {
        val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        // Call through the exported interface. The HotSpot implementation class
        // is not opened to us, so invoking its own method throws IllegalAccessException.
        val iface = Class.forName("com.sun.management.OperatingSystemMXBean")
        val method = iface.getMethod("getProcessCpuTime")
        method.invoke(bean) as Long
    } catch (_: Throwable) {
        -1L
    }
}

private fun cpuPercent(start: Long, end: Long, wallNs: Long): Double {
    if (start < 0 || end < start || wallNs <= 0) return -1.0
    return (end - start).toDouble() / wallNs.toDouble() * 100.0
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

private fun nanosToMs(ns: Long): Double = ns / 1_000_000.0

private fun fmtMs(ms: Double): String = String.format(Locale.US, "%.0f", ms)

private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun fmtNum(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "null"
    return String.format(Locale.US, "%.4f", value)
}

private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]+"), "_")

private fun log(message: String) {
    System.err.println(message)
    System.err.flush()
}
