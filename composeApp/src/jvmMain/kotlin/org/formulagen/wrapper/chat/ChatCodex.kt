package org.formulagen.wrapper.chat

import kotlinx.serialization.json.*
import org.formulagen.wrapper.providers.CodexRuntime
import org.formulagen.wrapper.providers.StdioCodexAppServer

/** Keep conversation capabilities separate from the approved workspace-task runtime. */
internal fun chatCodex(runtime: CodexRuntime, receive: (String, JsonObject) -> Unit) =
    StdioCodexAppServer(runtime,
        allowedMethods = setOf("account/read", "thread/start", "thread/resume", "turn/start", "turn/interrupt"),
        arguments = chatProcessArguments(), onNotification = receive, timeoutSeconds = 15)

/** Explicit tool restrictions apply at process startup, before any thread can run. */
internal fun chatProcessArguments(): List<String> {
    val disabled = listOf("shell_tool", "apps", "plugins", "remote_plugin", "hooks", "multi_agent", "multi_agent_v2",
        "computer_use", "browser_use", "browser_use_external", "in_app_browser", "image_generation", "view_image",
        "unified_exec", "code_mode_host", "workspace_dependencies", "worktrees", "shell_snapshot",
        "chronicle", "goals", "sleep_tool", "tool_suggest", "realtime_conversation",
        "memories", "skill_search", "skill_mcp_dependency_install", "request_permissions_tool")
    return disabled.flatMap { listOf("-c", "features.$it=false") } + listOf(
        "-c", "web_search=\"disabled\"", "-c", "mcp_servers={}", "-c", "skills.bundled.enabled=false",
        "-c", "skills.include_instructions=false", "-c", "agents.enabled=false",
        "-c", "project_doc_max_bytes=0", "-c", "sandbox_mode=\"read-only\"", "-c", "approval_policy=\"never\"",
    )
}

/**
 * A bounded companion identity is data; tool access remains controlled by native configuration.
 * A resume asks for thread metadata only: the full turn history of a long conversation would not
 * fit in one bounded protocol line, and the native record already holds the visible messages.
 */
internal fun chatThreadParams(name: String, cwd: String, threadId: String?): JsonObject = buildJsonObject {
    threadId?.let { put("threadId", it); put("excludeTurns", true) }
    put("cwd", cwd); put("sandbox", "read-only"); put("approvalPolicy", "never")
    put("baseInstructions", "You are a friendly AI companion in Hovel. Your display name is ${JsonPrimitive(name)}. " +
        "Chat warmly and clearly. Be honest that you are an AI. Keep most replies to a few sentences, suitable for spoken playback. " +
        "This is conversation mode: answer using only this conversation. Do not use tools, read files, run commands, browse, or change anything. " +
        "If asked to perform computer work, explain that workspace tasks need the separate approved task flow. " +
        "Never claim to have performed actions or accessed information you have not.")
}
