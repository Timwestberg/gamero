package org.formulagen.wrapper.companion

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cef.callback.CefQueryCallback
import org.formulagen.wrapper.bridge.BridgeRouter
import org.formulagen.wrapper.bridge.Capability
import org.formulagen.wrapper.bridge.HostCapabilities
import org.formulagen.wrapper.bridge.HostPlatform
import org.formulagen.wrapper.bridge.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JcefV2QueriesTest {
    private val trusted = "https://hovel.example/hovel.html"
    private val watch = """{"version":2,"method":"watchContext","args":{}}"""
    private fun call(contextId: String) = """{"version":2,"contextId":"$contextId","method":"host.info","args":{}}"""

    /** A `CefQueryCallback` that records what the transport sent. */
    private class Answers : CefQueryCallback {
        val successes = mutableListOf<String>()
        val failures = mutableListOf<Int>()
        override fun success(response: String) { successes += response }
        override fun failure(errorCode: Int, errorMessage: String) { failures += errorCode }
        fun code(): String? = Json.parseToJsonElement(successes.single()).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
    }

    private class Page {
        lateinit var queries: JcefV2Queries
        var pause: () -> Unit = {}
    }

    private fun TestScope.page(): Page {
        val page = Page()
        val router = BridgeRouter(
            capabilities = HostCapabilities(
                platform = HostPlatform.DESKTOP,
                appVersion = "1.0.0",
                links = Capability.Unsupported,
                haptics = Capability.Unsupported,
                lifecycle = Capability.Supported(Lifecycle { onPause, _ -> page.pause = onPause; AutoCloseable { } }),
                companion = Capability.Unsupported,
            ),
            scope = backgroundScope,
            transport = { json -> page.queries.events()?.success(json) },
        )
        page.queries = JcefV2Queries(NativeOriginPolicy(trusted), router)
        return page
    }

    /** The router runs in `backgroundScope`, which `advanceUntilIdle` alone does not drain. */
    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    private fun TestScope.ask(page: Page, request: String, persistent: Boolean = false, main: Boolean = true, url: String = trusted, queryId: Long = 1, frameId: String = "frame-1"): Answers {
        val answers = Answers()
        assertTrue(page.queries.onQuery(main, url, frameId, queryId, request, persistent, answers), "the query went to version 1")
        settle()
        return answers
    }

    private fun TestScope.open(page: Page, queryId: Long = 1): Pair<String, Answers> {
        val answers = ask(page, watch, persistent = true, queryId = queryId)
        return Json.parseToJsonElement(answers.successes.single()).jsonObject.getValue("contextId").jsonPrimitive.content to answers
    }

    @Test
    fun untrustedFramesGetForbiddenOnVersionTwoAnd403OnVersionOne() = runTest {
        val page = page()
        for ((main, url) in listOf(false to trusted, true to "https://evil.example/")) {
            // A non-persistent watch is a misuse, but the frame check still comes first.
            for (request in listOf(watch, call("6f1c2d3e-4a5b-4c6d-8e7f-901234567890"))) {
                assertEquals("forbidden", ask(page, request, main = main, url = url).code(), "$main $url")
            }
            assertEquals("forbidden", ask(page, watch, persistent = true, main = main, url = url).code())
            val v1 = ask(page, """{"version":1,"method":"watchContext","args":{}}""", persistent = true, main = main, url = url)
            assertEquals(listOf(403), v1.failures)
            val large = ask(page, """{"version":2,"method":"watchContext","args":{"pad":"${"a".repeat(5000)}"}}""", main = main, url = url)
            assertEquals(listOf(403), large.failures, "an untrusted frame got more than 4096 characters parsed")
        }
    }

    @Test
    fun trustedVersionOneQueriesGoToVersionOne() = runTest {
        val page = page()
        assertTrue(!page.queries.onQuery(true, trusted, "frame-1", 1, """{"version":1,"method":"watchContext","args":{}}""", true, Answers()))
    }

    @Test
    fun aPersistentWatchOpensAContextThatCarriesEvents() = runTest {
        val page = page()
        val (contextId, watchAnswers) = open(page)
        val reply = ask(page, call(contextId), queryId = 2)
        assertEquals("desktop", Json.parseToJsonElement(reply.successes.single()).jsonObject.getValue("result").jsonObject.getValue("platform").jsonPrimitive.content)
        page.pause()
        settle()
        assertEquals(2, watchAnswers.successes.size, "the event did not reach the watch query")
    }

    @Test
    fun cancellingTheWatchEndsTheContext() = runTest {
        val page = page()
        val (contextId, _) = open(page)
        assertTrue(page.queries.onQueryCanceled(1))
        settle()
        assertEquals("expired", ask(page, call(contextId), queryId = 2).code())
        assertTrue(!page.queries.onQueryCanceled(2))
    }

    @Test
    fun aWatchCancelledBeforeItsReplyDoesNotKeepTheContext() = runTest {
        val page = page()
        val answers = Answers()
        page.queries.onQuery(true, trusted, "frame-1", 1, watch, true, answers)
        assertTrue(page.queries.onQueryCanceled(1))
        settle()
        val contextId = Json.parseToJsonElement(answers.successes.single()).jsonObject.getValue("contextId").jsonPrimitive.content
        assertEquals("expired", ask(page, call(contextId), queryId = 2).code())
        page.pause()
        settle()
        assertEquals(1, answers.successes.size, "an event went to a cancelled query")
    }

    @Test
    fun aLateReplyToACancelledWatchLeavesTheNextContextOpen() = runTest {
        val page = page()
        page.queries.onQuery(true, trusted, "frame-1", 1, watch, true, Answers())
        assertTrue(page.queries.onQueryCanceled(1))
        val second = Answers()
        page.queries.onQuery(true, trusted, "frame-1", 2, watch, true, second)
        settle()
        val contextId = Json.parseToJsonElement(second.successes.single()).jsonObject.getValue("contextId").jsonPrimitive.content
        val reply = ask(page, call(contextId), queryId = 3)
        assertEquals("desktop", Json.parseToJsonElement(reply.successes.single()).jsonObject.getValue("result").jsonObject.getValue("platform").jsonPrimitive.content)
        page.pause()
        settle()
        assertEquals(2, second.successes.size, "the second watch lost its events")
    }
}
