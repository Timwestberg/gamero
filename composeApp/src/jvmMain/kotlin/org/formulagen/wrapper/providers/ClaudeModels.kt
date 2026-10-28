package org.formulagen.wrapper.providers

import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ProviderLimits
import org.formulagen.wrapper.bridge.ProviderModel
import org.formulagen.wrapper.bridge.SharedRules

/** Claude Code's selectable models, without a prompt or a saved conversation. */
internal fun readClaudeModels(runtime: ClaudeRuntime): List<ProviderModel> =
    claudeModelList(readClaudeControl(runtime, ClaudeRead.MODELS))

/** Keep runtime model values intact, including context suffixes; discard malformed and duplicate entries. */
internal fun claudeModelList(response: JsonObject?): List<ProviderModel> {
    val models = (response?.get("models") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val default = models.firstOrNull { it.text("value") == "default" }?.text("resolvedModel")
    return models.mapNotNull { model ->
        val id = model.text("value")?.takeIf { it != "default" && SharedRules.isModelId(it) } ?: return@mapNotNull null
        val name = model.text("displayName")?.takeIf { it.isNotBlank() && it.length <= ProviderLimits.DISPLAY_NAME }
            ?: id.take(ProviderLimits.DISPLAY_NAME)
        val efforts = if ((model["supportsEffort"] as? JsonPrimitive)?.booleanOrNull == false) emptyList()
            else providerEfforts(model["supportedEffortLevels"] as? JsonArray)
        ProviderModel(name, default != null && model.text("resolvedModel") == default, id, efforts)
    }.distinctBy { it.modelId }.take(ProviderLimits.MODELS)
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
