package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Companion chat is a product capability; it never accepts provider paths or credentials. */
interface ChatCapability {
    suspend fun open(scope: ChatScope): ChatSnapshot
    suspend fun snapshot(scope: ChatScope): ChatSnapshot
    suspend fun send(args: ChatSend): ChatSnapshot
    suspend fun stop(scope: ChatScope): ChatSnapshot
    suspend fun stopSpeaking(scope: ChatScope): ChatSnapshot
    suspend fun closeSession(scope: ChatScope)
}

@Serializable
data class ChatScope(val profile: String, val companionId: String, val name: String, val sessionId: String)

@Serializable
data class ChatSend(val profile: String, val companionId: String, val name: String, val sessionId: String,
    val requestId: String, val text: String, val speak: Boolean) {
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

@Serializable
enum class ChatState {
    @SerialName("checking") CHECKING,
    @SerialName("ready") READY,
    @SerialName("responding") RESPONDING,
    @SerialName("cancelled") CANCELLED,
    @SerialName("error") ERROR,
    @SerialName("disconnected") DISCONNECTED,
}

@Serializable
enum class ChatRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
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
data class ChatMessage(val id: String, val role: ChatRole, val text: String)

@Serializable
data class ChatVoice(val state: VoiceState = VoiceState.IDLE, val notice: String? = null)

@Serializable
data class ChatSnapshot(val state: ChatState = ChatState.READY, val messages: List<ChatMessage> = emptyList(),
    val notice: String? = null, val voice: ChatVoice = ChatVoice())

/** Mirrors event-contracts/host-bridge/chat.ts. */
object ChatMethods {
    private fun valid(scope: ChatScope) = SharedRules.isProfileId(scope.profile) &&
        BridgeProtocol.UUID_PATTERN.matches(scope.companionId) && BridgeProtocol.UUID_PATTERN.matches(scope.sessionId) &&
        SharedRules.isCompanionName(scope.name)
    private fun method(name: String) = BridgeMethod("chat.$name", ChatScope.serializer(), ChatSnapshot.serializer(), accepts = ::valid)
    val OPEN = method("open")
    val SNAPSHOT = method("snapshot")
    val STOP = method("stop")
    val STOP_SPEAKING = method("stopSpeaking")
    val CLOSE = BridgeMethod("chat.close", ChatScope.serializer(), NoResult.serializer(), accepts = ::valid)
    val SEND = BridgeMethod("chat.send", ChatSend.serializer(), ChatSnapshot.serializer()) {
        valid(it.scope()) && BridgeProtocol.UUID_PATTERN.matches(it.requestId) && it.text.trimEcma().length in 1..ChatLimits.MESSAGE
    }
    fun pack(chat: Capability<ChatCapability>): CapabilityPack = binder.pack(chat)
    internal val binder = CapabilityBinder("chat", listOf<CapabilityHandler<ChatCapability>>(
        OPEN.handle { chat, args -> chat.open(args) },
        SNAPSHOT.handle { chat, args -> chat.snapshot(args) },
        SEND.handle { chat, args -> chat.send(args) },
        STOP.handle { chat, args -> chat.stop(args) },
        STOP_SPEAKING.handle { chat, args -> chat.stopSpeaking(args) },
        CLOSE.handle { chat, args -> chat.closeSession(args); NoResult },
    ))
}
