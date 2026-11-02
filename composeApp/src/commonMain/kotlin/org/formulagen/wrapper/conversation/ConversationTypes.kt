package org.formulagen.wrapper.conversation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules

/*
 * What a conversation is made of on every platform: who a role runs on, a working turn's activity, and the Mac voice's
 * states. The brain's types (`bridge/BrainTypes.kt`) and the voice bridge (`conversationVoice.*`) carry them; the Mac's
 * voice is `conversation.voice`, and the runtimes a turn runs on are `runtimes`.
 */

/**
 * What a brain role runs on: a runtime and one of its models, or [SharedRules.RUNTIME_DEFAULT_MODEL]. Its provider is
 * [RuntimeId.providerId], so it is not sent.
 */
@Serializable
data class TeamMember(val runtimeId: RuntimeId, val modelId: String)

/**
 * How a role answers: [TALK] is a talk-only, locked-down turn; [WORK] is the runtime's own agent with the person's full
 * setup, in the project's working folder. Native code confirms the start of work and asks every approval in its own dialog.
 */
@Serializable
enum class MemberMode {
    @SerialName("talk") TALK,
    @SerialName("work") WORK,
}

/** Bounds of what a runtime's reply and the Mac's voice show. */
object ChatLimits {
    /** The longest reply a runtime returns: one message (`COMPANION_LIMITS.message`). */
    const val REPLY = CompanionLimits.MESSAGE
    /** One short sentence about the voice (`CONVERSATION_VOICE_LIMITS.notice`). */
    const val NOTICE = 300
}

/** Bounds of a working reply's activity: at most [STEPS] steps, each a [LABEL] and a shortened [DETAIL]. */
object ActivityLimits { // mirrors ACTIVITY_LIMITS
    const val DETAIL = 400
    const val LABEL = 240
    const val STEPS = 50
}

/**
 * One thing a working member did: `read`, `search` and `edit` files in the project folder, a
 * `command` it ran, a `connector` (a tool of the person's MCP servers or connectors), a `web`
 * search or fetch, or `other`.
 */
@Serializable
enum class ActivityKind {
    @SerialName("read") READ,
    @SerialName("search") SEARCH,
    @SerialName("edit") EDIT,
    @SerialName("command") COMMAND,
    @SerialName("connector") CONNECTOR,
    @SerialName("web") WEB,
    @SerialName("other") OTHER,
}

/** `denied` when the person or their own settings said no; `failed` when the step ran and failed. */
@Serializable
enum class ActivityStatus {
    @SerialName("done") DONE,
    @SerialName("denied") DENIED,
    @SerialName("failed") FAILED,
}

/**
 * [label] is a path relative to the project folder, a command, or `server · tool`; [detail] the
 * shortened output or result.
 */
@Serializable
data class ActivityStep(val kind: ActivityKind, val label: String, val status: ActivityStatus, val detail: String? = null) {
    init { require(label.length <= ActivityLimits.LABEL && (detail?.length ?: 0) <= ActivityLimits.DETAIL) }
}

/** The steps of one working reply, in the order they ran; [truncated] when more than [ActivityLimits.STEPS] ran. */
@Serializable
data class Activity(val steps: List<ActivityStep>, val truncated: Boolean) {
    init { require(steps.size <= ActivityLimits.STEPS) }
}

@Serializable
enum class VoiceState {
    @SerialName("idle") IDLE,
    @SerialName("preparing") PREPARING,
    @SerialName("speaking") SPEAKING,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("error") ERROR,
}

@Serializable
data class ChatVoice(val state: VoiceState = VoiceState.IDLE, val notice: String? = null)

/** Talk's microphone, separate from [VoiceState], in `ember-hearing.v1`'s states. It is not audio. */
@Serializable
enum class HearingState {
    @SerialName("off") OFF,
    @SerialName("requesting") REQUESTING,
    @SerialName("listening") LISTENING,
    @SerialName("hearing") HEARING,
    @SerialName("thinking") THINKING,
    @SerialName("muted") MUTED,
    @SerialName("unavailable") UNAVAILABLE,
}

/** What a page asks of Talk, in `ember-hearing.v1`'s actions. */
@Serializable
enum class HearingAction {
    @SerialName("press") PRESS,
    @SerialName("release") RELEASE,
    @SerialName("handsFree") HANDS_FREE,
    @SerialName("mute") MUTE,
    @SerialName("unmute") UNMUTE,
    @SerialName("off") OFF,
}

/** Dictation, separate from [HearingState]: the microphone fills the page's draft and sends nothing. */
@Serializable
enum class DictationState {
    @SerialName("listening") LISTENING,
    @SerialName("hearing") HEARING,
    /** Only in the result of [DictationAction.STOP]. */
    @SerialName("done") DONE,
    @SerialName("unavailable") UNAVAILABLE,
}

/** What a page asks of dictation: open the microphone, close it with the text, or close it and drop the text. */
@Serializable
enum class DictationAction {
    @SerialName("start") START,
    @SerialName("stop") STOP,
    @SerialName("cancel") CANCEL,
}
