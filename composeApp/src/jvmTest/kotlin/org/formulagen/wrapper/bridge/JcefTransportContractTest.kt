package org.formulagen.wrapper.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cef.callback.CefQueryCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

@OptIn(ExperimentalCoroutinesApi::class)
/** Rules only the JCEF transport has: the frame check before parsing, and watch queries that JCEF cancels. */
class JcefHostBridgeTest {
    private class Answers : CefQueryCallback {
        val successes = mutableListOf<String>()
        val failures = mutableListOf<Int>()
        override fun success(response: String) { successes += response }
        override fun failure(errorCode: Int, errorMessage: String) { failures += errorCode }
        fun code(): String? = Json.parseToJsonElement(successes.single()).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
    }

    private val trusted = "https://hovel.example/hovel.html"
    private val watch = """{"version":2,"method":"watchContext","args":{}}"""
    private fun call(contextId: String) = """{"version":2,"contextId":"$contextId","method":"host.info","args":{}}"""

    private fun TestScope.transport() = JcefHostBridge(
        HostBridge(
            trustedUrl = trusted,
            capabilities = HostCapabilities(HostPlatform.DESKTOP, "1.0.0", Capability.Unsupported, Capability.Unsupported, Capability.Unsupported),
            context = backgroundScope.coroutineContext,
        ),
    )

    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    private fun TestScope.ask(transport: JcefHostBridge, request: String, persistent: Boolean = false, main: Boolean = true, url: String = trusted, queryId: Long = 1): Answers {
        val answers = Answers()
        assertTrue(transport.onQuery(request, persistent, main, url, queryId, answers), "the query went to version 1")
        settle()
        return answers
    }

    @Test
    fun untrustedFramesAreAnsweredBeforeAnyOtherCheck() = runTest {
        val transport = transport()
        for ((main, url) in listOf(false to trusted, true to "https://evil.example/")) {
            // A non-persistent watch is a misuse, but forbidden wins first.
            assertEquals("forbidden", ask(transport, watch, main = main, url = url).code())
            assertEquals("forbidden", ask(transport, call("6f1c2d3e-4a5b-4c6d-8e7f-901234567890"), persistent = true, main = main, url = url).code())
            assertEquals(listOf(403), ask(transport, """{"version":1,"method":"watchContext","args":{}}""", persistent = true, main = main, url = url).failures)
            val large = ask(transport, """{"version":2,"method":"watchContext","args":{"pad":"${"a".repeat(5000)}"}}""", main = main, url = url)
            assertEquals(listOf(403), large.failures, "an untrusted frame got more than 4096 characters parsed")
        }
    }

    @Test
    fun aWatchCancelledBeforeItsReplyDoesNotKeepTheContext() = runTest {
        val transport = transport()
        val answers = Answers()
        transport.onQuery(watch, true, true, trusted, 1, answers)
        assertTrue(transport.onQueryCanceled(1))
        settle()
        val contextId = Json.parseToJsonElement(answers.successes.single()).jsonObject.getValue("contextId").jsonPrimitive.content
        assertEquals("expired", ask(transport, call(contextId), queryId = 2).code())
    }
}
