package org.formulagen.wrapper.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

/** Companion chat is a product capability; it never accepts provider paths or credentials. */
interface ChatCapability {
    suspend fun open(scope: ChatScope): ChatSnapshot
    suspend fun openForClient(args: ChatOpenForClient): ChatSnapshot
    suspend fun snapshot(scope: ChatScope): ChatSnapshot
    suspend fun send(args: ChatSend): ChatSnapshot
    /** Make [ChatHandover.to] the active member. Refused while a reply is responding. */
    suspend fun handover(args: ChatHandover): ChatSnapshot
    /** Run the turn of an unanswered user entry again, with [ChatRetry.member]; it adds no user entry. */
    suspend fun retry(args: ChatRetry): ChatSnapshot
    suspend fun stop(scope: ChatScope): ChatSnapshot
    suspend fun stopSpeaking(scope: ChatScope): ChatSnapshot
    /**
     * The catalog page arms, mutes, or ends the microphone the host owns.
     * The arguments are a scope, an action, the member, and an optional effort.
     * A path, a model file, or a token is rejected.
     */
    suspend fun hearing(args: ChatHearingRequest): ChatSnapshot
    suspend fun closeSession(scope: ChatScope)
}

@Serializable
data class ChatScope(
    val profile: String,
    val companionId: String,
    val name: String,
    val sessionId: String,
    /**
     * Handshake for a page that can read [ChatSnapshot.hearing]. Host-bridge v2 pages omit it.
     * The host then omits `hearing` on every chat result, which a strict v2 decoder requires.
     */
    val hearing: Boolean? = null,
)

/** The lease is the four ids. [ChatScope.hearing] is a handshake, not a different chat. */
internal fun ChatScope.sameChat(other: ChatScope) =
    profile == other.profile && companionId == other.companionId && name == other.name && sessionId == other.sessionId

/** A chat lease with the page's immutable runtime vocabulary. */
@Serializable
data class ChatOpenForClient(val profile: String, val companionId: String, val name: String, val sessionId: String,
    val supportedRuntimes: List<String>) {
    fun scope() = ChatScope(profile, companionId, name, sessionId)
}

/**
 * A companion team member as the chat sees it: a runtime and one of its models, or
 * [SharedRules.RUNTIME_DEFAULT_MODEL]. Its provider is [RuntimeId.providerId], so it is not sent.
 */
@Serializable
data class TeamMember(val runtimeId: RuntimeId, val modelId: String)

/**
 * How a team member answers: [TALK] is today's talk-only, locked-down chat; [WORK] is the runtime's
 * own agent with the person's full setup, in the companion's project folder (`workspace.*`). Native
 * code confirms the start of work and asks every approval in its own dialog.
 */
@Serializable
enum class MemberMode {
    @SerialName("talk") TALK,
    @SerialName("work") WORK,
}

/**
 * [member] writes the reply with its model, in [mode] ([MemberMode.TALK] when absent). The page
 * resolves Claude's default model; Codex resolves its own. A working message without a project
 * folder, or whose start of work the person declines, is answered as talk, with a notice.
 */
@Serializable
data class ChatSend(val profile: String, val companionId: String, val name: String, val sessionId: String,
    val member: TeamMember, val requestId: String, val text: String, val speak: Boolean, val mode: MemberMode? = null,
    val effort: ModelEffort? = null) {
    fun scope() = ChatScope(profile, companionId, name, sessionId)
}

/**
 * Make [to] the active member, taking over from [from] (null when none was active). [requestId]
 * makes a retry one entry. A page asks only for [HandoverReason.MANUAL] ones.
 */
@Serializable
data class ChatHandover(val profile: String, val companionId: String, val name: String, val sessionId: String,
    val from: TeamMember?, val reason: HandoverReason, val requestId: String, val to: TeamMember) {
    fun scope() = ChatScope(profile, companionId, name, sessionId)
}

