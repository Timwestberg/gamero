package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules every transport must keep, run against each platform adapter with
 * a fake WebView. A platform test extends this class and implements [open].
 * A new platform passes this test with no change to the bridge kit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class TransportContractTest {
    /** A page in a fake WebView, as the platform adapter sees it. */
    protected interface FakeWebView {
        /**
         * Sends a host-bridge.v2 request from a frame of the current document,
         * the way the web client does on this platform. Returns the replies to
         * this request, as v2 envelopes, as they arrive.
         */
        fun send(raw: String, mainFrame: Boolean = true, url: String = TRUSTED_URL): List<String>

        /** The main frame loads a new document. */
        fun navigate()

        /** Events that reached the current document, as v2 envelopes. */
        val events: List<String>

        /** Every string the adapter handed to the WebView, in order. */
        val wire: List<String>
    }

    /** Starts the platform adapter on [bridge] in a fake WebView. */
    protected abstract fun open(bridge: HostBridge): FakeWebView

    private class Fakes {
        val prepareStarted = CompletableDeferred<Unit>()
        var prepareCancelled = false
        var pause: () -> Unit = {}
    }

    private fun TestScope.start(fakes: Fakes = Fakes(), appVersion: String = "1.0.0"): FakeWebView {
        val capabilities = HostCapabilities(
            platform = HostPlatform.ANDROID,
            appVersion = appVersion,
            links = Capability.Unsupported,
            haptics = Capability.Unsupported,
            lifecycle = Capability.Supported(Lifecycle { onPause, _ ->
                fakes.pause = onPause
                AutoCloseable { }
            }),
        )
        val companion = object : CompanionCapability {
            override suspend fun prepareConnection(ownerId: String): CompanionRegistration {
                fakes.prepareStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    fakes.prepareCancelled = true
                }
            }

            override suspend fun activateConnection(ownerId: String, hostId: String) = JsonObject(emptyMap())
        }
        val bridge = HostBridge(
            product = "Hovel",
            trustedUrl = TRUSTED_URL,
            capabilities = capabilities,
            products = listOf(CompanionMethods.pack(Capability.Supported(companion))),
            context = backgroundScope.coroutineContext,
            // The default logs through the platform logger, which Android's JVM unit tests can't call.
            onFirstAnswer = { _, _ -> },
        )
        return open(bridge)
    }

    /** The bridge runs in `backgroundScope`, which `advanceUntilIdle` alone does not drain. */
    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    private fun TestScope.openContext(page: FakeWebView): String {
        val replies = page.send(WATCH)
        settle()
        return Json.parseToJsonElement(replies.single()).jsonObject.getValue("contextId").jsonPrimitive.content
    }

    private fun request(contextId: String, method: String, args: String = "{}") =
        """{"version":2,"contextId":"$contextId","method":"$method","args":$args}"""

    private fun String.code(): String? = Json.parseToJsonElement(this).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    @Test
    fun aMainFrameCallFromTheTrustedOriginGetsAnAnswer() = runTest {
        val page = start()
        val contextId = openContext(page)
        val replies = page.send(request(contextId, "host.info"))
        settle()
        val result = Json.parseToJsonElement(replies.single()).jsonObject.getValue("result").jsonObject
        assertEquals("android", result.getValue("platform").jsonPrimitive.content)
    }

    @Test
    fun aSubFrameCallIsForbidden() = runTest {
        val page = start()
        val contextId = openContext(page)
        val watch = page.send(WATCH, mainFrame = false)
        val call = page.send(request(contextId, "host.info"), mainFrame = false)
        settle()
        assertEquals("forbidden", watch.single().code())
        assertEquals("forbidden", call.single().code())
    }

    @Test
    fun aCallFromAnotherOriginIsForbidden() = runTest {
        val page = start()
        val contextId = openContext(page)
        val watch = page.send(WATCH, url = "https://evil.example/")
        val call = page.send(request(contextId, "host.info"), url = "https://evil.example/")
        settle()
        assertEquals("forbidden", watch.single().code())
        assertEquals("forbidden", call.single().code())
    }

    @Test
    fun aNavigationCancelsARunningCallAndTheOldDocumentGetsNoReply() = runTest {
        val fakes = Fakes()
        val page = start(fakes)
        val oldContext = openContext(page)
        val running = page.send(request(oldContext, "companion.prepareConnection", """{"ownerId":"00000000-0000-4000-8000-000000000001"}"""))
        settle()
        assertTrue(fakes.prepareStarted.isCompleted, "the call did not start")

        page.navigate()
        // The new document's first message. A port transport learns of the navigation from it.
        val stale = page.send(request(oldContext, "host.info"))
        settle()

        assertEquals("expired", stale.single().code())
        assertTrue(fakes.prepareCancelled, "the old document's call is still running")
        assertEquals(emptyList(), running)
        assertTrue(openContext(page) != oldContext)
    }

    @Test
    fun anEventReachesTheOpenContextOnly() = runTest {
        val fakes = Fakes()
        val page = start(fakes)
        settle()
        fakes.pause()
        settle()
        assertEquals(emptyList(), page.events, "an event arrived before watchContext")

        openContext(page)
        fakes.pause()
        settle()
        assertEquals(listOf("lifecycle.pause"), page.events.map { Json.parseToJsonElement(it).jsonObject.getValue("event").jsonPrimitive.content })

        page.navigate()
        fakes.pause()
        settle()
        assertEquals(emptyList(), page.events, "an event reached a document with no context")
    }

    @Test
    fun aReplyGoesBackAsAJsonValueNeverAsScript() = runTest {
        val hostile = "1.0\"</script><script>alert(1)</script>\\u2028'`"
        val page = start(appVersion = hostile)
        val contextId = openContext(page)
        val replies = page.send(request(contextId, "host.info"))
        settle()
        val result = Json.parseToJsonElement(replies.single()).jsonObject.getValue("result").jsonObject
        assertEquals(hostile, result.getValue("appVersion").jsonPrimitive.content)
        assertTrue(page.wire.isNotEmpty())
        for (sent in page.wire) assertTrue(Json.parseToJsonElement(sent) is JsonObject, "not a JSON object: $sent")
    }

    protected companion object {
        const val TRUSTED_URL = "https://hovel.example/hovel.html"
        const val WATCH = """{"version":2,"method":"watchContext","args":{}}"""
    }
}
