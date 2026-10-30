package org.formulagen.wrapper.voice

/**
 * The voice conversation web, iOS, and Android share. Playback stays on the host-bridge
 * voice field. This file does not open a microphone, write a socket, or name a token.
 *
 * ember-voice.v2 stays the Kokoro recipe (engine, voice id, speed, lexicon checksum).
 * Hearing is a separate contract: states, actions, endpoint and discard reasons, barge-in,
 * and the hearing-model checksums. The two do not define the same knob.
 */
object HearingStates {
    const val OFF = "off"
    const val REQUESTING = "requesting"
    const val LISTENING = "listening"
    const val HEARING = "hearing"
    const val THINKING = "thinking"
    const val MUTED = "muted"
    const val UNAVAILABLE = "unavailable"
    val ALL = listOf(OFF, REQUESTING, LISTENING, HEARING, THINKING, MUTED, UNAVAILABLE)
}

object HearingActions {
    const val PRESS = "press"
    const val RELEASE = "release"
    const val HANDS_FREE = "handsFree"
    const val MUTE = "mute"
    const val UNMUTE = "unmute"
    const val OFF = "off"
    val ALL = listOf(PRESS, RELEASE, HANDS_FREE, MUTE, UNMUTE, OFF)
}

/** Why a heard turn was thrown away. The wire name is what the hearing log prints. */
enum class DiscardReason(val wire: String) {
    NO_VAD_SPEECH("no_vad_speech"),
    SELF_ECHO("self_echo"),
    TOO_SHORT("too_short"),
}

/** Stage names and the caps a test can fail. Provider time has no cap. */
object LatencyMetric {
    const val ENDPOINT_TO_SEND_MS = "endpoint_to_send_ms"
    const val SEND_TO_FIRST_TOKEN_MS = "send_to_first_token_ms"
    const val FIRST_TOKEN_TO_FIRST_AUDIO_MS = "first_token_to_first_audio_ms"
    const val FLUSH_MS = "flush_ms"
    const val ENDPOINT_TO_SEND_CAP_MS = 80L
    const val ENQUEUE_TO_SPEAKER_CAP_MS = 280L
    const val FLUSH_CAP_MS = 50L
}

/**
 * Full-duplex barge-in. Speech has to stay voiced for [sustainMs] at or above [minProbability].
 * While Ember is audible, and for [hotAfterStopMs] after she stops, a one- or two-word turn
 * without that evidence is [DiscardReason.TOO_SHORT], and a turn that shares
 * [echoOverlapPercent] of its words with her last line is [DiscardReason.SELF_ECHO].
 * The device flush is capped at [flushCapMs].
 */
data class BargeInPolicy(
    val sustainMs: Int = HEARING_BARGE_SUSTAIN_MS,
    val minProbability: Float = 0.5f,
    val echoOverlapPercent: Int = 60,
    val shortWordLimit: Int = 2,
    val hotAfterStopMs: Int = 500,
    val flushCapMs: Int = LatencyMetric.FLUSH_CAP_MS.toInt(),
)

data class ConversationVoice(val id: String, val name: String, val speed: String)

data class ConversationChecksums(
    val modelOnnxSha256: String,
    val tokensSha256: String,
    val lexiconSha256: String,
    val upstreamVoicesSha256: String,
    val derivedVoicesSha256: String,
    val sileroVadSha256: String,
    val zipformerArchiveSha256: String,
    val zipformerEncoderSha256: String,
    val zipformerDecoderSha256: String,
    val zipformerJoinerSha256: String,
    val zipformerTokensSha256: String,
)

/** Silero v5 (MIT) and the Apache-2.0 streaming Zipformer pins from scripts/fetch-voice.sh. */
object HearingModelPins {
    const val SILERO_VAD_SHA256 = "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396"
    const val ZIPFORMER_ARCHIVE_SHA256 = "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282"
    const val ENCODER_SHA256 = "5022b2eca5b19d1bc104fcf33e26bc32604b7df553cd2e1f62e31dc7b05e9c87"
    const val DECODER_SHA256 = "85914f840b71110487ece7a30ebd0bd4b8fb5c2623215bba3aa5ab4c138fc591"
    const val JOINER_SHA256 = "abd5e30f3f16fc510605c6029dba33f10e4386bd75c5bdc30cf94076864db10d"
    const val TOKENS_SHA256 = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"
}

