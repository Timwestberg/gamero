package org.formulagen.wrapper.conversations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.formulagen.wrapper.hovel.HovelEventLines
import org.formulagen.wrapper.hovel.NativeExchange
import org.formulagen.wrapper.hovel.NativeRoute

/**
 * The server side of conversations as the Mac sees it: one method per conversation [NativeRoute]. The runner,
 * engine, folders and approvals talk only to this, so tests drive them with a fake server. A 409 refusal throws
 * `HovelRejectedException`; a 400, 403 or 404 is a final `HovelStatusException`; anything else may hide a
 * committed request.
 */
internal interface ConversationApi {
    /** Reports readiness, the provider directory and usage. Answers the heartbeat's data, which carries memory jobs. */
    fun heartbeat(beat: HostHeartbeat): JsonObject

    /** Opens the host event stream; reading its lines blocks until the server ends it or it is closed. */
    fun openStream(): HovelEventLines

    fun claim(request: ClaimRequest): ClaimResponse

    /**
     * Sends a heard voice turn as the owner's message in [conversationId], which must be in this computer's
     * projects. Answers it as stored (queued), or null when this build cannot read the answer.
     */
    fun send(conversationId: String, request: MessageSend): ConversationMessage?

    fun checkpoint(messageId: String, request: MessageCheckpointRequest)

    fun delta(messageId: String, request: ReplyDeltaRequest)

    /** Moves the claimed message on. Answers its new revision when the server says, else null. */
    fun progress(messageId: String, progress: MessageProgress): Long?

    /** A project of this computer's, with its folders and revision. */
    fun project(projectId: String): CompanionProject

    /** Records a project's folders. Answers the project as stored when the server returns it. */
    fun saveFolders(projectId: String, save: ProjectFoldersSave): CompanionProject?

    /** One approval as stored: who answered it and with what. */
    fun approval(approvalId: String): AgentApproval

    /** One page of an artifact's text, from character [cursor]. */
    fun artifactText(artifactId: String, cursor: Int): ArtifactTextPage

    /** Ephemeral preview transport, never journaled as conversation output. */
    fun imageWork(requestId: String): ImageRelayWork = throw UnsupportedOperationException()
    fun imageChunk(requestId: String, chunk: JsonObject) { throw UnsupportedOperationException() }
}

/** [ConversationApi] over the host transport: each call is the route's payload, in the contract's form. */
internal class HttpConversationApi(private val exchange: NativeExchange, private val stream: () -> HovelEventLines) : ConversationApi {
    override fun heartbeat(beat: HostHeartbeat): JsonObject = send(NativeRoute.HEARTBEAT, NativeRoute.HEARTBEAT.path(), wire(HostHeartbeat.serializer(), beat))

    override fun openStream(): HovelEventLines = stream()

    override fun claim(request: ClaimRequest): ClaimResponse = fromWire(ClaimResponse.serializer(),
        send(NativeRoute.CONVERSATION_CLAIM, NativeRoute.CONVERSATION_CLAIM.path(), wire(ClaimRequest.serializer(), request)))

    override fun send(conversationId: String, request: MessageSend): ConversationMessage? {
        val data = send(NativeRoute.MESSAGE_SEND, NativeRoute.MESSAGE_SEND.path(conversationId), wire(MessageSend.serializer(), request))
        return runCatching { fromWire(ConversationMessage.serializer(), data) }.getOrNull()
    }

    override fun checkpoint(messageId: String, request: MessageCheckpointRequest) {
        send(NativeRoute.MESSAGE_CHECKPOINT, NativeRoute.MESSAGE_CHECKPOINT.path(messageId), wire(MessageCheckpointRequest.serializer(), request))
    }

    override fun delta(messageId: String, request: ReplyDeltaRequest) {
        send(NativeRoute.MESSAGE_DELTA, NativeRoute.MESSAGE_DELTA.path(messageId), wire(ReplyDeltaRequest.serializer(), request))
    }

    override fun progress(messageId: String, progress: MessageProgress): Long? =
        revisionOf(send(NativeRoute.MESSAGE_PROGRESS, NativeRoute.MESSAGE_PROGRESS.path(messageId), wire(MessageProgress.serializer(), progress)))

    override fun project(projectId: String): CompanionProject =
        fromWire(CompanionProject.serializer(), send(NativeRoute.PROJECT, NativeRoute.PROJECT.path(projectId), null))

    override fun saveFolders(projectId: String, save: ProjectFoldersSave): CompanionProject? {
        val data = send(NativeRoute.PROJECT_FOLDERS, NativeRoute.PROJECT_FOLDERS.path(projectId), wire(ProjectFoldersSave.serializer(), save))
        return runCatching { fromWire(CompanionProject.serializer(), data) }.getOrNull()
    }

    override fun approval(approvalId: String): AgentApproval =
        fromWire(AgentApproval.serializer(), send(NativeRoute.APPROVAL, NativeRoute.APPROVAL.path(approvalId), null))

    override fun artifactText(artifactId: String, cursor: Int): ArtifactTextPage =
        fromWire(ArtifactTextPage.serializer(), send(NativeRoute.ARTIFACT_TEXT, NativeRoute.ARTIFACT_TEXT.path(artifactId, cursor = cursor), null))

    override fun imageWork(requestId: String): ImageRelayWork =
        fromWire(ImageRelayWork.serializer(), send(NativeRoute.IMAGE_RELAY_READ, NativeRoute.IMAGE_RELAY_READ.path(requestId), null))

    override fun imageChunk(requestId: String, chunk: JsonObject) {
        send(NativeRoute.IMAGE_RELAY_CHUNK, NativeRoute.IMAGE_RELAY_CHUNK.path(requestId), chunk)
    }

    private fun send(route: NativeRoute, path: String, body: JsonObject?) = exchange.exchange(route.method, path, body)

    /** The message's revision in a progress answer: the message itself, or a record that wraps it. */
    private fun revisionOf(data: JsonObject): Long? = ((data["message"]?.jsonObject ?: data)["revision"] as? JsonPrimitive)?.longOrNull
}
