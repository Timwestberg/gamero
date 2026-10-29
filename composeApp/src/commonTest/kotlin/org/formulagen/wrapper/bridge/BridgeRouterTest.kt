package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeRouterTest {
    @Test fun newerHistoryUsesAnExistingErrorCodeAndFixedUpdateGuidance() = runTest {
        val chat = object : ChatCapability by FakeChatCapability() {
            override suspend fun open(scope: ChatScope): ChatSnapshot = throw BridgeUpdateRequiredException()
        }
        val router = router(HostPlatform.DESKTOP, products = listOf(ChatMethods.pack(Capability.Supported(chat))))
        call(router, watch)
        val reply = call(router, request("chat.open", buildJsonObject {
            put("profile", "local"); put("companionId", "10000000-0000-4000-8000-000000000001")
            put("sessionId", "20000000-0000-4000-8000-000000000002"); put("name", "Ember")
        })).single()
        assertEquals("failed", reply.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(BridgeUpdateRequiredException().message, reply.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        router.close()
    }

    @Test fun aBrainThePageCannotDecodeAnswersTheSameUpdateGuidance() = runTest {
        val brain = object : BrainCapability by FakeBrainCapability() {
            override suspend fun read(args: BrainRead): BrainReadResult = throw BridgeUpdateRequiredException()
        }
        val router = router(HostPlatform.DESKTOP, products = listOf(BrainMethods.pack(Capability.Supported(brain))))
        call(router, watch)
        val reply = call(router, request("brain.read", buildJsonObject {
            put("profile", "local"); put("companionId", "10000000-0000-4000-8000-000000000001")
        })).single()
        assertEquals("failed", reply.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(BridgeUpdateRequiredException().message, reply.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        router.close()
    }

    private val parity = Json.parseToJsonElement(HostBridgeFixtures.PARITY).jsonObject
    private val trusted = FrameFacts(mainFrame = true, trustedOrigin = true, documentId = "document-1")
    private val watch = """{"version":2,"method":"watchContext","args":{}}"""

    private class Fakes {
        val opened = mutableListOf<String>()
        val played = mutableListOf<HapticEffect>()
        var pause: () -> Unit = {}
        var resume: () -> Unit = {}
        val games = FakeGameCapability()
        var prepare: suspend (String) -> CompanionRegistration = {
            CompanionRegistration("00000000-0000-4000-8000-000000000002", "Mac", "a".repeat(64))
        }
    }

    /** Supported or not, as the parity table says for [platform]. */
    private fun <T> supported(platform: HostPlatform, name: String, impl: T): Capability<T> =
        if (parity.getValue(platform.wireName).jsonObject.getValue(name).jsonPrimitive.content == "supported") Capability.Supported(impl)
        else Capability.Unsupported

    private fun capabilities(platform: HostPlatform, fakes: Fakes = Fakes()): HostCapabilities = HostCapabilities(
        platform = platform,
        appVersion = "1.0.0",
        links = supported(platform, "link.open", LinkOpener { fakes.opened += it }),
        haptics = supported(platform, "haptic.play", Haptics { fakes.played += it }),
        lifecycle = supported(platform, "lifecycle.pause", Lifecycle { onPause, onResume ->
            fakes.pause = onPause
            fakes.resume = onResume
            AutoCloseable { }
        }),
    )

    /** Hovel's product set: the companion, supported only where the parity table says. */
    private fun hovelPacks(platform: HostPlatform, fakes: Fakes = Fakes()): List<CapabilityPack> = listOf(
        BrainMethods.pack(supported(platform, "brain.open", FakeBrainCapability())),
        ChatMethods.pack(supported(platform, "chat.open", FakeChatCapability())),
        ProviderMethods.pack(supported(platform, "providers.status", FakeProviderCapability())),
        PetMethods.pack(supported(platform, "pet.status", FakePetCapability())),
        GameMethods.pack(supported(platform, "games.launch", fakes.games)),
        WorkspaceMethods.pack(supported(platform, "workspace.status", FakeWorkspaceCapability())),
        ProjectMethods.pack(supported(platform, "project.folderStatus", FakeProjectCapability())),
        ConversationVoiceMethods.pack(supported(platform, "conversationVoice.voiceStatus", FakeConversationVoiceCapability())),
        ApprovalMethods.pack(supported(platform, "approvals.present", FakeApprovalsCapability())),
        CompanionMethods.pack(supported(platform, "companion.capabilities", object : CompanionCapability {
            override suspend fun prepareConnection(ownerId: String) = fakes.prepare(ownerId)
            override suspend fun activateConnection(ownerId: String, hostId: String) = JsonObject(emptyMap())
        })),
    )

    private fun TestScope.router(
        platform: HostPlatform = HostPlatform.ANDROID,
        fakes: Fakes = Fakes(),
        posted: MutableList<String> = mutableListOf(),
        failures: MutableList<String> = mutableListOf(),
        products: List<CapabilityPack> = hovelPacks(platform, fakes),
        newContextId: () -> String = { HostBridgeFixtures.CONTEXT_ID },
    ) = BridgeRouter(
        product = "Hovel",
        capabilities = capabilities(platform, fakes),
        products = products,
        scope = backgroundScope,
        transport = { posted += it },
        newContextId = newContextId,
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
            val platform = HostPlatform.entries.single { it.wireName == fixture.getValue("platform").jsonPrimitive.content }
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
        assertEquals(
            fixture("event-games-closed.json"),
            Json.parseToJsonElement(BridgeEnvelopes.event(GameMethods.CLOSED.name, bridgeJson.encodeToJsonElement(GameClosed.serializer(), GameClosed(GameId.HOVEL_COMPANIONS)))),
        )
        assertEquals(fixture("watch-context-reply.json"), Json.parseToJsonElement(BridgeEnvelopes.watchContextReply(HostBridgeFixtures.CONTEXT_ID)))
    }

    @Test
    fun capabilitiesListExactlyTheSupportedColumnOfTheParityTable() = runTest {
        for (platform in HostPlatform.entries) {
            val router = router(platform)
            call(router, watch)
            val result = call(router, request("host.capabilities")).single().getValue("result").jsonObject
            val listed = (result.getValue("methods").jsonArray + result.getValue("events").jsonArray).map { it.jsonPrimitive.content }.toSet()
            val expected = parity.getValue(platform.wireName).jsonObject.filterValues { it.jsonPrimitive.content == "supported" }.keys
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
    fun aDeclinedCallAnswersDeclined() = runTest {
        val fakes = Fakes().apply { prepare = { throw BridgeDeclinedException("Connection declined") } }
        val failures = mutableListOf<String>()
        val router = router(HostPlatform.DESKTOP, fakes, failures = failures)
        call(router, watch)
        val reply = call(router, request("companion.prepareConnection", buildJsonObject { put("ownerId", "00000000-0000-4000-8000-000000000001") })).single()
        assertEquals("declined", reply.code())
        assertEquals(emptyList(), failures)
        router.close()
    }

    @Test
    fun anUnsupportedCallNamesTheProductThePlatformAndTheCapability() = runTest {
        val cases = listOf(
            Triple(HostPlatform.DESKTOP, "haptic.play", "Hovel on desktop has no haptics capability (haptic.play)"),
            Triple(HostPlatform.ANDROID, "companion.capabilities", "Hovel on android has no companion capability (companion.capabilities)"),
        )
        for ((platform, method, message) in cases) {
            val router = router(platform)
            call(router, watch)
            val args = if (method == "haptic.play") buildJsonObject { put("effect", "light") } else JsonObject(emptyMap())
            val error = call(router, request(method, args)).single().getValue("error").jsonObject
            assertEquals("unsupported", error.getValue("code").jsonPrimitive.content)
            assertEquals(message, error.getValue("message").jsonPrimitive.content)
            router.close()
        }
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
    fun aCallPastItsTimeLimitAnswersFailed() = runTest {
        val fakes = Fakes().apply { prepare = { awaitCancellation() } }
        val failures = mutableListOf<String>()
        val router = router(HostPlatform.DESKTOP, fakes, failures = failures)
        call(router, watch)
        val replies = mutableListOf<JsonObject>()
        router.handle(request("companion.prepareConnection", buildJsonObject { put("ownerId", "00000000-0000-4000-8000-000000000001") }), trusted) {
            replies += Json.parseToJsonElement(it).jsonObject
        }
        advanceTimeBy(CompanionMethods.PREPARE.timeout - 1.seconds)
        runCurrent()
        assertEquals(emptyList(), replies)
        // Background work runs on virtual time only when the test advances it.
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals("failed", replies.single().code())
        assertEquals(listOf("companion.prepareConnection"), failures)
        router.close()
    }

    @Test
    fun aHostWithoutAProductDoesNotKnowItsMethods() = runTest {
        val router = router(HostPlatform.DESKTOP, products = emptyList())
        call(router, watch)
        val reply = call(router, request("companion.capabilities")).single()
        assertEquals("unknown-method", reply.code())
        assertEquals("This host does not define this method", reply["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        val methods = call(router, request("host.capabilities")).single()["result"]!!.jsonObject["methods"]!!.jsonArray
        assertTrue(methods.none { it.jsonPrimitive.content.startsWith("companion.") }, methods.toString())
        router.close()
    }

    @Test
    fun endingAContextByIdLeavesANewerContextOpen() = runTest {
        val first = "00000000-0000-4000-8000-00000000000a"
        val second = "00000000-0000-4000-8000-00000000000b"
        val ids = ArrayDeque(listOf(first, second))
        val router = router(newContextId = { ids.removeFirst() })
        call(router, watch)
        router.endContext(second)
        settle()
        assertEquals(true, call(router, request("host.info", contextId = first)).single()["ok"]?.jsonPrimitive?.content?.toBoolean())
        call(router, watch)
        // The first watch goes away after the second one replaced its context.
        router.endContext(first)
        settle()
        assertEquals(true, call(router, request("host.info", contextId = second)).single()["ok"]?.jsonPrimitive?.content?.toBoolean())
        router.endContext(second)
        settle()
        assertEquals("expired", call(router, request("host.info", contextId = second)).single().code())
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
    fun aClosedGameIsSentAsGamesClosedOnDesktop() = runTest {
        val fakes = Fakes()
        val posted = mutableListOf<String>()
        val router = router(HostPlatform.DESKTOP, fakes, posted)
        call(router, watch)
        fakes.games.closed!!(GameId.HOVEL_COMPANIONS)
        settle()
        assertEquals(listOf("""{"version":2,"event":"games.closed","data":{"gameId":"hovel-companions"}}"""), posted)
        router.close()
    }

    @Test
    fun anUnsupportedGamesCapabilityNeverListensForClosedGames() = runTest {
        val fakes = Fakes()
        val router = router(HostPlatform.IOS, fakes)
        call(router, watch)
        assertNull(fakes.games.closed)
        router.close()
    }

    /** Desktop builds a router per WebView while the game launcher outlives them, so a closed router must stop listening. */
    @Test
    fun closingTheRouterReleasesTheGamesSubscription() = runTest {
        val fakes = Fakes()
        val router = router(HostPlatform.DESKTOP, fakes)
        assertNotNull(fakes.games.closed)
        router.close()
        assertNull(fakes.games.closed)
    }

    @Test
    fun anUndeclaredEventIsReportedAndNotSent() = runTest {
        val undeclared = BridgeEvent("rogue.event", NoData.serializer())
        var sink: EventSink? = null
        val rogue = CapabilityBinder<Unit>(
            name = "rogue",
            handlers = emptyList(),
            observe = { _, events -> sink = events; AutoCloseable { } },
        ).pack(Capability.Supported(Unit))
        val posted = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val router = router(HostPlatform.DESKTOP, posted = posted, failures = failures, products = listOf(rogue))
        call(router, watch)
        sink!!.emit(undeclared, NoData)
        settle()
        assertEquals(emptyList(), posted)
        assertEquals(listOf("rogue.event"), failures)
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
