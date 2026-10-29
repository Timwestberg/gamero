package org.formulagen.wrapper.conversations

import org.formulagen.wrapper.bridge.SharedRules

/**
 * The routes a registered Mac calls for companion conversations, relative to
 * `/v1/hovel/native/:ownerId/:hostId` (`HovelHttpTransport` adds that base and the
 * `x-hovel-host-token`). This is the one place they are spelled; hovel-service implements the same
 * table. Each payload is named by its event-contracts `hovel/companions` schema.
 */
internal object NativeRoutes {
    /** `POST`: `computerHeartbeat` (the provider directory with each runtime's readiness, and usage) plus the memory features. */
    const val HEARTBEAT = "heartbeat"

    /** `GET`: the computer's server-sent events, each a `hostStreamEvent`. */
    const val STREAM = "stream"

    /** `POST`: `claimRequest` → `claimResponse`. */
    const val CLAIM = "conversations/claim"

    /** `POST`: a heard voice turn, sent as the person's message in [conversationId]. */
    fun messages(conversationId: String) = "conversations/${record(conversationId)}/messages"

    /** `POST`: `messageCheckpointRequest`, one durable step of the claimed message. */
    fun checkpoints(messageId: String) = "messages/${record(messageId)}/checkpoints"

    /** `POST`: `replyDeltaRequest`, a streamed piece of a reply, relayed and never stored. */
    fun deltas(messageId: String) = "messages/${record(messageId)}/deltas"

    /** `POST`: `messageProgress`, `running` once launched and then the state that settles the message. */
    fun progress(messageId: String) = "messages/${record(messageId)}/progress"

    /** `PUT`: `projectFoldersSave`, the folders the person chose for a project on this Mac. */
    fun folders(projectId: String) = "projects/${record(projectId)}/folders"

    /** `POST`: `approvalAnswer`, the person's answer on this Mac. The approval as stored answers it. */
    fun answer(approvalId: String) = "approvals/${record(approvalId)}/answer"

    /**
     * `GET`: one approval (`agentApproval`), to learn the answer another surface gave after
     * `approval.answered`. Not in the agreed table yet: the phone answers approvals from step F on,
     * and only then does this route carry traffic.
     */
    fun approval(approvalId: String) = "approvals/${record(approvalId)}"

    /** The routes and methods [org.formulagen.wrapper.hovel.HovelHttpTransport] may call for conversations. */
    val ALLOWED: List<Pair<String, Regex>> = listOf(
        "POST" to Regex.fromLiteral(HEARTBEAT),
        "GET" to Regex.fromLiteral(STREAM),
        "POST" to Regex.fromLiteral(CLAIM),
        "POST" to Regex("conversations/$UUID/messages"),
        "POST" to Regex("messages/$UUID/(checkpoints|deltas|progress)"),
        "PUT" to Regex("projects/$UUID/folders"),
        "POST" to Regex("approvals/$UUID/answer"),
        "GET" to Regex("approvals/$UUID"),
    )

    /** Whether [method] [route] is one of [ALLOWED]. */
    fun allows(method: String, route: String) = ALLOWED.any { (allowed, pattern) -> allowed == method && pattern.matches(route) }

    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

    private fun record(id: String) = id.also { require(SharedRules.isRecordId(it)) { "A route names a lower-case record id" } }
}
