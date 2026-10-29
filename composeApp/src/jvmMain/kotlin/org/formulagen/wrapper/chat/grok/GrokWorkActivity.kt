package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.ActivityLog
import org.formulagen.wrapper.chat.WorkPaths

/** Bounded work-turn folding; only current-session text and completed tool steps reach the page. */
internal class GrokWorkActivity(sessionId: String, folder: Path) {
    var state = GrokTurnState(sessionId)
        private set
    private val paths = WorkPaths(folder)
    private val log = ActivityLog(paths)
    private data class ToolSummary(val kind: ActivityKind = ActivityKind.OTHER, val path: String? = null)
    private val calls = LinkedHashMap<String, ToolSummary>()
    private val finished = HashSet<String>()

    /** Merge sparse ACP tool updates without rendering reasoning, raw output, provider IDs or replay. */
    fun update(params: JsonObject): Activity? {
        val update = params["update"] as? JsonObject
        val kind = update?.get("sessionUpdate")
        if (params["sessionId"] != JsonPrimitive(state.sessionId) ||
            kind !in setOf(JsonPrimitive("tool_call"), JsonPrimitive("tool_call_update"))) {
            state = state.update(params)
            return null
        }
        val id = (update?.get("toolCallId") as? JsonPrimitive)?.takeIf { it.isString && it.content.length in 1..256 }?.content
        if (id == null || calls.size + finished.size >= 256 && id !in calls && id !in finished) {
            state = state.copy(end = GrokTurnEnd.INVALID); return null
        }
        state = state.copy(accepted = true)
        if (id in finished) return null
        val previous = calls[id] ?: ToolSummary()
        val toolKind = when (update["kind"]) {
            JsonPrimitive("read") -> ActivityKind.READ
            JsonPrimitive("search") -> ActivityKind.SEARCH
            JsonPrimitive("edit") -> ActivityKind.EDIT
            JsonPrimitive("execute") -> ActivityKind.COMMAND
            null -> previous.kind
            else -> ActivityKind.OTHER
        }
        val raw = update["rawInput"] as? JsonObject
        val path = (raw?.get("file_path") as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 4_096 }?.content ?: previous.path
        calls[id] = ToolSummary(toolKind, path)
        val status = when (update["status"]) {
            JsonPrimitive("completed") -> ActivityStatus.DONE
            JsonPrimitive("failed") -> ActivityStatus.FAILED
            else -> return null
        }
        calls.remove(id); finished += id
        // Commands/results can contain secrets; activity uses a category, while approvals show exact inputs.
        val label = path?.let(paths::label) ?: when (toolKind) {
            ActivityKind.COMMAND -> "Run command"
            ActivityKind.SEARCH -> "Search project"
            ActivityKind.READ -> "Read project"
            ActivityKind.EDIT -> "Edit file"
            else -> "Project tool"
        }
        log.add(toolKind, label, status, null)
        return log.activity()
    }

    /** Only the prompt response ends a turn. */
    fun finish(result: JsonObject): GrokTurnState = state.finish(result).also { state = it }
}
