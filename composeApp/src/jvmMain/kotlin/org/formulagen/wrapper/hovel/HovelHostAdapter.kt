package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.HovelContract
import org.formulagen.wrapper.companion.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

fun interface HovelTransport { fun post(route: String, body: JsonObject): JsonObject }

/**
 * A native call by method: what the conversation routes need beside [HovelTransport.post]. Answers
 * the response's `data` object; [body] null sends none (`GET`).
 */
fun interface NativeExchange { fun exchange(method: String, route: String, body: JsonObject?): JsonObject }

/**
 * The server answered and refused the request (HTTP 409), so it committed nothing. Lost or failed delivery is not
 * this. [code] is the refusal's code when its body names one, such as the contract's `update-required`.
 */
class HovelRejectedException(val code: String? = null) : IllegalStateException("Host server returned HTTP 409")

/**
 * The server answered with [status], neither success nor a 409 refusal; [code] is the body's code when it names one,
 * such as a `hovelContractError`'s. A 400, 403 or 404 is [final]: the request names something the server does not
 * have, or this computer may not use, and sending it again cannot help. A [serverOlder] refusal never is.
 */
class HovelStatusException(val status: Int, val code: String? = null) : IllegalStateException("Host server returned HTTP $status") {
    /** A 400 `unsupported-contract`: the service is older than this build's contract, so the work waits for it. */
    val serverOlder: Boolean get() = status == 400 && code == UNSUPPORTED_CONTRACT
    val final: Boolean get() = (status == 400 || status == 403 || status == 404) && !serverOlder

    companion object {
        /** The contract's refusal of a client newer than the server. */
        const val UNSUPPORTED_CONTRACT = "unsupported-contract"
    }
}

/**
 * Host bearer stays in native memory and is sent only to the configured server. Every request names
 * this build's companion contract ([HovelContract.HEADER]).
 */
class HovelHttpTransport(base: URI, ownerId: String, hostId: String, private val token: String, private val timeoutMillis: Long = 15_000) : HovelTransport, NativeExchange {
    private val endpoint: String
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    init {
        require(UUID.fromString(ownerId).toString() == ownerId && UUID.fromString(hostId).toString() == hostId)
        require(token.matches(Regex("[0-9a-f]{64}")))
        require(base.userInfo == null && base.query == null && base.fragment == null)
        require(base.scheme == "https" || (base.scheme == "http" && base.host in setOf("localhost", "127.0.0.1", "[::1]")))
        endpoint = base.toASCIIString().trimEnd('/') + "/native/$ownerId/$hostId/"
    }
    override fun post(route: String, body: JsonObject): JsonObject = exchange("POST", route, body)

    override fun exchange(method: String, route: String, body: JsonObject?): JsonObject {
        require(NativeRoute.allows(method, route)) { "Route not allowed: $method $route" }
        require((method == "GET") == (body == null))
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()
        val request = builder(route).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .method(method, publisher).build()
        val future = client.sendAsync(request, HttpResponse.BodyHandler { BoundedBodySubscriber(16 * 1024 * 1024) })
        try {
            val response = future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (response.statusCode() == 409) throw HovelRejectedException(refusalCode(response.body()))
            if (response.statusCode() !in 200..299) throw HovelStatusException(response.statusCode(), refusalCode(response.body()))
            return Json.parseToJsonElement(response.body().toString(Charsets.UTF_8)).jsonObject.getValue("data").jsonObject
        } finally { if (!future.isDone) future.cancel(true) }
    }

