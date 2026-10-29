package org.formulagen.wrapper.voice

import java.io.File

/**
 * JVM proof for the Mac companion path. Logic checks always run.
 * Pass --speak to synthesize one line when the spike model is on disk.
 * Pass --write-pieces FILE to emit the piece list the iOS runner speaks.
 * Pass --check-voice FILE to validate a reference clip, or
 * --prepare-voice IN OUT to write the 24 kHz mono copy.
 */
fun main(args: Array<String>) {
    try {
        runCheck(args)
    } catch (e: VoiceClipException) {
        System.err.println("error: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}

private fun runCheck(args: Array<String>) {
    runVoiceSelfCheck()
    println("voice self-check: ok")
    val writeAt = args.indexOf("--write-pieces")
    if (writeAt >= 0) {
        val path = args.getOrNull(writeAt + 1) ?: error("--write-pieces needs a path")
        val corpus = File(arg(args, "--corpus") ?: "../spikes/ember-voice/bench/corpus/ember-voice-corpus.json")
        File(path).writeText(pieceCatalog(corpus))
        println("pieces: $path")
    }
    val checkVoice = arg(args, "--check-voice")
    if (checkVoice != null) {
        refuseNonCommercialVoice(checkVoice)
        val clip = prepareReferenceClip(File(checkVoice).readBytes())
        println(
            "voice: ${"%.2f".format(clip.seconds)} s, ${clip.sourceRate} Hz, " +
                "${clip.sourceChannels} ch -> ${clip.sampleRate} Hz mono",
        )
    }
    val prepareIn = arg(args, "--prepare-voice")
    if (prepareIn != null) {
        val prepareOut = args.getOrNull(args.indexOf("--prepare-voice") + 2)
            ?: error("--prepare-voice needs an output path")
        refuseNonCommercialVoice(prepareIn)
        val clip = prepareReferenceClip(File(prepareIn).readBytes())
        val dest = File(prepareOut)
        dest.parentFile?.mkdirs()
        dest.writeBytes(referenceClipWavBytes(clip))
        println("prepared: ${dest.path} (${"%.2f".format(clip.seconds)} s, ${clip.sampleRate} Hz mono)")
    }
    if ("--speak" in args) {
        val voiceWav = arg(args, "--voice") ?: "../spikes/ember-voice/mac/models/voices/0a67_enhanced-24k.wav"
        refuseNonCommercialVoice(voiceWav)
        val config = VoiceConfig(
            modelDir = arg(args, "--model") ?: "../spikes/ember-voice/mac/models/sherpa-onnx-pocket-tts-int8-2026-01-26",
            voiceWav = voiceWav,
        )
        val voice = EmberVoice(SherpaPocketEngine(config), config)
        try {
            val result = voice.speak("Done! Your build passed.")
            println(
                "speak: TTFA ${result.ttfaMs.toInt()} ms, audio ${"%.2f".format(result.audioSeconds)} s, " +
                    "events ${result.events.joinToString(",") { it.name }}",
            )
        } finally {
            voice.release()
        }
    }
}

private fun arg(args: Array<String>, name: String): String? {
    val index = args.indexOf(name)
    if (index < 0) return null
    return args.getOrNull(index + 1)
}

private fun pieceCatalog(corpus: File): String {
    val config = VoiceConfig()
    val lines = corpusLines(corpus.readText())
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"silenceBetweenPiecesMs\": ${config.silenceBetweenPiecesMs},\n")
    sb.append("  \"numSteps\": ${config.numSteps},\n")
    sb.append("  \"fallbackNumSteps\": ${config.fallbackNumSteps},\n")
    sb.append("  \"temperature\": ${config.temperature},\n")
    sb.append("  \"seed\": ${config.seed},\n")
    sb.append("  \"decoderFramesPerCallback\": ${config.decoderFramesPerCallback},\n")
    sb.append("  \"longSentenceMaxChars\": ${config.longSentenceMaxChars},\n")
    sb.append("  \"lines\": [\n")
    lines.forEachIndexed { index, (id, text) ->
        val pieces = speechPieces(text, config.longSentenceMaxChars)
        sb.append("    {\"id\": ${json(id)}, \"text\": ${json(text)}, \"pieces\": [")
        pieces.forEachIndexed { pieceIndex, piece ->
            if (pieceIndex > 0) sb.append(", ")
            sb.append("{\"text\": ${json(piece)}, \"max_frames\": ${config.framesFor(piece.length)}}")
        }
        sb.append("]}")
        if (index != lines.lastIndex) sb.append(",")
        sb.append("\n")
    }
    sb.append("  ]\n}\n")
    return sb.toString()
}

private fun corpusLines(raw: String): List<Pair<String, String>> {
    val ids = Regex(""""id"\s*:\s*"([^"]+)"""").findAll(raw).map { it.groupValues[1] }.toList()
    val texts = Regex(""""text"\s*:\s*"([^"]+)"""").findAll(raw).map { it.groupValues[1] }.toList()
    val lineIds = ids.drop(1)
    if (lineIds.size != texts.size) error("corpus has ${lineIds.size} ids and ${texts.size} texts")
    return lineIds.zip(texts)
}

private fun json(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}
