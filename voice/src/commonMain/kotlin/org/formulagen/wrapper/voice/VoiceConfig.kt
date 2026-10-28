package org.formulagen.wrapper.voice

/**
 * Local names for ember-voice.v2 (server-platform #192). v1 names that only
 * cut inside a sentence are gone. Sherpa still receives `chunk_size` and
 * `max_frames`; those are this config's decoder frame count and ceiling.
 * [fallbackNumSteps] is what to ship if a Mac cannot stay in real time at
 * [numSteps]. This module does not switch steps by itself.
 *
 * Mac chat uses [KOKORO]. [SHERPA] stays for a later "speak in my voice" clip.
 * Kokoro's frame ceiling, flow steps, and reference clip are unused.
 */
data class VoiceConfig(
    val numSteps: Int = 5,
    val fallbackNumSteps: Int = 3,
    val temperature: Double = 0.3,
    val seed: Int = 1,
    val numThreads: Int = 2,
    val decoderFramesPerCallback: Int = 2,
    val split: String = SENTENCE,
    val longSentenceMaxChars: Int = 400,
    val longSentenceSplit: String = WORD_BOUNDARY,
    val silenceBetweenPiecesMs: Int = 0,
    /**
     * Silence inserted between sentences after each sentence's own leading and
     * trailing silence is trimmed. `-1` uses [KOKORO_SENTENCE_GAP_MS] for Kokoro
     * and no inserted gap for Pocket, which still crossfades.
     */
    val sentenceGapMs: Int = -1,
    val silenceTrimThreshold: Double = 0.02,
    val crossfadeMs: Int = 10,
    val crossfadeEdgeThreshold: Double = 0.02,
    val startAfterMs: Int = 200,
    val frameIntercept: Double = 4.0,
    val frameSlope: Double = 0.8,
    val frameFloor: Int = 12,
    val shortPieceChars: Int = 24,
    val shortPieceMinFrames: Int = 36,
    val maxDurationRatio: Double = 2.0,
    val raisedCapFactor: Int = 2,
    val raisedCapMaxFrames: Int = 500,
    val minExpectedAudioS: Double = 0.8,
    val charsPerSecond: Double = 15.0,
    val minSpectralCosine: Double = 0.75,
    val maxF0RangeHz: Double = 15.0,
    val f0ConversationTurns: Int = 3,
    val maxZeroRunMs: Int = 5,
    val ttfaMarginMs: Int = 200,
    val passTtfaMs: Double = 500.0,
    val passRtf: Double = 1.5,
    val backend: String = SHERPA,
    val modelDir: String = "",
    val voiceWav: String = "",
    val kokoroModelDir: String = "",
    val kokoroVoiceId: String = "",
    val kokoroVoiceFile: String = "",
    val kokoroVoiceFromEnv: Boolean = false,
) {
    val version: String
        get() {
            val voiceId = kokoroVoiceId.ifEmpty { kokoroCatalog().defaultId }
            val speed = kokoroVoice(voiceId)?.speedText ?: "none"
            return "v2 steps=$numSteps,fallback=$fallbackNumSteps,temp=$temperature,seed=$seed," +
                "threads=$numThreads,decoderFrames=$decoderFramesPerCallback,split=$split," +
                "long=$longSentenceMaxChars/$longSentenceSplit,silence=$silenceBetweenPiecesMs," +
                "sentenceGap=$resolvedSentenceGapMs," +
                "fade=$crossfadeMs@$crossfadeEdgeThreshold,start=$startAfterMs," +
                "frames=$frameFloor+$frameIntercept+$frameSlope,short=$shortPieceChars/$shortPieceMinFrames," +
                "ratio=$maxDurationRatio,cap=$raisedCapFactor/$raisedCapMaxFrames,backend=$backend," +
                "kokoro=$voiceId@$speed"
        }

    /** The gap a listener hears between sentences, in milliseconds. */
    val resolvedSentenceGapMs: Int
        get() = when {
            sentenceGapMs >= 0 -> sentenceGapMs
            backend == KOKORO -> KOKORO_SENTENCE_GAP_MS
            else -> 0
        }

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
        const val KOKORO = "kokoro"
        const val FLUID_AUDIO = "fluid-audio"
        const val SENTENCE = "sentence"
        const val WORD_BOUNDARY = "word-boundary"
        /** Phone-call pause after Kokoro's own sentence padding is removed. */
        const val KOKORO_SENTENCE_GAP_MS = 160
    }
}
