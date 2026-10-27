package org.formulagen.wrapper.voice

internal fun createPocketEngine(config: VoiceConfig): PieceEngine {
    throw IllegalStateException(
        "sherpa-onnx jar is missing (backend ${config.backend}). Run spikes/ember-voice/mac/setup.sh, then rebuild.",
    )
}
