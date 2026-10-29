package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

class ProviderConnectionsTest {
    private open class Rpc : CodexAppServer {
        val calls = mutableListOf<String>()
        var account: JsonElement = JsonNull
        var closed = false
        override fun call(method: String, params: JsonObject): JsonObject {
            calls += method
            return when (method) {
                "account/read" -> {
                    assertEquals(JsonPrimitive(false), params["refreshToken"])
                    buildJsonObject { put("account", account) }
                }
                else -> buildJsonObject {}
            }
        }
        override fun close() { closed = true }
    }
    private fun chatgpt() = buildJsonObject { put("type", "chatgpt"); put("email", "player@example.test"); put("planType", "plus") }
    /** The page owns the sign-in help, so a signed-out card carries no message. */
    private val signedOut = ProviderStatus(RuntimeId.CODEX, ProviderState.DISCONNECTED, detail = ProviderDetail.SIGNED_OUT)

    @Test fun codexIsTheExternalSignInThePlayerManagesInTerminal() {
        val rpc = Rpc()
        val connection = CodexConnection(rpc)
        assertEquals(signedOut, connection.perform(ProviderAction.STATUS))
        // Every other action answers the status with where sign-in happens, and keeps its detail.
        for (action in listOf(ProviderAction.CONNECT, ProviderAction.CANCEL, ProviderAction.REOPEN, ProviderAction.DISCONNECT)) {
            assertEquals(signedOut.copy(message = "Sign in and out with `codex login` in Terminal."), connection.perform(action))
        }
        rpc.account = chatgpt()
        val connected = ProviderStatus(RuntimeId.CODEX, ProviderState.CONNECTED, accountLabel = "player@example.test", plan = "plus")
        assertEquals(connected, connection.perform(ProviderAction.STATUS))
        assertEquals(connected.copy(message = "Sign in and out with `codex login` in Terminal."), connection.perform(ProviderAction.DISCONNECT))
        assertEquals(chatgpt(), rpc.account, "Disconnect never signs the player out")
        rpc.account = buildJsonObject { put("type", "apiKey") }
        assertEquals(ProviderStatus(RuntimeId.CODEX, ProviderState.ERROR, detail = ProviderDetail.API_KEY,
            message = "Codex is using an API key. Sign in with your ChatGPT subscription to use it here."),
            connection.perform(ProviderAction.STATUS))
        rpc.account = buildJsonObject { put("type", "somethingNew") }
        assertFails { connection.perform(ProviderAction.STATUS) }
        assertTrue(rpc.calls.all { it == "account/read" }, "Hovel only reads the account: ${rpc.calls}")
        connection.close(); assertTrue(rpc.closed)
    }

    @Test fun everyProfileSharesThePlayersCodexHomeAndOldHomesStayPut() {
        val root = Files.createTempDirectory("hovel-provider-test")
        try {
            val runtime = CodexRuntime(Path.of("/bin/echo"), root)
            assertEquals(root.resolve("codex"), runtime.statusFolder())
            assertEquals(root.resolve(LOGIN_ID).resolve("codex-chat"), runtime.conversationFolder(LOGIN_ID))
            assertFails { runtime.conversationFolder("../../.codex") }
            val command = runtime.builder(listOf("app-server"))
            // Codex's own default home: whatever the player set, never a folder of Hovel's.
            assertEquals(System.getenv("CODEX_HOME"), command.environment()["CODEX_HOME"])
            assertEquals(listOf("/bin/echo", "app-server"), command.command())
            assertEquals(runtime.statusFolder().toFile(), command.directory())
            assertFalse(command.environment().keys.any { it.startsWith("OPENAI_") || it in setOf("CODEX_API_KEY", "CODEX_ACCESS_TOKEN", "ACCESS_TOKEN") })
            // The old per-profile home is only named, for migrating a chat record; nothing creates or signs in there.
            assertEquals(root.resolve("local").resolve("codex"), legacyCodexProfileHome(root, "local"))
            assertFalse(Files.exists(legacyCodexProfileHome(root, LOGIN_ID)))
            assertFails { legacyCodexProfileHome(root, "../x") }
            assertNull(findCodexExecutable(mapOf("GAMERO_CODEX_PATH" to "/missing/codex")))
        } finally { root.toFile().deleteRecursively() }
    }

