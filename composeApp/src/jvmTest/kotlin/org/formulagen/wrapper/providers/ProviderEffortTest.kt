package org.formulagen.wrapper.providers

import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ModelEffort

class ProviderEffortTest {
    @Test fun codexEffortsAreDiscoveredDeduplicatedAndDefaultedOnlyWhenSupported() {
        val rpc = object : CodexAppServer {
            override fun close() = Unit
            override fun call(method: String, params: JsonObject): JsonObject = Json.parseToJsonElement("""{"data":[
              {"model":"gpt-6-astra","hidden":false,"isDefault":true,"defaultReasoningEffort":"medium",
               "supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"medium"},{"reasoningEffort":"ultra"},
                 {"reasoningEffort":"ultra"},{"reasoningEffort":"future"},{"reasoningEffort":1}]},
              {"model":"old-model","hidden":false},
              {"model":"bad-default","hidden":false,"defaultReasoningEffort":"high","supportedReasoningEfforts":[]}]}""").jsonObject
        }
        val models = codexModelList(rpc)
        assertEquals(listOf(ModelEffort.LOW, ModelEffort.MEDIUM, ModelEffort.ULTRA), models[0].effortLevels)
        assertEquals(ModelEffort.MEDIUM, models[0].defaultEffort)
        assertNull(models[1].effortLevels)
        assertNull(models[2].defaultEffort)
        assertEquals(emptyList(), models[2].effortLevels)
    }

    @Test fun claudeEffortSupportCanBeMissingUnsupportedOrModelSpecific() {
        val models = claudeModelList(Json.parseToJsonElement("""{"models":[
          {"value":"claude-fable-5-1[1m]","supportsEffort":true,"supportedEffortLevels":["low","high","max","future","high"]},
          {"value":"old-model"}, {"value":"unsupported","supportsEffort":false,"supportedEffortLevels":["high"]}]}""").jsonObject)
        assertEquals(listOf(ModelEffort.LOW, ModelEffort.HIGH, ModelEffort.MAX), models[0].effortLevels)
        assertNull(models[0].defaultEffort)
        assertNull(models[1].effortLevels)
        assertEquals(emptyList(), models[2].effortLevels)
    }
}
