package org.formulagen.wrapper.voice

/** Lead-ins Ember can play while a reply is still unspoken. One per hearing turn. */
val FILLER_LINES: List<String> = listOf("Hmm,", "Let me think,", "Okay, so")

const val FILLER_THRESHOLD_MS = 450

data class FillerSettings(val enabled: Boolean = true, val thresholdMs: Int = FILLER_THRESHOLD_MS)

data class FillerPcm(val pcm: ByteArray, val sampleRate: Int)

data class FillerCue(val line: String, val waitedMs: Long)

/**
 * [HOVEL_VOICE_FILLERS] unset means on. `0`, `off`, and `false` turn them off.
 * [HOVEL_VOICE_FILLER_MS] is the wait after the endpoint, clamped to 0..10000.
 */
fun readFillerSettings(environment: Map<String, String>): FillerSettings {
    val raw = environment["HOVEL_VOICE_FILLERS"]?.trim()?.lowercase()
    val enabled = raw != "0" && raw != "off" && raw != "false"
    val ms = environment["HOVEL_VOICE_FILLER_MS"]?.trim()?.toIntOrNull()?.coerceIn(0, 10_000) ?: FILLER_THRESHOLD_MS
    return FillerSettings(enabled, ms)
}

/** Walks [FILLER_LINES] in order. With more than one line, the same line never plays twice in a row. */
class FillerRotation(private val lines: List<String> = FILLER_LINES) {
    private var index = 0

    fun next(): String {
        check(lines.isNotEmpty()) { "filler lines are empty" }
        val line = lines[index % lines.size]
        index += 1
        return line
    }
}

/**
 * One hearing turn's filler. [begin] at the release, [arm] after the send if the
 * reply has not already started, [poll] once the threshold has elapsed.
 * [replyArrived] and [cancel] both keep a later [poll] from starting a clip.
 */
class FillerTurn(
    private val settings: FillerSettings = FillerSettings(),
    private val rotation: FillerRotation = FillerRotation(),
) {
    private var replyStarted = false
    private var played = false
    var playing: Boolean = false
        private set
    private var endpointNs: Long? = null

    fun begin() {
        replyStarted = false
        played = false
        playing = false
        endpointNs = null
    }

    fun replyArrived() {
        replyStarted = true
        playing = false
    }

    fun cancel() {
        replyStarted = true
        playing = false
    }

    /** Remember the endpoint. A reply that already started is left alone. */
    fun arm(nowNs: Long): Boolean {
        if (!settings.enabled || replyStarted) return false
        endpointNs = nowNs
        return true
    }

    fun poll(nowNs: Long): FillerCue? {
        val start = endpointNs ?: return null
        if (!settings.enabled || played || replyStarted) return null
        val waited = (nowNs - start) / 1_000_000
        if (waited < settings.thresholdMs) return null
        played = true
        playing = true
        return FillerCue(rotation.next(), waited)
    }

    fun clipFinished() {
        playing = false
    }
}
