package org.formulagen.wrapper.providers

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderConnectionsTest {
    private class Rpc : CodexAccountRpc {
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
    private fun JsonObject.state() = getValue("state").jsonPrimitive.content
    private fun chatgpt() = buildJsonObject { put("type", "chatgpt"); put("email", "player@example.test"); put("planType", "plus") }

    @Test fun browserLaunchIsPendingUntilAccountReadConfirmsChatgpt() {
        val rpc = Rpc()
        val opened = mutableListOf<URI>()
        val connection = CodexConnection(rpc, { opened += it })
        assertEquals("disconnected", connection.dispatch("status").state())
        val pending = connection.dispatch("connect")
        assertEquals("signing-in", pending.state())
        assertFalse(pending.toString().contains("authUrl"))
        assertEquals("auth.openai.com", opened.single().host)
        connection.dispatch("connect")
        assertEquals(1, rpc.calls.count { it == "account/login/start" })
        connection.dispatch("reopen")
        assertEquals(2, opened.size)
        assertEquals("signing-in", connection.dispatch("status").state())
        rpc.account = chatgpt(); rpc.outcome = true
        val connected = connection.dispatch("status")
        assertEquals("connected", connected.state())
        assertEquals("player@example.test", connected["accountLabel"]?.jsonPrimitive?.content)
        assertEquals("plus", connected["plan"]?.jsonPrimitive?.content)
        assertEquals("disconnected", connection.dispatch("disconnect").state())
        connection.close(); assertTrue(rpc.closed)
    }

    @Test fun cancellationFailureAndBrowserFailureStayTruthful() {
        val rpc = Rpc()
        val connection = CodexConnection(rpc, { error("Browser not available") })
        assertEquals("signing-in", connection.dispatch("connect").state())
        assertEquals("disconnected", connection.dispatch("cancel").state())
        assertTrue(rpc.calls.contains("account/login/cancel"))
        connection.dispatch("connect"); rpc.outcome = false
        assertEquals("error", connection.dispatch("status").state())
        rpc.account = buildJsonObject { put("type", "apiKey") }
        assertFails { connection.dispatch("status") }
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
        connection.dispatch("connect")
        instant = instant.plusSeconds(601)
        assertEquals("error", connection.dispatch("status").state())
        assertTrue(rpc.calls.contains("account/login/cancel"))
    }

    @Test fun onlySupportedProviderAuthorizationUrlsCanOpen() {
        for (url in listOf("http://auth.openai.com/test", "https://auth.openai.com.evil.test/", "file:///tmp/test", "https://user@auth.openai.com/", "https://auth.openai.com:444/")) {
            val rpc = Rpc().also { it.authUrl = url }
            var opened = false
            assertFails { CodexConnection(rpc, { opened = true }).dispatch("connect") }
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

    @Test fun registryRejectsArbitraryRoutingAndClosesThePreviousProfile() = runBlocking {
        val root = Files.createTempDirectory("hovel-provider-registry")
        val processes = mutableListOf<Rpc>()
        val registry = DesktopProviders(root, Path.of("/bin/echo"), {}, { _, open ->
            val rpc = Rpc().also { processes += it }
            CodexConnection(rpc, open)
        })
        fun args(profile: String = "local") = buildJsonObject { put("providerId", "codex"); put("profile", profile) }
        try {
            assertFails { registry.dispatch("providers.execute", args()) }
            assertFails { registry.dispatch("providers.status", buildJsonObject { put("providerId", "unknown"); put("profile", "local") }) }
            assertFails { registry.dispatch("providers.status", buildJsonObject { put("providerId", "codex"); put("profile", "local"); put("path", "/tmp") }) }
            assertTrue(processes.isEmpty())
            assertEquals("disconnected", registry.dispatch("providers.status", args()).state())
            registry.dispatch("providers.connect", args())
            registry.dispatch("providers.status", args(LOGIN_ID))
            assertEquals(2, processes.size)
            assertTrue(processes.first().closed)
            assertFalse(processes.last().closed)
        } finally { registry.close(); root.toFile().deleteRecursively() }
        assertTrue(processes.last().closed)
    }

    companion object { private const val LOGIN_ID = "30000000-0000-4000-8000-000000000003" }
}
