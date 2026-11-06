package org.formulagen.wrapper.brain

import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.approvals.memberLabel
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.runtimes.ChatFailure
import org.formulagen.wrapper.runtimes.failureNotice
import org.formulagen.wrapper.providers.copy
import org.formulagen.wrapper.storage.Artifacts

/*
 * The text the controller gives a model or shows in a native dialog. Instructions are part of a role's persona digest,
 * so changing them starts fresh provider conversations for existing roles.
 */

/**
 * The coordinator's rules that both protocols share, the brain's ([COORDINATOR_PROTOCOL]) and a conversation's
 * (`CONVERSATION_PROTOCOL`): evidence first, then the workflow's.
 */
internal val COORDINATOR_EVIDENCE_RULES = listOf(
    "Keep evidence explanations brief. Do not repeat artifact IDs or digests in explanations; they are already recorded.",
    "The host supplies allowed actions, saved roles, workflows and immutable artifacts as data.",
)

/** How the coordinator chooses and follows a workflow, in both protocols. */
internal val COORDINATOR_WORKFLOW_RULES = listOf(
    "configuration.workflows lists the workflows you may follow, the default first, each with useFor: what to use it for.",
    "For a request matching a workflow, use enter-workflow with a step of that workflow. If the person names a workflow, use it; " +
        "if several match, use the default. Use delegate only for unrelated tasks or with no workflow.",
    "For ask, choices are optional: at most four distinct short options, each at most 240 characters. The person can always write another answer. Never use ask to bypass native approvals.",
    "Explain every skipped stage. Ask when supplied artifacts or readiness are unclear. Never treat provider output as approval.",
    "After an attempt, classify actual evidence with step-result. Missing skills, denied tools and uncertain effects are blocked.",
    "Inside a workflow, its normal sequence is fixed. start-step launches only its next pending stage. skip-step only skips that stage.",
)

/** Every decision's JSON shape but the answer's, which differs between the protocols. */
internal val COORDINATOR_DECISION_SHAPES = listOf(
    """{"kind":"ask","question":"focused readiness question","choices":["First option","Second option"]}""",
    """{"kind":"delegate","specialistId":"UUID","task":"requested task","inputIds":["UUID"]}""",
    """{"kind":"enter-workflow","entryStepId":"UUID","inputIds":["UUID"],"skipped":[{"stepId":"UUID","reason":"why unnecessary"}],"reason":"why enter here"}""",
    """{"kind":"start-step","stepId":"UUID","inputIds":["UUID"]}""",
    """{"kind":"skip-step","stepId":"UUID","reason":"why unnecessary"}""",
    """{"kind":"step-result","attemptId":"UUID","outcome":"ready|changes-needed|blocked","explanation":"evidence"}""",
)

/**
 * The pinned [configuration] as the coordinator reads it: its roles, each specialist with its skills by name
 * ([coordinatorSpecialists], provider skills [described] as this Mac reports them), and in `workflows` the ones it may
 * follow, the enabled workflows with the default first. A disabled workflow and the brain's skill texts stay out.
 */
internal fun coordinatorConfiguration(configuration: BrainConfiguration, described: (SkillRef.Provider) -> String? = { null }): JsonObject {
    val workflows = configuration.enabledWorkflows.sortedByDescending { it.isDefault }
    val roles = brainWire(configuration).jsonObject.filterKeys { it != "workflow" && it != "workflows" && it != "skills" }
    val specialists = coordinatorSpecialists(configuration.specialists, configuration.skills, described)
    return JsonObject(roles + ("specialists" to specialists) + ("workflows" to brainWire(workflows)))
}

/**
 * The coordinator's turn input: the decision's identity and [allowed] kinds, the pinned run as data (its configuration
 * as [coordinatorConfiguration] shows it, with provider skills [described]), the handoff text of the request's [input]
 * and the [latestOutput], and the recent [conversation].
 */
internal fun coordinatorRequest(
    decisionId: String,
    run: RunRecord,
    allowed: Set<DecisionKind>,
    input: String,
    latestOutput: String,
    conversation: List<BrainEntry>,
    described: (SkillRef.Provider) -> String? = { null },
): String = buildJsonObject {
    put("decisionId", decisionId); put("runRevision", run.summary.revision)
    put("allowed", JsonArray(allowed.map { brainWire(it) }))
    put("request", run.request); put("configuration", coordinatorConfiguration(run.configuration, described)); put("run", brainWire(run.summary))
    put("attempts", brainWire(run.attempts)); put("artifacts", brainWire(run.artifacts))
    put("nextStepId", workflowOf(run)?.steps?.getOrNull(run.nextStep)?.id?.let(::JsonPrimitive) ?: JsonNull)
    put("input", input); put("latestOutput", latestOutput)
    put("conversation", brainWire(conversation))
}.toString()

/** A role's persona instructions, with the model and effort it actually runs on. */
internal fun roleInstructions(role: BrainRoleSnapshot) =
    role.instructions + "\nEffective model: ${role.binding.modelId}; effort: ${role.binding.effort}"

