package org.formulagen.wrapper.voice

/** Opens the platform engine. Mac chat selects Kokoro or Pocket from [VoiceConfig.backend]. */
expect fun openPieceEngine(config: VoiceConfig): PieceEngine
