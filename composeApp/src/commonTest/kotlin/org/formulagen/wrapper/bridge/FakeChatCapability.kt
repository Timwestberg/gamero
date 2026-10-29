package org.formulagen.wrapper.bridge

/** Protocol fixtures exercise dispatch without starting provider or audio processes. */
class FakeChatCapability : ChatCapability {
    /** A notice as a host without voice gives one. Its own text, since `voice` is a JVM-only module. */
    private val noHost = "Open Hovel on your Mac to connect…"

    override suspend fun open(scope: ChatScope) = ChatSnapshot()
    override suspend fun openForClient(args: ChatOpenForClient) = ChatSnapshot()
    override suspend fun snapshot(scope: ChatScope) = ChatSnapshot()
    override suspend fun send(args: ChatSend) = ChatSnapshot(ChatState.RESPONDING)
    override suspend fun handover(args: ChatHandover) = ChatSnapshot(ChatState.HANDING_OVER)
    override suspend fun retry(args: ChatRetry) = ChatSnapshot(ChatState.RESPONDING)
    override suspend fun stop(scope: ChatScope) = ChatSnapshot(ChatState.CANCELLED)
    override suspend fun stopSpeaking(scope: ChatScope) = ChatSnapshot()
    override suspend fun hearing(args: ChatHearingRequest) = ChatSnapshot(
        hearing = ChatHearing(HearingState.UNAVAILABLE, notice = noHost),
    )
    override suspend fun dictation(args: ChatDictationRequest) = ChatSnapshot(
        dictation = ChatDictation(DictationState.UNAVAILABLE, notice = noHost),
    )
    override suspend fun closeSession(scope: ChatScope) = Unit
}
