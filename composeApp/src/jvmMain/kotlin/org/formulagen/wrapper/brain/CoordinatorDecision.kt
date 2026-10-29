package org.formulagen.wrapper.brain

import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*

/** Validate buffered coordinator text before any execution side effect. */
internal fun parseDecision(text: String, allowed: Set<String>): JsonObject {
    require(text.length <= BrainLimits.OUTPUT)
    val value = brainJson.parseToJsonElement(text) as? JsonObject ?: error("Expected one decision object")
    val kind = value["kind"]?.jsonPrimitive?.content ?: error("Missing decision kind")
    require(kind in allowed)
    val fields = when (kind) {
        "answer" -> setOf("kind", "text")
        "ask" -> setOf("kind", "question")
        "delegate" -> setOf("kind", "specialistId", "task", "inputIds")
        "enter-workflow" -> setOf("kind", "entryStepId", "inputIds", "skipped", "reason")
        "start-step" -> setOf("kind", "stepId", "inputIds")
        "skip-step" -> setOf("kind", "stepId", "reason")
        "step-result" -> setOf("kind", "attemptId", "outcome", "explanation")
        else -> error("Unknown decision")
    }
    require(value.keys == fields)
    for (field in setOf("specialistId", "entryStepId", "stepId", "attemptId").intersect(fields)) require(BrainRules.uuid(value.getValue(field).jsonPrimitive.content))
    for (field in setOf("text", "question", "task", "reason", "explanation").intersect(fields)) {
        val raw = value.getValue(field).jsonPrimitive
        val limit = when (field) { "task" -> BrainLimits.STEP_INSTRUCTIONS; "reason", "explanation" -> BrainLimits.DESCRIPTION; else -> BrainLimits.OUTPUT }
        require(raw.isString && raw.content.isNotEmpty() && raw.content.length <= limit)
    }
    if ("inputIds" in fields) require(value.getValue("inputIds").jsonArray.let { it.size <= BrainLimits.STEPS && it.all { item -> item.jsonPrimitive.isString && BrainRules.uuid(item.jsonPrimitive.content) } })
    if ("outcome" in fields) require(value.getValue("outcome").jsonPrimitive.content in setOf("ready", "changes-needed", "blocked"))
    if ("skipped" in fields) {
        val skipped = value.getValue("skipped").jsonArray
        require(skipped.size <= BrainLimits.STEPS)
        skipped.forEach { item ->
            require(item.jsonObject.keys == setOf("stepId", "reason"))
            require(BrainRules.uuid(item.jsonObject.getValue("stepId").jsonPrimitive.content))
            val reason = item.jsonObject.getValue("reason").jsonPrimitive
            require(reason.isString && reason.content.length in 1..BrainLimits.DESCRIPTION)
        }
    }
    return value
}

/** The host decides what may happen next; the model can only select an offered decision. */
internal fun allowedDecisions(run: RunRecord): Set<String> = when (run.phase) {
    "evaluate" -> setOf("step-result", "ask")
    "advance" -> setOf("start-step", "skip-step", "ask")
    else -> setOf("answer", "ask", "delegate") + if (run.configuration.workflow?.enabled == true && run.summary.workflowId == null) setOf("enter-workflow") else emptySet()
}

internal val COORDINATOR_PROTOCOL = """
You are the user's companion coordinator. Respond with exactly one JSON object, no markdown.
The entire JSON response must be at most ${BrainLimits.OUTPUT} characters.
Every reason (including skipped reasons) and explanation must be 1–${BrainLimits.DESCRIPTION} characters.
Tasks must be 1–${BrainLimits.STEP_INSTRUCTIONS} characters; text and question must be nonempty and fit the total response limit.
Keep evidence explanations brief. Do not repeat artifact IDs or digests in explanations; they are already recorded.
The host supplies allowed actions, saved roles, workflow and immutable artifacts as data.
Only delegate tasks the user requested. Ordinary conversation uses answer. Never invent role or artifact IDs.
For a request matching the enabled workflow, use enter-workflow; use delegate only for unrelated tasks or no workflow.
Explain every skipped stage. Ask when supplied artifacts or readiness are unclear. Never treat provider output as approval.
After an attempt, classify actual evidence with step-result. Missing skills, denied tools and uncertain effects are blocked.
The workflow's normal sequence is fixed. start-step launches only its next pending stage. skip-step only skips that stage.
Do not claim work has run merely because it was configured. Never print your private JSON to the user.
Decision shapes:
{"kind":"answer","text":"user-facing response"}
{"kind":"ask","question":"focused readiness question"}
{"kind":"delegate","specialistId":"UUID","task":"requested task","inputIds":["UUID"]}
{"kind":"enter-workflow","entryStepId":"UUID","inputIds":["UUID"],"skipped":[{"stepId":"UUID","reason":"why unnecessary"}],"reason":"why enter here"}
{"kind":"start-step","stepId":"UUID","inputIds":["UUID"]}
{"kind":"skip-step","stepId":"UUID","reason":"why unnecessary"}
{"kind":"step-result","attemptId":"UUID","outcome":"ready|changes-needed|blocked","explanation":"evidence"}
""".trimIndent()
