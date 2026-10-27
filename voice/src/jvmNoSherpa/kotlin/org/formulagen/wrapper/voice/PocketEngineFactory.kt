package org.formulagen.wrapper.voice

internal const val POCKET_ENGINE_LINKED = false

internal fun createPocketEngine(config: VoiceConfig): PieceEngine {
    throw IllegalStateException(
        "sherpa-onnx jar is missing (backend ${config.backend}). Run scripts/fetch-voice.sh, then rebuild.",
    )
}