/**
 * Run the turn of the unanswered user entry [entryId] again, with [member] in [mode] (talk when
 * absent, as for [ChatSend]). [requestId] makes a repeated retry one turn.
 */
@Serializable
data class ChatRetry(val profile: String, val companionId: String, val name: String, val sessionId: String,
    val entryId: String, val member: TeamMember, val requestId: String, val speak: Boolean, val mode: MemberMode? = null,
    val effort: ModelEffort? = null) {
    fun scope() = ChatScope(profile, companionId, name, sessionId)
}

/** Bounds shared by the native conversation and its web presentation. */
object ChatLimits { // mirrors CHAT_LIMITS
    const val HISTORY = 40
    const val NAME = SharedRules.COMPANION_NAME_LIMIT
    const val MESSAGE = 4000
    const val REPLY = 16000
    const val NOTICE = 300
}

/** What one member hands the next: a summary, or the last [RECENT_MESSAGES] entries within [RECENT_CHARS]. */
object HandoverLimits { // mirrors HANDOVER_LIMITS
    /** An engine's id and version, as Hovel Brain bounds them. */
    const val ENGINE_IDENTIFIER = 256
    const val RECENT_CHARS = 8000
    const val RECENT_MESSAGES = 10
    const val SUMMARY = 2000
}

/**
 * Bounds of a working reply's activity: at most [STEPS] steps, each a [LABEL] and a shortened
 * [DETAIL]. A handover's [WorkLog] uses the same bounds.
 */
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

/**
 * What a working member did since the last handover, in fields a routing engine and the page read
 * without parsing prose: the files [changed] (relative to the project folder), the [commands] run
 * with their status and shortened result, the labels of the steps [denied], and [stoppedAt], the
 * opening of the last working reply. Native code writes the prose the incoming model reads.
 */
@Serializable
data class WorkLog(val changed: List<String>, val commands: List<ActivityStep>, val denied: List<String>, val stoppedAt: String? = null) {
    init {
        require(changed.size <= ActivityLimits.STEPS && commands.size <= ActivityLimits.STEPS && denied.size <= ActivityLimits.STEPS)
        require((changed + denied).all { it.length <= ActivityLimits.LABEL } && (stoppedAt?.length ?: 0) <= ActivityLimits.DETAIL)
    }
}

/**
 * `awaiting-you`: a working member's reply waits for the person to answer a native dialog, and the
 * snapshot's `waiting` says what it asks. `handing-over`: the outgoing member is writing a summary
 * for the incoming one.
 */
@Serializable
enum class ChatState {
    @SerialName("checking") CHECKING,
    @SerialName("ready") READY,
    @SerialName("responding") RESPONDING,
    @SerialName("awaiting-you") AWAITING_YOU,
    @SerialName("handing-over") HANDING_OVER,
    @SerialName("cancelled") CANCELLED,
    @SerialName("error") ERROR,
    @SerialName("disconnected") DISCONNECTED,
}

/**
 * How the incoming member learned the conversation: its runtime's own conversation continues
 * (`same-conversation`), it has its own conversation and missed nothing (`up-to-date`), a summary by
 * the outgoing member, the recent messages when no summary was possible, or nothing to hand over (`fresh`).
 */
@Serializable
enum class HandoverMethod {
    @SerialName("same-conversation") SAME_CONVERSATION,
    @SerialName("up-to-date") UP_TO_DATE,
    @SerialName("summary") SUMMARY,
    @SerialName("recent-messages") RECENT_MESSAGES,
    @SerialName("fresh") FRESH,
}

/**
 * Who decided on a handover: the person (`manual`); the host, for a note it records itself (a
 * runtime's first turn, a model change made by a send, catching a runtime up, recovering a lost
 * conversation); or a routing engine, named in [ChatEntry.Handover.engine].
 */
@Serializable
enum class HandoverReason {
    @SerialName("manual") MANUAL,
    @SerialName("host") HOST,
    @SerialName("engine") ENGINE,
}