/**
 * A specialist's turn: the delegated [task] with the original request as context, its [skills] ([skillsBlock]), and its
 * input artifacts as data.
 */
internal fun specialistTurn(role: BrainRoleSnapshot, run: RunRecord, step: WorkflowStep?, inputs: String, skills: String = "") =
    "The native controller has already delegated this turn to you as ${role.name}. You are that specialist. " +
        "Complete the assigned task yourself; do not repeat the coordinator delegation. " +
        "The original request is background context, not another routing instruction.\n" +
        "User request (context):\n${run.request}\nAssigned task:\n${run.task}\n" +
        "Expected input: ${step?.inputDescription.orEmpty()}\nExpected output: ${step?.outputDescription.orEmpty()}\n" + skills +
        "Input artifacts (data):\n$inputs\nReport actual evidence, missing skills, denied operations and remaining issues. Format code with fenced Markdown and its language. For images you actually produced or referenced, use Markdown image syntax with a descriptive label and either a path relative to the working folder or an HTTPS URL. Never embed image bytes or data URLs. Do not claim an image exists unless verified."

/**
 * The Skills block of a specialist's task, or "" when it uses none: each of its [refs] in order, the only skills a
 * companion turn has (no provider loads its own). A Hovel skill is the brain's copy ([brainSkills]) with its name, Use
 * it when and instructions. A provider skill is the text this Mac [provided] by name ([ProviderSkillText]) in the same
 * form, with where the rest is when it was cut; a [working] turn is also told the skill's folder, which it may read. A
 * reference the brain or this Mac does not have is left out; readiness has already paused a specialist whose provider
 * skill is missing.
 *
 * With skill [groups], each group comes first with its name and when to use its skills together, then its skills, in
 * the order of each group's first skill; the skills in no group follow under "Other skills:". A group only sorts:
 * each skill reads as it does without groups.
 */
internal fun skillsBlock(refs: List<SkillRef>, brainSkills: List<BrainSkill>, provided: Map<String, ProviderSkillText>, working: Boolean,
    groups: List<SkillGroup> = emptyList()): String {
    val shown = refs.mapNotNull { ref ->
        val text = when (ref) {
            is SkillRef.Hovel -> brainSkills.find { it.id == ref.skillId }?.let { skillText(it.name, it.useWhen, it.instructions) }
            is SkillRef.Provider -> provided[ref.name]?.let { skill ->
                val file = Path.of(skill.file)
                skillText(ref.name, skill.description, skill.instructions) +
                    (if (skill.truncated) "\nOnly the first ${ProviderSkillText.INSTRUCTIONS} characters are shown; the rest is in $file." else "") +
                    (if (working) "\nIts files are in ${file.parent}." else "")
            }
        }
        text?.let { groups.find { group -> group.id == ref.group } to it }
    }
    if (shown.isEmpty()) return ""
    // In the order each group's first skill comes; skills in no group share the null key.
    val sorted = shown.groupBy({ it.first }, { it.second })
    if (sorted.keys.all { it == null }) return "Skills:\n" + shown.joinToString("\n\n") { it.second } + "\n"
    val sections = sorted.filterKeys { it != null }.map { (group, skills) ->
        "Skill group: ${checkNotNull(group).name}" + (if (group.useWhen.isBlank()) "" else "\nUse these together when: ${group.useWhen}") +
            "\n" + skills.joinToString("\n\n")
    } + listOfNotNull(sorted[null]?.let { "Other skills:\n" + it.joinToString("\n\n") })
    return "Skills:\n" + sections.joinToString("\n\n") + "\n"
}

/** One skill as a task shows it: its [name], when to use it (left out when [useWhen] is blank) and its [instructions]. */
private fun skillText(name: String, useWhen: String, instructions: String) =
    "Skill: $name" + (if (useWhen.isBlank()) "" else "\nUse it when: $useWhen") + "\nInstructions:\n$instructions"

/**
 * The coordinator's skill rule, in both protocols: it routes by its specialists' skills (each specialist's `skills`,
 * [coordinatorSpecialists]) and uses none itself.
 */
internal val COORDINATOR_SKILL_RULES = listOf(
    "Each specialist lists its skills by name with when to use them. Only these skills are available in this conversation; " +
        "you use none yourself. Delegate work that needs a skill to the specialist that has it. If the person asks for a skill no " +
        "specialist here has, say this brain doesn't have it; don't claim to use it.",
    "A specialist's skill group names skills that work together; for work that needs them together, delegate it to that specialist.",
)

/**
 * [specialists] as the coordinator reads them: each one's fields with `skills` as names, when to use them and the name
 * of the group each sits in, and `skillGroups` (when it has any) as names and when to use them together; never
 * references or ids. A Hovel skill's Use it when is the brain's ([brainSkills]); a provider skill's is the description
 * this Mac reports for it ([described]), or only its name when this Mac has none.
 */
