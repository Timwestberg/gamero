package org.formulagen.wrapper.chat.claude

import java.nio.file.Path
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalDecision
import org.formulagen.wrapper.approvals.ApprovalKind
import org.formulagen.wrapper.approvals.ApprovalRequest
import org.formulagen.wrapper.approvals.ApprovalSubject
import org.formulagen.wrapper.approvals.displayPath
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.util.text

/**
 * One `can_use_tool` request from a working Claude Code process: what [toolName] wants to do with
 * [input], what Claude Code itself says about it ([description], [blockedPath], [mcpServer]), and
 * the grants it would make for the session ([suggestions], `permission_suggestions` as sent).
 */
internal data class ToolPermission(
    val requestId: String,
    val toolName: String,
    val displayName: String?,
    val input: JsonObject,
    val description: String?,
    val blockedPath: String?,
    val mcpServer: String?,
    val suggestions: JsonArray,
    val toolUseId: String?,
)

/** A control message from Claude Code, as [ClaudeControl.decode] reads it. */
internal sealed interface ClaudeControlMessage {
    /** A permission request the native dialog can show. */
    data class CanUseTool(val permission: ToolPermission) : ClaudeControlMessage
    /** A permission request in a form Hovel cannot show: answered with a denial. */
    data class Unreadable(val requestId: String, val toolUseId: String?) : ClaudeControlMessage
    /** Any other request: answered with an error. */
    data class Unsupported(val requestId: String, val subtype: String?) : ClaudeControlMessage
    /** Claude Code withdrew request [requestId] (its dialog closes, as a denial). */
    data class Cancel(val requestId: String) : ClaudeControlMessage
    /** Claude Code's answer to a request of Hovel's ([ClaudeControl.initialize], [ClaudeControl.interrupt]). */
    data class Response(val requestId: String, val success: Boolean, val response: JsonObject?) : ClaudeControlMessage
}

/**
 * Claude Code's host control protocol over stdin/stdout, as `--permission-prompt-tool stdio` speaks
 * it (abilities spike, 2026-10-28): Hovel sends `initialize` first; Claude Code asks `can_use_tool`
 * for anything the player's settings do not already allow, and Hovel answers each with exactly the
 * shapes Claude Code's own host sends. A grant "for this conversation" applies Claude Code's own
 * suggestions with every destination forced to `session`, so nothing is ever written to the
 * player's settings files. Every encoder returns one NDJSON line.
 */
internal object ClaudeControl {
    /** Why a denied tool did not run, as Claude Code passes it to the model. */
    const val DENIED = "The player chose Deny in Hovel for this action."
    /** Why an unreadable request was denied. */
    const val UNREADABLE = "Hovel could not show this request to the player, so it was denied."

    fun initialize(requestId: String): String = request(requestId) { put("subtype", "initialize") }

    /** Stop the turn in progress; the process and its session grants stay. */
    fun interrupt(requestId: String): String = request(requestId) { put("subtype", "interrupt") }

    /** A control message, or null for any other line (or one without a request id to answer). */
    fun decode(message: JsonObject): ClaudeControlMessage? = when (message.text("type")) {
        "control_request" -> {
            val requestId = message.text("request_id")
            val request = message["request"] as? JsonObject
            when {
                requestId == null -> null
                request?.text("subtype") != "can_use_tool" -> ClaudeControlMessage.Unsupported(requestId, request?.text("subtype"))
                else -> permission(requestId, request)?.let(ClaudeControlMessage::CanUseTool)
                    ?: ClaudeControlMessage.Unreadable(requestId, request.text("tool_use_id"))
            }
        }
        "control_cancel_request" -> message.text("request_id")?.let(ClaudeControlMessage::Cancel)
        "control_response" -> (message["response"] as? JsonObject)?.let { response ->
            response.text("request_id")?.let { ClaudeControlMessage.Response(it, response.text("subtype") == "success", response["response"] as? JsonObject) }
        }
        else -> null
    }

