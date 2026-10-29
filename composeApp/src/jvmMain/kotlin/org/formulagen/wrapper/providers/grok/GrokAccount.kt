package org.formulagen.wrapper.providers.grok

import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ProviderModel
import org.formulagen.wrapper.bridge.SharedRules

/** Runtime-reported subscription access, separate from saved-login detection and talk isolation. */
internal data class GrokAccount(val fingerprint: String, val defaultModel: String, val models: List<ProviderModel>)

/** One deadline shared by version, configuration, authentication and session startup. */
internal class GrokBudget(private val timeout: Duration = 15.seconds) {
    private val start = TimeSource.Monotonic.markNow()
    fun remaining(): Duration = (timeout - start.elapsedNow()).also { check(it.isPositive()) { "Grok startup timed out" } }
}

/** Authenticate cached OIDC in a verified, OIDC-pinned launch. Never fall back to interactive login. */
internal fun grokAccount(rpc: GrokAcp, initialized: JsonObject, budget: GrokBudget): GrokAccount {
    check(grokHandshake(initialized).auth == GrokAuthAvailability.CACHED_CREDENTIAL) { "Grok sign-in required" }
    val before = grokAccountFingerprint(rpc.call("_x.ai/auth/info", timeout = budget.remaining()))
    val subscription = rpc.call("_x.ai/auth/check_subscription", timeout = budget.remaining())
    check(subscription["authenticated"] == JsonPrimitive(true)) { "Grok subscription unavailable" }
    verifyGrokSubscription(subscription["meta"] as? JsonObject ?: error("Missing Grok subscription"))
    val authenticated = rpc.call("authenticate", buildJsonObject {
        put("methodId", "cached_token"); putJsonObject("_meta") { put("headless", true) }
    }, budget.remaining())
    verifyGrokSubscription(authenticated["_meta"] as? JsonObject ?: error("Missing Grok authentication result"))
    val after = grokAccountFingerprint(rpc.call("_x.ai/auth/info", timeout = budget.remaining()))
    check(before == after) { "Grok account changed" }
    val state = (initialized["_meta"] as? JsonObject)?.get("modelState") as? JsonObject ?: error("Missing Grok model state")
    val (model, models) = grokModels(state)
    return GrokAccount(after, model, models)
}

/** No raw account identity, email, team label or token leaves the parser. */
internal fun grokAccountFingerprint(info: JsonObject): String {
    check(info["methodId"] == JsonPrimitive("cached_token"))
    check(info["userBlockedReason"] == null || info["userBlockedReason"] == JsonNull)
    check(info["teamBlockedReasons"] == null || info["teamBlockedReasons"] == JsonNull || (info["teamBlockedReasons"] as? JsonArray)?.isEmpty() == true)
    val fields = listOf("principalType", "principalId", "teamId", "organizationId").map { key ->
        val value = info[key]
        if (key in listOf("teamId", "organizationId") && (value == null || value == JsonNull)) ""
        else (value as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= 256 && it.content.none(Char::isISOControl) }
            ?.content ?: error("Unverified Grok identity")
    }
    val encoded = JsonArray(fields.map(::JsonPrimitive)).toString()
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** A free, API-key, gated or managed account cannot become a subscription-ready isolated conversation. */
internal fun verifyGrokSubscription(meta: JsonObject) {
    check(meta["auth_mode"] == JsonPrimitive("Oidc") && meta["backend_billed"] == JsonPrimitive(false))
    check(meta["is_team_principal"] == JsonPrimitive(false) && meta["is_organization_principal"] == JsonPrimitive(false))
    check(meta["gate"] == JsonNull) { "Grok access is gated or unknown" }
    val tier = (meta["subscription_tier"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    check(tier != null && tier.isNotBlank() && tier != "Free" && tier.length <= 128 && tier.none(Char::isISOControl))
}

/** Validate a bounded catalog only after subscription authentication; never invent or truncate model identifiers. */
internal fun grokModels(state: JsonObject): Pair<String, List<ProviderModel>> {
    val current = (state["currentModelId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing default model")
    val entries = state["availableModels"] as? JsonArray ?: error("Missing model catalog")
    check(entries.size in 1..32)
    val models = entries.map { entry ->
        val value = entry as? JsonObject ?: error("Invalid model")
        val id = (value["modelId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid model id")
        val name = (value["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid model name")
        check(SharedRules.isModelId(id) && id != SharedRules.RUNTIME_DEFAULT_MODEL)
        check(name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl))
        ProviderModel(displayName = name, isDefault = id == current, modelId = id)
    }
    check(models.map { it.modelId }.distinct().size == models.size && models.any { it.modelId == current })
    return current to models
}

internal val GROK_ACCOUNT_METHODS = setOf("initialize", "_x.ai/auth/info", "_x.ai/auth/check_subscription", "authenticate")
