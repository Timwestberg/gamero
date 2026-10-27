package org.formulagen.wrapper.voice

actual fun openPieceEngine(config: VoiceConfig): PieceEngine = createPocketEngine(config)