    /** The answer for [decision]; a session grant with nothing to grant is answered as allow once. */
    fun answer(permission: ToolPermission, decision: ApprovalDecision): String = when (decision) {
        ApprovalDecision.ALLOW_ONCE -> allowOnce(permission)
        ApprovalDecision.ALLOW_FOR_CONVERSATION -> if (permission.suggestions.isEmpty()) allowOnce(permission) else allowForSession(permission)
        ApprovalDecision.DENY -> deny(permission.requestId, permission.toolUseId)
    }

    fun allowOnce(permission: ToolPermission): String = success(permission.requestId) {
        put("behavior", "allow"); put("updatedInput", permission.input)
        permission.toolUseId?.let { put("toolUseID", it) }
        put("decisionClassification", "user_temporary")
    }

    /** Allow, and apply Claude Code's own suggestions for the rest of this process's session only. */
    fun allowForSession(permission: ToolPermission): String = success(permission.requestId) {
        put("behavior", "allow"); put("updatedInput", permission.input)
        permission.toolUseId?.let { put("toolUseID", it) }
        put("decisionClassification", "user_permanent")
        put("updatedPermissions", sessionOnly(permission.suggestions))
    }

    fun deny(requestId: String, toolUseId: String?, message: String = DENIED): String = success(requestId) {
        put("behavior", "deny"); put("message", message)
        toolUseId?.let { put("toolUseID", it) }
        put("decisionClassification", "user_reject")
    }

    /** The answer to a request Hovel does not handle. */
    fun error(requestId: String, message: String = "Hovel does not handle this request."): String = line {
        put("type", "control_response")
        putJsonObject("response") { put("subtype", "error"); put("request_id", requestId); put("error", message) }
    }

    /** Every suggestion as sent, with its `destination` replaced by `session`. */
    fun sessionOnly(suggestions: JsonArray): JsonArray = JsonArray(suggestions.map { suggestion ->
        (suggestion as? JsonObject)?.let { JsonObject(it + ("destination" to JsonPrimitive("session"))) } ?: suggestion
    })

    /**
     * What the native dialog shows for [permission], by a member of [companionName] working in
     * [folder]: exactly what Claude Code asks, the command verbatim and every path in full (relative
     * paths resolved against the folder). The page's summary is worked out from it separately.
     */
    fun approvalRequest(permission: ToolPermission, companionName: String, member: TeamMember, folder: Path, lease: String? = null): ApprovalRequest {
        val input = permission.input
        fun path(raw: String) = runCatching { Path.of(raw) }.getOrNull()?.let { if (it.isAbsolute) it else folder.resolve(it) }?.normalize()
        val subject = when (kindOf(permission.toolName, permission.mcpServer)) {
            ApprovalKind.EDIT -> ApprovalSubject.Edit(if (permission.toolName == "Write") "Write" else "Edit",
                listOfNotNull((input.text("file_path") ?: input.text("notebook_path"))?.let(::path)), editPreview(permission.toolName, input))
            ApprovalKind.COMMAND -> ApprovalSubject.Command(input.text("command").orEmpty(), cwd = null,
                needs = permission.blockedPath?.let(::path), reason = permission.description)
            ApprovalKind.CONNECTOR -> connectorLabel(permission.toolName).split(" · ", limit = 2).let { parts ->
                ApprovalSubject.Connector(permission.mcpServer ?: parts.first(), parts.getOrElse(1) { permission.toolName }, input.toString())
            }
            ApprovalKind.OTHER -> ApprovalSubject.Other(permission.displayName ?: permission.toolName, permission.description ?: input.toString())
        }
        return ApprovalRequest(companionName, member, folder, subject, sessionLabel(permission.suggestions, folder), lease)
    }

    /** Edits, commands and connectors as the dialog groups them; a tool of an MCP server is a connector. */
    fun kindOf(toolName: String, mcpServer: String? = null): ApprovalKind = when {
        toolName in EDIT_TOOLS -> ApprovalKind.EDIT
        toolName == "Bash" -> ApprovalKind.COMMAND
        toolName.startsWith("mcp__") || mcpServer != null -> ApprovalKind.CONNECTOR
        else -> ApprovalKind.OTHER
    }

