package org.formulagen.wrapper.voice

import java.io.File

/**
 * JVM proof for the Mac companion path. Logic checks always run.
 * Pass --speak to synthesize one line when the spike model is on disk.
 * Pass --write-pieces FILE to emit the piece list the iOS runner speaks.
 */
fun main(args: Array<String>) {
    runVoiceSelfCheck()
    println("voice self-check: ok")
    val writeAt = args.indexOf("--write-pieces")
    if (writeAt >= 0) {
        val path = args.getOrNull(writeAt + 1) ?: error("--write-pieces needs a path")
        val corpus = File(arg(args, "--corpus") ?: "../spikes/ember-voice/bench/corpus/ember-voice-corpus.json")
        File(path).writeText(pieceCatalog(corpus))
        println("pieces: $path")
    }
    if ("--speak" in args) {
        val config = VoiceConfig(
            modelDir = arg(args, "--model") ?: "../spikes/ember-voice/mac/models/sherpa-onnx-pocket-tts-int8-2026-01-26",
            voiceWav = arg(args, "--voice") ?: "../spikes/ember-voice/mac/models/voices/0a67_enhanced-24k.wav",
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
    sb.append("  \"silence_ms\": ${config.silenceMs},\n")
    sb.append("  \"num_steps\": ${config.numSteps},\n")
    sb.append("  \"temperature\": ${config.temperature},\n")
    sb.append("  \"seed\": ${config.seed},\n")
    sb.append("  \"lines\": [\n")
    lines.forEachIndexed { index, (id, text) ->
        val pieces = speechPieces(text, config.firstPieceTargetChars, config.laterPieceMaxChars)
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