object VoiceConversation {
    const val ENGINE = VoiceConfig.KOKORO
    const val SENTENCE_GAP_MS = VoiceConfig.KOKORO_SENTENCE_GAP_MS
    const val FILLERS_DEFAULT = false
    const val CATALOG_PRODUCT = "Gamero"
    const val LAUNCHER_PRODUCT = "Hovel"
    /** Shown before the first capture. The recording itself is not saved. */
    const val PERMISSION_COPY = "Ember hears you on this Mac to turn what you say into a chat message. The recording is not saved."
    /** A browser with no host. The same sentence chat already shows. It does not ask for a microphone. */
    const val NO_HOST_NOTICE = "Open Hovel on your Mac to connect…"
    const val MIC_SETTINGS_NOTICE = "Turn the microphone on for Gamero Adventures in System Settings."

    fun voices(): List<ConversationVoice> = kokoroVoices().map { ConversationVoice(it.id, it.name, it.speedText) }

    fun defaultVoiceId(): String = kokoroCatalog().defaultId

    fun checksums(): ConversationChecksums {
        val catalog = kokoroCatalog()
        return ConversationChecksums(
            modelOnnxSha256 = catalog.modelSha256,
            tokensSha256 = catalog.tokensSha256,
            lexiconSha256 = catalog.lexiconSha256,
            upstreamVoicesSha256 = catalog.upstreamVoicesSha256,
            derivedVoicesSha256 = catalog.derivedVoicesSha256,
            sileroVadSha256 = HearingModelPins.SILERO_VAD_SHA256,
            zipformerArchiveSha256 = HearingModelPins.ZIPFORMER_ARCHIVE_SHA256,
            zipformerEncoderSha256 = HearingModelPins.ENCODER_SHA256,
            zipformerDecoderSha256 = HearingModelPins.DECODER_SHA256,
            zipformerJoinerSha256 = HearingModelPins.JOINER_SHA256,
            zipformerTokensSha256 = HearingModelPins.TOKENS_SHA256,
        )
    }
}

/** The Gamero Adventures catalog owns the conversation controls. Ember Tower only launches the game. */
fun catalogOwnsConversationControls(product: String): Boolean = product == VoiceConversation.CATALOG_PRODUCT

fun duplexStatusLabel(mode: HearingDuplex, reason: String): String = when (mode) {
    HearingDuplex.FULL -> "Full duplex"
    HearingDuplex.HALF -> "Half duplex · $reason"
}

/** The next hearing state for one page action. A missing host never asks for a microphone. */
data class HearingStep(val state: String, val requestMicrophone: Boolean)

fun reduceHearing(
    state: String,
    action: String,
    hostPresent: Boolean,
    permissionGranted: Boolean,
): HearingStep {
    if (!hostPresent) return HearingStep(HearingStates.UNAVAILABLE, requestMicrophone = false)
    val next = when (action) {
        HearingActions.OFF, HearingActions.RELEASE -> HearingStates.OFF
        HearingActions.MUTE -> if (state == HearingStates.OFF || state == HearingStates.UNAVAILABLE || state == HearingStates.REQUESTING) state else HearingStates.MUTED
        HearingActions.UNMUTE -> if (state == HearingStates.MUTED) HearingStates.LISTENING else state
        HearingActions.PRESS, HearingActions.HANDS_FREE -> if (permissionGranted) HearingStates.LISTENING else HearingStates.REQUESTING
        else -> state
    }
    val ask = action == HearingActions.PRESS || action == HearingActions.HANDS_FREE
    return HearingStep(next, requestMicrophone = ask && !permissionGranted)
}

/**
 * The hearing field the catalog reads while a session is up. Speaking stays on voice state.
 * [phase] is `off`, `listening`, `thinking`, or `speaking`.
 */
fun catalogHearingState(
    hostPresent: Boolean,
    armed: Boolean,
    muted: Boolean,
    phase: String,
    partial: String,
    unavailable: Boolean,
): String {
    if (!hostPresent || unavailable) return HearingStates.UNAVAILABLE
    if (!armed) return HearingStates.OFF
    if (muted) return HearingStates.MUTED
    return when (phase) {
        "thinking" -> HearingStates.THINKING
        "listening", "speaking" -> if (partial.isNotBlank()) HearingStates.HEARING else HearingStates.LISTENING
        else -> HearingStates.OFF
    }
}

/** A browser with no host. [HearingStep.requestMicrophone] is false. */
fun hearingWithoutHost(): HearingStep = reduceHearing(
    HearingStates.OFF,
    HearingActions.PRESS,
    hostPresent = false,
    permissionGranted = false,
)