    /**
     * The "for this conversation" button, named exactly as Claude Code means the grant: accept-edits
     * is "all edits in <folder>", a command rule is that command verbatim, a connector rule is
     * `server · tool`. A directory inside the folder adds nothing; one outside it is named in full.
     * Null when there is nothing to grant, or any suggestion is of a kind Hovel cannot describe, so
     * no such button is offered.
     */
    fun sessionLabel(suggestions: JsonArray, folder: Path): String? {
        if (suggestions.isEmpty()) return null
        val bases = listOfNotNull(folder.toAbsolutePath().normalize(), runCatching { folder.toRealPath() }.getOrNull())
        val parts = mutableListOf<String>()
        for (suggestion in suggestions) {
            val grant = suggestion as? JsonObject ?: return null
            when (grant.text("type")) {
                "setMode" -> parts += if (grant.text("mode") == "acceptEdits") "all edits in ${folder.fileName ?: folder}" else return null
                "addRules" -> {
                    if (grant.text("behavior") != "allow") return null
                    for (rule in grant["rules"] as? JsonArray ?: return null) {
                        val r = rule as? JsonObject ?: return null
                        val tool = r.text("toolName") ?: return null
                        val content = r.text("ruleContent")
                        parts += when {
                            tool == "Bash" && content != null -> "`${content.removeSuffix(":*")}${if (content.endsWith(":*")) " …" else ""}`"
                            tool.startsWith("mcp__") -> connectorLabel(tool)
                            content != null -> "$tool($content)"
                            else -> tool
                        }
                    }
                }
                "addDirectories" -> for (directory in grant["directories"] as? JsonArray ?: return null) {
                    // The project folder and anything in it are already the working folder; only one outside it is named.
                    val raw = (directory as? JsonPrimitive)?.contentOrNull ?: return null
                    val path = runCatching { Path.of(raw) }.getOrNull()?.takeIf { it.isAbsolute }?.normalize() ?: return null
                    if (bases.none { path.startsWith(it) }) parts += "access to ${displayPath(path)}"
                }
                else -> return null
            }
        }
        if (parts.isEmpty()) return null
        return "Allow ${parts.distinct().joinToString(" and ")} for this conversation"
    }

    /** `mcp__server__tool` as `server · tool`. */
    fun connectorLabel(toolName: String): String {
        val rest = toolName.removePrefix("mcp__")
        return if ("__" in rest) "${rest.substringBefore("__")} · ${rest.substringAfter("__")}" else rest
    }

    private fun permission(requestId: String, request: JsonObject): ToolPermission? {
        val toolName = request.text("tool_name")?.takeIf { it.isNotBlank() } ?: return null
        val input = request["input"] as? JsonObject ?: return null
        val suggestions = when (val value = request["permission_suggestions"]) { null, JsonNull -> JsonArray(emptyList()); is JsonArray -> value; else -> return null }
        return ToolPermission(requestId, toolName, request.text("display_name"), input, request.text("description"),
            request.text("blocked_path"), request.text("mcp_server"), suggestions, request.text("tool_use_id"))
    }

    private fun editPreview(tool: String, input: JsonObject): String = when (tool) {
        "Edit" -> listOfNotNull(input.text("old_string")?.let { "- $it" }, input.text("new_string")?.let { "+ $it" }).joinToString("\n")
        "MultiEdit" -> "${(input["edits"] as? JsonArray)?.size ?: 0} changes"
        "Write" -> input.text("content")?.lines()?.let { "${it.size} lines" }.orEmpty()
        else -> input.text("new_source")?.let { "+ $it" }.orEmpty()
    }

    private fun request(requestId: String, body: JsonObjectBuilder.() -> Unit) = line {
        put("type", "control_request"); put("request_id", requestId); putJsonObject("request", body)
    }

    private fun success(requestId: String, body: JsonObjectBuilder.() -> Unit) = line {
        put("type", "control_response")
        putJsonObject("response") { put("subtype", "success"); put("request_id", requestId); putJsonObject("response", body) }
    }

    private fun line(body: JsonObjectBuilder.() -> Unit) = buildJsonObject(body).toString()

    private val EDIT_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")
}
