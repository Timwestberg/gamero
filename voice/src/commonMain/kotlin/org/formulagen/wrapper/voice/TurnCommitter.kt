package org.formulagen.wrapper.voice

import kotlin.math.sqrt

/** Why a hands-free turn was committed. */
enum class EndpointReason(val wire: String) {
    VAD_SILENCE("vad_silence"),
    TEXT_STABLE("text_stable"),
    CAP("cap"),
}

data class EndpointEvent(
    val decision: EndpointDecision,
    val reason: EndpointReason? = null,
    /** This frame counted as the user's speech, after the ambient floor. */
    val speech: Boolean = false,
    val vadSpeechMs: Int = 0,
    val maxProb: Float = 0f,
    /** Set when the partial is thrown away instead of sent. */
    val discard: String? = null,
)

/**
 * Sends a turn when the room has been quiet for [silenceMs], when the partial text has
 * not changed for [textStableMs] after nonempty speech, or after [maxSpeechMs] of continuous speech.
 * The first [calibrateMs] measure the room. Frames at or below that floor plus [floorMargin] are silence
 * even when the VAD still calls them speech.
 */
class TurnCommitter(
    val silenceMs: Int = HEARING_ENDPOINT_SILENCE_MS,
    val minSpeechMs: Int = HEARING_MIN_SPEECH_MS,
    val textStableMs: Int = HEARING_ENDPOINT_SILENCE_MS,
    val calibrateMs: Int = 300,
    val floorMargin: Float = 0.01f,
    val maxSpeechMs: Int = 15_000,
) {
    private val floorSamples = ArrayList<Float>()
    private var calibratedMs = 0
    private var floor = 0f
    var floorReady: Boolean = false
        private set
    private var speechMs = 0
    private var trailingMs = 0
    private var stableMs = 0
    private var lastText = ""
    private var heard = false
    private var textSeen = false
    private var maxProb = 0f

    /** Keep the noise floor. Clear the clocks for the next utterance. */
    fun resetUtterance() {
        speechMs = 0
        trailingMs = 0
        stableMs = 0
        lastText = ""
        heard = false
        textSeen = false
        maxProb = 0f
    }

    fun belowFloor(rms: Float): Boolean = floorReady && rms <= floor + floorMargin

    fun observe(
        vadVoiced: Boolean,
        frameMs: Int,
        rms: Float,
        text: String,
        vadProb: Float = if (vadVoiced) 1f else 0f,
        emberHot: Boolean = false,
        emberText: String = "",
    ): EndpointEvent {
        if (frameMs <= 0) return EndpointEvent(EndpointDecision.LISTEN)
        if (vadProb > maxProb) maxProb = vadProb
        if (!floorReady) {
            if (!vadVoiced) floorSamples.add(rms)
            calibratedMs += frameMs
            if (calibratedMs >= calibrateMs) {
                floor = if (floorSamples.isEmpty()) 0f else median(floorSamples)
                floorReady = true
            }
        }
        val spoken = text.trim()
        if (spoken.isNotEmpty() && spoken != lastText) {
            lastText = spoken
            stableMs = 0
            textSeen = true
        } else if (textSeen && spoken.isNotEmpty()) {
            stableMs += frameMs
        }
        val speech = vadVoiced && !belowFloor(rms)
        if (speech) {
            heard = true
            speechMs += frameMs
            trailingMs = 0
            if (speechMs >= maxSpeechMs) return finish(EndpointReason.CAP, spoken, emberHot, emberText)
            if (textSeen && stableMs >= textStableMs) return finish(EndpointReason.TEXT_STABLE, spoken, emberHot, emberText)
            return EndpointEvent(EndpointDecision.LISTEN, speech = true)
        }
        if (!heard && !textSeen) return EndpointEvent(EndpointDecision.LISTEN)
        trailingMs += frameMs
        if (textSeen && stableMs >= textStableMs) return finish(EndpointReason.TEXT_STABLE, spoken, emberHot, emberText)
        if (trailingMs >= silenceMs) return finish(EndpointReason.VAD_SILENCE, spoken, emberHot, emberText)
        return EndpointEvent(EndpointDecision.LISTEN)
    }

    private fun finish(reason: EndpointReason, text: String, emberHot: Boolean, emberText: String): EndpointEvent {
        val discard = screenHeardTurn(text, speechMs, maxProb, emberHot, emberText)
        val event = if (discard == null && text.isNotBlank() && speechMs >= minSpeechMs) {
            EndpointEvent(EndpointDecision.SEND, reason, speech = true, vadSpeechMs = speechMs, maxProb = maxProb)
        } else {
            EndpointEvent(
                EndpointDecision.DROP,
                discard = discard ?: "no_vad_speech",
                vadSpeechMs = speechMs,
                maxProb = maxProb,
            )
        }
        resetUtterance()
        return event
    }
}

/** Null means the turn may be sent. Otherwise the discard reason. */
fun screenHeardTurn(
    text: String,
    vadSpeechMs: Int,
    maxProb: Float,
    emberHot: Boolean,
    emberText: String,
): String? {
    val words = speechTokens(text)
    val barge = BargeInPolicy()
    if (emberHot) {
        val strong = vadSpeechMs >= barge.sustainMs && maxProb >= barge.minProbability
        if (words.size in 1..barge.shortWordLimit && !strong) return DiscardReason.TOO_SHORT.wire
        if (!strong) return DiscardReason.NO_VAD_SPEECH.wire
        if (mostlyEcho(words, emberText, barge.echoOverlapPercent)) return DiscardReason.SELF_ECHO.wire
        return null
    }
    if (vadSpeechMs < HEARING_MIN_SPEECH_MS) return DiscardReason.NO_VAD_SPEECH.wire
    return null
}

internal fun speechTokens(text: String): List<String> =
    text.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }

private fun mostlyEcho(words: List<String>, emberText: String, overlapPercent: Int): Boolean {
    if (words.isEmpty()) return false
    val ember = speechTokens(emberText).toSet()
    if (ember.isEmpty()) return false
    val overlap = words.count { it in ember }
    return overlap * 100 / words.size >= overlapPercent
}

fun chunkRms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (sample in samples) sum += sample.toDouble() * sample.toDouble()
    return sqrt(sum / samples.size).toFloat()
}

private fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}
