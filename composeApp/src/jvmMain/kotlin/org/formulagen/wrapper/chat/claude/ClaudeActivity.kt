package org.formulagen.wrapper.chat.claude

import java.nio.file.Path
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.Activity
import org.formulagen.wrapper.bridge.ActivityKind
import org.formulagen.wrapper.bridge.ActivityStatus
import org.formulagen.wrapper.chat.ActivityLog
import org.formulagen.wrapper.chat.WorkPaths
import org.formulagen.wrapper.util.text

/**
 * A working Claude turn's activity, from its `stream-json` lines (abilities spike, 2026-10-28):
 * an `assistant` message's `tool_use {id, name, input}` opens a step, the `user` message carrying
 * its `tool_result` (with `tool_use_result` and `tool_result_meta[].permission_decision`) finishes
 * it, and `result.permission_denials` adds any denial no result reported. A subagent's own tool
 * uses (`parent_tool_use_id` set) are its business, not steps. A tool still running when the turn
 * ends is no step: only finished work is shown. Feed every line to [receive].
 */
internal class ClaudeActivity(folder: Path) {
    private val paths = WorkPaths(folder)
    private val log = ActivityLog(paths)
    /** Tool uses seen but not finished, by id. */
    private val open = LinkedHashMap<String, Pair<String, JsonObject>>()
    private val finished = mutableSetOf<String>()

    /**
     * Claude Code denied a tool because it needed the player's approval, without asking Hovel
     * (`system/permission_denied` saying the permission "needs approval" or "haven't granted it
     * yet"): the permission prompt never reached the host. A denial by the player's own rules says
     * neither, and does not count.
     */
    var deniedWithoutAsking = false
        private set

    fun activity(): Activity = log.activity()

    /** A step Hovel records itself, such as a request it could not show the player. */
    fun note(kind: ActivityKind, label: String, status: ActivityStatus, detail: String?) = log.add(kind, label, status, detail)

    /** Apply one line; true when it added a step. */
    fun receive(message: JsonObject): Boolean {
        val type = message.text("type")
        if (type == "system" && message.text("subtype") == "permission_denied") {
            val why = message.text("message").orEmpty()
            if ("needs approval" in why || "haven't granted" in why) deniedWithoutAsking = true
            return false
        }
        if (message["parent_tool_use_id"].let { it != null && it != JsonNull }) return false
        return when (type) {
            "assistant" -> { content(message).filter { it.text("type") == "tool_use" }.forEach { use ->
                val id = use.text("id") ?: return@forEach
                if (id !in finished) open.putIfAbsent(id, (use.text("name") ?: "tool") to (use["input"] as? JsonObject ?: JsonObject(emptyMap())))
            }; false }
            "user" -> content(message).filter { it.text("type") == "tool_result" }.map { result -> finish(message, result) }.any { it }
            "result" -> (message["permission_denials"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { denial ->
                val id = denial.text("tool_use_id") ?: return@map false
                if (id in finished) return@map false
                finished += id; open.remove(id)
                step(denial.text("tool_name") ?: "tool", denial["tool_input"] as? JsonObject ?: JsonObject(emptyMap()), ActivityStatus.DENIED, null)
                true
            }.any { it }
            else -> false
        }
    }

    private fun finish(message: JsonObject, result: JsonObject): Boolean {
        val id = result.text("tool_use_id") ?: return false
        if (!finished.add(id)) return false
        val (name, input) = open.remove(id) ?: return false
        val meta = (message["tool_result_meta"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.text("id") == id }
        val decision = (meta?.get("permission_decision") as? JsonObject)?.text("decision")
        val denied = decision == "reject" || meta?.text("non_execution_kind") in setOf("user-rejected", "permission-rule")
        val failed = (result["is_error"] as? JsonPrimitive)?.booleanOrNull == true
        val status = when { denied -> ActivityStatus.DENIED; failed -> ActivityStatus.FAILED; else -> ActivityStatus.DONE }
        val detail = if (status == ActivityStatus.DONE) outcome(name, message["tool_use_result"], result) else text(result["content"])
        step(name, input, status, detail)
        return true
    }

    private fun step(name: String, input: JsonObject, status: ActivityStatus, detail: String?) =
        log.add(kindOf(name), label(name, input), status, detail)

    /** What a finished tool produced, shortly: lines read, lines changed, a command's output. */
    private fun outcome(name: String, use: JsonElement?, result: JsonObject): String? {
        val details = use as? JsonObject
        return when (name) {
            "Read" -> ((details?.get("file") as? JsonObject)?.get("numLines") as? JsonPrimitive)?.intOrNull?.let { "$it lines" }
            "Edit", "MultiEdit", "Write" -> (details?.get("structuredPatch") as? JsonArray)?.let(::changedLines)
            "Bash" -> details?.let { listOfNotNull(it.text("stdout"), it.text("stderr")).filter(String::isNotBlank).joinToString("\n") }
                ?.ifBlank { "(no output)" }
            else -> null
        } ?: text(result["content"])
    }

    /** "+2 -1" from Claude Code's structured patch. */
    private fun changedLines(patch: JsonArray): String {
        val lines = patch.mapNotNull { it as? JsonObject }.flatMap { (it["lines"] as? JsonArray).orEmpty().mapNotNull { line -> (line as? JsonPrimitive)?.contentOrNull } }
        return "+${lines.count { it.startsWith("+") }} -${lines.count { it.startsWith("-") }}"
    }

    private fun label(name: String, input: JsonObject): String {
        val file = input.text("file_path") ?: input.text("notebook_path")
        return when {
            file != null -> paths.label(file)
            name == "Bash" -> paths.scrub(input.text("command").orEmpty())
            name == "Glob" || name == "Grep" -> listOfNotNull(input.text("pattern"), input.text("path")?.let { "in ${paths.label(it)}" }).joinToString(" ")
            name == "LS" -> paths.label(input.text("path") ?: ".")
            name.startsWith("mcp__") -> ClaudeControl.connectorLabel(name)
            name == "WebFetch" -> input.text("url").orEmpty()
            name == "WebSearch" -> input.text("query").orEmpty()
            else -> input.text("description")?.let { "$name: $it" } ?: name
        }
    }

    private fun content(message: JsonObject): List<JsonObject> =
        ((message["message"] as? JsonObject)?.get("content") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** A tool result's text: a string, or its text blocks. */
    private fun text(content: JsonElement?): String? = when (content) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { block -> block.text("type") == "text" }?.text("text") }.joinToString("\n")
        else -> null
    }?.takeIf { it.isNotBlank() }

    companion object {
        /** Reading, searching, editing, commands, connectors, the web, or anything else. */
        fun kindOf(name: String): ActivityKind = when {
            name in setOf("Read", "NotebookRead") -> ActivityKind.READ
            name in setOf("Glob", "Grep", "LS") -> ActivityKind.SEARCH
            name in setOf("Edit", "Write", "MultiEdit", "NotebookEdit") -> ActivityKind.EDIT
            name in setOf("Bash", "BashOutput", "KillBash", "KillShell") -> ActivityKind.COMMAND
            name.startsWith("mcp__") -> ActivityKind.CONNECTOR
            name in setOf("WebFetch", "WebSearch") -> ActivityKind.WEB
            else -> ActivityKind.OTHER
        }
    }
}
