package org.formulagen.wrapper.conversations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.formulagen.wrapper.hovel.HovelEventLines
import org.formulagen.wrapper.hovel.NativeExchange

/**
 * The server side of conversations as the Mac sees it: one method per [NativeRoutes] entry. The
 * runner, engine, folders and approvals talk only to this, so tests drive them with a fake server.
 * A 409 refusal throws `HovelRejectedException`; any other failure may hide a committed request.
 */
internal interface ConversationApi {
    /** Reports readiness, the provider directory and usage. Answers the heartbeat's data, which carries memory jobs. */
    fun heartbeat(beat: HostHeartbeat): JsonObject

    /** Opens the host event stream; reading its lines blocks until the server ends it or it is closed. */
    fun openStream(): HovelEventLines

    fun claim(request: ClaimRequest): ClaimResponse

    fun checkpoint(messageId: String, request: MessageCheckpointRequest)

    fun delta(messageId: String, request: ReplyDeltaRequest)

    /** Moves the claimed message on. Answers its new revision when the server says, else null. */
    fun progress(messageId: String, progress: MessageProgress): Long?

    /** Records a project's folders. Answers the project as stored when the server returns it. */
    fun saveFolders(projectId: String, save: ProjectFoldersSave): CompanionProject?

    /** This Mac's answer. Answers the approval as stored, whose answer is the one that won. */
    fun answer(approvalId: String, answer: ApprovalAnswer): AgentApproval

    /** One approval, to learn another surface's answer. */
    fun approval(approvalId: String): AgentApproval

    /** A heard voice turn, sent as the person's message. Answers the message as stored. */
    fun sendHeard(conversationId: String, send: MessageSend): ConversationMessage
}

/** [ConversationApi] over the host transport: each call is the route's payload, in the contract's form. */
internal class HttpConversationApi(private val exchange: NativeExchange, private val stream: () -> HovelEventLines) : ConversationApi {
    override fun heartbeat(beat: HostHeartbeat): JsonObject =
        exchange.exchange("POST", NativeRoutes.HEARTBEAT, wire(HostHeartbeat.serializer(), beat))

    override fun openStream(): HovelEventLines = stream()

    override fun claim(request: ClaimRequest): ClaimResponse =
        fromWire(ClaimResponse.serializer(), exchange.exchange("POST", NativeRoutes.CLAIM, wire(ClaimRequest.serializer(), request)))

    override fun checkpoint(messageId: String, request: MessageCheckpointRequest) {
        exchange.exchange("POST", NativeRoutes.checkpoints(messageId), wire(MessageCheckpointRequest.serializer(), request))
    }

    override fun delta(messageId: String, request: ReplyDeltaRequest) {
        exchange.exchange("POST", NativeRoutes.deltas(messageId), wire(ReplyDeltaRequest.serializer(), request))
    }

    override fun progress(messageId: String, progress: MessageProgress): Long? =
        revisionOf(exchange.exchange("POST", NativeRoutes.progress(messageId), wire(MessageProgress.serializer(), progress)))

    override fun saveFolders(projectId: String, save: ProjectFoldersSave): CompanionProject? {
        val data = exchange.exchange("PUT", NativeRoutes.folders(projectId), wire(ProjectFoldersSave.serializer(), save))
        return runCatching { fromWire(CompanionProject.serializer(), data["project"] ?: data) }.getOrNull()
    }

    override fun answer(approvalId: String, answer: ApprovalAnswer): AgentApproval =
        approvalOf(exchange.exchange("POST", NativeRoutes.answer(approvalId), wire(ApprovalAnswer.serializer(), answer)))

    override fun approval(approvalId: String): AgentApproval = approvalOf(exchange.exchange("GET", NativeRoutes.approval(approvalId), null))

    override fun sendHeard(conversationId: String, send: MessageSend): ConversationMessage {
        val data = exchange.exchange("POST", NativeRoutes.messages(conversationId), wire(MessageSend.serializer(), send))
        return fromWire(ConversationMessage.serializer(), data["message"] ?: data)
    }

    private fun approvalOf(data: JsonObject) = fromWire(AgentApproval.serializer(), data["approval"] ?: data)

    /** The message's revision in a progress answer: the message itself, or the record that wraps it. */
    private fun revisionOf(data: JsonObject): Long? =
        ((data["message"]?.jsonObject ?: data)["revision"] as? JsonPrimitive)?.longOrNull
}
