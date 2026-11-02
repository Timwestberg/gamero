package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.formulagen.wrapper.conversation.ActivityLimits
import org.formulagen.wrapper.conversation.ChatLimits

/**
 * The limits, the model name rule and each runtime's provider, as native code mirrors them, equal event-contracts
 * `fixtures/limits.json`.
 */
class SharedLimitsTest {
    private val limits = Json.parseToJsonElement(HostBridgeFixtures.LIMITS).jsonObject
    private fun numbers(name: String) = limits.getValue(name).jsonObject.mapValues { it.value.jsonPrimitive.int }
    private fun wire(runtime: RuntimeId) = bridgeJson.encodeToString(RuntimeId.serializer(), runtime).trim('"')
    private fun wire(provider: RuntimeProviderId) = bridgeJson.encodeToString(RuntimeProviderId.serializer(), provider).trim('"')

    @Test fun nativeMirrorsEqualTheContract() {
        assertEquals(mapOf("notice" to ChatLimits.NOTICE), numbers("CONVERSATION_VOICE_LIMITS"))
        assertEquals(ChatLimits.REPLY, numbers("COMPANION_LIMITS").getValue("message"))
        assertEquals(SharedRules.COMPANION_NAME_LIMIT, limits.getValue("COMPANION_NAME_LIMIT").jsonPrimitive.int)
        assertEquals(mapOf("detail" to ActivityLimits.DETAIL, "label" to ActivityLimits.LABEL, "steps" to ActivityLimits.STEPS), numbers("ACTIVITY_LIMITS"))
        assertEquals(mapOf("accountLabel" to ProviderLimits.ACCOUNT_LABEL, "displayName" to ProviderLimits.DISPLAY_NAME,
            "message" to ProviderLimits.MESSAGE, "models" to ProviderLimits.MODELS, "plan" to ProviderLimits.PLAN,
            "runtimes" to ProviderLimits.RUNTIMES), numbers("PROVIDER_LIMITS"))
        // The Mac mirrors only the brain limits it applies; each one equals the contract's.
        val brain = mapOf("description" to BrainLimits.DESCRIPTION, "dispatches" to BrainLimits.DISPATCHES, "history" to BrainLimits.HISTORY,
            "input" to BrainLimits.INPUT, "inputs" to BrainLimits.INPUTS, "label" to BrainLimits.LABEL, "output" to BrainLimits.OUTPUT,
            "preview" to BrainLimits.PREVIEW, "returns" to BrainLimits.RETURNS, "stepInstructions" to BrainLimits.STEP_INSTRUCTIONS,
            "steps" to BrainLimits.STEPS)
        assertEquals(brain, numbers("BRAIN_LIMITS").filterKeys { it in brain })
        assertEquals(SharedRules.MODEL_ID_PATTERN, limits.getValue("MODEL_ID_PATTERN").jsonPrimitive.content)
        assertEquals(BridgeUpdateRequiredException.MESSAGE, limits.getValue("UPDATE_REQUIRED_MESSAGE").jsonPrimitive.content)
        assertEquals(limits.getValue("RUNTIME_PROVIDERS").jsonObject.mapValues { it.value.jsonPrimitive.content },
            RuntimeId.entries.associate { wire(it) to wire(it.providerId) })
    }

    @Test fun modelNamesAreOneLowerCaseTokenThatIsNeverAnOption() {
        for (name in listOf("sonnet", "runtime-default", "gpt-6.1-sol", "llama3.1:8b", "org/model", "a" + "b".repeat(127))) assertTrue(SharedRules.isModelId(name), name)
        for (name in listOf("", "-p", "--dangerously-skip-permissions", "Sonnet", "a b", "a" + "b".repeat(128), ".hidden")) assertFalse(SharedRules.isModelId(name), name)
    }
}
