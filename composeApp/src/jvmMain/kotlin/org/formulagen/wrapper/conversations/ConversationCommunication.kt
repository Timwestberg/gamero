package org.formulagen.wrapper.conversations

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import org.formulagen.wrapper.bridge.brainJson

private fun reportText(value: String, limit: Int = 600) = require(value.isNotBlank() && value.length <= limit)

/** Coordinator reports describe intent and evidence; they never grant execution permission. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface ConversationCommunication {
    @Serializable @SerialName("correction")
    data class Correction(val status: String, val change: String, val earlierWork: String,
        val correctionMessageId: String? = null) : ConversationCommunication {
        init { require(status in setOf("applied", "needs-decision")); reportText(change); reportText(earlierWork) }
    }
    @Serializable @SerialName("direction")
    data class Direction(val status: String, val change: String, val earlierWork: String,
        val directionMessageId: String? = null) : ConversationCommunication {
        init { require(status in setOf("applied", "needs-decision")); reportText(change); reportText(earlierWork) }
    }
    @Serializable @SerialName("progress")
    data class Progress(val purpose: String) : ConversationCommunication { init { reportText(purpose, 240) } }
    @Serializable @SerialName("handoff")
    data class Handoff(val changed: String, val checks: List<ReportedCheck>, val attention: String) : ConversationCommunication {
        init { reportText(changed); reportText(attention); require(checks.size <= 8) }
    }
    /** History and older native consumers retain a plain-text account of every report. */
    fun text(): String = when (this) {
        is Correction -> "${if (status == "applied") "Applied to plan" else "Needs your decision"}: $change\nEarlier work: $earlierWork"
        is Direction -> "Direction ${status}: $change\nEarlier work: $earlierWork"
        is Progress -> purpose
        is Handoff -> "What changed: $changed\nChecks (coordinator report):\n" +
            checks.joinToString("\n") { "${it.label}: ${it.result}. ${it.evidence}" } + "\nNeeds your attention: $attention"
    }
}

@Serializable
internal data class ReportedCheck(val label: String, val result: String, val evidence: String) {
    init { reportText(label); reportText(evidence); require(result in setOf("passed", "failed", "not-run")) }
}

/** Decode optional report metadata separately from the existing control decision. */
internal fun communicationHeader(header: String): ConversationCommunication? {
    val value = runCatching { brainJson.parseToJsonElement(header.trim()) }.getOrNull() as? JsonObject ?: return null
    return value["communication"]?.let { brainJson.decodeFromJsonElement(ConversationCommunication.serializer(), it) }
}

internal const val COMMUNICATION_INSTRUCTIONS = "In the main conversation, communicate purpose and outcomes using an optional communication field in your JSON decision header. " +
    "For progress use {\"kind\":\"progress\",\"purpose\":\"Checking whether the keyboard covers Send\"}; purpose is at most 240 characters. " +
    "State actual intent, never invented activity. For a user correction beginning 'Update shared understanding', use " +
    "{\"kind\":\"correction\",\"status\":\"applied\",\"change\":\"Main conversation only; specialist threads stay unchanged\",\"earlierWork\":\"No earlier edits need reverting\"}. " +
    "Applied means adopted into the plan, and requires a revised understanding field in the same header. Otherwise use status needs-decision and explain the choice and earlier-work impact; never imply a tool approval. Do not supply correctionMessageId; the host binds it. " +
    "When finishing substantive work, an answer header may include {\"kind\":\"handoff\",\"changed\":\"What actually changed\",\"checks\":[{\"label\":\"Browser tests\",\"result\":\"passed\",\"evidence\":\"Describe actual observed results\"}],\"attention\":\"What the user should check or decide\"}. " +
    "Check result is passed, failed or not-run. Do not infer success from completion. Include unrun checks explicitly, never invent verification. At most 8 checks; all other report strings are 1-600 characters. " +
    "For a message with direction instructions, acknowledge using {\"kind\":\"direction\",\"status\":\"applied\",\"change\":\"What the next step will do differently\",\"earlierWork\":\"What can be kept or needs review\"}. Use needs-decision for unresolved choices. Do not supply directionMessageId; the host binds it. Applied means you adopted the instruction for the next step, not that edits are complete. " +
    "All reports are optional for ordinary chat. Keep technical commands in detailed activity. Reports do not replace the normal answer and never authorize work."

/** Required only for a direction turn; omission triggers a bounded protocol repair before dispatch. */
internal const val DIRECTION_REPORT_REQUIRED = "Your first decision must include a communication field in its JSON header: {\"kind\":\"direction\",\"status\":\"applied\",\"change\":\"How the next step changes\",\"earlierWork\":\"What earlier work can be kept or needs review\"}. Use needs-decision instead of applied when unresolved. Do not supply directionMessageId. This acknowledgement is required for this direction turn, even when answering without delegation."
