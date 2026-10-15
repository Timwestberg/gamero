package org.formulagen.wrapper.bridge

/**
 * The WebKit transport rules, with no WebKit types so tests can drive them.
 * `WKScriptMessageHandlerWithReply` answers each message once, so a request's
 * reply goes back on its own message. Events have no message to ride on; they
 * go out through [postEvent], which [IosHostBridge] runs as
 * `callAsyncJavaScript` with the event as an argument, never as script text.
 *
 * WebKit has no message port per document, so a script that runs at the start
 * of each main-frame document tells the host the document changed.
 */
class WebKitHostTransport(private val bridge: HostBridge, private val postEvent: (String) -> Unit) {
    /** The main frame started a new document. */
    fun documentStarted() = bridge.documentChanged()

    /** Handles one request. [reply] gets at most one JSON string, from any thread; a cancelled call gets none. */
    fun receive(request: String, frame: PageFrame, reply: (String) -> Unit) {
        bridge.receive(request, frame, object : BridgeChannel {
            override fun reply(json: String) = reply(json)
            override fun event(json: String) = postEvent(json)
        })
    }
}
