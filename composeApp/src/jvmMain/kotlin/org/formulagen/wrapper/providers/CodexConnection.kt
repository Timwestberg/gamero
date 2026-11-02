package org.formulagen.wrapper.providers

import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderDetail
import org.formulagen.wrapper.bridge.ProviderLimits
import org.formulagen.wrapper.bridge.ProviderModel
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.bridge.ProviderUsage
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules

/** The Codex account process behind [CodexProvider], replaceable in tests. */
internal interface ProviderConnection : AutoCloseable {
    fun perform(action: ProviderAction): ProviderStatus
    /** The models this account's plan offers a team member. */
    fun models(): List<ProviderModel>
    fun usage(): ProviderUsage
}

/**
 * The player's own Codex sign-in, read with `account/read`. Codex owns sign-in and sign-out
 * (`codex login`, `codex logout` in Terminal), so every action other than [ProviderAction.STATUS]
 * answers the status with where to do them: signing out from Hovel would sign the player out of
 * Codex everywhere. Only a ChatGPT login is connected; an API-key login is an error, never used.
 */
internal class CodexConnection(private val rpc: CodexAppServer) : ProviderConnection {
    override fun perform(action: ProviderAction): ProviderStatus =
        status().let { if (action == ProviderAction.STATUS) it else it.copy(message = CODEX_SIGN_IN_ELSEWHERE) }

    /** Readiness only: a forced refresh costs a round trip and can race the player's other Codex sessions sharing the login. */
    private fun status(): ProviderStatus {
        val result = rpc.call("account/read", buildJsonObject { put("refreshToken", false) })
        require(result.containsKey("account")) { "Invalid Codex account reply" }
        return codexAccountStatus(result["account"])
    }

    override fun models(): List<ProviderModel> = codexModelList(rpc)

    override fun usage(): ProviderUsage = if (status().state == ProviderState.CONNECTED)
        codexUsage(rpc.call("account/rateLimits/read"), System.currentTimeMillis())
    else ProviderUsage(RuntimeId.CODEX, System.currentTimeMillis())

    override fun close() = rpc.close()
}

/** What every non-status Codex action answers with: Hovel never signs Codex in or out. */
internal const val CODEX_SIGN_IN_ELSEWHERE = "Sign in and out with `codex login` in Terminal."

/** An API-key login is never used: conversations and memory jobs run only on the player's ChatGPT subscription. */
internal const val CODEX_API_KEY = "Codex is using an API key. Sign in with your ChatGPT subscription to use it here."

/**
 * The card state for `account/read`'s `account`: none is signed out, a ChatGPT account is connected
 * (with its email and plan for the card), an API key is an error. Anything else is not understood,
 * and throws.
 */
internal fun codexAccountStatus(account: JsonElement?): ProviderStatus {
    // The page owns the sign-in help for a signed-out card.
    if (account == null || account == JsonNull) return ProviderStatus(RuntimeId.CODEX, ProviderState.DISCONNECTED, detail = ProviderDetail.SIGNED_OUT)
    require(account is JsonObject) { "Invalid Codex account" }
    return when ((account["type"] as? JsonPrimitive)?.contentOrNull) {
        "chatgpt" -> ProviderStatus(RuntimeId.CODEX, ProviderState.CONNECTED,
            accountLabel = (account["email"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= ProviderLimits.ACCOUNT_LABEL },
            plan = (account["planType"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= ProviderLimits.PLAN })
        "apiKey" -> ProviderStatus(RuntimeId.CODEX, ProviderState.ERROR, message = CODEX_API_KEY, detail = ProviderDetail.API_KEY)
        else -> error("Unsupported Codex account")
    }
}

/**
 * The models Codex offers the signed-in plan, for a team member to choose from: visible ones whose
 * id can be a `modelId`, the runtime's default marked, at most [ProviderLimits.MODELS].
 */
internal fun codexModelList(rpc: CodexAppServer): List<ProviderModel> {
    val models = mutableListOf<ProviderModel>()
    var cursor: String? = null
    var pages = 0
    while (pages++ < 4 && models.size < ProviderLimits.MODELS) {
        val result = rpc.call("model/list", buildJsonObject { put("includeHidden", false); cursor?.let { put("cursor", it) } })
        for (item in (result["data"] as? JsonArray).orEmpty()) {
            val model = item as? JsonObject ?: continue
            if ((model["hidden"] as? JsonPrimitive)?.booleanOrNull != false) continue
            val id = (model["model"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (!SharedRules.isModelId(id) || models.any { it.modelId == id }) continue
            val name = (model["displayName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= ProviderLimits.DISPLAY_NAME } ?: id
            val efforts = providerEfforts((model["supportedReasoningEfforts"] as? JsonArray)?.map { (it as? JsonObject)?.get("reasoningEffort") })
            val defaultEffort = providerEffort(model["defaultReasoningEffort"])?.takeIf { efforts?.contains(it) == true }
            models += ProviderModel(name, (model["isDefault"] as? JsonPrimitive)?.booleanOrNull == true, id, efforts, defaultEffort)
        }
        cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull ?: break
    }
    return models.take(ProviderLimits.MODELS)
}
