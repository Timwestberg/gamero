package org.formulagen.wrapper.bridge

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/**
 * The Android transport. `addWebMessageListener` puts a `gameroHost` object in
 * each frame of the trusted origin, and gives each document its own reply
 * port. [MessagePortTransport] holds the rules; this file only moves strings.
 *
 * Attach it before the WebView loads its first page: the object only reaches
 * documents that load after the listener is added.
 */
class AndroidHostBridge private constructor(private val webView: WebView, private val bridge: HostBridge) : AutoCloseable {
    private val transport = MessagePortTransport(bridge)
    private val main = Handler(Looper.getMainLooper())

    init {
        // WebView calls the listener on the UI thread, which keeps the transport on one thread.
        WebViewCompat.addWebMessageListener(webView, OBJECT_NAME, setOf(bridge.origin.rule)) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            val data = message.data ?: return@addWebMessageListener
            transport.receive(data, PageFrame(isMainFrame, sourceOrigin.toString()), replyProxy) { json ->
                main.post { replyProxy.postMessage(json) }
            }
        }
    }

    override fun close() {
        WebViewCompat.removeWebMessageListener(webView, OBJECT_NAME)
        bridge.close()
    }

    companion object {
        const val OBJECT_NAME = "gameroHost"

        /**
         * Attaches [bridge] to [webView]. Returns null, and closes [bridge], when
         * this WebView cannot report a message's frame and origin; the page then
         * sees no host.
         */
        fun attach(webView: WebView, bridge: HostBridge): AndroidHostBridge? {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return AndroidHostBridge(webView, bridge)
            log(LogLevel.Warn, "AndroidHostBridge", "This WebView has no WEB_MESSAGE_LISTENER; the host bridge is off")
            bridge.close()
            return null
        }
    }
}
