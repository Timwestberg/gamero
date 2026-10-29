package org.formulagen.wrapper.providers.grok

import kotlinx.serialization.json.*

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
