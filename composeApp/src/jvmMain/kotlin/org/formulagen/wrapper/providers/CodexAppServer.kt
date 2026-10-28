package org.formulagen.wrapper.providers

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.terminateProcessTree
import org.formulagen.wrapper.process.readBoundedLine
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Codex app-server child over bounded stdio JSON-RPC. Its policy is the allowed-method list
 * each owner passes: Providers gets [StdioCodexAppServer.ACCOUNT_METHODS], chat gets account and
 * conversation methods (chatCodex). Server requests such as tool approvals are always refused,
 * and callers never receive tokens.
 */
internal interface CodexAppServer : AutoCloseable {
    fun call(method: String, params: JsonObject = buildJsonObject {}): JsonObject
}

/** Correlate bounded stdio requests and own the lifetime of one installed Codex app-server child. */
internal class StdioCodexAppServer(
    runtime: CodexRuntime,
    private val allowedMethods: Set<String> = ACCOUNT_METHODS,
    arguments: List<String> = emptyList(),
    private val onNotification: (String, JsonObject) -> Unit = { _, _ -> },
    private val timeoutSeconds: Long = 5,
) : CodexAppServer {
    private val process = runtime.builder(arguments + listOf("app-server", "--listen", "stdio://"))
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val writer = process.outputStream.bufferedWriter()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val sequence = AtomicInteger()
    private val closed = AtomicBoolean()

    init {
        Thread.ofPlatform().daemon().name("hovel-codex-app-server").start {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = readBoundedLine(reader) ?: break
                        val message = Json.parseToJsonElement(line).jsonObject
                        val id = (message["id"] as? JsonPrimitive)?.intOrNull
                        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
                        if (message.containsKey("id") && method != null) {
                            // The host never approves tools through this transport.
                            send(buildJsonObject {
                                put("id", message.getValue("id"))
                                putJsonObject("error") { put("code", -32601); put("message", "This client does not permit tool requests") }
                            })
                            onNotification("request/refused", buildJsonObject {})
                        } else if (id != null) {
                            pending.remove(id)?.let { waiting ->
                                val result = message["result"] as? JsonObject
                                if (result != null) waiting.complete(result)
                                else waiting.completeExceptionally(CodexRequestRejected())
                            }
                        }
                        if (method != null && !message.containsKey("id")) {
                            onNotification(method, message["params"] as? JsonObject ?: buildJsonObject {})
                        }
                    }
                }
            } catch (_: Exception) { /* Raw provider output must never reach logs or the page. */ }
            finally { failPending(); onNotification("transport/closed", buildJsonObject {}) }
        }
        try {
            call("initialize", buildJsonObject {
                putJsonObject("clientInfo") { put("name", "hovel"); put("title", "Hovel"); put("version", "0.1.0") }
                put("capabilities", JsonNull)
            })
            send(buildJsonObject { put("method", "initialized") })
        } catch (error: Exception) { close(); throw error }
    }

    /** Bound each call below the web bridge deadline; failures terminate ambiguous sessions. */
    override fun call(method: String, params: JsonObject): JsonObject {
        require(method == "initialize" || method in allowedMethods)
        check(!closed.get() && process.isAlive) { "Codex app-server is unavailable" }
        val id = sequence.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        try {
            send(buildJsonObject { put("id", id); put("method", method); put("params", params) })
            return future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            // An error reply leaves the app-server usable; a timeout or a stopped process does not.
            (error.cause as? CodexRequestRejected)?.let { throw it }
            close(); throw IllegalStateException("Codex did not complete the request", error)
        } catch (error: Exception) { close(); throw IllegalStateException("Codex did not complete the request", error) }
        finally { pending.remove(id) }
    }

    /** Serialize whole JSON lines so interleaved calls cannot corrupt the transport. */
    private fun send(message: JsonObject) = synchronized(writer) {
        writer.write(message.toString()); writer.newLine(); writer.flush()
    }

    /** Release waiters immediately if the subprocess exits or its protocol breaks. */
    private fun failPending() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Codex app-server stopped")) }
        pending.clear()
    }

    /** Stop only Hovel's child. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        failPending()
        terminateProcessTree(process)
        runCatching { writer.close() }
    }

    companion object {
        /**
         * Reading the player's Codex account and the model list a team member chooses from. No sign-in
         * or sign-out (the player runs `codex login` and `codex logout`), no inference or workspace execution.
         */
        val ACCOUNT_METHODS = setOf("account/read", "model/list")
    }
}

/** Codex answered with a JSON-RPC error. Unlike a timeout or a stopped process, the app-server is still usable. */
internal class CodexRequestRejected : IllegalStateException("Codex rejected the request")
