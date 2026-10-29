package org.formulagen.wrapper.chat.grok

import kotlinx.serialization.json.*
import kotlin.test.*

class GrokProtocolTest {
    private fun chunk(text: String, session: String = "opaque/session", kind: String = "agent_message_chunk") = buildJsonObject {
        put("sessionId", session)
        putJsonObject("update") {
            put("sessionUpdate", kind)
            putJsonObject("content") { put("type", "text"); put("text", text) }
        }
    }
    private fun finish(reason: String) = buildJsonObject { put("stopReason", reason) }

    @Test fun onlyCorrelatedAssistantTextIsAcceptedAndCompletionRequiresATerminalResponse() {
        val initial = GrokTurnState("opaque/session")
        assertEquals(initial, initial.update(chunk("Other chat", session = "other")))
        assertEquals(initial, initial.update(chunk("Private reasoning", kind = "agent_thought_chunk")))
        assertEquals(initial, initial.update(chunk("", kind = "available_commands_update")))
        assertFalse(initial.update(chunk("")).accepted)

        val streamed = initial.update(chunk("Hello ")).update(chunk("world"))
        assertEquals("Hello world", streamed.text)
        assertTrue(streamed.accepted)
        assertNull(streamed.end)
        val completed = streamed.finish(finish("end_turn"))
        assertEquals(GrokTurnEnd.COMPLETE, completed.end)
        assertEquals(completed, completed.update(chunk("late")))
        assertEquals(completed, completed.finish(finish("cancelled")))
    }

    @Test fun nonSuccessStopReasonsRemainDistinctAndPreservePartialText() {
        val partial = GrokTurnState("opaque/session").update(chunk("Partial reply"))
        assertEquals(GrokTurnEnd.CANCELLED, partial.finish(finish("cancelled")).end)
        assertEquals(GrokTurnEnd.REFUSED, partial.finish(finish("refusal")).end)
        assertEquals(GrokTurnEnd.LIMIT, partial.finish(finish("max_tokens")).end)
        assertEquals(GrokTurnEnd.LIMIT, partial.finish(finish("max_turn_requests")).end)
        assertEquals(GrokTurnEnd.INVALID, partial.finish(finish("future_reason")).end)
        assertEquals("Partial reply", partial.finish(finish("max_tokens")).text)
        assertTrue(GrokTurnState("opaque/session").finish(finish("end_turn")).accepted)
        assertFalse(GrokTurnState("opaque/session").finish(finish("refusal")).accepted)
    }

    @Test fun toolActivityIsALockdownFailureAndDoesNotBecomeATextReply() {
        val state = GrokTurnState("opaque/session").update(chunk("hello"))
        val failed = state.update(chunk("command", kind = "tool_call"))
        assertEquals(GrokTurnEnd.LOCKDOWN, failed.end)
        assertEquals("hello", failed.text)
        assertEquals(failed, failed.finish(finish("end_turn")))
        assertEquals(GrokTurnEnd.LOCKDOWN, state.update(chunk("command", kind = "tool_call_update")).end)
    }

    @Test fun malformedRequiredUpdatesAndOversizedRepliesFailWithoutReplacingGoodText() {
        val state = GrokTurnState("opaque/session").update(chunk("a".repeat(16_000)))
        assertEquals(GrokTurnEnd.LIMIT, state.update(chunk("b")).end)
        assertEquals(16_000, state.update(chunk("b")).text.length)
        assertEquals(GrokTurnEnd.INVALID, state.update(buildJsonObject {}).end)
        assertEquals(GrokTurnEnd.INVALID, state.update(buildJsonObject { put("sessionId", "opaque/session") }).end)
        assertEquals(GrokTurnEnd.INVALID, state.update(buildJsonObject {
            put("sessionId", "opaque/session")
            putJsonObject("update") { put("sessionUpdate", "agent_message_chunk"); putJsonObject("content") { put("type", "image") } }
        }).end)
    }
}