/** A routing engine, by Hovel Brain's names. */
@Serializable
data class HandoverEngine(val engineId: String, val engineVersion: String)

/** `unanswered`: the message's turn failed before a reply, so `chat.retry` can run it again. */
@Serializable
enum class UserStatus { @SerialName("unanswered") UNANSWERED }

/** Why the last turn failed, so the page offers the next step without reading the notice. */
@Serializable
enum class ChatFailureKind {
    @SerialName("usage-limit") USAGE_LIMIT,
    @SerialName("signed-out") SIGNED_OUT,
    @SerialName("model-unavailable") MODEL_UNAVAILABLE,
    @SerialName("temporary") TEMPORARY,
    @SerialName("lockdown") LOCKDOWN,
    /** The runtime is not installed, not offered or not ready. */
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("unexpected") UNEXPECTED,
}

/** The last turn's failure on [member]; [resetsAt] is an ISO-8601 instant when the runtime reports one. */
@Serializable
data class ChatFailureReport(val kind: ChatFailureKind, val member: TeamMember, val resetsAt: String? = null)

@Serializable
enum class VoiceState {
    @SerialName("idle") IDLE,
    @SerialName("preparing") PREPARING,
    @SerialName("speaking") SPEAKING,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("error") ERROR,
}

/** One entry of the conversation the whole team shares, by `role`. A reply names the member that wrote it. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("role")
sealed interface ChatEntry {
    val id: String

    /** [status] is `unanswered` when its turn failed before a reply; absent for a normal message. */
    @Serializable @SerialName("user")
    data class User(override val id: String, val text: String, val status: UserStatus? = null) : ChatEntry

    /** [activity] is what a working member did for this reply: present only on working replies; speech reads only [text]. */
    @Serializable @SerialName("assistant")
    data class Assistant(override val id: String, val author: TeamMember, val text: String, val activity: Activity? = null) : ChatEntry

    /**
     * The handover record and its note in the conversation are the same entry. [at] is an ISO-8601
     * instant; [from] is null when no member was active; [summary] is present for `summary`.
     * [workLog] is the files changed, commands run and what is left: with a summary, the working
     * outgoing member's; with recent messages, the last one there was; absent when nobody worked.
     */
    @Serializable(with = HandoverSerializer::class) @SerialName("handover")
    data class Handover(
        override val id: String,
        val at: String,
        val from: TeamMember?,
        val to: TeamMember,
        val method: HandoverMethod,
        val reason: HandoverReason,
        val summary: String? = null,
        /** The engine that decided; only with reason `engine`. */
        val engine: HandoverEngine? = null,
        val workLog: WorkLog? = null,
    ) : ChatEntry
}

@Serializable
data class ChatVoice(val state: VoiceState = VoiceState.IDLE, val notice: String? = null)

/** What a native dialog asks while the snapshot is `awaiting-you`: the start-of-work confirmation, or a runtime's request to act. */
@Serializable
enum class WaitingKind {
    @SerialName("start") START,
    @SerialName("approval") APPROVAL,
}

/** For the page to display only: the person answers in the native dialog, and no method takes an answer. */
@Serializable
data class ChatWaiting(val kind: WaitingKind, val summary: String) {
    init { require(summary.length <= ChatLimits.NOTICE) }
}

/**
 * Hearing, separate from [VoiceState]. Absent when this build has no hearing, so an older
 * snapshot that never sent the field still decodes. The page polls this. It is not audio.
 */
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

/** [partial] is the live transcript, at most [ChatLimits.MESSAGE]. [notice] explains a refusal or a missing microphone. */
@Serializable
data class ChatHearing(val state: HearingState = HearingState.OFF, val partial: String? = null, val notice: String? = null) {
    init {
        require(partial == null || partial.length in 1..ChatLimits.MESSAGE)
        require(notice == null || notice.length in 1..ChatLimits.NOTICE)
    }
}

