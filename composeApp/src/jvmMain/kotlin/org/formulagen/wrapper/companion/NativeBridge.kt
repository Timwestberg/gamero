package org.formulagen.wrapper.companion

import dev.datlag.kcef.KCEFBrowser
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.CapabilityPack
import org.formulagen.wrapper.bridge.HostCapabilities
import org.formulagen.wrapper.bridge.JcefHostBridge
import org.formulagen.wrapper.bridge.TrustedOrigin
import org.cef.browser.*
import org.cef.callback.CefQueryCallback
import org.cef.handler.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JCEF transport. Version 2 requests go to the shared bridge through
 * [JcefHostBridge]; version 1 keeps the Hovel companion protocol until its web
 * client moves (task B4 removes it).
 */
class NativeBridge(
    private val browser: KCEFBrowser,
    product: String,
    trustedUrl: String,
    capabilities: HostCapabilities,
    products: List<CapabilityPack>,
    private val onInvalidate: () -> Unit,
    private val dispatch: (suspend (String, JsonObject) -> JsonObject)?,
) : AutoCloseable {
    private val gate = Any()
    private val origin = TrustedOrigin(trustedUrl)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostBridge = JcefHostBridge(product, trustedUrl, capabilities, products)
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
            if (closed.get() || source.identifier != browser.identifier || !frame.isValid) {
                callback.failure(403, "Native request unavailable for this frame")
                return@synchronized true
            }
            if (hostBridge.onQuery(request, persistent, frame.isMain, frame.url, queryId, callback)) return@synchronized true
            if (dispatch == null || !frame.isMain || !origin.matches(frame.url) || request.length > 4096) {
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
                    val result = checkNotNull(dispatch)(message.getValue("method").jsonPrimitive.content, message.getValue("args").jsonObject)
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
                    if (hostBridge.onQueryCanceled(queryId)) Unit
                    else if (watchId == queryId) invalidate()
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
        hostBridge.close()
        scope.cancel()
        browser.client.removeMessageRouter(router)
        router.dispose()
    }
}