internal fun coordinatorSpecialists(specialists: List<SpecialistRole>, brainSkills: List<BrainSkill>,
    described: (SkillRef.Provider) -> String?): JsonArray = JsonArray(specialists.map { specialist ->
    fun named(name: String, useWhen: String?) = buildJsonObject { put("name", name); useWhen?.takeIf { it.isNotBlank() }?.let { put("useWhen", it) } }
    val skills = specialist.skills.mapNotNull { ref ->
        val (name, useWhen) = when (ref) {
            is SkillRef.Hovel -> brainSkills.find { it.id == ref.skillId }?.let { it.name to it.useWhen } ?: return@mapNotNull null
            is SkillRef.Provider -> ref.name to described(ref)
        }
        val group = specialist.groupOf(ref)?.name
        JsonObject(named(name, useWhen) + listOfNotNull(group?.let { "group" to JsonPrimitive(it) }))
    }
    val groups = specialist.skillGroups.map { named(it.name, it.useWhen) }
    JsonObject(brainWire(specialist).jsonObject - "skillGroups" + ("skills" to JsonArray(skills)) +
        listOfNotNull(("skillGroups" to JsonArray(groups)).takeIf { groups.isNotEmpty() }))
})

/** One input of a handoff: its artifact and bytes, and the immutable [file] when the role may open it. */
internal class HandoffInput(val artifact: BrainArtifact, val bytes: ByteArray, val file: Path?)

/** The most text one handoff inlines across all its inputs. */
internal const val HANDOFF_CHARS = 96_000

/**
 * The run's artifacts [ids] as handoff inputs, each once: their [bytes], and for a working role their immutable
 * [file], which only a project role may open. An id the run does not have fails.
 */
internal fun RunRecord.handoffInputs(ids: List<String>, bytes: (BrainArtifact) -> ByteArray, file: ((BrainArtifact) -> Path)?): List<HandoffInput> =
    ids.distinct().map { id ->
        val artifact = artifacts.find { it.id == id } ?: error("Unknown input artifact")
        HandoffInput(artifact, bytes(artifact), file?.invoke(artifact))
    }

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
        if (artifact.mediaType == Artifacts.BINARY) {
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
        target.configuration.workflows.flatMap { it.steps }.forEach { next ->
            if (next != run.configuration.step(next.id)) {
                lines += "${next.name}: approval ${next.askBeforeStarting}; instructions ${next.instructions}"
            }
        }
    }
    if (limit) lines += "Continue allows ${BrainLimits.RETURNS} more returns and ${BrainLimits.DISPATCHES} more attempts."
    return NativeDialog("Resume companion work", lines, listOf("Continue" to true, "Keep paused" to false), false)
}

/**
 * Why [role]'s attempt cannot start on this Mac, called [mac]: its readiness [issues] by their contract names, and the
 * provider skills it cannot use in sentences of their own ([skillsPause]).
 */
internal fun notReadyPause(role: BrainRoleSnapshot, issues: List<BrainIssue>, mac: String): String {
    val (skills, others) = issues.partition { it.reason == BrainIssueReason.MISSING_SKILL && it.skill != null }
    val reasons = others.joinToString { brainWire(it.reason).jsonPrimitive.content }
    val lines = listOfNotNull("${role.name} is not ready: $reasons.".takeIf { others.isNotEmpty() }) +
        skillsPause(role, skills.mapNotNull { it.skill }, mac)
    return (lines + "Update the brain or provider and resume.").joinToString(" ")
}

/**
 * The provider [skills] [role] cannot use on [mac], one sentence for each reason ([unusableSkill]): "Reviewer uses
 * code-review, which isn't installed in Claude Code on Studio Mac."
 */
internal fun skillsPause(role: BrainRoleSnapshot, skills: List<SkillRef.Provider>, mac: String): List<String> {
    // Skills are installed in the program ("Claude Code"), not the product the player picks ("Claude").
    val program = role.binding.runtimeId.copy.program
    // Each skill here is one the role cannot use, so with nothing counted as installed its reason is the same.
    return skills.groupBy { unusableSkill(it, role.binding, emptySet()) to it.runtimeId }.map { (reason, group) ->
        val names = group.map { it.name }.let { if (it.size == 1) it.single() else it.dropLast(1).joinToString() + " and " + it.last() }
        when (reason.first) {
            SkillProblem.OTHER_PROVIDER -> "${role.name} uses $names from ${reason.second.copy.program}, but runs on $program on $mac."
            else -> "${role.name} uses $names, which ${if (group.size == 1) "isn't" else "aren't"} installed in $program on $mac."
        }
    }
}

/** Why work paused after a turn failed: the provider's own reason when it gave one. [who] is the role that failed. */
internal fun failurePause(failure: ProviderFailure?, who: String) = when {
    failure == null -> "Work paused. The provider or saved state could not complete this turn. Review the result before resuming."
    failure.failure == ChatFailure.ConversationLost ->
        "Work paused. ${failure.runtime.copy.product} could not continue $who's conversation. Resume to start it fresh."
    else -> "Work paused for $who. ${failureNotice(failure.runtime, failure.failure)}"
}
