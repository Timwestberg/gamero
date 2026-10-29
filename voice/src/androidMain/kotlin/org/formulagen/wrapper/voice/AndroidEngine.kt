package org.formulagen.wrapper.voice

/** sherpa-onnx's Android AAR exposes the same com.k2fsa.sherpa.onnx Kotlin API as the JVM jar. */
actual fun openPieceEngine(config: VoiceConfig): PieceEngine = when (config.backend) {
    VoiceConfig.KOKORO -> createKokoroEngine(config)
    else -> createPocketEngine(config)
}