    private class MutableClock(var now: Instant = Instant.EPOCH) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = now
    }

    /** A Codex provider whose account processes are [Rpc]s recorded in [processes]. */
    private fun codex(root: Path, processes: MutableList<Rpc>, events: MutableList<String> = mutableListOf(), executable: Path? = Path.of("/bin/echo"),
        clock: Clock = Clock.systemUTC(), models: () -> JsonObject = { JsonObject(emptyMap()) }) =
        CodexProvider(root, executable, clock = clock, createConnection = { _ ->
            val rpc = object : Rpc() {
                override fun call(method: String, params: JsonObject) =
                    (if (method == "model/list") models() else super.call(method, params)).also { events += method }
            }
            processes += rpc
            CodexConnection(rpc)
        })

    @Test fun oneAccountProcessServesEveryProfileAndNothingSignsOut() = runBlocking {
        val root = Files.createTempDirectory("hovel-provider-registry")
        val processes = mutableListOf<Rpc>()
        val events = mutableListOf<String>()
        val registry = DesktopProviders(listOf(codex(root, processes, events)))
        fun scope(profile: String = "local") = ProviderScope(profile, RuntimeId.CODEX)
        try {
            assertFails { registry.perform(ProviderAction.STATUS, scope("../../.codex")) }
            assertTrue(processes.isEmpty())
            assertEquals(signedOut, registry.perform(ProviderAction.STATUS, scope()))
            registry.perform(ProviderAction.CONNECT, scope())
            registry.perform(ProviderAction.STATUS, scope(LOGIN_ID))
            registry.perform(ProviderAction.DISCONNECT, scope(LOGIN_ID))
            // Every profile shares the player's one Codex login, so one account process answers them all.
            assertEquals(1, processes.size)
            assertEquals(listOf("account/read", "account/read", "account/read", "account/read"), events)
        } finally { registry.close(); root.toFile().deleteRecursively() }
        assertTrue(processes.single().closed)
    }

    @Test fun registryWithoutCodexIsUnavailable() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hovel-provider-none")
        DesktopProviders(listOf(CodexProvider(root, null))).use { registry ->
            val status = registry.perform(ProviderAction.CONNECT, ProviderScope("local", RuntimeId.CODEX))
            assertEquals(ProviderStatus(RuntimeId.CODEX, ProviderState.UNAVAILABLE, message = "Install Codex on this Mac, then restart Hovel to connect.",
                detail = ProviderDetail.NOT_INSTALLED), status)
        }
        root.toFile().deleteRecursively()
    }

    @Test fun claudeIsRoutedApartFromCodexAndNeverSignedInOrOut() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hovel-provider-claude")
        val fake = FakeClaude(); val clock = MutableClock(); val processes = mutableListOf<Rpc>(); val codexCalls = mutableListOf<String>()
        val signIn = ClaudeConnection(fake.runtime(), clock = clock)
        val registry = DesktopProviders(listOf(codex(root, processes, codexCalls), ClaudeProvider(signIn)))
        fun scope(runtime: RuntimeId) = ProviderScope(LOGIN_ID, runtime)
        val connected = ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.CONNECTED, accountLabel = "player@example.test", plan = "max")
        try {
            assertEquals(connected, registry.perform(ProviderAction.STATUS, scope(RuntimeId.CLAUDE_CODE)))
            for (action in listOf(ProviderAction.CONNECT, ProviderAction.CANCEL, ProviderAction.REOPEN, ProviderAction.DISCONNECT)) {
                assertEquals(connected.copy(message = "Sign in and out in Claude Code."), registry.perform(action, scope(RuntimeId.CLAUDE_CODE)))
            }
            // Several views ask on focus; one check answers them for a few seconds, and chat readiness shares it.
            assertEquals(org.formulagen.wrapper.chat.Readiness.Ready, org.formulagen.wrapper.chat.ClaudeChatRuntime(fake.runtime(), signIn).use { it.readiness("local") })
            assertEquals(2, fake.calls().size, "One --version and one auth status")
            clock.now = clock.now.plusMillis(CLAUDE_STATUS_TTL.inWholeMilliseconds)
            fake.scenario(auth = FakeClaude.fixture("auth-status-signed-out.json"), authExit = 1)
            assertEquals(ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.DISCONNECTED, detail = ProviderDetail.SIGNED_OUT),
                registry.perform(ProviderAction.STATUS, scope(RuntimeId.CLAUDE_CODE)))
            assertTrue(codexCalls.isEmpty())
            assertEquals(ProviderState.DISCONNECTED, registry.perform(ProviderAction.STATUS, scope(RuntimeId.CODEX)).state)
            assertEquals(RuntimeProviderId.OPENAI, registry.perform(ProviderAction.STATUS, scope(RuntimeId.CODEX)).providerId)
            assertEquals(listOf("account/read", "account/read"), codexCalls)
            assertFails { registry.perform(ProviderAction.STATUS, ProviderScope("../x", RuntimeId.CLAUDE_CODE)) }
        } finally { registry.close(); root.toFile().deleteRecursively() }
    }

    @Test fun aRuntimeThisBuildDoesNotOfferHasNoCardAndAnswersNotOffered() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hovel-provider-list")
        DesktopProviders(listOf(CodexProvider(root, null))).use { registry ->
            assertEquals(ProviderList(listOf(RuntimeId.CODEX), working = emptyList()), registry.list("local"))
            val notOffered = ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.UNAVAILABLE, message = "Claude is not available in this version of Hovel.",
                detail = ProviderDetail.NOT_OFFERED)
            for (action in ProviderAction.entries) assertEquals(notOffered, registry.perform(action, ProviderScope("local", RuntimeId.CLAUDE_CODE)))
            assertEquals(ProviderModels(emptyList(), RuntimeId.CLAUDE_CODE), registry.models(ProviderScope("local", RuntimeId.CLAUDE_CODE)))
            assertFails { registry.list("../x") }
        }
        DesktopProviders(listOf(ClaudeProvider(ClaudeConnection(null)), CodexProvider(root, null)), working = setOf(RuntimeId.CLAUDE_CODE)).use { registry ->
            assertEquals(ProviderList(listOf(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE), working = listOf(RuntimeId.CLAUDE_CODE)), registry.list(LOGIN_ID),
                "The contract's order, and only the runtimes that may work")
            // Offered but not installed: the card says how to install it.
            assertEquals(ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.UNAVAILABLE, message = "Install Claude Code on this Mac, then restart Hovel to use Claude.",
                detail = ProviderDetail.NOT_INSTALLED), registry.perform(ProviderAction.STATUS, ProviderScope("local", RuntimeId.CLAUDE_CODE)))
        }
        root.toFile().deleteRecursively()
    }

    @Test fun modelsComeFromTheAccountProcessAndMissingClaudeHasNoCatalog() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hovel-provider-models")
        val processes = mutableListOf<Rpc>(); val events = mutableListOf<String>(); val clock = MutableClock()
        var fail = false
        val catalog = json("""{"data":[{"model":"gpt-6.1-sol","displayName":"GPT-6.1 Sol","hidden":false,"isDefault":true}],"nextCursor":null}""")
        val registry = DesktopProviders(listOf(codex(root, processes, events, clock = clock, models = { if (fail) throw CodexRequestRejected(); catalog }),
            ClaudeProvider(ClaudeConnection(null))))
        try {
            val sol = ProviderModels(listOf(ProviderModel("GPT-6.1 Sol", true, "gpt-6.1-sol")), RuntimeId.CODEX)
            assertEquals(sol, registry.models(ProviderScope(LOGIN_ID, RuntimeId.CODEX)))
            registry.perform(ProviderAction.STATUS, ProviderScope(LOGIN_ID, RuntimeId.CODEX))
            // The card's account process answers models too, and its list answers again for a minute.
            assertEquals(sol, registry.models(ProviderScope(LOGIN_ID, RuntimeId.CODEX)))
            assertEquals(1, processes.size); assertEquals(1, events.count { it == "model/list" })
            clock.now = clock.now.plusMillis(MODELS_TTL_MILLIS); fail = true
            assertEquals(ProviderModels(emptyList(), RuntimeId.CODEX), registry.models(ProviderScope(LOGIN_ID, RuntimeId.CODEX)), "Nothing known is an empty list")
            assertEquals(ProviderModels(emptyList(),
                RuntimeId.CLAUDE_CODE), registry.models(ProviderScope(LOGIN_ID, RuntimeId.CLAUDE_CODE)))
        } finally { registry.close() }
        DesktopProviders(listOf(CodexProvider(root, null))).use { assertEquals(ProviderModels(emptyList(), RuntimeId.CODEX), it.models(ProviderScope("local", RuntimeId.CODEX))) }
        root.toFile().deleteRecursively()
    }

    @Test fun codexModelsAreTheVisibleOnesWithUsableIds() {
        val pages = mutableListOf<JsonObject>()
        val rpc = object : Rpc() {
            override fun call(method: String, params: JsonObject): JsonObject {
                assertEquals("model/list", method); assertEquals(JsonPrimitive(false), params["includeHidden"]); pages += params
                return if (params["cursor"] == null) json("""{"data":[
                    {"id":"gpt-6.1-sol","model":"gpt-6.1-sol","displayName":"GPT-6.1 Sol","hidden":false,"isDefault":true},
                    {"id":"gpt-reserve","model":"gpt-reserve","displayName":"Reserve","hidden":true,"isDefault":false},
                    {"id":"Bad Id","model":"Bad Id","displayName":"Bad","hidden":false,"isDefault":false},
                    {"id":"gpt-6-luna","model":"gpt-6-luna","displayName":"","hidden":false,"isDefault":false}],"nextCursor":"next"}""")
                else json("""{"data":[{"id":"gpt-6.1-sol","model":"gpt-6.1-sol","displayName":"Again","hidden":false,"isDefault":false},
                    {"id":"codex-auto-review","model":"codex-auto-review","displayName":"Auto review","hidden":false,"isDefault":false}],"nextCursor":null}""")
            }
        }
        assertEquals(listOf(ProviderModel("GPT-6.1 Sol", true, "gpt-6.1-sol"), ProviderModel("gpt-6-luna", false, "gpt-6-luna"),
            ProviderModel("Auto review", false, "codex-auto-review")), codexModelList(rpc))
        assertEquals(listOf(null, JsonPrimitive("next")), pages.map { it["cursor"] })
        assertTrue("model/list" in StdioCodexAppServer.ACCOUNT_METHODS)
        assertFalse("turn/start" in StdioCodexAppServer.ACCOUNT_METHODS)
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    companion object { private const val LOGIN_ID = "30000000-0000-4000-8000-000000000003" }
}
