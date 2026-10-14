package org.formulagen.wrapper.bridge

import org.cef.callback.CefQueryCallback

/** The desktop transport: one `cefQuery` per request, and a persistent query for `watchContext`. */
class JcefTransportContractTest : TransportContractTest() {
    override fun open(bridge: HostBridge): FakeWebView = object : FakeWebView {
        val transport = JcefHostBridge(bridge)
        var document = Document()
        var nextQuery = 0L
        override val wire = mutableListOf<String>()
        override val events get() = document.events

        inner class Document {
            val events = mutableListOf<String>()
            /** Queries that are still open. JCEF cancels them when the frame navigates. */
            val open = mutableListOf<Long>()
        }

        override fun send(raw: String, mainFrame: Boolean, url: String): List<String> {
            val page = document
            val queryId = nextQuery++
            val persistent = BridgeProtocol.WATCH_CONTEXT in raw
            val replies = mutableListOf<String>()
            val callback = object : CefQueryCallback {
                override fun success(response: String) {
                    wire += response
                    if (replies.isEmpty()) replies += response else page.events += response
                    if (!persistent) page.open -= queryId
                }

                override fun failure(errorCode: Int, errorMessage: String) {
                    error("A v2 query must answer with a JSON envelope, not a CEF failure: $errorCode $errorMessage")
                }
            }
            page.open += queryId
            check(transport.onQuery(raw, persistent, mainFrame, url, queryId, callback)) { "a v2 request was not handled" }
            return replies
        }

        override fun navigate() {
            document.open.toList().forEach { transport.onQueryCanceled(it) }
            document = Document()
        }
    }
}
