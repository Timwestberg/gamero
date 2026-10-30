package org.formulagen.wrapper.hovel

import org.formulagen.wrapper.bridge.SharedRules

/**
 * Every route a registered Mac calls, relative to `/v1/hovel/native/:ownerId/:hostId`
 * ([HovelHttpTransport] adds that base and the `x-hovel-host-token`): one table of method, path template
 * and builder, which also decides what the transport allows. Conversation routes mirror event-contracts
 * `HOVEL_COMPANION_ROUTES.native`; each payload is named by its `hovel/companions` schema.
 */
internal enum class NativeRoute(val method: String, val template: String) {
    /** The hovel heartbeat body (`providerState`, `features`) plus `computerHeartbeat`'s directory and usage. */
    HEARTBEAT("POST", "heartbeat"),

    /** Server-sent events whose payload is a `hostStreamEvent`. */
    STREAM("GET", "stream"),

    /** `claimRequest` → `claimResponse`. */
    CONVERSATION_CLAIM("POST", "conversations/claim"),

    /** `messageSend` → `conversationMessage`, queued: a heard voice turn, as the owner's message, in this Mac's projects only. */
    MESSAGE_SEND("POST", "conversations/:conversationId/messages"),

    /** `messageCheckpointRequest` → the stored record. */
    MESSAGE_CHECKPOINT("POST", "messages/:messageId/checkpoints"),

    /** `replyDeltaRequest`, relayed and never stored. */
    MESSAGE_DELTA("POST", "messages/:messageId/deltas"),

    /** `messageProgress` → `conversationMessage`. */
    MESSAGE_PROGRESS("POST", "messages/:messageId/progress"),

    /** → `companionProject`, with its folders and revision. */
    PROJECT("GET", "projects/:projectId"),

    /** `projectFoldersSave` → `companionProject`. */
    PROJECT_FOLDERS("PUT", "projects/:projectId/folders"),

    /** → `agentApproval`: the winner after `approval.answered`, or after the Mac's own close was refused. */
    APPROVAL("GET", "approvals/:approvalId"),

    /** → `approvalDeviceList`: the phones this Mac confirmed. */
    DEVICES("GET", "devices"),

    /** `devicePairRequest` → `approvalDevice`, unchanged: the phone a typed code names, so the window asks first. */
    DEVICE_LOOKUP("POST", "devices/lookup"),

    /** `devicePairRequest` → `approvalDevice`: once the person confirmed it in "Pair a phone…", that phone is paired. */
    DEVICE_PAIR("POST", "devices/pair"),

    /** → `approvalDevice`: this Mac lets one phone go. */
    DEVICE_UNPAIR("POST", "devices/:deviceId/unpair"),

    /** `?cursor=` → `artifactTextPage`: a message input's whole text, page by page. */
    ARTIFACT_TEXT("GET", "artifacts/:artifactId/text"),

    /** Hovel tasks, until step E removes them with the web task UI. */
    TASK_CLAIM("POST", "claim"),
    TASK_PROGRESS("POST", "progress"),

    /** Memory jobs. */
    MEMORY_CLAIM("POST", "memory/claim"),
    MEMORY_PROGRESS("POST", "memory/progress"),
    MEMORY_CONSOLIDATE("POST", "memory/consolidate");

    private val segments: List<String> get() = template.split('/')

    /** The route with its record ids filled in, in the template's order, and for an artifact's text its page [cursor]. */
    fun path(vararg ids: String, cursor: Int? = null): String {
        val parameters = ids.iterator()
        val path = segments.joinToString("/") { segment ->
            if (!segment.startsWith(":")) segment
            else parameters.next().also { require(SharedRules.isRecordId(it)) { "A route names a lower-case record id" } }
        }
        require(!parameters.hasNext()) { "Too many route parameters" }
        require(cursor == null || (this == ARTIFACT_TEXT && cursor >= 0)) { "Only an artifact's text pages" }
        return if (cursor == null) path else "$path?cursor=$cursor"
    }

    /** What [path] can build: record ids in place of the parameters, and an artifact's page cursor. */
    private val pattern by lazy {
        Regex(segments.joinToString("/") { if (it.startsWith(":")) UUID_PATTERN else Regex.escape(it) } +
            if (template.endsWith("/text")) "(\\?cursor=[0-9]{1,9})?" else "")
    }

    companion object {
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

        /** Whether [method] [route] is one of the table's routes. */
        fun allows(method: String, route: String): Boolean = entries.any { it.method == method && it.pattern.matches(route) }
    }
}
