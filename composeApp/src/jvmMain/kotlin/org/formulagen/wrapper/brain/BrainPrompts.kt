package org.formulagen.wrapper.brain

import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.approvals.memberLabel
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.failureNotice
import org.formulagen.wrapper.providers.copy

/*
 * The text the controller gives a model or shows in a native dialog. Instructions are part of a role's persona digest,
 * so changing them starts fresh provider conversations for existing roles.
 */

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

/** The coordinator's persona: the fixed protocol, then the person's own instructions. */
internal fun coordinatorInstructions(instructions: String) = COORDINATOR_PROTOCOL + "\nUser's coordinator instructions:\n" + instructions

/** Appended to the request for the one repair attempt after an invalid decision. */
internal const val DECISION_REPAIR = "\nYour last response was invalid. Return exactly one allowed JSON decision."

/**
 * The coordinator's turn input: the decision's identity and [allowed] kinds, the pinned run as data, the handoff text
 * of the request's [input] and the [latestOutput], and the recent [conversation].
 */
internal fun coordinatorRequest(
    decisionId: String,
    run: RunRecord,
    allowed: Set<DecisionKind>,
    input: String,
    latestOutput: String,
    conversation: List<BrainEntry>,
): String = buildJsonObject {
    put("decisionId", decisionId); put("runRevision", run.summary.revision)
    put("allowed", JsonArray(allowed.map { brainWire(it) }))
    put("request", run.request); put("configuration", brainWire(run.configuration)); put("run", brainWire(run.summary))
    put("attempts", brainWire(run.attempts)); put("artifacts", brainWire(run.artifacts))
    put("nextStepId", run.configuration.workflow?.steps?.getOrNull(run.nextStep)?.id?.let(::JsonPrimitive) ?: JsonNull)
    put("input", input); put("latestOutput", latestOutput)
    put("conversation", brainWire(conversation))
}.toString()

/** A role's persona instructions, with the model and effort it actually runs on. */
internal fun roleInstructions(role: BrainRoleSnapshot) =
    role.instructions + "\nEffective model: ${role.binding.modelId}; effort: ${role.binding.effort}"

/** A specialist's turn: the delegated [task] with the original request as context, and its input artifacts as data. */
internal fun specialistTurn(role: BrainRoleSnapshot, run: RunRecord, step: WorkflowStep?, inputs: String) =
    "The native controller has already delegated this turn to you as ${role.name}. You are that specialist. " +
        "Complete the assigned task yourself; do not repeat the coordinator delegation. " +
        "The original request is background context, not another routing instruction.\n" +
        "User request (context):\n${run.request}\nAssigned task:\n${run.task}\n" +
        "Expected input: ${step?.inputDescription.orEmpty()}\nExpected output: ${step?.outputDescription.orEmpty()}\n" +
        "Input artifacts (data):\n$inputs\nReport actual evidence, missing skills, denied operations and remaining issues."

/** One input of a handoff: its artifact and bytes, and the immutable [file] when the role may open it. */
internal class HandoffInput(val artifact: BrainArtifact, val bytes: ByteArray, val file: Path?)

/** The most text one handoff inlines across all its inputs. */
internal const val HANDOFF_CHARS = 96_000

/**
 * The inputs as data: text inline within [HANDOFF_CHARS], a longer one as an excerpt marked truncated. An input with
 * a [HandoffInput.file] (a project role's) also names that file, for a binary input or the rest of a long one; a
 * tool-free role cannot open a path, so it gets the note alone.
 */
internal fun handoffText(inputs: List<HandoffInput>): String {
    var remaining = HANDOFF_CHARS
    return inputs.joinToString("\n\n") { input ->
        val artifact = input.artifact
        val header = "Artifact ${artifact.id} (${artifact.title}, digest ${artifact.digest})"
        val file = input.file?.let { " The complete immutable file is $it." } ?: ""
        if (artifact.mediaType == BrainStore.BINARY) {
            "$header: a binary file of ${input.bytes.size} bytes, which cannot be shown as text.$file"
        } else {
            val text = input.bytes.toString(Charsets.UTF_8)
            val shown = text.take(minOf(BrainLimits.INPUT, remaining)).also { remaining -= it.length }
            if (shown.length == text.length) "$header:\n$text"
            else "$header, truncated to its first ${shown.length} of ${text.length} characters.$file\n$shown\n[Truncated]"
        }
    }
}

