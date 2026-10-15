package org.formulagen.wrapper.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.cef.callback.CefQueryCallback

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
 * The JCEF half of the host bridge. Each request is one `cefQuery`; the
 * persistent `watchContext` query stays open and carries the events. JCEF
 * cancels a frame's queries when it navigates, so the end of the watch query
 * is the end of the document.
 *
 * The frame comes first: a query from a child frame or another origin is
 * answered before anything else reads it, and only its first
 * [UNTRUSTED_MAX_CHARS] characters are parsed, the same cap version 1 has.
 *
 * [NativeBridge][org.formulagen.wrapper.companion.NativeBridge] checks the
 * browser and frame, then calls this from the CEF UI thread.
 */
class JcefHostBridge(private val bridge: HostBridge) : AutoCloseable {
    constructor(trustedUrl: String, capabilities: HostCapabilities, products: List<CapabilityPack>) :
        this(HostBridge(trustedUrl, capabilities, products, Dispatchers.IO))

    /** Guards [watchQuery] and [pendingWatches]: the router's thread and the CEF UI thread both change them. */
    private val lock = Any()

    /** The watch query of the open context, or [NONE]. */
    private var watchQuery = NONE

    /** Watch queries the router has not answered yet. A cancel removes its id, so the late reply knows. */
    private val pendingWatches = mutableSetOf<Long>()

    /**
     * Answers a version 2 query, or any query from an untrusted frame, and
     * returns true. Returns false for a version 1 query from the trusted main frame.
     */
    fun onQuery(request: String, persistent: Boolean, mainFrame: Boolean, frameUrl: String, queryId: Long, callback: CefQueryCallback): Boolean {
        if (!mainFrame || !bridge.origin.matches(frameUrl)) {
            val kind = if (request.length > UNTRUSTED_MAX_CHARS) JcefQueryKind.V1 else classifyJcefQuery(request, persistent)
            if (kind == JcefQueryKind.V1) callback.failure(403, "Native request unavailable for this frame")
            else callback.success(bridgeFailureEnvelope(BridgeErrorCode.FORBIDDEN))
            return true
        }
        val kind = classifyJcefQuery(request, persistent)
        if (kind == JcefQueryKind.V1) return false
        if (kind == JcefQueryKind.V2_MISUSE) {
            callback.success(bridgeFailureEnvelope(BridgeErrorCode.INVALID))
            return true
        }
        if (kind == JcefQueryKind.V2_WATCH) synchronized(lock) { pendingWatches += queryId }
        bridge.receive(request, PageFrame(mainFrame, frameUrl), object : BridgeChannel {
            override fun reply(json: String) {
                synchronized(lock) { pendingWatches -= queryId }
                callback.success(json)
            }

            override fun event(json: String) = callback.success(json)

            override fun opened() = synchronized(lock) {
                // A watch that JCEF cancelled before this reply has already ended its context.
                if (pendingWatches.remove(queryId)) watchQuery = queryId
            }
        })
        return true
    }

    /** Returns true when [queryId] was a version 2 watch query. Cancelling the open one ends the document's context. */
    fun onQueryCanceled(queryId: Long): Boolean {
        synchronized(lock) {
            // A pending watch ends its document now, queued behind its own reply and ahead of any later watch.
            if (!pendingWatches.remove(queryId)) {
                if (watchQuery != queryId) return false
                watchQuery = NONE
            }
        }
        bridge.documentChanged()
        return true
    }

    override fun close() {
        synchronized(lock) {
            watchQuery = NONE
            pendingWatches.clear()
        }
        bridge.close()
    }

    private companion object {
        const val NONE = Long.MIN_VALUE

        /** Version 1's request cap. An untrusted frame never gets more parsed than this. */
        const val UNTRUSTED_MAX_CHARS = 4096
    }
}
