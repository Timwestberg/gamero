package org.formulagen.wrapper.chat.grok

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.terminateProcessTree
import org.formulagen.wrapper.process.MAX_PROTOCOL_LINE
import org.formulagen.wrapper.process.ProtocolLine
import org.formulagen.wrapper.process.readProtocolLine

/** A bounded Grok ACP connection. Launch policy and subscription eligibility belong to its owner. */
internal class GrokAcp(
    builder: ProcessBuilder,
    private val allowedMethods: Set<String> = setOf("initialize"),
    private val onNotification: (String, JsonObject) -> Unit = { _, _ -> },
    /** Reader-thread callback; answer asynchronously, once. Null rejects the request. */
    private val onRequest: ((String, JsonObject, (JsonObject?) -> Unit) -> Unit)? = null,
    private val lineLimit: Int = MAX_PROTOCOL_LINE,
    private val allowReloadResponses: Boolean = false,
) : AutoCloseable {
    private val process = builder.also { require(lineLimit > 0) }.redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val writer = process.outputStream.bufferedWriter()
    private val closed = AtomicBoolean()
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val requests = ConcurrentHashMap<JsonPrimitive, AtomicBoolean>()
    private val descendants = ConcurrentHashMap.newKeySet<ProcessHandle>()
    private val finished = CompletableFuture<Unit>()
    private val failure = AtomicReference(GrokTransportIssue.ENDED)
    private val writes = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        Thread.ofPlatform().daemon().name("hovel-grok-write").factory())
    private val observer = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("hovel-grok-children").factory())

    init {
        observer.scheduleWithFixedDelay({ observeChildren() }, 0, 100, TimeUnit.MILLISECONDS)
        Thread.ofPlatform().daemon().name("hovel-grok-read").start {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (!closed.get()) {
                        val line = readProtocolLine(reader, lineLimit) ?: break
                        if (line !is ProtocolLine.Whole) throw GrokTransportFailure(GrokTransportIssue.FRAME)
                        receive(Json.parseToJsonElement(line.text) as? JsonObject ?: throw GrokTransportFailure())
                    }
                }
            } catch (error: Exception) {
                failure.set((error as? GrokTransportFailure)?.issue ?: GrokTransportIssue.READER)
            }
            finally { close() }
        }
    }

    /** One allowlisted request; its deadline includes blocked stdin, not only the response wait. */
    fun call(method: String, params: JsonObject = buildJsonObject {}, timeout: Duration = 10.seconds,
        onSubmitted: () -> Unit = {}): JsonObject {
        require(method in allowedMethods && timeout.isPositive() && timeout.isFinite())
        val id = sequence.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        synchronized(pending) {
            check(!closed.get()) { "Grok connection has ended" }
            check(pending.size < 16) { "Too many Grok requests" }
            pending[id] = future
        }
        try {
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
            onSubmitted()
            return future.get(timeout.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            (error.cause as? GrokRequestRejected)?.let { throw it }
            close(); throw (error.cause as? GrokTransportFailure ?: GrokTransportFailure())
        } catch (error: Exception) {
            close()
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw GrokTransportFailure(if (error is java.util.concurrent.TimeoutException) GrokTransportIssue.TIMEOUT else GrokTransportIssue.CALL)
        } finally { pending.remove(id) }
    }

    /** Cancel is a notification. The turn owner must still await its terminal response or close. */
    fun cancel(sessionId: String) {
        require("session/prompt" in allowedMethods && validGrokSessionId(sessionId))
        send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "session/cancel")
            putJsonObject("params") { put("sessionId", sessionId) }
        })
    }

    /** Validate envelopes before dispatch; malformed required frames end the entire connection. */
    private fun receive(message: JsonObject) {
        if (message["jsonrpc"] != JsonPrimitive("2.0")) throw GrokTransportFailure(GrokTransportIssue.ENVELOPE)
        val method = message["method"]
        if (method != null) {
            val name = (method as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw GrokTransportFailure(GrokTransportIssue.METHOD)
            val params = message["params"] as? JsonObject ?: throw GrokTransportFailure(GrokTransportIssue.PARAMS)
            if (message.containsKey("result") || message.containsKey("error")) throw GrokTransportFailure(GrokTransportIssue.MIXED)
            if (!message.containsKey("id")) { onNotification(name, params); return }
            val id = message["id"] as? JsonPrimitive ?: throw GrokTransportFailure(GrokTransportIssue.CLIENT_ID)
            if (id == JsonNull || !(id.isString && id.content.length <= 256 || !id.isString && id.longOrNull != null))
                throw GrokTransportFailure(GrokTransportIssue.CLIENT_ID)
            val answered = AtomicBoolean()
            if (requests.size >= 16 || requests.putIfAbsent(id, answered) != null) throw GrokTransportFailure(GrokTransportIssue.CLIENT_LIMIT)
            val respond: (JsonObject?) -> Unit = { result ->
                if (answered.compareAndSet(false, true)) {
                    requests.remove(id)
                    if (!closed.get()) runCatching {
                        send(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", id)
                            if (result != null) put("result", result)
                            else putJsonObject("error") { put("code", -32601); put("message", "Unsupported client request") }
                        })
                    }
                }
            }
            if (name == "session/request_permission" && onRequest != null) onRequest.invoke(name, params, respond)
            else {
                respond(null)
                onNotification("request/refused", buildJsonObject {})
                throw GrokTransportFailure(GrokTransportIssue.CLIENT_REQUEST)
            }
            return
        }
        // Grok 1.0.70 injects watcher requests into its own stdio input.
        if (allowReloadResponses && grokReloadResponse(message)) return
        val id = (message["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: throw GrokTransportFailure(GrokTransportIssue.RESPONSE_TYPE)
        val future = pending.remove(id) ?: throw GrokTransportFailure(GrokTransportIssue.RESPONSE_ID)
        val result = message["result"] as? JsonObject
        val error = message["error"] as? JsonObject
        when {
            result != null && !message.containsKey("error") -> future.complete(result)
            error != null && !message.containsKey("result") -> {
                val code = (error["code"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                future.completeExceptionally(if (code != null) GrokRequestRejected(code) else GrokTransportFailure())
                if (code == null) throw GrokTransportFailure()
            }
            else -> { future.completeExceptionally(GrokTransportFailure(GrokTransportIssue.RESULT)); throw GrokTransportFailure(GrokTransportIssue.RESULT) }
        }
    }

    /** Queue bounded whole lines so a stalled child cannot block its caller or cancellation thread. */
    private fun send(message: JsonObject) {
        val text = message.toString()
        if (text.length > lineLimit || closed.get()) { close(); throw GrokTransportFailure() }
        try {
            writes.execute {
                if (!closed.get()) try { writer.write(text); writer.newLine(); writer.flush() }
                catch (_: Exception) { close() }
            }
        } catch (_: Exception) { close(); throw GrokTransportFailure() }
    }

    /** Retain descendants before their parent exits, for bounded local cleanup. */
    private fun observeChildren() = runCatching {
        process.descendants().use { handles -> handles.forEach { descendants.add(it) } }
    }.let { Unit }

    /** Invalidate callbacks immediately; reap the process tree off the caller's thread. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(pending) {
            pending.values.forEach { it.completeExceptionally(GrokTransportFailure(failure.get())) }
            pending.clear()
        }
        requests.values.forEach { it.set(true) }; requests.clear()
        observeChildren(); observer.shutdownNow(); writes.shutdownNow()
        Thread.ofPlatform().daemon().name("hovel-grok-close").start {
            try {
                terminateProcessTree(process, descendants.toSet())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while ((process.isAlive || descendants.any { it.isAlive }) && System.nanoTime() < deadline) Thread.sleep(20)
                check(!process.isAlive && descendants.none { it.isAlive }) { "Grok process cleanup was not confirmed" }
                finished.complete(Unit)
            } catch (error: Exception) { finished.completeExceptionally(error) }
            finally { runCatching { writer.close() } }
        }
    }

    /** A probe or teardown can await local cleanup; this does not certify cancellation of remote work. */
    fun awaitClosed(timeout: Duration = 10.seconds) { finished.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
}

/** Raw provider error messages/data never escape the transport; callers classify only verified codes. */
internal class GrokRequestRejected(val code: Int) : IllegalStateException("Grok rejected the request")

/** An ambiguous transport failure must never trigger automatic prompt replay. */
internal enum class GrokTransportIssue { ENDED, READER, PROTOCOL, CLIENT_REQUEST, LOCKDOWN, FRAME, ENVELOPE, PARAMS, RESPONSE_ID, RESULT, TIMEOUT, CALL, METHOD, MIXED, CLIENT_ID, CLIENT_LIMIT, RESPONSE_TYPE }
internal class GrokTransportFailure(val issue: GrokTransportIssue = GrokTransportIssue.PROTOCOL) :
    IllegalStateException("Grok connection did not complete the request ($issue)")

/** ACP session IDs are opaque, bounded identifiers, never paths or Hovel history IDs. */
internal fun validGrokSessionId(value: String): Boolean = value.isNotEmpty() && value.length <= 4_096 &&
    value.toByteArray(Charsets.UTF_8).size <= 4_096 && value.none(Char::isISOControl)

/** Capabilities used by both probes and future adapters; no client file or terminal methods. */
internal fun grokInitialize() = buildJsonObject {
    put("protocolVersion", 1)
    putJsonObject("clientInfo") { put("name", "hovel"); put("version", "0.1.0") }
    putJsonObject("clientCapabilities") {
        put("terminal", false)
        putJsonObject("fs") { put("readTextFile", false); put("writeTextFile", false) }
    }
}

/** Exact replies to Grok's internal skill/workflow watcher; never satisfy a Hovel request. */
internal fun grokReloadResponse(message: JsonObject): Boolean {
    if (message.keys != setOf("jsonrpc", "id", "result") || message["jsonrpc"] != JsonPrimitive("2.0")) return false
    if (message["id"] !in setOf(JsonPrimitive("skills-reload"), JsonPrimitive("workflows-reload"))) return false
    val envelope = message["result"] as? JsonObject ?: return false
    if (envelope.keys != setOf("result")) return false
    val result = envelope["result"] as? JsonObject ?: return false
    if (result.keys != setOf("reloaded")) return false
    val count = (result["reloaded"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return false
    return count in 0..1
}
