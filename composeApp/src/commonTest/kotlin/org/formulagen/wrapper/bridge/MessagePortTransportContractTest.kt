package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Android's transport: one message port per document, as `addWebMessageListener` gives it. */
class MessagePortTransportContractTest : TransportContractTest() {
    override fun open(bridge: HostBridge): FakeWebView = object : FakeWebView {
        val transport = MessagePortTransport(bridge)
        var document = Document()
        var nextId = 0L
        override val wire = mutableListOf<String>()
        override val events get() = document.events

        /** One port per frame of the document, as the platform makes them. */
        inner class Document {
            val events = mutableListOf<String>()
            val replies = mutableMapOf<Long, MutableList<String>>()
            val mainPort = Any()
            val childPort = Any()

            fun deliver(frame: String) {
                wire += frame
                val parsed = Json.parseToJsonElement(frame).jsonObject
                parsed["event"]?.let { events += it.toString() }
                parsed["reply"]?.let { replies.getValue(parsed.getValue("id").jsonPrimitive.long) += it.toString() }
            }
        }

        override fun send(raw: String, mainFrame: Boolean, url: String): List<String> {
            val page = document
            val id = nextId++
            val replies = mutableListOf<String>().also { page.replies[id] = it }
            val frame = """{"id":$id,"request":${Json.encodeToString(raw)}}"""
            transport.receive(frame, PageFrame(mainFrame, url), if (mainFrame) page.mainPort else page.childPort, page::deliver)
            return replies
        }

        override fun navigate() {
            document = Document()
        }
    }
}
