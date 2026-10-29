package org.formulagen.wrapper.voice

internal const val POCKET_ENGINE_LINKED = false

internal fun createPocketEngine(config: VoiceConfig): PieceEngine = missingSherpa(config)

internal fun createKokoroEngine(config: VoiceConfig): PieceEngine = missingSherpa(config)

internal fun createHearingEngine(modelDir: String): HearingEngine = createMissingHearing(
    "sherpa-onnx AAR is missing. Put it at voice/libs/sherpa-onnx.aar and rebuild with -Pgamero.mobileTargets=true.",
)

private fun missingSherpa(config: VoiceConfig): PieceEngine {
    throw IllegalStateException(
        "sherpa-onnx AAR is missing (backend ${config.backend}). Put it at voice/libs/sherpa-onnx.aar and rebuild with -Pgamero.mobileTargets=true.",
    )
}
