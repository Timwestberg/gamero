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
    suspend fun snapshot(scope: ChatScope): ChatSnapshot
    suspend fun send(args: ChatSend): ChatSnapshot
    /** Make [ChatHandover.to] the active member. Refused while a reply is responding. */
    suspend fun handover(args: ChatHandover): ChatSnapshot
    suspend fun stop(scope: ChatScope): ChatSnapshot
    suspend fun stopSpeaking(scope: ChatScope): ChatSnapshot
    suspend fun closeSession(scope: ChatScope)
}

@Serializable
data class ChatScope(val profile: String, val companionId: String, val name: String, val sessionId: String)

/**
 * A companion team member as the chat sees it: a runtime and one of its models, or
 * [SharedRules.RUNTIME_DEFAULT_MODEL]. Its provider is [RuntimeId.providerId], so it is not sent.
 */
@Serializable
data class TeamMember(val runtimeId: RuntimeId, val modelId: String)

/** [member] writes the reply with its model. The page resolves Claude's default model; Codex resolves its own. */
@Serializable
data class ChatSend(val profile: String, val companionId: String, val name: String, val sessionId: String,
    val member: TeamMember, val requestId: String, val text: String, val speak: Boolean) {
    fun scope() = ChatScope(profile, companionId, name, sessionId)
}

/** Make [to] the active member, taking over from [from] (null when none was active). [requestId] makes a retry one entry. */
@Serializable
data class ChatHandover(val profile: String, val companionId: String, val name: String, val sessionId: String,
    val from: TeamMember?, val reason: HandoverReason, val requestId: String, val to: TeamMember) {
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
    const val RECENT_CHARS = 8000
    const val RECENT_MESSAGES = 10
    const val SUMMARY = 2000
}

/** `handing-over`: the outgoing member is writing a summary for the incoming one. */
@Serializable
enum class ChatState {
    @SerialName("checking") CHECKING,
    @SerialName("ready") READY,
    @SerialName("responding") RESPONDING,
    @SerialName("handing-over") HANDING_OVER,
    @SerialName("cancelled") CANCELLED,
    @SerialName("error") ERROR,
    @SerialName("disconnected") DISCONNECTED,
}

/**
 * How the incoming member learned the conversation: its runtime's own conversation continues
 * (`same-conversation`), a summary by the outgoing member, the recent messages when no summary was
 * possible, or nothing to hand over (`fresh`).
 */
@Serializable
enum class HandoverMethod {
    @SerialName("same-conversation") SAME_CONVERSATION,
    @SerialName("summary") SUMMARY,
    @SerialName("recent-messages") RECENT_MESSAGES,
    @SerialName("fresh") FRESH,
}

/** Why a handover happened. `manual` is the person's switch; a rule system adds its own reason. */
@Serializable
enum class HandoverReason { @SerialName("manual") MANUAL }

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

    @Serializable @SerialName("user")
    data class User(override val id: String, val text: String) : ChatEntry

    @Serializable @SerialName("assistant")
    data class Assistant(override val id: String, val author: TeamMember, val text: String) : ChatEntry

    /**
     * The handover record and its note in the conversation are the same entry. [at] is an ISO-8601
     * instant; [from] is null when no member was active; [summary] is present for `summary`.
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
    ) : ChatEntry
}

@Serializable
data class ChatVoice(val state: VoiceState = VoiceState.IDLE, val notice: String? = null)

@Serializable
data class ChatSnapshot(val state: ChatState = ChatState.READY, val messages: List<ChatEntry> = emptyList(),
    val notice: String? = null, val voice: ChatVoice = ChatVoice())

/**
 * The contract's handover `from` is `nullable`, not `optional`: the key is always there. The bridge
 * leaves out null fields, so the entry is written through [Wire], whose `from` is not a nullable
 * property and writes its own `null`.
 */
internal object HandoverSerializer : KSerializer<ChatEntry.Handover> {
    @Serializable @SerialName("handover")
    private class Wire(val id: String, val at: String, val from: Previous, val to: TeamMember, val method: HandoverMethod,
        val reason: HandoverReason, val summary: String? = null)

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
        Wire(value.id, value.at, Previous(value.from), value.to, value.method, value.reason, value.summary))
    override fun deserialize(decoder: Decoder) = decoder.decodeSerializableValue(Wire.serializer())
        .let { ChatEntry.Handover(it.id, it.at, it.from.member, it.to, it.method, it.reason, it.summary) }
}

/** Mirrors event-contracts/host-bridge/chat.ts. */
object ChatMethods {
    private fun valid(scope: ChatScope) = SharedRules.isProfileId(scope.profile) &&
        BridgeProtocol.UUID_PATTERN.matches(scope.companionId) && BridgeProtocol.UUID_PATTERN.matches(scope.sessionId) &&
        SharedRules.isCompanionName(scope.name)
    private fun valid(member: TeamMember) = SharedRules.isModelId(member.modelId)
    private fun method(name: String) = BridgeMethod("chat.$name", ChatScope.serializer(), ChatSnapshot.serializer(), accepts = ::valid)
    val OPEN = method("open")
    val SNAPSHOT = method("snapshot")
    val STOP = method("stop")
    val STOP_SPEAKING = method("stopSpeaking")
    val CLOSE = BridgeMethod("chat.close", ChatScope.serializer(), NoResult.serializer(), accepts = ::valid)
    val SEND = BridgeMethod("chat.send", ChatSend.serializer(), ChatSnapshot.serializer()) {
        valid(it.scope()) && valid(it.member) && BridgeProtocol.UUID_PATTERN.matches(it.requestId) && it.text.trimEcma().length in 1..ChatLimits.MESSAGE
    }
    val HANDOVER = BridgeMethod("chat.handover", ChatHandover.serializer(), ChatSnapshot.serializer()) {
        valid(it.scope()) && it.from?.let(::valid) != false && valid(it.to) && BridgeProtocol.UUID_PATTERN.matches(it.requestId)
    }
    fun pack(chat: Capability<ChatCapability>): CapabilityPack = binder.pack(chat)
    internal val binder = CapabilityBinder("chat", listOf<CapabilityHandler<ChatCapability>>(
        OPEN.handle { chat, args -> chat.open(args) },
        SNAPSHOT.handle { chat, args -> chat.snapshot(args) },
        SEND.handle { chat, args -> chat.send(args) },
        HANDOVER.handle { chat, args -> chat.handover(args) },
        STOP.handle { chat, args -> chat.stop(args) },
        STOP_SPEAKING.handle { chat, args -> chat.stopSpeaking(args) },
        CLOSE.handle { chat, args -> chat.closeSession(args); NoResult },
    ))
}
