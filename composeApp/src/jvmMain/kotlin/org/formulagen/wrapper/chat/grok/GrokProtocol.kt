package org.formulagen.wrapper.chat.grok

import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ChatLimits

/** An advertised credential path is not verified subscription eligibility. */
internal enum class GrokAuthAvailability { CACHED_CREDENTIAL, SIGNED_OUT, API_KEY, UNKNOWN }

/** Only standard initialization fields; model metadata is not an eligible-model catalog. */
internal data class GrokHandshake(val auth: GrokAuthAvailability, val loadSession: Boolean)

/** Validate the version and bounded auth vocabulary without invoking authentication or fallbacks. */
internal fun grokHandshake(result: JsonObject): GrokHandshake {
    require(result["protocolVersion"] == JsonPrimitive(1)) { "Unsupported Grok ACP version" }
    val methods = result["authMethods"] as? JsonArray ?: error("Missing Grok authentication methods")
    require(methods.size <= 8)
    val ids = methods.map { method ->
        ((method as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl) }
            ?: error("Invalid Grok authentication method")
    }
    require(ids.distinct().size == ids.size)
    val auth = when {
        "cached_token" in ids -> GrokAuthAvailability.CACHED_CREDENTIAL
        "xai.api_key" in ids -> GrokAuthAvailability.API_KEY
        ids.isNotEmpty() && ids.all { it == "grok.com" || it == "oidc" } -> GrokAuthAvailability.SIGNED_OUT
        else -> GrokAuthAvailability.UNKNOWN
    }
    val capabilities = result["agentCapabilities"] as? JsonObject ?: error("Missing Grok capabilities")
    val loading = capabilities["loadSession"]
    require(loading == null || loading == JsonPrimitive(true) || loading == JsonPrimitive(false))
    return GrokHandshake(auth, loading == JsonPrimitive(true))
}

/** Protocol outcomes stay distinct until a runtime maps them into the existing chat lifecycle. */
internal enum class GrokTurnEnd { COMPLETE, CANCELLED, REFUSED, LIMIT, LOCKDOWN, INVALID }

/** One prompt's text state; restoration updates must never be routed into a live prompt. */
internal data class GrokTurnState(
    val sessionId: String,
    val text: String = "",
    val accepted: Boolean = false,
    val end: GrokTurnEnd? = null,
) {
    init { require(validGrokSessionId(sessionId)) }

    /** Fold a correlated update; thoughts and informational notifications never become reply text. */
    fun update(params: JsonObject): GrokTurnState {
        if (end != null) return this
        val session = params["sessionId"] as? JsonPrimitive
        if (session?.isString != true) return copy(end = GrokTurnEnd.INVALID)
        if (session.content != sessionId) return this
        val update = params["update"] as? JsonObject ?: return copy(end = GrokTurnEnd.INVALID)
        val kind = update["sessionUpdate"] as? JsonPrimitive
        if (kind?.isString != true) return copy(end = GrokTurnEnd.INVALID)
        return when (kind.content) {
            "agent_message_chunk" -> {
                val content = update["content"] as? JsonObject
                val chunk = content?.get("text") as? JsonPrimitive
                if (content?.get("type") != JsonPrimitive("text") || chunk?.isString != true)
                    copy(end = GrokTurnEnd.INVALID)
                else if (chunk.content.length > ChatLimits.REPLY - text.length) copy(end = GrokTurnEnd.LIMIT)
                else copy(text = text + chunk.content, accepted = accepted || chunk.content.isNotEmpty())
            }
            "tool_call", "tool_call_update" -> copy(end = GrokTurnEnd.LOCKDOWN)
            else -> this
        }
    }

    /** Completion comes only from the prompt response, never silence or the last text chunk. */
    fun finish(result: JsonObject): GrokTurnState {
        if (end != null) return this
        val outcome = when (result["stopReason"]) {
            JsonPrimitive("end_turn") -> GrokTurnEnd.COMPLETE
            JsonPrimitive("cancelled") -> GrokTurnEnd.CANCELLED
            JsonPrimitive("refusal") -> GrokTurnEnd.REFUSED
            JsonPrimitive("max_tokens"), JsonPrimitive("max_turn_requests") -> GrokTurnEnd.LIMIT
            else -> GrokTurnEnd.INVALID
        }
        return copy(end = outcome, accepted = accepted || outcome == GrokTurnEnd.COMPLETE)
    }
}
