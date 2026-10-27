package org.formulagen.wrapper.bridge

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

@Serializable
data class ChatMessage(val id: String, val role: String, val text: String)

@Serializable
data class ChatVoice(val state: String = "idle", val notice: String? = null)

@Serializable
data class ChatSnapshot(val state: String = "ready", val messages: List<ChatMessage> = emptyList(),
    val notice: String? = null, val voice: ChatVoice = ChatVoice())

/** Mirrors event-contracts/host-bridge/chat.ts. */
object ChatMethods {
    private fun valid(scope: ChatScope) = (scope.profile == "local" || BridgeProtocol.UUID_PATTERN.matches(scope.profile)) &&
        BridgeProtocol.UUID_PATTERN.matches(scope.companionId) && BridgeProtocol.UUID_PATTERN.matches(scope.sessionId) &&
        scope.name.trim().length in 1..40
    private fun method(name: String) = BridgeMethod("chat.$name", ChatScope.serializer(), ChatSnapshot.serializer(), accepts = ::valid)
    val OPEN = method("open")
    val SNAPSHOT = method("snapshot")
    val STOP = method("stop")
    val STOP_SPEAKING = method("stopSpeaking")
    val CLOSE = BridgeMethod("chat.close", ChatScope.serializer(), NoResult.serializer(), accepts = ::valid)
    val SEND = BridgeMethod("chat.send", ChatSend.serializer(), ChatSnapshot.serializer()) {
        valid(it.scope()) && BridgeProtocol.UUID_PATTERN.matches(it.requestId) && it.text.trim().length in 1..4000
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
