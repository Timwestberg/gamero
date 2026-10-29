package org.formulagen.wrapper.voice

actual fun openPieceEngine(config: VoiceConfig): PieceEngine = when (config.backend) {
    VoiceConfig.KOKORO -> createKokoroEngine(config)
    else -> createPocketEngine(config)
}

/** False when this build had no sherpa-onnx jar, so callers can report voice as unavailable before the first reply. */
fun pocketEngineLinked(): Boolean = POCKET_ENGINE_LINKED

fun openHearingEngine(modelDir: String): HearingEngine = createHearingEngine(modelDir)
