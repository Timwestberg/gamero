package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

fun interface HovelTransport { fun post(route: String, body: JsonObject): JsonObject }

/** The server answered and refused the request (HTTP 409), so it committed nothing. Lost or failed delivery is not this. */
class HovelRejectedException : IllegalStateException("Host server returned HTTP 409")

/** Host bearer stays in native memory and is sent only to the configured server. */
class HovelHttpTransport(base: URI, ownerId: String, hostId: String, private val token: String, private val timeoutMillis: Long = 15_000, private val brainEnabled: Boolean = false) : HovelTransport {
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
    override fun post(route: String, body: JsonObject): JsonObject {
        require(route in setOf("heartbeat", "claim", "progress", "memory/claim", "memory/progress", "memory/consolidate") ||
            (brainEnabled && route in setOf(
                "brain/admit", "brain/authorize", "brain/outcome", "brain/presentation-claim", "brain/presentation")))
        val request = HttpRequest.newBuilder(URI(endpoint + route)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").header("X-Hovel-Host-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val future = client.sendAsync(request, HttpResponse.BodyHandler { BoundedBodySubscriber(16 * 1024 * 1024) })
        try {
            val response = future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (response.statusCode() == 409) throw HovelRejectedException()
            check(response.statusCode() in 200..299) { "Host server returned HTTP ${response.statusCode()}" }
            return Json.parseToJsonElement(response.body().toString(Charsets.UTF_8)).jsonObject.getValue("data").jsonObject
        } finally { if (!future.isDone) future.cancel(true) }
    }
}

/**
 * Hovel's revision protocol for one claimed item: claim with the journal's claim ID, then report each
 * state at the revision last seen. Launch decisions remain in the generic journal.
 */
abstract class HovelRevisionRemote(private val transport: HovelTransport, initial: JsonObject) : ExecutionRemote {
    private var item = initial
    protected abstract val claimRoute: String
    protected abstract val progressRoute: String
    /** The key naming the item in a claim or report: `taskId` or `jobId`. */
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

/** A user task on the task routes. */
class HovelTaskRemote(transport: HovelTransport, initial: JsonObject) : HovelRevisionRemote(transport, initial) {
    override val claimRoute = "claim"
    override val progressRoute = "progress"
    override val idKey = "taskId"
    override fun idOf(item: JsonObject) = item.getValue("request").jsonObject.getValue("id").jsonPrimitive.content
}

/**
 * A memory job on the `memory/*` routes. The server closes a job that was replaced or taken back and
 * answers 409 to its reports, so the job stops there and is never offered again.
 */
class HovelMemoryJobRemote(transport: HovelTransport, initial: JsonObject) : HovelRevisionRemote(transport, initial) {
    override val claimRoute = "memory/claim"
    override val progressRoute = "memory/progress"
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

/**
 * What this companion can run, declared on every heartbeat. The server sends follow-ups only to a computer
 * that lists `provider-input`, recalled memory only with `memory-context`, and memory jobs only with
 * `memory-extraction`.
 */
val HOVEL_HOST_FEATURES = listOf("provider-input", "memory-context", "memory-extraction")

fun hovelHeartbeat(providerState: String) = buildJsonObject {
    put("providerState", providerState)
    putJsonArray("features") { HOVEL_HOST_FEATURES.forEach(::add) }
}

/** A follow-up runs the server-built `providerInput` (earlier exchanges, then the new prompt) in place of its bare prompt. */
fun hovelExecutionRequest(task: JsonObject, hostId: String): ExecutionRequest {
    val request = task.getValue("request").jsonObject
    require(request.getValue("hostId").jsonPrimitive.content == hostId)
    require(request.getValue("mode").jsonPrimitive.content == "local") { "Cloud execution is unavailable" }
    // An explicit null or a non-string is malformed, not absent: it would otherwise run as the text "null".
    val providerInput = task.optionalText("providerInput")
    val followUp = request["followUp"]?.also { require(it is JsonObject) { "Follow-up must be an object" } }
    // The contract pairs them; one without the other would run a reply without its conversation.
    require((followUp == null) == (providerInput == null)) { "Follow-up and its provider input must arrive together" }
    val prompt = providerInput ?: request.getValue("prompt").jsonPrimitive.content
    return ExecutionRequest(request.getValue("id").jsonPrimitive.content, hostId, prompt,
        task.getValue("characterMemory").jsonPrimitive.content, recalledMemory = task.optionalText("recalledMemory"))
}

/** The kind and provider of memory job this companion runs. Others are left for a companion that can. */
fun isRunnableMemoryJob(job: JsonObject) =
    job["kind"] == JsonPrimitive("extract") && job["providerId"] == JsonPrimitive("codex")

/** A memory job runs its server-built `input` alone, with no character section, on the job's own model and effort. */
fun hovelMemoryJobRequest(job: JsonObject, hostId: String): ExecutionRequest {
    require(job.getValue("hostId").jsonPrimitive.content == hostId) { "Memory job belongs to another computer" }
    require(isRunnableMemoryJob(job)) { "This companion runs only Codex extraction jobs" }
    val choice = ProviderChoice(job.text("model"), job.text("effort"))
    require(choice.isValid()) { "Memory job model or effort is invalid" }
    return ExecutionRequest(job.text("id"), hostId, job.text("input"), extraction = choice)
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
