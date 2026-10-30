package org.formulagen.wrapper.runtimes

import java.time.Instant
import kotlinx.serialization.json.*

/** The parts of Claude Code's `stream-json` output a companion turn uses. Everything else is [Other]. */
internal sealed interface ClaudeEvent {
    /**
     * `system/init`, sent at the start of every turn. A list field is its length, or null when it is
     * missing or not a list, which [ClaudeLockdown] treats as a violation.
     */
    data class Init(
        val sessionId: String?,
        val tools: Int?,
        val mcpServers: Int?,
        val skills: Int?,
        val slashCommands: Int?,
        val plugins: List<Plugin>?,
        val permissionMode: String?,
        val apiKeySource: String?,
        val agents: List<String>? = emptyList(),
        val outputStyle: String? = "default",
    ) : ClaudeEvent

    data class Plugin(val name: String?, val path: String?)

    /** More reply text. Thinking blocks and a subagent's text are not this. */
    data class TextDelta(val text: String) : ClaudeEvent

    /** `rate_limit_event`: [status] is `allowed`, `allowed_warning` or `rejected`. */
    data class RateLimit(val status: String?, val resetsAt: Instant?) : ClaudeEvent

    /** An `assistant` message marked as an API error (`authentication_failed`, `rate_limit`, `model_not_found`, …). */
    data class AssistantError(val error: String) : ClaudeEvent

    /** The turn's last message. A missing `is_error` counts as an error. */
    data class Result(
        val isError: Boolean,
        val result: String?,
        val terminalReason: String?,
        val sessionId: String?,
        val apiErrorStatus: Int?,
        val errors: List<String>,
    ) : ClaudeEvent

    data object Other : ClaudeEvent
}

internal object ClaudeStream {
    /** One NDJSON line. A line that is not a JSON object is [ClaudeEvent.Other]. */
    fun parse(line: String): ClaudeEvent {
        val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return ClaudeEvent.Other
        return when (message.text("type")) {
            "system" -> if (message.text("subtype") == "init") init(message) else ClaudeEvent.Other
            "stream_event" -> textDelta(message) ?: ClaudeEvent.Other
            "rate_limit_event" -> (message["rate_limit_info"] as? JsonObject).let { info ->
                ClaudeEvent.RateLimit(info?.text("status"), (info?.get("resetsAt") as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochSecond))
            }
            "assistant" -> message.text("error")?.let(ClaudeEvent::AssistantError) ?: ClaudeEvent.Other
            "result" -> ClaudeEvent.Result(
                isError = (message["is_error"] as? JsonPrimitive)?.booleanOrNull ?: true,
                result = message.text("result"),
                terminalReason = message.text("terminal_reason"),
                sessionId = message.text("session_id"),
                apiErrorStatus = (message["api_error_status"] as? JsonPrimitive)?.intOrNull,
                errors = (message["errors"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
            )
            else -> ClaudeEvent.Other
        }
    }

    private fun init(message: JsonObject) = ClaudeEvent.Init(
        sessionId = message.text("session_id"),
        tools = message.size("tools"),
        mcpServers = message.size("mcp_servers"),
        skills = message.size("skills"),
        slashCommands = message.size("slash_commands"),
        plugins = (message["plugins"] as? JsonArray)?.map { (it as? JsonObject).let { p -> ClaudeEvent.Plugin(p?.text("name"), p?.text("path")) } },
        permissionMode = message.text("permissionMode"),
        apiKeySource = message.text("apiKeySource"),
        agents = (message["agents"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content.orEmpty() },
        outputStyle = message.text("output_style"),
    )

    /** Only the main reply's text: `parent_tool_use_id` is set for a subagent's. */
    private fun textDelta(message: JsonObject): ClaudeEvent.TextDelta? {
        if (message["parent_tool_use_id"].let { it != null && it != JsonNull }) return null
        val event = message["event"] as? JsonObject ?: return null
        if (event.text("type") != "content_block_delta") return null
        val delta = event["delta"] as? JsonObject ?: return null
        return if (delta.text("type") == "text_delta") ClaudeEvent.TextDelta(delta.text("text").orEmpty()) else null
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.size(key: String) = (this[key] as? JsonArray)?.size
}

/** What every turn's `system/init` must show before any of its text is used. */
internal object ClaudeLockdown {
    /** The plugins Claude Code always loads from inside itself; with no tools they have nothing to act through. */
    val BUILTIN_PLUGINS = setOf("cc-plugin-agents-md", "cc-plugin-plugin-authoring")
    /** The agents Claude Code always lists; with no Task tool none can run. A custom agent is someone's configuration. */
    val BUILTIN_AGENTS = setOf("claude", "Explore", "general-purpose", "Plan")

    /** The names of the fields that break the lockdown; empty when the turn may run. Only these names are ever logged. */
    fun verify(init: ClaudeEvent.Init): Set<String> = buildSet {
        if (init.tools != 0) add("tools")
        if (init.mcpServers != 0) add("mcp_servers")
        if (init.skills != 0) add("skills")
        if (init.slashCommands != 0) add("slash_commands")
        if (init.plugins == null || init.plugins.any { it.path != "builtin" || it.name !in BUILTIN_PLUGINS }) add("plugins")
        if (init.permissionMode != "dontAsk") add("permissionMode")
        if (init.apiKeySource != "none") add("apiKeySource")
        if (init.agents == null || !BUILTIN_AGENTS.containsAll(init.agents)) add("agents")
        // A custom output style is someone's instructions, not the companion's persona.
        if (init.outputStyle != "default") add("output_style")
    }
}

/**
 * How a Claude turn ended, from the exit code, `is_error`, the assistant's error code,
 * `terminal_reason` and the rate-limit report. The result `subtype` is not used: a bad model
 * reports `success` with `is_error: true`.
 */
internal object ClaudeFailures {
    fun succeeded(exitCode: Int?, result: ClaudeEvent.Result?): Boolean =
        exitCode == 0 && result != null && !result.isError && result.terminalReason in setOf(null, "completed")

    /** A turn that did not [succeed][succeeded]; the exit code adds nothing once that is known. Anything unrecognised is [ChatFailure.Unexpected]. */
    fun classify(result: ClaudeEvent.Result?, lastAssistantError: String?, rateLimit: ClaudeEvent.RateLimit?): ChatFailure {
        val status = result?.apiErrorStatus
        return when {
            lastAssistantError == "authentication_failed" || status == 401 -> ChatFailure.SignedOut
            lastAssistantError == "rate_limit" || result?.terminalReason == "blocking_limit" || rateLimit?.status == "rejected" ->
                ChatFailure.UsageLimit(rateLimit?.takeIf { it.status == "rejected" }?.resetsAt)
            lastAssistantError == "model_not_found" -> ChatFailure.ModelUnavailable
            lastAssistantError in setOf("overloaded", "server_error") || (status != null && status >= 500) -> ChatFailure.Temporary
            result?.errors.orEmpty().any { it.startsWith("No conversation found") } -> ChatFailure.ConversationLost
            else -> ChatFailure.Unexpected
        }
    }
}