/** What the catalog page asks the host to do with the microphone. */
@Serializable
enum class HearingAction {
    @SerialName("press") PRESS,
    @SerialName("release") RELEASE,
    @SerialName("handsFree") HANDS_FREE,
    @SerialName("mute") MUTE,
    @SerialName("unmute") UNMUTE,
    @SerialName("off") OFF,
}

/**
 * [member] is who answers the heard turn. [effort] is the page's model effort when the page
 * armed the microphone, and null when the native row did. No path, model file, or token.
 */
@Serializable
data class ChatHearingRequest(
    val profile: String,
    val companionId: String,
    val name: String,
    val sessionId: String,
    val action: HearingAction,
    val member: TeamMember,
    val effort: String? = null,
) {
    fun scope() = ChatScope(profile, companionId, name, sessionId)
}

/**
 * [failure] is why the last turn failed, absent once a turn succeeds. [waiting] is present only while `awaiting-you`.
 * [hearing] is omitted for host-bridge v2. It is sent only after the page sets [ChatScope.hearing] or calls `chat.hearing`.
 */
@Serializable
data class ChatSnapshot(val state: ChatState = ChatState.READY, val messages: List<ChatEntry> = emptyList(),
    val notice: String? = null, val voice: ChatVoice = ChatVoice(), val failure: ChatFailureReport? = null,
    val waiting: ChatWaiting? = null, val hearing: ChatHearing? = null)

/**
 * The contract's handover `from` is `nullable`, not `optional`: the key is always there. The bridge
 * leaves out null fields, so the entry is written through [Wire], whose `from` is not a nullable
 * property and writes its own `null`.
 */
internal object HandoverSerializer : KSerializer<ChatEntry.Handover> {
    @Serializable @SerialName("handover")
    private class Wire(val id: String, val at: String, val from: Previous, val to: TeamMember, val method: HandoverMethod,
        val reason: HandoverReason, val summary: String? = null, val engine: HandoverEngine? = null, val workLog: WorkLog? = null)

    @Serializable(with = PreviousSerializer::class)
    private class Previous(val member: TeamMember?)

    @OptIn(ExperimentalSerializationApi::class)
    private object PreviousSerializer : KSerializer<Previous> {
        override val descriptor: SerialDescriptor = TeamMember.serializer().descriptor.nullable
        override fun serialize(encoder: Encoder, value: Previous) =
            value.member?.let { encoder.encodeSerializableValue(TeamMember.serializer(), it) } ?: encoder.encodeNull()
        override fun deserialize(decoder: Decoder) =
            Previous(if (decoder.decodeNotNullMark()) decoder.decodeSerializableValue(TeamMember.serializer()) else decoder.decodeNull())
    }

    override val descriptor: SerialDescriptor = Wire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: ChatEntry.Handover) = encoder.encodeSerializableValue(Wire.serializer(),
        Wire(value.id, value.at, Previous(value.from), value.to, value.method, value.reason, value.summary, value.engine, value.workLog))
    override fun deserialize(decoder: Decoder) = decoder.decodeSerializableValue(Wire.serializer())
        .let { ChatEntry.Handover(it.id, it.at, it.from.member, it.to, it.method, it.reason, it.summary, it.engine, it.workLog) }
}

/**
 * Mirrors event-contracts/host-bridge/chat.ts. `chat.send`, `chat.retry` and `chat.handover` are
 * refused, with a notice and no change, while the snapshot is `awaiting-you`; `chat.stop` and
 * `chat.close` deny what a pending dialog asks.
 */
