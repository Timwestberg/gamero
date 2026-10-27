package org.formulagen.wrapper.companion

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.cef.callback.CefQueryCallback

/**
 * Version 1 of the JCEF bridge, kept only for the Hovel companion's legacy protocol until task B4
 * removes it: one persistent `watchContext` per document, then companion calls bound to that context.
 * Chat, providers, pet and games are host-bridge v2 capabilities and are refused here.
 *
 * [NativeBridge] checks the browser, frame and origin first and calls this on the CEF UI thread.
 */
internal class LegacyCompanionQueries(
    private val dispatch: suspend (String, JsonObject) -> JsonObject,
    /** A companion call was cancelled, or the v1 context ended: an approval it waited on is void. */
    private val onCallEnded: () -> Unit,
    private val scope: CoroutineScope,
) {
    private val gate = Any()
    private val pending = ConcurrentHashMap<Long, Job>()
    private var watchId: Long? = null
    private var contextId: String? = null
    private var frameId: String? = null

    /** Answer one version 1 query from the trusted main frame [frame]. */
    fun onQuery(request: String, persistent: Boolean, frame: String, queryId: Long, callback: CefQueryCallback): Unit = synchronized(gate) {
        if (request.length > MAX_REQUEST_CHARS) return callback.failure(403, "Native request unavailable for this frame")
        val message = runCatching { Json.parseToJsonElement(request).jsonObject }.getOrNull()
        if (message == null || (message["version"] as? JsonPrimitive)?.intOrNull != 1) {
            return callback.failure(400, "Invalid native protocol")
        }
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        if (persistent && method == "watchContext" && message.keys == setOf("version", "method", "args") && message["args"] == buildJsonObject {}) {
            endContext()
            watchId = queryId
            frameId = frame
            contextId = UUID.randomUUID().toString()
            return callback.success(buildJsonObject { put("version", 1); put("contextId", contextId) }.toString())
        }
        if (persistent || message.keys != setOf("version", "method", "args", "contextId") ||
            contextId == null || (message["contextId"] as? JsonPrimitive)?.content != contextId || frame != frameId) {
            return callback.failure(403, "Native context expired")
        }
        if (method !in COMPANION_METHODS || message["args"] !is JsonObject) return callback.failure(400, "Unknown native method")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = dispatch(checkNotNull(method), message.getValue("args").jsonObject)
                ensureActive()
                callback.success(result.toString())
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { callback.failure(400, "Native action failed; inspect the companion status") }
            finally { pending.remove(queryId) }
        }
        pending.put(queryId, job)?.cancel()
        job.start()
    }

    /**
     * Returns true when [queryId] was a version 1 query. Cancelling the watch ends the v1 context;
     * cancelling one call cancels only that call. Neither ends the page's v2 context.
     */
    fun onQueryCanceled(queryId: Long): Boolean = synchronized(gate) {
        if (watchId == queryId) { endContext(); return true }
        val job = pending.remove(queryId) ?: return false
        job.cancel()
        onCallEnded()
        true
    }

    /** End the v1 context and every call bound to it. */
    fun close() = synchronized(gate) { endContext() }

    private fun endContext() {
        contextId = null
        frameId = null
        watchId = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        onCallEnded()
    }

    private companion object {
        /** The companion protocol's methods. Everything else moved to host-bridge v2. */
        val COMPANION_METHODS = setOf("capabilities", "prepareConnection", "activateConnection")
        const val MAX_REQUEST_CHARS = 4096
    }
}
