package org.formulagen.wrapper.voice

internal fun createPocketEngine(config: VoiceConfig): PieceEngine {
    throw IllegalStateException(
        "sherpa-onnx AAR is missing (backend ${config.backend}). Put it at voice/libs/sherpa-onnx.aar and rebuild with -Pgamero.mobileTargets=true.",
    )
}
