package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.ExecutionRequest
import org.formulagen.wrapper.companion.providerPrompt
import org.junit.Test
import java.util.UUID
import kotlin.test.*

/** The companion's provider text is byte for byte the server's `hovelProviderPrompt`. */
class HovelProviderPromptTest {
    private val host = UUID.randomUUID().toString()

    /** One fixture case's inputs as a request: the conversation text when it has one, else its prompt. */
    private fun request(case: JsonObject) = ExecutionRequest(UUID.randomUUID().toString(), host,
        (case["providerInput"] ?: case.getValue("prompt")).jsonPrimitive.content, case.getValue("characterMemory").jsonPrimitive.content,
        recalledMemory = case["recalledMemory"]?.jsonPrimitive?.content)

    @Test fun everyFixtureCaseBuildsTheServersExactText() {
        val cases = Json.parseToJsonElement(HovelProviderPromptFixture.JSON).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(setOf("first-task", "recalled-memory", "follow-up-with-recalled-memory", "empty-character-memory"),
            cases.map { it.getValue("name").jsonPrimitive.content }.toSet())
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            assertEquals(case.getValue("expected").jsonPrimitive.content, providerPrompt(request(case)), name)
        }
    }
}
