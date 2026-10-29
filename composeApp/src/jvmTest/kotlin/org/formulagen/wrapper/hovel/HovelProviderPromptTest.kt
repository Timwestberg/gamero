package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.providerPrompt
import org.junit.Test
import java.util.UUID
import kotlin.test.*

/** The companion sends a task's provider text byte for byte as the server's `hovelProviderPrompt` builds it. */
class HovelProviderPromptTest {
    private val host = UUID.randomUUID().toString()

    /** A task as the server sends it, carrying one fixture case's inputs. */
    private fun task(case: JsonObject) = buildJsonObject {
        put("ownerId", UUID.randomUUID().toString()); put("revision", 0)
        put("characterMemory", case.getValue("characterMemory"))
        put("progress", buildJsonObject { put("state", "queued") })
        case["providerInput"]?.let { put("providerInput", it) }
        case["recalledMemory"]?.let { put("recalledMemory", it) }
        put("request", buildJsonObject {
            put("id", UUID.randomUUID().toString()); put("hostId", host); put("mode", "local")
            put("prompt", case.getValue("prompt"))
            if ("providerInput" in case) put("followUp", buildJsonObject {
                put("conversationId", UUID.randomUUID().toString()); put("previousTaskId", UUID.randomUUID().toString())
            })
        })
    }

    @Test fun everyFixtureCaseBuildsTheServersExactText() {
        val cases = Json.parseToJsonElement(HovelProviderPromptFixture.JSON).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(setOf("first-task", "recalled-memory", "follow-up-with-recalled-memory", "empty-character-memory"),
            cases.map { it.getValue("name").jsonPrimitive.content }.toSet())
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            assertEquals(case.getValue("expected").jsonPrimitive.content, providerPrompt(hovelExecutionRequest(task(case), host)), name)
        }
    }
}
