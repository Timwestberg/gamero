package org.formulagen.wrapper.bridge

import kotlin.test.*
import kotlinx.serialization.json.*

class ModelEffortWireTest {
    @Test fun optionalEffortRoundTripsAndUnknownValuesAreRejected() {
        val binding = BrainBinding(RuntimeId.CODEX, "gpt-6-astra")
        val old = brainWire(binding).jsonObject
        assertFalse("effort" in old)
        val wire = JsonObject(old + ("effort" to JsonPrimitive("high")))
        assertEquals(binding.copy(effort = ModelEffort.HIGH), brainJson.decodeFromJsonElement(BrainBinding.serializer(), wire))
        assertFails { brainJson.decodeFromJsonElement(BrainBinding.serializer(), JsonObject(old + ("effort" to JsonPrimitive("unknown")))) }
        val model = ProviderModel("Astra", false, "gpt-6-astra", listOf(ModelEffort.LOW, ModelEffort.HIGH), ModelEffort.LOW)
        assertEquals(model, bridgeJson.decodeFromJsonElement(ProviderModel.serializer(), bridgeJson.encodeToJsonElement(ProviderModel.serializer(), model)))
    }
}