    /**
     * Opens the host's server-sent events ([NativeRoute.STREAM]): connected within the connect
     * timeout, then read line by line for as long as the server keeps it open. Closing the answer
     * ends the connection.
     */
    fun openStream(): HovelEventLines {
        val request = builder(NativeRoute.STREAM.path()).header("Accept", "text/event-stream").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() !in 200..299) {
            // A refusal body is a small JSON object, such as a `hovelContractError`.
            val body = response.body().use { it.limit(64).toList().joinToString("\n").take(16 * 1024) }.toByteArray()
            if (response.statusCode() == 409) throw HovelRejectedException(refusalCode(body))
            throw HovelStatusException(response.statusCode(), refusalCode(body))
        }
        val lines = response.body()
        return object : HovelEventLines {
            override fun lines(): Sequence<String> = lines.iterator().asSequence()
            override fun close() = lines.close()
        }
    }

    private fun builder(route: String): HttpRequest.Builder = HttpRequest.newBuilder(URI(endpoint + route))
        .header("X-Hovel-Host-Token", token).header(HovelContract.HEADER, HovelContract.CURRENT.toString())

    /** A refusal body's `code`, when it names one. */
    private fun refusalCode(body: ByteArray): String? =
        runCatching { (Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject["code"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
}

/** An open event stream: its raw lines, read until the server ends it or [close] does. */
interface HovelEventLines : AutoCloseable {
    fun lines(): Sequence<String>
}

/**
 * Hovel's revision protocol for one claimed item, such as a memory job: claim with the journal's claim ID, then report
 * each state at the revision last seen. Launch decisions remain in the generic journal.
 */
abstract class HovelRevisionRemote(private val transport: HovelTransport, initial: JsonObject) : ExecutionRemote {
    private var item = initial
    protected abstract val claimRoute: String
    protected abstract val progressRoute: String
    /** The key naming the item in a claim or report, such as `jobId`. */
    protected abstract val idKey: String
    protected abstract fun idOf(item: JsonObject): String

    override fun claim(record: ExecutionRecord) {
        item = transport.post(claimRoute, buildJsonObject {
            put(idKey, record.request.id); put("claimId", record.claimId)
        })
        require(idOf(item) == record.request.id)
        require(item.getValue("progress").jsonObject.getValue("claimId").jsonPrimitive.content == record.claimId)
    }
    override fun running(record: ExecutionRecord) = progress(record, "running", null)
    override fun publish(record: ExecutionRecord, preview: String) {
        val state = when (record.phase) {
            ExecutionPhase.SUCCEEDED -> "succeeded"
            ExecutionPhase.FAILED -> "failed"
            ExecutionPhase.RECONCILIATION_REQUIRED -> "reconciliation-required"
            else -> error("Only durable outcomes can be published")
        }
        progress(record, state, preview)
    }
    protected fun progress(record: ExecutionRecord, state: String, text: String?) {
        val current = item.getValue("progress").jsonObject
        require(current.getValue("claimId").jsonPrimitive.content == record.claimId) { "Server claim differs; reconcile manually" }
        val payload = buildJsonObject {
            put("state", state); put("claimId", record.claimId)
            if (text != null) put(if (state == "succeeded") "result" else "error", text)
        }
        item = transport.post(progressRoute, buildJsonObject {
            put(idKey, record.request.id)
            put("expectedRevision", item.getValue("revision").jsonPrimitive.int)
            put("progress", payload)
        })
    }
}

/**
 * A memory job on the `memory/claim` and `memory/progress` routes. The server closes a job that was replaced or taken back and
 * answers 409 to its reports, so the job stops there and is never offered again.
 */
class HovelMemoryJobRemote(transport: HovelTransport, initial: JsonObject) : HovelRevisionRemote(transport, initial) {
    override val claimRoute = NativeRoute.MEMORY_CLAIM.path()
    override val progressRoute = NativeRoute.MEMORY_PROGRESS.path()
    override val idKey = "jobId"
    override fun idOf(item: JsonObject) = item.getValue("id").jsonPrimitive.content

    /** A successful result longer than the server takes is reported failed, never cut short into broken JSON. */
    override fun publish(record: ExecutionRecord, preview: String) {
        val length = record.result.orEmpty().length
        if (record.phase == ExecutionPhase.SUCCEEDED && length > HOVEL_MEMORY_JOB_OUTPUT_CHARS) {
            progress(record, "failed", "Extraction output is $length characters; the limit is $HOVEL_MEMORY_JOB_OUTPUT_CHARS.")
        } else super.publish(record, preview)
    }
}

/** event-contracts `HOVEL_MEMORY_LIMITS.jobOutput`: the longest successful result a memory job reports. */
const val HOVEL_MEMORY_JOB_OUTPUT_CHARS = 65_536

private val EXTRACT_KIND = JsonPrimitive("extract")
private val CODEX_RUNTIME = JsonPrimitive("codex")

/**
 * Whether a memory job is this companion's to run. A job naming another `kind` or host-bridge
 * `runtimeId` is left for a companion that runs it. A job missing either field is not someone else's:
 * it is offered, refused by [hovelMemoryJobRequest] and shown as unreadable, so a renamed field
 * asks for an update instead of being skipped in silence.
 */
fun isRunnableMemoryJob(job: JsonObject) =
    job["kind"].let { it == null || it == EXTRACT_KIND } && job["runtimeId"].let { it == null || it == CODEX_RUNTIME }

/**
 * A memory job runs its server-built `input` as it is, on the job's own model and effort. It is checked here as the journal would check it, so a job this version cannot run is refused
 * before it is offered, never left failing quietly on the worker.
 */
fun hovelMemoryJobRequest(job: JsonObject, hostId: String): ExecutionRequest {
    require(job.getValue("hostId").jsonPrimitive.content == hostId) { "Memory job belongs to another computer" }
    require(job["kind"] == EXTRACT_KIND && job["runtimeId"] == CODEX_RUNTIME) { "This companion runs only Codex extraction jobs" }
    val id = job.text("id").also { require(UUID.fromString(it).toString() == it) { "Memory job ID must be a canonical UUID" } }
    val input = job.text("input").also {
        require(it.isNotBlank() && it.length <= ExecutionRequest.MAX_PROMPT_CHARS) { "Memory job input is empty or too long" }
    }
    return ExecutionRequest(id, hostId, input, ProviderChoice(job.text("model"), job.text("effort")))
}

private fun JsonObject.text(key: String): String = optionalText(key) ?: error("$key is missing")

/** A string field, or null when absent. An explicit null or a non-string is malformed. */
private fun JsonObject.optionalText(key: String): String? = this[key]?.let {
    require(it is JsonPrimitive && it.isString) { "$key must be text" }
    it.content
}

/** Complete-body timeout belongs to the exchange future; this subscriber bounds memory. */
private class BoundedBodySubscriber(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = java.util.concurrent.CompletableFuture<ByteArray>()
    private val bytes = java.io.ByteArrayOutputStream()
    private var subscription: java.util.concurrent.Flow.Subscription? = null
    override fun getBody(): java.util.concurrent.CompletionStage<ByteArray> = result
    override fun onSubscribe(value: java.util.concurrent.Flow.Subscription) {
        subscription = value
        value.request(Long.MAX_VALUE)
    }
    override fun onNext(items: List<java.nio.ByteBuffer>) {
        try {
            for (item in items) {
                check(item.remaining() <= limit - bytes.size()) { "Host response exceeds limit" }
                val chunk = ByteArray(item.remaining())
                item.get(chunk)
                bytes.write(chunk)
            }
        } catch (error: Exception) {
            subscription?.cancel()
            result.completeExceptionally(error)
        }
    }
    override fun onError(error: Throwable) { result.completeExceptionally(error) }
    override fun onComplete() { result.complete(bytes.toByteArray()) }
}
