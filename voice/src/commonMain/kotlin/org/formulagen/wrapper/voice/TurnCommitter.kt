package org.formulagen.wrapper.voice

import kotlin.math.sqrt

/** Why a hands-free turn was committed. */
enum class EndpointReason { VAD_SILENCE, TEXT_STABLE, CAP }

data class EndpointEvent(
    val decision: EndpointDecision,
    val reason: EndpointReason? = null,
    /** This frame counted as the user's speech, after the ambient floor. */
    val speech: Boolean = false,
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

    /** Keep the noise floor. Clear the clocks for the next utterance. */
    fun resetUtterance() {
        speechMs = 0
        trailingMs = 0
        stableMs = 0
        lastText = ""
        heard = false
        textSeen = false
    }

    fun belowFloor(rms: Float): Boolean = floorReady && rms <= floor + floorMargin

    fun observe(vadVoiced: Boolean, frameMs: Int, rms: Float, text: String): EndpointEvent {
        if (frameMs <= 0) return EndpointEvent(EndpointDecision.LISTEN)
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
            if (speechMs >= maxSpeechMs) return send(EndpointReason.CAP)
            if (textSeen && stableMs >= textStableMs && speechMs >= minSpeechMs) return send(EndpointReason.TEXT_STABLE)
            return EndpointEvent(EndpointDecision.LISTEN, speech = true)
        }
        if (!heard && !textSeen) return EndpointEvent(EndpointDecision.LISTEN)
        trailingMs += frameMs
        if (trailingMs >= silenceMs && (speechMs >= minSpeechMs || textSeen)) return send(EndpointReason.VAD_SILENCE)
        if (textSeen && stableMs >= textStableMs && (speechMs >= minSpeechMs || heard)) return send(EndpointReason.TEXT_STABLE)
        if (trailingMs >= silenceMs) {
            resetUtterance()
            return EndpointEvent(EndpointDecision.DROP)
        }
        return EndpointEvent(EndpointDecision.LISTEN)
    }

    private fun send(reason: EndpointReason): EndpointEvent {
        resetUtterance()
        return EndpointEvent(EndpointDecision.SEND, reason)
    }
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
