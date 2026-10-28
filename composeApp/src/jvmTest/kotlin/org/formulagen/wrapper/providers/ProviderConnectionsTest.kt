package org.formulagen.wrapper.providers

import java.net.URI
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
        var outcome: Boolean? = null
        var authUrl = "https://auth.openai.com/oauth/authorize?fixture=1"
        var closed = false
        override fun call(method: String, params: JsonObject): JsonObject {
            calls += method
            return when (method) {
                "account/read" -> {
                    assertEquals(JsonPrimitive(false), params["refreshToken"])
                    buildJsonObject { put("account", account) }
                }
                "account/login/start" -> {
                    assertEquals("chatgpt", params["type"]?.jsonPrimitive?.content)
                    buildJsonObject { put("type", "chatgpt"); put("loginId", LOGIN_ID); put("authUrl", authUrl) }
                }
                "account/logout" -> { account = JsonNull; buildJsonObject {} }
                else -> buildJsonObject {}
            }
        }
        override fun loginResult(loginId: String) = outcome
        override fun close() { closed = true }
    }
    private fun chatgpt() = buildJsonObject { put("type", "chatgpt"); put("email", "player@example.test"); put("planType", "plus") }

    @Test fun browserLaunchIsPendingUntilAccountReadConfirmsChatgpt() {
        val rpc = Rpc()
        val opened = mutableListOf<URI>()
        val connection = CodexConnection(rpc, { opened += it })
        assertEquals(ProviderState.DISCONNECTED, connection.perform(ProviderAction.STATUS).state)
        val pending = connection.perform(ProviderAction.CONNECT)
        assertEquals(ProviderState.SIGNING_IN, pending.state)
        assertFalse(pending.toString().contains("auth.openai.com"))
        assertEquals("auth.openai.com", opened.single().host)
        connection.perform(ProviderAction.CONNECT)
        assertEquals(1, rpc.calls.count { it == "account/login/start" })
        connection.perform(ProviderAction.REOPEN)
        assertEquals(2, opened.size)
        assertEquals(ProviderState.SIGNING_IN, connection.perform(ProviderAction.STATUS).state)
        rpc.account = chatgpt(); rpc.outcome = true
        val connected = connection.perform(ProviderAction.STATUS)
        assertEquals(ProviderState.CONNECTED, connected.state)
        assertEquals("player@example.test", connected.accountLabel)
        assertEquals("plus", connected.plan)
        assertEquals(ProviderState.DISCONNECTED, connection.perform(ProviderAction.DISCONNECT).state)
        connection.close(); assertTrue(rpc.closed)
    }

    @Test fun cancellationFailureAndBrowserFailureStayTruthful() {
        val rpc = Rpc()
        val connection = CodexConnection(rpc, { error("Browser not available") })
        assertEquals(ProviderState.SIGNING_IN, connection.perform(ProviderAction.CONNECT).state)
        assertEquals(ProviderState.DISCONNECTED, connection.perform(ProviderAction.CANCEL).state)
        assertTrue(rpc.calls.contains("account/login/cancel"))
        connection.perform(ProviderAction.CONNECT); rpc.outcome = false
        assertEquals(ProviderState.ERROR, connection.perform(ProviderAction.STATUS).state)
        rpc.account = buildJsonObject { put("type", "apiKey") }
        assertFails { connection.perform(ProviderAction.STATUS) }
    }

    @Test fun expiredLoginCancelsInsteadOfPollingForever() {
        val rpc = Rpc()
        var instant = Instant.EPOCH
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = instant
        }
        val connection = CodexConnection(rpc, {}, clock)
        connection.perform(ProviderAction.CONNECT)
        instant = instant.plusSeconds(601)
        assertEquals(ProviderState.ERROR, connection.perform(ProviderAction.STATUS).state)
        assertTrue(rpc.calls.contains("account/login/cancel"))
    }

    @Test fun onlySupportedProviderAuthorizationUrlsCanOpen() {
        for (url in listOf("http://auth.openai.com/test", "https://auth.openai.com.evil.test/", "file:///tmp/test", "https://user@auth.openai.com/", "https://auth.openai.com:444/")) {
            val rpc = Rpc().also { it.authUrl = url }
            var opened = false
            assertFails { CodexConnection(rpc, { opened = true }).perform(ProviderAction.CONNECT) }
            assertFalse(opened)
        }
    }

    @Test fun profileAndRuntimeNeverUseTheGlobalCodexCredentialDirectory() {
        val root = Files.createTempDirectory("hovel-provider-test")
        try {
            val home = codexProfileHome(root, LOGIN_ID)
            assertNotEquals(home, codexProfileHome(root, "local"))
            assertFails { codexProfileHome(root, "../../.codex") }
            val runtime = CodexRuntime(Path.of("/bin/echo"), home)
            runtime.prepare()
            val command = runtime.builder(listOf("login", "status"))
            assertEquals(home.toString(), command.environment()["CODEX_HOME"])
            assertTrue(command.command().contains("cli_auth_credentials_store=\"keyring\""))
            assertTrue(command.command().contains("forced_login_method=\"chatgpt\""))
            assertFalse(command.environment().keys.any { it.startsWith("OPENAI_") || it in setOf("CODEX_API_KEY", "CODEX_ACCESS_TOKEN", "ACCESS_TOKEN") })
            assertNull(findCodexExecutable(mapOf("GAMERO_CODEX_PATH" to "/missing/codex")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun registryClosesThePreviousProfileAndSignsChatsOutFirst() = runBlocking {
        val root = Files.createTempDirectory("hovel-provider-registry")
        val processes = mutableListOf<Rpc>()
        val events = mutableListOf<String>()
        val registry = DesktopProviders(root, Path.of("/bin/echo"), openBrowser = {}, onDisconnect = { events += "chat invalidated" },
            createConnection = { _, open ->
                val rpc = object : Rpc() { override fun call(method: String, params: JsonObject) = super.call(method, params).also { events += method } }
                processes += rpc
                CodexConnection(rpc, open)
            })
        fun scope(profile: String = "local") = ProviderScope(profile, RuntimeId.CODEX)
        try {
            assertFails { registry.perform(ProviderAction.STATUS, scope("../../.codex")) }
            assertTrue(processes.isEmpty())
            assertEquals(ProviderState.DISCONNECTED, registry.perform(ProviderAction.STATUS, scope()).state)
            registry.perform(ProviderAction.CONNECT, scope())
            registry.perform(ProviderAction.STATUS, scope(LOGIN_ID))
            assertEquals(2, processes.size)
            assertTrue(processes.first().closed)
            assertFalse(processes.last().closed)
            events.clear()
            registry.perform(ProviderAction.DISCONNECT, scope(LOGIN_ID))
            assertEquals(listOf("chat invalidated", "account/logout", "account/read"), events)
        } finally { registry.close(); root.toFile().deleteRecursively() }
        assertTrue(processes.last().closed)
    }

    @Test fun registryWithoutCodexIsUnavailable() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hovel-provider-none")
        DesktopProviders(root, null).use { registry ->
            val status = registry.perform(ProviderAction.CONNECT, ProviderScope("local", RuntimeId.CODEX))
            assertEquals(ProviderStatus(RuntimeId.CODEX, ProviderState.UNAVAILABLE, message = "Install Codex on this Mac, then restart Hovel to connect."), status)
        }
        root.toFile().deleteRecursively()
    }

    /** Claude's sign-in as a test sets it, counting each check. */
    private class ClaudeSignIn : ProviderConnection {
        var checks = 0
        var status = ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.CONNECTED, accountLabel = "player@example.test", plan = "max")
        var closed = false
        override fun perform(action: ProviderAction): ProviderStatus { assertEquals(ProviderAction.STATUS, action); checks++; return status }
        override fun close() { closed = true }
    }

    private class MutableClock(var now: Instant = Instant.EPOCH) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = now
    }

    @Test fun claudeIsRoutedApartFromCodexAndNeverSignedInOrOut() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hovel-provider-claude")
        val claude = ClaudeSignIn(); val clock = MutableClock(); val codexCalls = mutableListOf<String>(); var invalidated = 0
        val registry = DesktopProviders(root, Path.of("/bin/echo"), claude = claude, openBrowser = {}, onDisconnect = { invalidated++ },
            createConnection = { _, open -> CodexConnection(object : Rpc() { override fun call(method: String, params: JsonObject) = super.call(method, params).also { codexCalls += method } }, open) },
            clock = clock)
        fun scope(runtime: RuntimeId) = ProviderScope(LOGIN_ID, runtime)
        try {
            assertEquals(claude.status, registry.perform(ProviderAction.STATUS, scope(RuntimeId.CLAUDE_CODE)))
            for (action in listOf(ProviderAction.CONNECT, ProviderAction.CANCEL, ProviderAction.REOPEN, ProviderAction.DISCONNECT)) {
                assertEquals(claude.status.copy(message = "Sign in and out in Claude Code."), registry.perform(action, scope(RuntimeId.CLAUDE_CODE)))
            }
            // Several views ask on focus; one check answers them for a few seconds.
            assertEquals(1, claude.checks)
            clock.now = clock.now.plusMillis(CLAUDE_STATUS_TTL_MILLIS)
            claude.status = ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.DISCONNECTED)
            assertEquals(ProviderState.DISCONNECTED, registry.perform(ProviderAction.STATUS, scope(RuntimeId.CLAUDE_CODE)).state)
            assertEquals(2, claude.checks)
            assertEquals(0, invalidated, "Claude's disconnect signs nobody out, so open chats keep going")
            assertTrue(codexCalls.isEmpty())
            assertEquals(ProviderState.DISCONNECTED, registry.perform(ProviderAction.STATUS, scope(RuntimeId.CODEX)).state)
            assertEquals(RuntimeProviderId.OPENAI, registry.perform(ProviderAction.STATUS, scope(RuntimeId.CODEX)).providerId)
            assertEquals(listOf("account/read", "account/read"), codexCalls)
            assertFails { registry.perform(ProviderAction.STATUS, ProviderScope("../x", RuntimeId.CLAUDE_CODE)) }
        } finally { registry.close(); root.toFile().deleteRecursively() }
        assertTrue(claude.closed)
    }

    @Test fun theListOffersClaudeOnlyBehindTheGateAndCodexAlways() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hovel-provider-list")
        DesktopProviders(root, null).use { registry ->
            assertEquals(ProviderList(listOf(RuntimeId.CODEX)), registry.list("local"))
            assertEquals(ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.UNAVAILABLE, message = "Claude is not available in this version of Hovel."),
                registry.perform(ProviderAction.STATUS, ProviderScope("local", RuntimeId.CLAUDE_CODE)))
            assertEquals(ProviderModels(emptyList(), RuntimeId.CLAUDE_CODE), registry.models(ProviderScope("local", RuntimeId.CLAUDE_CODE)))
            assertFails { registry.list("../x") }
        }
        DesktopProviders(root, null, claude = ClaudeConnection(null)).use { registry ->
            assertEquals(ProviderList(listOf(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE)), registry.list(LOGIN_ID))
            // Offered but not installed: the card says how to install it.
            assertEquals(ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.UNAVAILABLE, message = "Install Claude Code on this Mac to use Claude."),
                registry.perform(ProviderAction.STATUS, ProviderScope("local", RuntimeId.CLAUDE_CODE)))
        }
        root.toFile().deleteRecursively()
    }

    @Test fun modelsComeFromCodexAndFromClaudesAliases() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hovel-provider-models")
        val codexModels = listOf(ProviderModel("GPT-6.1 Sol", true, "gpt-6.1-sol"))
        var fail = false
        val registry = DesktopProviders(root, Path.of("/bin/echo"), claude = ClaudeConnection(null),
            codexModels = { runtime -> assertEquals(codexProfileHome(root, LOGIN_ID), runtime.home); if (fail) error("Codex stopped"); codexModels })
        try {
            assertEquals(ProviderModels(codexModels, RuntimeId.CODEX), registry.models(ProviderScope(LOGIN_ID, RuntimeId.CODEX)))
            fail = true
            assertEquals(ProviderModels(emptyList(), RuntimeId.CODEX), registry.models(ProviderScope(LOGIN_ID, RuntimeId.CODEX)), "Nothing known is an empty list")
            assertEquals(ProviderModels(listOf(ProviderModel("Sonnet", true, "sonnet"), ProviderModel("Opus", false, "opus"), ProviderModel("Haiku", false, "haiku")),
                RuntimeId.CLAUDE_CODE), registry.models(ProviderScope(LOGIN_ID, RuntimeId.CLAUDE_CODE)))
        } finally { registry.close() }
        DesktopProviders(root, null).use { assertEquals(ProviderModels(emptyList(), RuntimeId.CODEX), it.models(ProviderScope("local", RuntimeId.CODEX))) }
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
