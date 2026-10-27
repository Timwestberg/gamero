package org.formulagen.wrapper.bridge

/** Protocol fixtures exercise dispatch without starting provider or audio processes. */
class FakeChatCapability : ChatCapability {
    override suspend fun open(scope: ChatScope) = ChatSnapshot()
    override suspend fun snapshot(scope: ChatScope) = ChatSnapshot()
    override suspend fun send(args: ChatSend) = ChatSnapshot("responding")
    override suspend fun stop(scope: ChatScope) = ChatSnapshot("cancelled")
    override suspend fun stopSpeaking(scope: ChatScope) = ChatSnapshot()
    override suspend fun closeSession(scope: ChatScope) = Unit
}
