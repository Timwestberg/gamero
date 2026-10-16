package org.formulagen.wrapper.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.cef.callback.CefQueryCallback

/** How the JCEF transport handles one query from the trusted main frame, decided before any product code runs. */
internal enum class JcefQueryKind {
    /** A persistent `watchContext`: the reply opens the context, and later successes carry events. */
    WATCH,
    CALL,
    /**
     * Not a `host-bridge.v2` message, or a v2 message on the wrong query kind:
     * `watchContext` must be persistent, and nothing else may be. Version 1 lands here too.
     */
    INVALID,
}

internal fun classifyJcefQuery(request: String, persistent: Boolean): JcefQueryKind {
    if (request.length > BridgeProtocol.MAX_REQUEST_CHARS) return JcefQueryKind.INVALID
    val message = runCatching { Json.parseToJsonElement(request) }.getOrNull() as? JsonObject ?: return JcefQueryKind.INVALID
    if (!BridgeEnvelopes.isVersion(message["version"])) return JcefQueryKind.INVALID
    val watch = (message["method"] as? JsonPrimitive)?.content == BridgeProtocol.WATCH_CONTEXT
    return when {
        watch && persistent -> JcefQueryKind.WATCH
        !watch && !persistent -> JcefQueryKind.CALL
        else -> JcefQueryKind.INVALID
    }
}

/**
 * The JCEF half of the host bridge. Each request is one `cefQuery`; the
 * persistent `watchContext` query stays open and carries the events. JCEF
 * cancels a frame's queries when it navigates, so the end of a watch query
 * ends the context that watch opened.
 *
 * The frame comes first: a query from a child frame or another origin gets
 * `forbidden` and is never parsed. Every answer is a `host-bridge.v2`
 * envelope; anything that is not a v2 request, version 1 included, gets `invalid`.
 *
 * [NativeBridge][org.formulagen.wrapper.companion.NativeBridge] checks the
 * browser and frame, then calls this from the CEF UI thread.
 */
class JcefHostBridge(private val bridge: HostBridge) : AutoCloseable {
    constructor(product: String, trustedUrl: String, capabilities: HostCapabilities, products: List<CapabilityPack>) :
        this(HostBridge(product, trustedUrl, capabilities, products, Dispatchers.IO))

    /** Guards [watchQuery] and [pendingWatches]: the router's thread and the CEF UI thread both change them. */
    private val lock = Any()

    /** The watch query of the open context, or [NONE], and the context it opened. */
    private var watchQuery = NONE
    private var watchContext: String? = null

    /** Watch queries the router has not answered yet. A cancel removes its id, so the late reply knows. */
    private val pendingWatches = mutableSetOf<Long>()

    /** Answers one query, always with a `host-bridge.v2` envelope. */
    fun onQuery(request: String, persistent: Boolean, mainFrame: Boolean, frameUrl: String, queryId: Long, callback: CefQueryCallback) {
        if (!mainFrame || !bridge.origin.matches(frameUrl)) {
            callback.success(bridgeFailureEnvelope(BridgeErrorCode.FORBIDDEN))
            return
        }
        val kind = classifyJcefQuery(request, persistent)
        if (kind == JcefQueryKind.INVALID) {
            callback.success(bridgeFailureEnvelope(BridgeErrorCode.INVALID))
            return
        }
        if (kind == JcefQueryKind.WATCH) synchronized(lock) { pendingWatches += queryId }
        bridge.receive(request, PageFrame(mainFrame, frameUrl), object : BridgeChannel {
            override fun reply(json: String) {
                synchronized(lock) { pendingWatches -= queryId }
                callback.success(json)
            }

            override fun event(json: String) = callback.success(json)

            override fun opened(contextId: String): Boolean = synchronized(lock) {
                // A watch that JCEF cancelled before this reply declines, so the bridge ends only its context.
                if (!pendingWatches.remove(queryId)) return false
                watchQuery = queryId
                watchContext = contextId
                true
            }
        })
    }

    /**
     * Returns true when [queryId] was the open or a pending watch query, and ends the
     * context that watch opened, never a newer one. A watch the router has not
     * answered yet declines its context when the reply arrives.
     */
    fun onQueryCanceled(queryId: Long): Boolean {
        val contextId = synchronized(lock) {
            if (pendingWatches.remove(queryId)) return true
            if (watchQuery != queryId) return false
            watchQuery = NONE
            watchContext.also { watchContext = null }
        }
        if (contextId != null) bridge.endContext(contextId)
        return true
    }

    override fun close() {
        synchronized(lock) {
            watchQuery = NONE
            watchContext = null
            pendingWatches.clear()
        }
        bridge.close()
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
