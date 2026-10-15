package org.formulagen.wrapper.bridge

/** iOS: one reply per message, events through `callAsyncJavaScript`, and a document-start script. */
class WebKitTransportContractTest : TransportContractTest() {
    override fun open(bridge: HostBridge): FakeWebView = object : FakeWebView {
        var document = Document()
        override val wire = mutableListOf<String>()
        override val events get() = document.events
        val transport = WebKitHostTransport(bridge) { json ->
            wire += json
            document.events += json
        }

        inner class Document {
            val events = mutableListOf<String>()
        }

        init {
            transport.documentStarted()
        }

        override fun send(raw: String, mainFrame: Boolean, url: String): List<String> {
            val replies = mutableListOf<String>()
            transport.receive(raw, PageFrame(mainFrame, url)) { json ->
                wire += json
                replies += json
            }
            return replies
        }

        override fun navigate() {
            document = Document()
            transport.documentStarted()
        }
    }
}
