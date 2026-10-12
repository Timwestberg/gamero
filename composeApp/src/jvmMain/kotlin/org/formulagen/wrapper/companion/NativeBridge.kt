package org.formulagen.wrapper.companion

import dev.datlag.kcef.KCEFBrowser
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.cef.browser.*
import org.cef.callback.CefQueryCallback
import org.cef.handler.*
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Only native-supplied frame facts authorize entry; page payloads cannot assert origin. */
class NativeOriginPolicy(url: String) {
    private val trusted = origin(url) ?: error("A trusted HTTP(S) origin is required")
    fun permits(main: Boolean, frameUrl: String): Boolean = main && origin(frameUrl) == trusted
    private fun origin(value: String): Triple<String, String, Int>? = runCatching {
        val uri = URI(value)
        require(uri.userInfo == null && uri.host != null && uri.scheme in setOf("https", "http"))
        Triple(uri.scheme.lowercase(), uri.host.lowercase(), if (uri.port == -1) { if (uri.scheme == "https") 443 else 80 } else uri.port)
    }.getOrNull()
}

/** Public JCEF router, independent of any product protocol. */
class NativeBridge(
    private val browser: KCEFBrowser,
    trustedUrl: String,
    private val onInvalidate: () -> Unit,
    private val dispatch: suspend (String, JsonObject) -> JsonObject,
) : AutoCloseable {
    private val gate = Any()
    private val policy = NativeOriginPolicy(trustedUrl)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Long, Job>()
    private val closed = AtomicBoolean(false)
    private var watchId: Long? = null
    private var contextId: String? = null
    private var frameId: String? = null
    private fun invalidate() = synchronized(gate) {
        contextId = null
        frameId = null
        watchId = null
        onInvalidate()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
    private val handler = object : CefMessageRouterHandlerAdapter() {
        override fun onQuery(source: CefBrowser, frame: CefFrame, queryId: Long, request: String, persistent: Boolean, callback: CefQueryCallback): Boolean = synchronized(gate) {
            if (closed.get() || source.identifier != browser.identifier || !frame.isValid ||
                !policy.permits(frame.isMain, frame.url) || request.length > 4096) {
                callback.failure(403, "Native request unavailable for this frame")
                return@synchronized true
            }
            val message = runCatching { Json.parseToJsonElement(request).jsonObject }.getOrNull()
            if (message == null || (message["version"] as? JsonPrimitive)?.intOrNull != 1) {
                callback.failure(400, "Invalid native protocol")
                return@synchronized true
            }
            if (persistent && (message["method"] as? JsonPrimitive)?.content == "watchContext" &&
                message.keys == setOf("version", "method", "args") && message["args"] == buildJsonObject {}) {
                invalidate()
                watchId = queryId
                frameId = frame.identifier
                contextId = java.util.UUID.randomUUID().toString()
                callback.success(buildJsonObject { put("version", 1); put("contextId", contextId) }.toString())
                return@synchronized true
            }
            if (persistent || message.keys != setOf("version", "method", "args", "contextId") ||
                contextId == null || (message["contextId"] as? JsonPrimitive)?.content != contextId || frame.identifier != frameId) {
                callback.failure(403, "Native context expired")
                return@synchronized true
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = dispatch(message.getValue("method").jsonPrimitive.content, message.getValue("args").jsonObject)
                    ensureActive()
                    callback.success(result.toString())
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { callback.failure(400, "Native action failed; inspect the companion status") }
                finally { pending.remove(queryId) }
            }
            pending.put(queryId, job)?.cancel()
            job.start()
            return@synchronized true
        }
        override fun onQueryCanceled(source: CefBrowser, frame: CefFrame, queryId: Long) {
            synchronized(gate) {
                if (source.identifier == browser.identifier) {
                    if (watchId == queryId) invalidate()
                    else pending.remove(queryId)?.let { it.cancel(); onInvalidate() }
                }
            }
        }
    }
    private val router = CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig("gameroQuery", "gameroQueryCancel"), handler)
    init {
        browser.client.addMessageRouter(router)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidate()
        scope.cancel()
        browser.client.removeMessageRouter(router)
        router.dispose()
    }
}