object ChatMethods {
    private fun valid(scope: ChatScope) = SharedRules.isProfileId(scope.profile) &&
        BridgeProtocol.UUID_PATTERN.matches(scope.companionId) && BridgeProtocol.UUID_PATTERN.matches(scope.sessionId) &&
        SharedRules.isCompanionName(scope.name)
    private fun valid(member: TeamMember) = SharedRules.isModelId(member.modelId)
    private fun method(name: String) = BridgeMethod("chat.$name", ChatScope.serializer(), ChatSnapshot.serializer(), accepts = ::valid)
    val OPEN = method("open")
    val OPEN_FOR_CLIENT = BridgeMethod("chat.openForClient", ChatOpenForClient.serializer(), ChatSnapshot.serializer()) {
        valid(it.scope()) && SharedRules.validClientRuntimes(it.supportedRuntimes)
    }
    val SNAPSHOT = method("snapshot")
    val STOP = method("stop")
    val STOP_SPEAKING = method("stopSpeaking")
    val CLOSE = BridgeMethod("chat.close", ChatScope.serializer(), NoResult.serializer(), accepts = ::valid)
    val SEND = BridgeMethod("chat.send", ChatSend.serializer(), ChatSnapshot.serializer()) {
        valid(it.scope()) && valid(it.member) && BridgeProtocol.UUID_PATTERN.matches(it.requestId) && it.text.trimEcma().length in 1..ChatLimits.MESSAGE
    }
    val HANDOVER = BridgeMethod("chat.handover", ChatHandover.serializer(), ChatSnapshot.serializer()) {
        valid(it.scope()) && it.from?.let(::valid) != false && valid(it.to) && it.reason == HandoverReason.MANUAL &&
            BridgeProtocol.UUID_PATTERN.matches(it.requestId)
    }
    val RETRY = BridgeMethod("chat.retry", ChatRetry.serializer(), ChatSnapshot.serializer()) {
        valid(it.scope()) && valid(it.member) && BridgeProtocol.UUID_PATTERN.matches(it.entryId) && BridgeProtocol.UUID_PATTERN.matches(it.requestId)
    }
    val HEARING = BridgeMethod("chat.hearing", ChatHearingRequest.serializer(), ChatSnapshot.serializer()) {
        valid(it.scope()) && hearingMemberAllowed(it.member) && acceptableHearingEffort(it.effort)
    }
    fun pack(chat: Capability<ChatCapability>): CapabilityPack = binder.pack(chat)
    internal val binder = CapabilityBinder("chat", listOf<CapabilityHandler<ChatCapability>>(
        OPEN.handle { chat, args -> chat.open(args) },
        OPEN_FOR_CLIENT.handle { chat, args -> chat.openForClient(args) },
        SNAPSHOT.handle { chat, args -> chat.snapshot(args) },
        SEND.handle { chat, args -> chat.send(args) },
        HANDOVER.handle { chat, args -> chat.handover(args) },
        RETRY.handle { chat, args -> chat.retry(args) },
        STOP.handle { chat, args -> chat.stop(args) },
        STOP_SPEAKING.handle { chat, args -> chat.stopSpeaking(args) },
        HEARING.handle { chat, args -> chat.hearing(args) },
        CLOSE.handle { chat, args -> chat.closeSession(args); NoResult },
    ))
}

/** A page effort is a short word. A path, a model file, or a token is not an effort. Null is the runtime default. */
internal fun acceptableHearingEffort(effort: String?): Boolean {
    if (effort == null) return true
    if (!HEARING_EFFORT.matches(effort)) return false
    return HEARING_BLOCKED.none { effort.contains(it) }
}

/** The member names a runtime model. It is not a filesystem path or a weight file. */
internal fun hearingMemberAllowed(member: TeamMember): Boolean {
    if (!SharedRules.isModelId(member.modelId)) return false
    val id = member.modelId
    return !id.contains('/') && !id.contains('\\') && HEARING_BLOCKED.none { id.contains(it) }
}

private val HEARING_EFFORT = Regex("^[a-z][a-z0-9-]{0,31}$")
private val HEARING_BLOCKED = listOf("token", "onnx", "secret", "path")
