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
        require(route in setOf("heartbeat", "claim", "progress") || (brainEnabled && route in setOf(
            "brain/admit", "brain/authorize", "brain/outcome", "brain/presentation-claim", "brain/presentation")))
        val request = HttpRequest.newBuilder(URI(endpoint + route)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").header("X-Hovel-Host-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val future = client.sendAsync(request, HttpResponse.BodyHandler { BoundedBodySubscriber(16 * 1024 * 1024) })
        try {
            val response = future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            check(response.statusCode() in 200..299) { "Host server returned HTTP ${response.statusCode()}" }
            return Json.parseToJsonElement(response.body().toString(Charsets.UTF_8)).jsonObject.getValue("data").jsonObject
        } finally { if (!future.isDone) future.cancel(true) }
    }
}

/** Hovel-specific revision protocol; launch decisions remain in the generic journal. */
class HovelTaskRemote(private val transport: HovelTransport, initial: JsonObject) : ExecutionRemote {
    private var task = initial
    override fun claim(record: ExecutionRecord) {
        task = transport.post("claim", buildJsonObject {
            put("taskId", record.request.id); put("claimId", record.claimId)
        })
        require(task.getValue("request").jsonObject.getValue("id").jsonPrimitive.content == record.request.id)
        require(task.getValue("progress").jsonObject.getValue("claimId").jsonPrimitive.content == record.claimId)
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
    private fun progress(record: ExecutionRecord, state: String, text: String?) {
        val current = task.getValue("progress").jsonObject
        require(current.getValue("claimId").jsonPrimitive.content == record.claimId) { "Server claim differs; reconcile manually" }
        val payload = buildJsonObject {
            put("state", state); put("claimId", record.claimId)
            if (text != null) put(if (state == "succeeded") "result" else "error", text)
        }
        task = transport.post("progress", buildJsonObject {
            put("taskId", record.request.id)
            put("expectedRevision", task.getValue("revision").jsonPrimitive.int)
            put("progress", payload)
        })
    }
}

fun hovelExecutionRequest(task: JsonObject, hostId: String): ExecutionRequest {
    val request = task.getValue("request").jsonObject
    require(request.getValue("hostId").jsonPrimitive.content == hostId)
    require(request.getValue("mode").jsonPrimitive.content == "local") { "Cloud execution is unavailable" }
    return ExecutionRequest(request.getValue("id").jsonPrimitive.content, hostId,
        request.getValue("prompt").jsonPrimitive.content, task.getValue("characterMemory").jsonPrimitive.content)
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
