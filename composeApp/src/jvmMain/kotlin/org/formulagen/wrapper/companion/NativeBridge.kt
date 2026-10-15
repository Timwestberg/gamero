package org.formulagen.wrapper.companion

import dev.datlag.kcef.KCEFBrowser
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.BridgeProtocol
import org.formulagen.wrapper.bridge.BridgeRouter
import org.formulagen.wrapper.bridge.BridgeTransport
import org.formulagen.wrapper.bridge.CapabilityPack
import org.formulagen.wrapper.bridge.HostCapabilities
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
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

/** How the JCEF transport handles one query, decided before any product code runs. */
internal enum class JcefQueryKind {
    /** The Hovel companion protocol that `nativeCompanion.ts` speaks until it moves to the v2 client. */
    V1,
    /** A persistent `watchContext`: the reply opens the context, and later successes carry events. */
    V2_WATCH,
    V2_CALL,
    /** A v2 message on the wrong query kind: `watchContext` must be persistent, and nothing else may be. */
    V2_MISUSE,
}

internal fun classifyJcefQuery(request: String, persistent: Boolean): JcefQueryKind {
    if (request.length > BridgeProtocol.MAX_REQUEST_CHARS) return JcefQueryKind.V1
    val message = runCatching { Json.parseToJsonElement(request) }.getOrNull() as? JsonObject ?: return JcefQueryKind.V1
    val version = message["version"] as? JsonPrimitive
    if (version == null || version.isString || version.intOrNull != BridgeProtocol.VERSION) return JcefQueryKind.V1
    val watch = (message["method"] as? JsonPrimitive)?.content == BridgeProtocol.WATCH_CONTEXT
    return when {
        watch && persistent -> JcefQueryKind.V2_WATCH
        !watch && !persistent -> JcefQueryKind.V2_CALL
        else -> JcefQueryKind.V2_MISUSE
    }
}

/**
 * The JCEF transport. Version 2 requests go to the shared [BridgeRouter]; version 1
 * keeps the Hovel companion protocol until its web client moves (task B4 removes it).
 */
class NativeBridge(
    private val browser: KCEFBrowser,
    trustedUrl: String,
    capabilities: HostCapabilities,
    products: List<CapabilityPack>,
    private val onInvalidate: () -> Unit,
    private val dispatch: (suspend (String, JsonObject) -> JsonObject)?,
) : AutoCloseable {
    private val gate = Any()
    private val policy = NativeOriginPolicy(trustedUrl)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostRouter = BridgeRouter(
        capabilities = capabilities,
        products = products,
        scope = scope,
        transport = BridgeTransport { json -> v2.events()?.success(json) },
        onFailure = { name, error -> log(LogLevel.Warn, "NativeBridge", "Host bridge $name failed", error) },
    )
    private val v2: JcefV2Queries = JcefV2Queries(policy, hostRouter)
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
            if (v2.onQuery(frame.isMain, frame.url, frame.identifier, queryId, request, persistent, callback)) return@synchronized true
            if (dispatch == null || request.length > 4096) {
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
                    if (v2.onQueryCanceled(queryId)) Unit
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
        v2.close()
        hostRouter.close()
        scope.cancel()
        browser.client.removeMessageRouter(router)
        router.dispose()
    }
}
