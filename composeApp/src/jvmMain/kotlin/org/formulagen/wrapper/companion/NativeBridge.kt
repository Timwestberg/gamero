package org.formulagen.wrapper.companion

import dev.datlag.kcef.KCEFBrowser
import org.formulagen.wrapper.bridge.CapabilityPack
import org.formulagen.wrapper.bridge.HostCapabilities
import org.formulagen.wrapper.bridge.JcefHostBridge
import org.cef.browser.*
import org.cef.callback.CefQueryCallback
import org.cef.handler.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JCEF transport. Every `gameroQuery` goes to the shared bridge through
 * [JcefHostBridge], which speaks only `host-bridge.v2`.
 */
class NativeBridge(
    private val browser: KCEFBrowser,
    product: String,
    trustedUrl: String,
    capabilities: HostCapabilities,
    products: List<CapabilityPack>,
    /**
     * Called when [JcefHostBridge.onQueryCanceled] reports that the cancelled query was
     * the open watch or a still-pending one, and on [close]. A reload or navigation
     * cancelling the open watch calls it. Cancelling an older watch after the newer one
     * is answered does not. Cancelling a still-pending watch (the client's timeout), or
     * the open one before a newer watch is answered, also calls it; that fails closed,
     * and the page must call `companion.prepareConnection` again.
     */
    private val onInvalidate: () -> Unit,
) : AutoCloseable {
    private val gate = Any()
    private val hostBridge = JcefHostBridge(product, trustedUrl, capabilities, products)
    private val closed = AtomicBoolean(false)
    private val handler = object : CefMessageRouterHandlerAdapter() {
        override fun onQuery(source: CefBrowser, frame: CefFrame, queryId: Long, request: String, persistent: Boolean, callback: CefQueryCallback): Boolean = synchronized(gate) {
            if (closed.get() || source.identifier != browser.identifier || !frame.isValid) {
                callback.failure(403, "Native request unavailable for this frame")
            } else {
                hostBridge.onQuery(request, persistent, frame.isMain, frame.url, queryId, callback)
            }
            true
        }
        override fun onQueryCanceled(source: CefBrowser, frame: CefFrame, queryId: Long) {
            synchronized(gate) {
                if (source.identifier == browser.identifier && hostBridge.onQueryCanceled(queryId)) onInvalidate()
            }
        }
    }
    private val router = CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig("gameroQuery", "gameroQueryCancel"), handler)
    init {
        browser.client.addMessageRouter(router)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(gate) { onInvalidate() }
        hostBridge.close()
        browser.client.removeMessageRouter(router)
        router.dispose()
    }
}
