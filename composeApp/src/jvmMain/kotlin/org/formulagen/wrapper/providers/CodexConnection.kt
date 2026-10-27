package org.formulagen.wrapper.providers

import java.net.URI
import java.time.Clock
import java.util.UUID
import kotlinx.serialization.json.*

/** An independently replaceable provider connection behind the desktop registry. */
internal interface ProviderConnection : AutoCloseable {
    fun dispatch(action: String): JsonObject
}

/** Codex owns OAuth and tokens; this adapter exposes only display state and explicit actions. */
internal class CodexConnection(
    private val rpc: CodexAccountRpc,
    private val openBrowser: (URI) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : ProviderConnection {
    private data class Login(val id: String, val url: URI, val startedAt: Long)
    private var login: Login? = null

    /** Route only the account lifecycle; no inference or workspace execution is available here. */
    override fun dispatch(action: String): JsonObject = when (action) {
        "status" -> status()
        "connect" -> connect()
        "reopen" -> {
            val pending = login ?: error("No sign-in is pending")
            openBrowser(pending.url)
            reply("signing-in", "Finish signing in in your browser, then return here.")
        }
        "cancel" -> {
            cancel()
            status()
        }
        "disconnect" -> {
            cancel()
            rpc.call("account/logout")
            status()
        }
        else -> error("Unsupported provider action")
    }

    /** Connected means a confirmed ChatGPT account, not an API key or a successful browser launch. */
    private fun status(): JsonObject {
        val pending = login
        if (pending != null && clock.millis() - pending.startedAt > 10 * 60 * 1000) {
            cancel()
            return reply("error", "Sign-in expired. Connect Codex again.")
        }
        if (pending != null && rpc.loginResult(pending.id) == false) {
            login = null
            return reply("error", "Sign-in did not finish. You can try connecting again.")
        }
        // Readiness only: a forced refresh costs a round trip and can race another app-server sharing the keyring login.
        val result = rpc.call("account/read", buildJsonObject { put("refreshToken", false) })
        require(result.containsKey("account")) { "Invalid Codex account reply" }
        val account = result["account"]
        if (account == JsonNull) {
            if (pending != null && rpc.loginResult(pending.id) == true) login = null
            return if (login != null) reply("signing-in", "Finish signing in in your browser, then return here.")
                else reply("disconnected")
        }
        require(account is JsonObject && account["type"] == JsonPrimitive("chatgpt")) { "A ChatGPT subscription login is required" }
        login = null
        return buildJsonObject {
            put("providerId", "codex"); put("state", "connected")
            (account["email"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 320 }?.let { put("accountLabel", it) }
            (account["planType"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 80 }?.let { put("plan", it) }
        }
    }

    /** Keep the pending login in native memory and open only the provider's HTTPS authorization page. */
    private fun connect(): JsonObject {
        if (login != null) return reply("signing-in", "Sign-in is already open in your browser.")
        val result = rpc.call("account/login/start", buildJsonObject { put("type", "chatgpt") })
        require(result["type"] == JsonPrimitive("chatgpt"))
        val id = result.getValue("loginId").jsonPrimitive.content
        require(UUID.fromString(id).toString() == id)
        val url = URI(result.getValue("authUrl").jsonPrimitive.content)
        require(url.scheme == "https" && url.host == "auth.openai.com" && url.userInfo == null && url.port == -1)
        login = Login(id, url, clock.millis())
        return try {
            openBrowser(url)
            reply("signing-in", "Finish signing in in your browser, then return here.")
        } catch (_: Exception) {
            reply("signing-in", "Your browser could not open. Choose Open sign-in to try again.")
        }
    }

    /** Cancel before clearing local state so uncertain native outcomes remain failures. */
    private fun cancel() {
        login?.let { rpc.call("account/login/cancel", buildJsonObject { put("loginId", it.id) }) }
        login = null
    }

    /** Keep provider internals out of the web-facing display contract. */
    private fun reply(state: String, message: String? = null) = buildJsonObject {
        put("providerId", "codex"); put("state", state); message?.let { put("message", it) }
    }

    /** Terminating this owned process also cancels its pending browser callback. */
    override fun close() = rpc.close()
}
