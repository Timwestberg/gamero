package org.formulagen.wrapper.providers

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.terminateProcessTree
import org.formulagen.wrapper.process.readBoundedLine

/**
 * Read Claude Code's experimental get_usage control response (verified with 2.1.304). The adapter
 * is deliberately isolated: unsupported versions or absent quota data simply mean unavailable.
 * Only initialize and get_usage are sent, never a user message. Safe mode disables integrations,
 * tools and persistence; skip_behaviors avoids scanning local conversation history.
 */
internal fun readClaudeUsage(runtime: ClaudeRuntime, timeoutMillis: Long = 5_000): JsonObject? =
    readClaudeControl(runtime, ClaudeRead.USAGE, timeoutMillis)

internal enum class ClaudeRead { MODELS, USAGE }

/** One bounded, read-only control exchange. Model discovery ends after initialize. */
internal fun readClaudeControl(runtime: ClaudeRuntime, request: ClaudeRead, timeoutMillis: Long = 5_000): JsonObject? {
    val child = runtime.builder(CLAUDE_USAGE_ARGUMENTS, runtime.statusFolder())
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val result = CompletableFuture<JsonObject?>()
    val writer = child.outputStream.bufferedWriter()
    fun send(id: String, subtype: String) {
        writer.write(buildJsonObject {
            put("type", "control_request"); put("request_id", id)
            putJsonObject("request") {
                put("subtype", subtype)
                if (subtype == "get_usage") put("skip_behaviors", true)
            }
        }.toString())
        writer.newLine(); writer.flush()
    }
    val reader = Thread.ofPlatform().daemon().name("hovel-claude-metadata").start {
        try {
            child.inputStream.bufferedReader().use { input ->
                while (!result.isDone) {
                    val line = readBoundedLine(input) ?: break
                    val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                    val response = message["response"] as? JsonObject ?: continue
                    if ((message["type"] as? JsonPrimitive)?.content != "control_response") continue
                    val id = (response["request_id"] as? JsonPrimitive)?.content
                    if (id !in listOf("usage-init", "usage-read")) continue
                    if ((response["subtype"] as? JsonPrimitive)?.content != "success") {
                        result.complete(null); break
                    }
                    if (id == "usage-init" && request == ClaudeRead.USAGE) send("usage-read", "get_usage")
                    else result.complete(response["response"] as? JsonObject)
                }
            }
        } catch (_: Exception) { /* Raw runtime output never leaves this adapter. */ }
        finally { result.complete(null) }
    }
    return try {
        send("usage-init", "initialize")
        result.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (error: InterruptedException) { throw error }
    catch (_: Exception) { null }
    finally {
        result.complete(null)
        terminateProcessTree(child)
        runCatching { writer.close() }
        reader.join(1_000)
    }
}

/** No inference, project settings, hooks, tools, transcript scan, or saved session. */
internal val CLAUDE_USAGE_ARGUMENTS = listOf(
    "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
    "--safe-mode", "--disable-slash-commands", "--tools", "", "--disallowedTools", "mcp__*", "--strict-mcp-config",
    "--permission-mode", "dontAsk", "--permission-prompts", "none", "--no-session-persistence",
)
