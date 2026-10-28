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

    companion object { private const val LOGIN_ID = "30000000-0000-4000-8000-000000000003" }
}
