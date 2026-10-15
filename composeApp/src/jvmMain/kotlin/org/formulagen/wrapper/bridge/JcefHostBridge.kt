package org.formulagen.wrapper.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.cef.callback.CefQueryCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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

    /** The watch query of the open context, or [NONE]. Set from the router's thread, read on the CEF UI thread. */
    private val watchQuery = AtomicLong(NONE)

    /** Watch queries the router has not answered yet. A cancel removes its id, so the late reply knows. */
    private val pendingWatches: MutableSet<Long> = ConcurrentHashMap.newKeySet()

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
        if (kind == JcefQueryKind.V2_WATCH) pendingWatches += queryId
        bridge.receive(request, PageFrame(mainFrame, frameUrl), object : BridgeChannel {
            override fun reply(json: String) {
                pendingWatches -= queryId
                callback.success(json)
            }

            override fun event(json: String) = callback.success(json)

            override fun opened() {
                watchQuery.set(queryId)
                // JCEF cancelled this watch before the reply: its document is gone, so close the context.
                if (!pendingWatches.remove(queryId) && watchQuery.compareAndSet(queryId, NONE)) bridge.documentChanged()
            }
        })
        return true
    }

    /** Returns true when [queryId] was a version 2 watch query. Cancelling the open one ends the document's context. */
    fun onQueryCanceled(queryId: Long): Boolean {
        if (pendingWatches.remove(queryId)) return true
        if (!watchQuery.compareAndSet(queryId, NONE)) return false
        bridge.documentChanged()
        return true
    }

    override fun close() {
        watchQuery.set(NONE)
        pendingWatches.clear()
        bridge.close()
    }

    private companion object {
        const val NONE = Long.MIN_VALUE

        /** Version 1's request cap. An untrusted frame never gets more parsed than this. */
        const val UNTRUSTED_MAX_CHARS = 4096
    }
}