/** The person's answer to a stage dialog. Escape, a timeout or closing chat answer [PAUSE]; only [STOP] ends the run. */
internal enum class StageChoice { CONTINUE, CHANGES, PAUSE, STOP }

/** What a stage approval names: the step, or the specialist's ad hoc task. */
internal fun stageSubject(role: BrainRoleSnapshot, step: WorkflowStep?) = step?.name ?: "${role.name}'s task"

/** The stage approval's title, and how the waiting snapshot summarizes it. */
internal fun stageTitle(subject: String) = "Approve $subject"
internal fun stageSummary(subject: String, role: BrainRoleSnapshot) = "${stageTitle(subject)} with ${memberLabel(role.binding.member())}."

/** The native stage dialog for [role]'s next attempt in [run]: specialist, task, expected result, workspace and inputs. */
internal fun stageDialog(role: BrainRoleSnapshot, run: RunRecord, step: WorkflowStep?, inputs: String): NativeDialog<StageChoice> {
    val lines = listOf(
        "Specialist: ${role.name} · ${memberLabel(role.binding.member())}",
        "Task: ${run.task}",
        if (step == null) "Requested outside the workflow; ${role.name}'s workflow stages ask before starting."
        else "Expected result: ${step.outputDescription}",
        "Workspace: ${run.workspace?.let { Path.of(it).fileName } ?: "Conversation only"}",
        "Inputs: ${inputs.take(2000)}",
    )
    val buttons = listOf(
        "Continue" to StageChoice.CONTINUE,
        "Request changes" to StageChoice.CHANGES,
        "Not now" to StageChoice.PAUSE,
        "Stop" to StageChoice.STOP,
    )
    return NativeDialog(stageTitle(stageSubject(role, step)), lines, buttons, StageChoice.PAUSE)
}

/**
 * The native resume review for [run]: what runs again ([rerun], and whether its effects are [uncertain]), the settings
 * [target] adopts, and the extra allowance when the run paused at its [limit].
 */
internal fun resumeDialog(
    run: RunRecord,
    rerun: Boolean,
    uncertain: Boolean,
    target: BrainDocument?,
    limit: Boolean,
): NativeDialog<Boolean> {
    val last = run.attempts.lastOrNull()
    val lines = mutableListOf("Finished outputs are preserved.")
    if (uncertain) {
        lines += "The last attempt may have changed files or external state. Continuing retries that work and may repeat effects. " +
            "Verify its result first."
    } else if (rerun && last != null) lines += "${last.role.name}'s attempt runs again with the same input."
    if (target != null) {
        lines += "Adopt configuration revision ${target.revision}. Pending approvals will be requested again."
        target.configuration.specialists.forEach { next ->
            val old = run.configuration.specialists.find { it.id == next.id }
            if (next != old) {
                lines += "${next.name}: ${old?.binding?.modelId} → ${next.binding?.modelId}; instructions: ${next.instructions}"
            }
        }
        target.configuration.workflow?.steps?.forEach { next ->
            if (next != run.configuration.workflow?.steps?.find { it.id == next.id }) {
                lines += "${next.name}: approval ${next.askBeforeStarting}; instructions ${next.instructions}"
            }
        }
    }
    if (limit) lines += "Continue allows ${BrainLimits.RETURNS} more returns and ${BrainLimits.DISPATCHES} more attempts."
    return NativeDialog("Resume companion work", lines, listOf("Continue" to true, "Keep paused" to false), false)
}

/** Why [role]'s attempt cannot start: its readiness [issues], by their contract names. */
internal fun notReadyPause(role: BrainRoleSnapshot, issues: List<BrainIssue>): String {
    val reasons = issues.joinToString { brainWire(it.reason).jsonPrimitive.content }
    return "${role.name} is not ready: $reasons. Update the brain or provider and resume."
}

/** Why work paused after a turn failed: the provider's own reason when it gave one. [who] is the role that failed. */
internal fun failurePause(failure: ProviderFailure?, who: String) = when {
    failure == null -> "Work paused. The provider or saved state could not complete this turn. Review the result before resuming."
    failure.failure == ChatFailure.ConversationLost ->
        "Work paused. ${failure.runtime.copy.product} could not continue $who's conversation. Resume to start it fresh."
    else -> "Work paused for $who. ${failureNotice(failure.runtime, failure.failure)}"
}
