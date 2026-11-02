package org.formulagen.wrapper.conversations

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.formulagen.wrapper.bridge.brainJson

/** Bounded coordinator-authored context; separate from consent and workflow state. */
@Serializable
internal data class SharedUnderstanding(
    val goal: String,
    val keep: String,
    val assumption: String,
    val alternatives: List<String>? = null,
) {
    init {
        require(listOf(goal, keep, assumption).all { it.isNotBlank() && it.length <= 240 })
        require(alternatives == null || (alternatives.size <= 3 && alternatives.distinct().size == alternatives.size &&
            alternatives.all { it.isNotBlank() && it.length <= 240 }))
    }
    /** Plain-text history remains useful without a dedicated card renderer. */
    fun text() = "Here's what I understood\nGoal: $goal\nKeep: $keep\nAssumption: $assumption"
}

/** Remove optional metadata before the existing strict decision parser reads its header. */
internal fun understandingHeader(header: String): Pair<String, SharedUnderstanding?> {
    val value = runCatching { brainJson.parseToJsonElement(header.trim()) }.getOrNull() as? JsonObject
        ?: return header to null
    val report = communicationHeader(header)
    val understanding = value["understanding"]?.let { brainJson.decodeFromJsonElement(SharedUnderstanding.serializer(), it) }
    if (report is ConversationCommunication.Correction && report.status == "applied") require(understanding != null)
    return JsonObject(value.filterKeys { it != "understanding" && it != "communication" }).toString() to
        understanding.takeUnless { report is ConversationCommunication.Correction }
}

/** One optional header field supplies context without introducing another decision kind. */
internal const val UNDERSTANDING_INSTRUCTIONS = "For a complex new request in the main conversation, before substantial work, optionally add " +
    "an understanding object to your first JSON header: {\"kind\":\"answer\",\"understanding\":{\"goal\":\"What the user wants\",\"keep\":\"What must stay intact\",\"assumption\":\"An explicit assumption\",\"alternatives\":[\"A plausible alternative\"]}}. " +
    "The same optional field works on any permitted decision header. Each text is 1-240 characters; alternatives are optional, at most 3 distinct strings for the assumption. " +
    "Use it sparingly for complex work, never routine conversation or thread replies. Ground every field in the request; do not invent constraints. " +
    "Do not wait for approval of this card. Continue with the normal answer or decision. A user correction is a follow-up instruction, not approval of tools or work. " +
    "When receiving a correction, acknowledge its actual effect and optionally publish a revised understanding; never claim already completed work was undone."
