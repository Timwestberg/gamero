package org.formulagen.wrapper.voice

import kotlin.math.log10

/** How the microphone behaves while Ember is speaking. */
enum class HearingDuplex { FULL, HALF }

/** What the launch asked for. [AUTO] uses the graph when it is available and the self-check has not failed. */
enum class DuplexRequest { AUTO, FULL, HALF }

/** The last echo self-check. Missing is not a failure. */
enum class SelfCheck { PASS, FAIL, ABSENT }

data class DuplexChoice(val mode: HearingDuplex, val reason: String)

/** Sustained speech required before a full-duplex barge-in. Inside the 200–300 ms guard. */
const val HEARING_BARGE_SUSTAIN_MS = 250

/** Quiet this long clears a short cough so it does not add up to a barge-in. */
const val HEARING_BARGE_GAP_MS = 80

/**
 * Quiet that clears a full-duplex barge while someone is actually talking.
 * One 100 ms mic chunk below the threshold must not wipe a run that is about to reach 250 ms.
 */
const val HEARING_BARGE_TALK_GAP_MS = 240

/** Played by the echo self-test. The recognizer must not return these words. */
const val ECHO_SELF_TEST_PHRASE = "Ember checking the microphone."

/** Residual mic energy this far below the clip counts as cancelled echo. */
const val ECHO_ERLE_PASS_DB = 12.0

fun readDuplexRequest(environment: Map<String, String>): DuplexRequest =
    when (environment["HOVEL_HEARING_DUPLEX"]?.trim()?.lowercase()) {
        "half", "0", "off" -> DuplexRequest.HALF
        "full", "1", "on" -> DuplexRequest.FULL
        else -> DuplexRequest.AUTO
    }

fun hearingBargeMs(environment: Map<String, String>): Int =
    environment["HOVEL_HEARING_BARGE_MS"]?.toIntOrNull()?.coerceIn(200, 300) ?: HEARING_BARGE_SUSTAIN_MS

/**
 * Full duplex only when voice processing can start and the self-check has not failed.
 * A forced half setting, a missing graph, or a failed check all keep the microphone closed while Ember speaks.
 */
fun chooseDuplex(request: DuplexRequest, aecAvailable: Boolean, selfCheck: SelfCheck): DuplexChoice = when {
    request == DuplexRequest.HALF -> DuplexChoice(HearingDuplex.HALF, "forced")
    !aecAvailable -> DuplexChoice(HearingDuplex.HALF, "aec-unavailable")
    selfCheck == SelfCheck.FAIL -> DuplexChoice(HearingDuplex.HALF, "self-check-failed")
    selfCheck == SelfCheck.PASS -> DuplexChoice(HearingDuplex.FULL, "self-check-passed")
    else -> DuplexChoice(HearingDuplex.FULL, "aec-unverified")
}

fun readSelfCheck(text: String?): SelfCheck = when (text?.lineSequence()?.firstOrNull()?.trim()?.lowercase()) {
    "pass" -> SelfCheck.PASS
    "fail" -> SelfCheck.FAIL
    else -> SelfCheck.ABSENT
}

/**
 * Speech has to stay above the VAD threshold for [sustainMs] without an [gapResetMs] quiet gap.
 * A cough or a short residual echo does not fire. [frame] returns true once, then waits for a new run.
 */
class BargeInGuard(
    val sustainMs: Int = HEARING_BARGE_SUSTAIN_MS,
    val gapResetMs: Int = HEARING_BARGE_GAP_MS,
) {
    private var runMs = 0
    private var gapMs = 0
    private var armed = true

    fun reset() {
        runMs = 0
        gapMs = 0
        armed = true
    }

    fun frame(voiced: Boolean, frameMs: Int): Boolean {
        if (frameMs <= 0 || !armed) return false
        if (voiced) {
            gapMs = 0
            runMs += frameMs
            if (runMs >= sustainMs) {
                armed = false
                runMs = 0
                return true
            }
            return false
        }
        gapMs += frameMs
        if (gapMs >= gapResetMs) {
            runMs = 0
            gapMs = 0
        }
        return false
    }
}

/** 10 log10 of played energy over the energy the recognizer still hears. Higher means less echo. */
fun erleDb(reference: FloatArray, recorded: FloatArray): Double {
    val far = energy(reference)
    val near = energy(recorded)
    if (far <= 1e-12) return 0.0
    if (near <= 1e-12) return 80.0
    return (10.0 * log10(far / near)).coerceIn(-20.0, 80.0)
}

fun leaksPhrase(transcript: String, phrase: String = ECHO_SELF_TEST_PHRASE): Boolean {
    val heard = transcript.lowercase()
    return phrase.split(Regex("\\s+"))
        .map { it.trim('.', ',', ' ').lowercase() }
        .filter { it.length >= 4 }
        .any { it.isNotEmpty() && heard.contains(it) }
}

data class EchoVerdict(val erleDb: Double, val leaked: Boolean, val passed: Boolean)

fun echoVerdict(
    reference: FloatArray,
    recorded: FloatArray,
    transcript: String,
    phrase: String = ECHO_SELF_TEST_PHRASE,
    passDb: Double = ECHO_ERLE_PASS_DB,
): EchoVerdict {
    val erle = erleDb(reference, recorded)
    val leaked = leaksPhrase(transcript, phrase)
    return EchoVerdict(erle, leaked, erle >= passDb && !leaked)
}

/** Remove a copy of [reference] from [mic]. The session test uses this as the simulated canceller. */
fun cancelEcho(mic: FloatArray, reference: FloatArray, gain: Float): FloatArray {
    val n = minOf(mic.size, reference.size)
    return FloatArray(mic.size) { index ->
        if (index < n) mic[index] - gain * reference[index] else mic[index]
    }
}

private fun energy(samples: FloatArray): Double {
    var sum = 0.0
    for (sample in samples) sum += sample.toDouble() * sample.toDouble()
    return sum
}
