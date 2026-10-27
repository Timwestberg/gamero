package org.formulagen.wrapper.voice

/**
 * Local voice settings. Field names here are the module's. The shared
 * event-contracts names are synced by hand until that repo is readable here.
 * The numbers match the Linux VM confirmation on the quick-wins branch.
 */
data class VoiceConfig(
    val numSteps: Int = 1,
    val temperature: Double = 0.3,
    val seed: Int = 1,
    val numThreads: Int = 2,
    val chunkSize: Int = 2,
    val firstPieceTargetChars: Int = 40,
    val laterPieceMaxChars: Int = 140,
    val frameIntercept: Double = 4.0,
    val frameSlope: Double = 0.8,
    val frameFloor: Int = 12,
    val shortPieceChars: Int = 24,
    val shortPieceMinFrames: Int = 36,
    val maxDurationRatio: Double = 2.0,
    val silenceMs: Int = 40,
    val passTtfaMs: Double = 500.0,
    val passRtf: Double = 1.5,
    val backend: String = SHERPA,
    val modelDir: String = "",
    val voiceWav: String = "",
) {
    val version: String
        get() = "steps=$numSteps,temp=$temperature,seed=$seed,threads=$numThreads,chunk=$chunkSize," +
            "first=$firstPieceTargetChars,later=$laterPieceMaxChars," +
            "frames=$frameFloor+$frameIntercept+$frameSlope,short=$shortPieceChars/$shortPieceMinFrames," +
            "ratio=$maxDurationRatio,silence=$silenceMs,backend=$backend"

    fun framesFor(chars: Int): Int = frameBudget(
        chars,
        frameIntercept,
        frameSlope,
        frameFloor,
        shortPieceChars,
        shortPieceMinFrames,
    )

    companion object {
        const val SHERPA = "sherpa"
        const val FLUID_AUDIO = "fluid-audio"
    }
}
