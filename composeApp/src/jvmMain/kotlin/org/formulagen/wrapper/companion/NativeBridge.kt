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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JCEF transport. Version 2 requests go to the shared bridge through [JcefHostBridge]; version 1
 * keeps only the Hovel companion's legacy protocol ([LegacyCompanionQueries]) until its web client
 * moves (task B4 removes it).
 */
class NativeBridge(
    private val browser: KCEFBrowser,
    product: String,
    trustedUrl: String,
    capabilities: HostCapabilities,
    products: List<CapabilityPack>,
    /** The page's v2 document context ended or was replaced: release what that page owned. */
    onContextEnded: () -> Unit,
    /** A legacy companion call was cancelled or its v1 context ended. */
    onCompanionCallEnded: () -> Unit,
    /** The legacy companion protocol's handler, or null when this host has no companion. */
    companion: (suspend (String, JsonObject) -> JsonObject)?,
) : AutoCloseable {
    private val gate = Any()
    private val origin = TrustedOrigin(trustedUrl)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostBridge = JcefHostBridge(product, trustedUrl, capabilities, products, onContextEnded)
    private val legacy = companion?.let { LegacyCompanionQueries(it, onCompanionCallEnded, scope) }
    private val closed = AtomicBoolean(false)
    private val handler = object : CefMessageRouterHandlerAdapter() {
        override fun onQuery(source: CefBrowser, frame: CefFrame, queryId: Long, request: String, persistent: Boolean, callback: CefQueryCallback): Boolean = synchronized(gate) {
            if (closed.get() || source.identifier != browser.identifier || !frame.isValid) {
                callback.failure(403, "Native request unavailable for this frame")
                return@synchronized true
            }
            if (hostBridge.onQuery(request, persistent, frame.isMain, frame.url, queryId, callback)) return@synchronized true
            if (legacy == null || !frame.isMain || !origin.matches(frame.url)) {
                callback.failure(403, "Native request unavailable for this frame")
                return@synchronized true
            }
            legacy.onQuery(request, persistent, frame.identifier, queryId, callback)
            true
        }
        override fun onQueryCanceled(source: CefBrowser, frame: CefFrame, queryId: Long) {
            synchronized(gate) {
                if (source.identifier == browser.identifier && !hostBridge.onQueryCanceled(queryId)) legacy?.onQueryCanceled(queryId)
            }
        }
    }
    private val router = CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig("gameroQuery", "gameroQueryCancel"), handler)
    init {
        browser.client.addMessageRouter(router)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        legacy?.close()
        hostBridge.close()
        scope.cancel()
        browser.client.removeMessageRouter(router)
        router.dispose()
    }
}
