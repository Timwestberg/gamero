package org.formulagen.wrapper.bridge

import kotlin.test.*
import kotlinx.serialization.json.*

class ModelEffortWireTest {
    @Test fun optionalEffortRoundTripsAndUnknownValuesAreRejected() {
        val member = TeamMember(RuntimeId.CODEX, "gpt-6-astra")
        val send = ChatSend("local", "10000000-0000-4000-8000-000000000001", "Ember",
            "20000000-0000-4000-8000-000000000002", member, "30000000-0000-4000-8000-000000000003", "Hi", false)
        val old = bridgeJson.encodeToJsonElement(ChatSend.serializer(), send).jsonObject
        assertFalse("effort" in old)
        val wire = JsonObject(old + ("effort" to JsonPrimitive("high")))
        assertEquals(send.copy(effort = ModelEffort.HIGH), bridgeJson.decodeFromJsonElement(ChatSend.serializer(), wire))
        assertFails { bridgeJson.decodeFromJsonElement(ChatSend.serializer(), JsonObject(old + ("effort" to JsonPrimitive("unknown")))) }
        val model = ProviderModel("Astra", false, "gpt-6-astra", listOf(ModelEffort.LOW, ModelEffort.HIGH), ModelEffort.LOW)
        assertEquals(model, bridgeJson.decodeFromJsonElement(ProviderModel.serializer(), bridgeJson.encodeToJsonElement(ProviderModel.serializer(), model)))
    }
}
