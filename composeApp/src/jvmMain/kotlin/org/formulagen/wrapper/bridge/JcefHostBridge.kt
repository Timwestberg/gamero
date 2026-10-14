package org.formulagen.wrapper.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.cef.callback.CefQueryCallback
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
 * [NativeBridge][org.formulagen.wrapper.companion.NativeBridge] checks the
 * browser and frame, then calls this from the CEF UI thread.
 */
class JcefHostBridge(private val bridge: HostBridge) : AutoCloseable {
    constructor(trustedUrl: String, capabilities: HostCapabilities, products: List<CapabilityPack>) :
        this(HostBridge(trustedUrl, capabilities, products, Dispatchers.IO))

    /** The watch query of the open context, or [NONE]. Set from the router's thread, read on the CEF UI thread. */
    private val watchQuery = AtomicLong(NONE)

    /** Handles a version 2 query and returns true, or returns false for a version 1 query. */
    fun onQuery(request: String, persistent: Boolean, mainFrame: Boolean, frameUrl: String, queryId: Long, callback: CefQueryCallback): Boolean {
        val kind = classifyJcefQuery(request, persistent)
        if (kind == JcefQueryKind.V1) return false
        if (kind == JcefQueryKind.V2_MISUSE) {
            callback.success(bridgeFailureEnvelope(BridgeErrorCode.INVALID))
            return true
        }
        bridge.receive(request, PageFrame(mainFrame, frameUrl), object : BridgeChannel {
            override fun reply(json: String) = callback.success(json)
            override fun event(json: String) = callback.success(json)
            override fun opened() = watchQuery.set(queryId)
        })
        return true
    }

    /** Returns true when [queryId] was the open watch query, which ends the document's context. */
    fun onQueryCanceled(queryId: Long): Boolean {
        if (!watchQuery.compareAndSet(queryId, NONE)) return false
        bridge.documentChanged()
        return true
    }

    override fun close() {
        watchQuery.set(NONE)
        bridge.close()
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
