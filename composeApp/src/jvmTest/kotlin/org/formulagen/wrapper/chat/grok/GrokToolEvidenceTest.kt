package org.formulagen.wrapper.chat.grok

import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.*

class GrokToolEvidenceTest {
    @Test fun toolEvidenceMustArriveForTheExactSessionAndNeverRecoversFromUnsafe() {
        val evidence = GrokToolEvidence()
        evidence.observe(toolUpdate("other", emptyList()))
        assertFails { evidence.requireAllowed("current", 10.milliseconds) }
        evidence.observe(toolUpdate("current", emptyList()))
        evidence.requireAllowed("current", 10.milliseconds)
        evidence.observe(toolUpdate("other", listOf("shell")))
        assertFails { evidence.observe(toolUpdate("current", listOf("shell"))) }
        evidence.observe(toolUpdate("current", emptyList()))
        assertFails { evidence.requireAllowed("current", 10.milliseconds) }
        val early = GrokToolEvidence()
        early.observe(toolUpdate("current", listOf("shell")))
        early.observe(toolUpdate("current", emptyList()))
        assertFails { early.requireAllowed("current", 10.milliseconds) }
    }
}

private fun toolUpdate(session: String, tools: List<String>) = buildJsonObject {
    put("sessionId", session)
    putJsonObject("update") {
        put("sessionUpdate", "available_commands_update")
        putJsonObject("_meta") { put("tools", JsonArray(tools.map(::JsonPrimitive))) }
    }
}
