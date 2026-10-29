package org.formulagen.wrapper.chat.grok

import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.providers.grok.validGrokSessionId

/** Protocol outcomes stay distinct until a runtime maps them into the existing chat lifecycle. */
internal enum class GrokTurnEnd { COMPLETE, CANCELLED, REFUSED, LIMIT, LOCKDOWN, INVALID }

/** One prompt's text state; restoration updates must never be routed into a live prompt. */
internal data class GrokTurnState(
    val sessionId: String,
    val text: String = "",
    val accepted: Boolean = false,
    val end: GrokTurnEnd? = null,
) {
    init { require(validGrokSessionId(sessionId)) }

    /** Fold a correlated update; thoughts and informational notifications never become reply text. */
    fun update(params: JsonObject): GrokTurnState {
        if (end != null) return this
        val session = params["sessionId"] as? JsonPrimitive
        if (session?.isString != true) return copy(end = GrokTurnEnd.INVALID)
        if (session.content != sessionId) return this
        val update = params["update"] as? JsonObject ?: return copy(end = GrokTurnEnd.INVALID)
        val kind = update["sessionUpdate"] as? JsonPrimitive
        if (kind?.isString != true) return copy(end = GrokTurnEnd.INVALID)
        return when (kind.content) {
            "agent_message_chunk" -> {
                val content = update["content"] as? JsonObject
                val chunk = content?.get("text") as? JsonPrimitive
                if (content?.get("type") != JsonPrimitive("text") || chunk?.isString != true)
                    copy(end = GrokTurnEnd.INVALID)
                else if (chunk.content.length > ChatLimits.REPLY - text.length) copy(end = GrokTurnEnd.LIMIT)
                else copy(text = text + chunk.content, accepted = accepted || chunk.content.isNotEmpty())
            }
            "tool_call", "tool_call_update" -> copy(end = GrokTurnEnd.LOCKDOWN)
            else -> this
        }
    }

    /** Completion comes only from the prompt response, never silence or the last text chunk. */
    fun finish(result: JsonObject): GrokTurnState {
        if (end != null) return this
        val outcome = when (result["stopReason"]) {
            JsonPrimitive("end_turn") -> GrokTurnEnd.COMPLETE
            JsonPrimitive("cancelled") -> GrokTurnEnd.CANCELLED
            JsonPrimitive("refusal") -> GrokTurnEnd.REFUSED
            JsonPrimitive("max_tokens"), JsonPrimitive("max_turn_requests") -> GrokTurnEnd.LIMIT
            else -> GrokTurnEnd.INVALID
        }
        return copy(end = outcome, accepted = accepted || outcome == GrokTurnEnd.COMPLETE)
    }
}
