package org.formulagen.wrapper.voice

/** Opens the platform engine. iOS honors [VoiceConfig.backend]; other targets use sherpa-onnx. */
expect fun openPieceEngine(config: VoiceConfig): PieceEngine
