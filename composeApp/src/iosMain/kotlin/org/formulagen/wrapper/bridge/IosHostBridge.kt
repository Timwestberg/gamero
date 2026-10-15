package org.formulagen.wrapper.bridge

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNumber
import platform.WebKit.WKContentWorld
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerWithReplyProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The iOS transport. A `gameroHost` message handler with replies takes each
 * request; [WebKitHostTransport] holds the rules, and this file only moves
 * values between WebKit and it, on the main thread.
 *
 * Attach it before the WebView loads its first page: the document-start
 * script only runs in documents that load after it is added.
 */
@OptIn(ExperimentalForeignApi::class)
class IosHostBridge(private val webView: WKWebView, private val bridge: HostBridge) : AutoCloseable {
    private val controller: WKUserContentController = webView.configuration.userContentController
    private val transport = WebKitHostTransport(bridge) { json -> onMain { postEvent(json) } }

    private val handler = object : NSObject(), WKScriptMessageHandlerWithReplyProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage,
            replyHandler: (Any?, String?) -> Unit,
        ) {
            val message = didReceiveScriptMessage
            val frame = message.frameInfo
            when (val body = message.body) {
                is String -> transport.receive(body, PageFrame(frame.mainFrame, originOf(frame)), { json -> onMain { replyHandler(json, null) } })
                else -> {
                    // The document-start script. A page can send the same thing, which only ends its own context.
                    if (frame.mainFrame) transport.documentStarted()
                    replyHandler(null, null)
                }
            }
        }
    }

    init {
        controller.addUserScript(
            WKUserScript(
                source = DOCUMENT_START_SCRIPT,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
                inContentWorld = WKContentWorld.pageWorld,
            ),
        )
        controller.addScriptMessageHandlerWithReply(handler, WKContentWorld.pageWorld, OBJECT_NAME)
    }

    override fun close() {
        controller.removeScriptMessageHandlerForName(OBJECT_NAME, WKContentWorld.pageWorld)
        bridge.close()
    }

    /** Hands [json] to the page's `gameroHostEvent` as an argument. The value is never pasted into script. */
    private fun postEvent(json: String) {
        webView.callAsyncJavaScript(
            EVENT_SCRIPT,
            arguments = mapOf<Any?, Any?>("event" to json),
            inFrame = null,
            inContentWorld = WKContentWorld.pageWorld,
            completionHandler = null,
        )
    }

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)

    private fun originOf(frame: platform.WebKit.WKFrameInfo): String {
        val origin = frame.securityOrigin
        val port = origin.port.toInt().takeIf { it != 0 }?.let { ":$it" } ?: ""
        return "${origin.protocol}://${origin.host}$port"
    }

    companion object {
        const val OBJECT_NAME = "gameroHost"

        private const val DOCUMENT_START_SCRIPT =
            "window.webkit.messageHandlers.$OBJECT_NAME.postMessage({documentStart: true}).catch(function () {});"

        private const val EVENT_SCRIPT =
            "if (typeof window.gameroHostEvent === 'function') window.gameroHostEvent(event);"
    }
}
