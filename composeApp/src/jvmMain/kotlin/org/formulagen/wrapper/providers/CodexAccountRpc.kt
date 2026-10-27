package org.formulagen.wrapper.providers

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.terminateProcessTree
import java.io.BufferedReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Account-only protocol seam; callers never receive tokens or arbitrary RPC access. */
internal interface CodexAccountRpc : AutoCloseable {
    fun call(method: String, params: JsonObject = buildJsonObject {}): JsonObject
    fun loginResult(loginId: String): Boolean?
}

/** Correlate bounded stdio requests and own the lifetime of one installed Codex child. */
internal class StdioCodexAccountRpc(
    runtime: CodexRuntime,
    private val allowedMethods: Set<String> = ACCOUNT_METHODS,
    arguments: List<String> = emptyList(),
    private val onNotification: (String, JsonObject) -> Unit = { _, _ -> },
    private val timeoutSeconds: Long = 5,
) : CodexAccountRpc {
    private val process = runtime.builder(arguments + listOf("app-server", "--listen", "stdio://"))
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val writer = process.outputStream.bufferedWriter()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val sequence = AtomicInteger()
    private val closed = AtomicBoolean()
    private val completion = AtomicReference<Pair<String, Boolean>?>()

    init {
        Thread.ofPlatform().daemon().name("hovel-codex-accounts").start {
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
                                else waiting.completeExceptionally(IllegalStateException("Codex account request failed"))
                            }
                        } else if (method == "account/login/completed") {
                            val params = message["params"] as? JsonObject
                            val loginId = (params?.get("loginId") as? JsonPrimitive)?.contentOrNull
                            val success = (params?.get("success") as? JsonPrimitive)?.booleanOrNull
                            if (loginId != null && success != null) completion.set(loginId to success)
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
        check(!closed.get() && process.isAlive) { "Codex account process is unavailable" }
        val id = sequence.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        try {
            send(buildJsonObject { put("id", id); put("method", method); put("params", params) })
            return future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (error: Exception) { close(); throw IllegalStateException("Codex did not complete the account request", error) }
        finally { pending.remove(id) }
    }

    /** Report only the matching login outcome, never raw notification text. */
    override fun loginResult(loginId: String): Boolean? = completion.get()?.takeIf { it.first == loginId }?.second

    /** Serialize whole JSON lines so interleaved calls cannot corrupt the transport. */
    private fun send(message: JsonObject) = synchronized(writer) {
        writer.write(message.toString()); writer.newLine(); writer.flush()
    }

    /** Release waiters immediately if the subprocess exits or its protocol breaks. */
    private fun failPending() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Codex account process stopped")) }
        pending.clear()
    }

    /** Stop only Hovel's child and cancel any callback listener it owns. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        failPending()
        terminateProcessTree(process)
        runCatching { writer.close() }
    }

    companion object {
        val ACCOUNT_METHODS = setOf("account/read", "account/login/start", "account/login/cancel", "account/logout")
    }
}

/** Cap protocol lines before allocating unbounded provider output. */
private fun readBoundedLine(reader: BufferedReader): String? {
    val line = StringBuilder()
    while (true) {
        val next = reader.read()
        if (next == -1) return line.toString().takeIf { it.isNotEmpty() }
        if (next == '\n'.code) return line.toString()
        check(line.length < 256 * 1024) { "Oversized Codex account response" }
        line.append(next.toChar())
    }
}
