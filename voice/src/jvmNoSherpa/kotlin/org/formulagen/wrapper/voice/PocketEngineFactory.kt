package org.formulagen.wrapper.voice

internal const val POCKET_ENGINE_LINKED = false

internal fun createPocketEngine(config: VoiceConfig): PieceEngine = missingSherpa(config)

internal fun createKokoroEngine(config: VoiceConfig): PieceEngine = missingSherpa(config)

internal fun createHearingEngine(modelDir: String): HearingEngine = createMissingHearing(
    "sherpa-onnx jar is missing. Run scripts/fetch-voice.sh, then rebuild.",
)

fun transcribeHearingFile(modelDir: String, wavPath: String): HearingResult? = null

private fun missingSherpa(config: VoiceConfig): PieceEngine {
    throw IllegalStateException(
        "sherpa-onnx jar is missing (backend ${config.backend}). Run scripts/fetch-voice.sh, then rebuild.",
    )
}
