package org.formulagen.wrapper.companion

import dev.datlag.kcef.KCEFBrowser
import org.formulagen.wrapper.bridge.CapabilityPack
import org.formulagen.wrapper.bridge.HostCapabilities
import org.formulagen.wrapper.bridge.JcefHostBridge
import org.formulagen.wrapper.bridge.TrustedOrigin
import kotlinx.serialization.json.JsonPrimitive
import org.cef.browser.*
import org.cef.callback.CefQueryCallback
import org.cef.handler.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JCEF transport: every query from this browser goes to the shared bridge through [JcefHostBridge], which
 * answers version 2 and refuses anything else.
 */
class NativeBridge(
    private val browser: KCEFBrowser,
    product: String,
    trustedUrl: String,
    capabilities: HostCapabilities,
    products: List<CapabilityPack>,
    /** The page's v2 document context ended or was replaced: release what that page owned. */
    onContextEnded: () -> Unit,
) : AutoCloseable {
    private val gate = Any()
    private val origin = TrustedOrigin(trustedUrl)
    private val hostBridge = JcefHostBridge(product, trustedUrl, capabilities, products, ::postEvent, onContextEnded)
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
            synchronized(gate) { if (source.identifier == browser.identifier) hostBridge.onQueryCanceled(queryId) }
        }
    }
    private val router = CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig("gameroQuery", "gameroQueryCancel"), handler)
    init {
        browser.client.addMessageRouter(router)
    }
    /** Deliver only to the trusted main frame; its context guard rejects navigation races. */
    private fun postEvent(contextId: String, json: String) {
        val frame = browser.mainFrame
        if (!closed.get() && frame.isValid && origin.matches(frame.url)) {
            frame.executeJavaScript(desktopEventScript(contextId, json), frame.url, 0)
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        hostBridge.close()
        browser.client.removeMessageRouter(router)
        router.dispose()
    }
}

/** JSON string literals keep event content as data, including quotes and line breaks. */
internal fun desktopEventScript(contextId: String, json: String): String =
    "window.gameroDesktopEvent?.(${JsonPrimitive(contextId)},${JsonPrimitive(json)});"
