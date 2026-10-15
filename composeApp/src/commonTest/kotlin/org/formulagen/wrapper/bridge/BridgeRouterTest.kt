package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeRouterTest {
    private val parity = Json.parseToJsonElement(HostBridgeFixtures.PARITY).jsonObject
    private val trusted = FrameFacts(mainFrame = true, trustedOrigin = true, documentId = "document-1")
    private val watch = """{"version":2,"method":"watchContext","args":{}}"""

    private class Fakes {
        val opened = mutableListOf<String>()
        val played = mutableListOf<HapticEffect>()
        var pause: () -> Unit = {}
        var resume: () -> Unit = {}
        var prepare: suspend (String) -> CompanionRegistration = {
            CompanionRegistration("00000000-0000-4000-8000-000000000002", "Mac", "a".repeat(64))
        }
    }

    private fun capabilities(platform: HostPlatform, fakes: Fakes = Fakes()): HostCapabilities {
        val column = parity.getValue(platform.wire).jsonObject
        fun <T> when_(name: String, impl: T): Capability<T> =
            if (column.getValue(name).jsonPrimitive.content == "supported") Capability.Supported(impl) else Capability.Unsupported
        return HostCapabilities(
            platform = platform,
            appVersion = "1.0.0",
            links = when_("link.open", LinkOpener { fakes.opened += it }),
            haptics = when_("haptic.play", Haptics { fakes.played += it }),
            lifecycle = when_("lifecycle.pause", Lifecycle { onPause, onResume ->
                fakes.pause = onPause
                fakes.resume = onResume
                AutoCloseable { }
            }),
            companion = when_("companion.capabilities", object : CompanionCapability {
                override suspend fun prepareConnection(ownerId: String) = fakes.prepare(ownerId)
                override suspend fun activateConnection(ownerId: String, hostId: String) = JsonObject(emptyMap())
            }),
        )
    }

    private fun TestScope.router(
        platform: HostPlatform = HostPlatform.ANDROID,
        fakes: Fakes = Fakes(),
        posted: MutableList<String> = mutableListOf(),
        failures: MutableList<String> = mutableListOf(),
    ) = BridgeRouter(
        capabilities = capabilities(platform, fakes),
        scope = backgroundScope,
        transport = { posted += it },
        newContextId = { HostBridgeFixtures.CONTEXT_ID },
        onFailure = { method, _ -> failures += method },
    )

    /** The router runs in `backgroundScope`, which `advanceUntilIdle` alone does not drain. */
    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    private fun TestScope.call(router: BridgeRouter, raw: String, frame: FrameFacts = trusted): List<JsonObject> {
        val replies = mutableListOf<JsonObject>()
        router.handle(raw, frame) { replies += Json.parseToJsonElement(it).jsonObject }
        settle()
        return replies
    }

    private fun request(method: String, args: JsonObject = JsonObject(emptyMap()), contextId: String = HostBridgeFixtures.CONTEXT_ID) =
        buildJsonObject {
            put("version", 2)
            put("contextId", contextId)
            put("method", method)
            put("args", args)
        }.toString()

    private fun JsonObject.code(): String? = this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    private val HostPlatform.wire get() = name.lowercase()

    /** The raw string a transport would deliver, as `hostBridgeFixtureRaw` builds it. */
    private fun rawOf(fixture: JsonObject): String {
        fixture["raw"]?.let { return it.jsonPrimitive.content }
        fixture["request"]?.let { return it.toString() }
        val sized = fixture.getValue("sizedRequest").jsonObject
        val length = sized.getValue("length").jsonPrimitive.int
        val contextId = sized.getValue("contextId").jsonPrimitive.content
        fun build(pad: String) = """{"args":{"pad":"$pad"},"contextId":"$contextId","method":"host.info","version":2}"""
        return build("a".repeat(length - build("").length))
    }

    @Test
    fun everyRouterFixtureGetsItsExpectedAnswer() = runTest {
        assertTrue(HostBridgeFixtures.ROUTER.size >= 30)
        for ((name, text) in HostBridgeFixtures.ROUTER) {
            val fixture = Json.parseToJsonElement(text).jsonObject
            val platform = HostPlatform.entries.single { it.wire == fixture.getValue("platform").jsonPrimitive.content }
            val router = router(platform)
            if (fixture.getValue("context").jsonPrimitive.content == "open") call(router, watch)
            val frame = fixture.getValue("frame").jsonObject
            val replies = call(
                router,
                rawOf(fixture),
                FrameFacts(frame.getValue("mainFrame").jsonPrimitive.boolean, frame.getValue("trustedOrigin").jsonPrimitive.boolean, trusted.documentId),
            )
            assertEquals(1, replies.size, "$name: one reply")
            val reply = replies.single()
            val actual = when {
                "contextId" in reply -> buildJsonObject { put("kind", "watch-context") }
                reply.getValue("ok").jsonPrimitive.boolean -> buildJsonObject { put("kind", "call") }
                else -> buildJsonObject { put("code", reply.code()); put("kind", "reject") }
            }
            assertEquals(fixture.getValue("expect"), actual, name)
            router.close()
        }
    }

    @Test
    fun envelopesMatchTheValidFixtures() {
        fun fixture(name: String): JsonElement =
            Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue(name)).jsonObject.getValue("value")
        assertEquals(fixture("success-empty.json"), Json.parseToJsonElement(BridgeEnvelopes.success(JsonObject(emptyMap()))))
        assertEquals(
            fixture("failure-unsupported.json"),
            Json.parseToJsonElement(BridgeEnvelopes.failure(BridgeErrorCode.UNSUPPORTED, "Hovel on desktop has no haptics capability (haptic.play)")),
        )
        // The contract rejects a message over the limit, so the router cuts it to fit.
        val long = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue("failure-long-message.json")).jsonObject.getValue("value")
            .jsonObject.getValue("error").jsonObject.getValue("message").jsonPrimitive.content
        assertTrue(long.length > BridgeProtocol.MAX_ERROR_MESSAGE_CHARS)
        val cut = Json.parseToJsonElement(BridgeEnvelopes.failure(BridgeErrorCode.FAILED, long)).jsonObject.getValue("error").jsonObject.getValue("message").jsonPrimitive.content
        assertEquals(long.take(BridgeProtocol.MAX_ERROR_MESSAGE_CHARS), cut)
        assertEquals(fixture("event-pause.json"), Json.parseToJsonElement(BridgeEnvelopes.event(LifecycleEvents.PAUSE.name, bridgeJson.encodeToJsonElement(NoData.serializer(), NoData))))
        assertEquals(fixture("watch-context-reply.json"), Json.parseToJsonElement(BridgeEnvelopes.watchContextReply(HostBridgeFixtures.CONTEXT_ID)))
    }

    @Test
    fun capabilitiesListExactlyTheSupportedColumnOfTheParityTable() = runTest {
        for (platform in HostPlatform.entries) {
            val router = router(platform)
            call(router, watch)
            val result = call(router, request("host.capabilities")).single().getValue("result").jsonObject
            val listed = (result.getValue("methods").jsonArray + result.getValue("events").jsonArray).map { it.jsonPrimitive.content }.toSet()
            val expected = parity.getValue(platform.wire).jsonObject.filterValues { it.jsonPrimitive.content == "supported" }.keys
            assertEquals(expected, listed, platform.name)
            router.close()
        }
    }

    @Test
    fun hostInfoReportsThePlatform() = runTest {
        val router = router(HostPlatform.IOS)
        call(router, watch)
        val result = call(router, request("host.info")).single().getValue("result").jsonObject
        assertEquals(JsonPrimitive("ios"), result["platform"])
        assertEquals(JsonPrimitive(2), result["bridgeVersion"])
        router.close()
    }

    @Test
    fun capabilitiesReceiveDecodedArguments() = runTest {
        val fakes = Fakes()
        val router = router(HostPlatform.ANDROID, fakes)
        call(router, watch)
        call(router, request("link.open", buildJsonObject { put("url", "https://example.com/a") }))
        call(router, request("haptic.play", buildJsonObject { put("effect", "warning") }))
        assertEquals(listOf("https://example.com/a"), fakes.opened)
        assertEquals(listOf(HapticEffect.WARNING), fakes.played)
        router.close()
    }

    @Test
    fun aThrowingCapabilityAnswersFailedAndReportsTheError() = runTest {
        val fakes = Fakes().apply { prepare = { error("Keychain locked: secret detail") } }
        val failures = mutableListOf<String>()
        val router = router(HostPlatform.DESKTOP, fakes, failures = failures)
        call(router, watch)
        val reply = call(router, request("companion.prepareConnection", buildJsonObject { put("ownerId", "00000000-0000-4000-8000-000000000001") })).single()
        assertEquals("failed", reply.code())
        assertTrue("secret" !in reply.toString())
        assertEquals(listOf("companion.prepareConnection"), failures)
        router.close()
    }

    @Test
    fun aBusyCapabilityAnswersBusy() = runTest {
        val fakes = Fakes().apply { prepare = { throw BridgeBusyException() } }
        val router = router(HostPlatform.DESKTOP, fakes)
        call(router, watch)
        val reply = call(router, request("companion.prepareConnection", buildJsonObject { put("ownerId", "00000000-0000-4000-8000-000000000001") })).single()
        assertEquals("busy", reply.code())
        router.close()
    }

    @Test
    fun endingTheContextCancelsRunningCallsWithoutAReply() = runTest {
        val started = CompletableDeferred<Unit>()
        val fakes = Fakes().apply { prepare = { started.complete(Unit); awaitCancellation() } }
        val router = router(HostPlatform.DESKTOP, fakes)
        call(router, watch)
        val replies = call(router, request("companion.prepareConnection", buildJsonObject { put("ownerId", "00000000-0000-4000-8000-000000000001") }))
        assertTrue(started.isCompleted)
        router.endContext()
        settle()
        assertEquals(emptyList(), replies)
        assertEquals("expired", call(router, request("host.info")).single().code())
        router.close()
    }

    @Test
    fun aNewDocumentCannotUseTheOldContext() = runTest {
        val router = router()
        call(router, watch)
        val reply = call(router, request("host.info"), trusted.copy(documentId = "document-2")).single()
        assertEquals("expired", reply.code())
        router.close()
    }

    @Test
    fun eventsWaitForAnOpenContext() = runTest {
        val fakes = Fakes()
        val posted = mutableListOf<String>()
        val router = router(HostPlatform.IOS, fakes, posted)
        fakes.pause()
        settle()
        assertEquals(emptyList(), posted)
        call(router, watch)
        fakes.resume()
        settle()
        assertEquals(listOf("""{"version":2,"event":"lifecycle.resume","data":{}}"""), posted)
        router.close()
    }

    @Test
    fun safeHttpsUrls() {
        for (url in listOf("https://example.com", "https://example.com:8443/a?b=c#d", "HTTPS://Example.com/", "https://" + "a.".repeat(125) + "com", "https://example.com/" + "a/".repeat(30_000))) assertTrue(isSafeHttpsUrl(url), url)
        for (url in listOf("http://example.com", "javascript:alert(1)", "https://", "https://user:pass@example.com", "https://a b.com", "intent://x#Intent;end", "https://:443/", "https:example.com", "https://exa\\mple.com", "https://[::1", "https://example.com:0/", "https://example.com:65536/", "https://example.com/caf\u00e9",
            "http\u017f://example.com", "https://exampl\u212a.com", "https://\u017fx.com", "https://\u0131.com", "https://example.com/\u0131",
            "https://-.com", "https://a-.com", "https://2130706433/", "https://127.0.0.1/",
            "https://localhost/", "https://app.localhost/", "https://intranet/", "https://" + "a.".repeat(2000) + "com",
        )) {
            assertTrue(!isSafeHttpsUrl(url), url)
        }
    }
}
