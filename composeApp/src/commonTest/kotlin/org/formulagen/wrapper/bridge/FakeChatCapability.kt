package org.formulagen.wrapper.bridge

/** Protocol fixtures exercise dispatch without starting provider or audio processes. */
class FakeChatCapability : ChatCapability {
    override suspend fun open(scope: ChatScope) = ChatSnapshot()
    override suspend fun snapshot(scope: ChatScope) = ChatSnapshot()
    override suspend fun send(args: ChatSend) = ChatSnapshot(ChatState.RESPONDING)
    override suspend fun handover(args: ChatHandover) = ChatSnapshot(ChatState.HANDING_OVER)
    override suspend fun retry(args: ChatRetry) = ChatSnapshot(ChatState.RESPONDING)
    override suspend fun stop(scope: ChatScope) = ChatSnapshot(ChatState.CANCELLED)
    override suspend fun stopSpeaking(scope: ChatScope) = ChatSnapshot()
    override suspend fun closeSession(scope: ChatScope) = Unit
}
