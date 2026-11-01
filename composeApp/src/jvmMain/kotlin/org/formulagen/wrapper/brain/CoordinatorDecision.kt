package org.formulagen.wrapper.brain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.formulagen.wrapper.bridge.*

/** The kinds of [CoordinatorDecision], as the coordinator names them in `kind`. */
@Serializable
internal enum class DecisionKind {
    @SerialName("answer") ANSWER,
    @SerialName("ask") ASK,
    @SerialName("delegate") DELEGATE,
    @SerialName("enter-workflow") ENTER_WORKFLOW,
    @SerialName("start-step") START_STEP,
    @SerialName("skip-step") SKIP_STEP,
    @SerialName("step-result") STEP_RESULT,
    /** A conversation's thread reply only ([THREAD_REPLY_DECISIONS]). */
    @SerialName("forward") FORWARD,
}

/** How the coordinator grades an attempt: `blocked` will not go on as it is, so the coordinator re-plans. */
@Serializable
internal enum class StepOutcome(val graded: BrainAttemptOutcome?) {
    @SerialName("ready") READY(BrainAttemptOutcome.READY),
    @SerialName("changes-needed") CHANGES_NEEDED(BrainAttemptOutcome.CHANGES_NEEDED),
    @SerialName("blocked") BLOCKED(null),
}

/**
 * The coordinator's private control decision, mirrored by the contracts' internal `coordinatorDecision` schema. The
 * model can only select one of these; the host resolves every ID against the pinned configuration.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface CoordinatorDecision {
    @Serializable @SerialName("answer")
    data class Answer(val text: String, val reaction: String? = null) : CoordinatorDecision

    @Serializable @SerialName("ask")
    data class Ask(val question: String, val choices: List<String>? = null) : CoordinatorDecision

    @Serializable @SerialName("delegate")
    data class Delegate(val specialistId: String, val task: String, val inputIds: List<String>) : CoordinatorDecision

    /** Enter the workflow at [entryStepId]; [skipped] gives a reason for every earlier step. */
    @Serializable @SerialName("enter-workflow")
    data class EnterWorkflow(val entryStepId: String, val inputIds: List<String>, val skipped: List<BrainSkip>, val reason: String) :
        CoordinatorDecision

    /** Launch the next pending step, which must be [stepId]. */
    @Serializable @SerialName("start-step")
    data class StartStep(val stepId: String, val inputIds: List<String>) : CoordinatorDecision

    /** Skip the next pending step, which must be [stepId]. */
    @Serializable @SerialName("skip-step")
    data class SkipStep(val stepId: String, val reason: String) : CoordinatorDecision

    /** Grade the last attempt, which must be [attemptId]. */
    @Serializable @SerialName("step-result")
    data class StepResult(val attemptId: String, val outcome: StepOutcome, val explanation: String) : CoordinatorDecision

    /** Hand a thread reply to [threadId]'s specialist, with the coordinator's [note] as context when it adds one. */
    @Serializable @SerialName("forward")
    data class Forward(val threadId: String, val note: String? = null) : CoordinatorDecision
}

internal val CoordinatorDecision.kind: DecisionKind
    get() = when (this) {
        is CoordinatorDecision.Answer -> DecisionKind.ANSWER
        is CoordinatorDecision.Ask -> DecisionKind.ASK
        is CoordinatorDecision.Delegate -> DecisionKind.DELEGATE
        is CoordinatorDecision.EnterWorkflow -> DecisionKind.ENTER_WORKFLOW
        is CoordinatorDecision.StartStep -> DecisionKind.START_STEP
        is CoordinatorDecision.SkipStep -> DecisionKind.SKIP_STEP
        is CoordinatorDecision.StepResult -> DecisionKind.STEP_RESULT
        is CoordinatorDecision.Forward -> DecisionKind.FORWARD
    }

/**
 * Validate buffered coordinator text before any execution side effect: exactly one decision of an [allowed] kind,
 * with no other field and nothing coerced, every ID a UUID and every text within its bound.
 */
internal fun parseDecision(text: String, allowed: Set<DecisionKind>): CoordinatorDecision {
    require(text.length <= BrainLimits.OUTPUT)
    // As a bridge argument is read: an extra field, or a quoted value where another type belongs, is refused.
    val decision = brainJson.decodeFromString(BrainWireSerializer(CoordinatorDecision.serializer()), text)
    require(decision.kind in allowed)
    fun text(value: String, limit: Int) = require(value.isNotEmpty() && value.length <= limit)
    fun ids(values: List<String>) = require(BrainRules.inputs(values))
    fun id(value: String) = require(BrainRules.uuid(value))
    when (decision) {
        is CoordinatorDecision.Answer -> {
            text(decision.text, BrainLimits.OUTPUT)
            decision.reaction?.let { require(it in COORDINATOR_REACTIONS) }
        }
        is CoordinatorDecision.Ask -> {
            text(decision.question, BrainLimits.OUTPUT)
            BrainQuestion(decision.choices.orEmpty())
        }
        is CoordinatorDecision.Delegate -> {
            id(decision.specialistId); text(decision.task, BrainLimits.STEP_INSTRUCTIONS); ids(decision.inputIds)
        }
        is CoordinatorDecision.EnterWorkflow -> {
            id(decision.entryStepId); ids(decision.inputIds); text(decision.reason, BrainLimits.DESCRIPTION)
            require(decision.skipped.size <= BrainLimits.STEPS)
            decision.skipped.forEach { id(it.stepId); text(it.reason, BrainLimits.DESCRIPTION) }
        }
        is CoordinatorDecision.StartStep -> { id(decision.stepId); ids(decision.inputIds) }
        is CoordinatorDecision.SkipStep -> { id(decision.stepId); text(decision.reason, BrainLimits.DESCRIPTION) }
        is CoordinatorDecision.StepResult -> { id(decision.attemptId); text(decision.explanation, BrainLimits.DESCRIPTION) }
        is CoordinatorDecision.Forward -> { id(decision.threadId); decision.note?.let { text(it, BrainLimits.STEP_INSTRUCTIONS) } }
    }
    return decision
}

/** The host decides what may happen next; the model can only select an offered decision. */
internal fun allowedDecisions(run: RunRecord): Set<DecisionKind> = when (run.phase) {
    BrainPhase.EVALUATE -> setOf(DecisionKind.STEP_RESULT, DecisionKind.ASK)
    BrainPhase.ADVANCE -> setOf(DecisionKind.START_STEP, DecisionKind.SKIP_STEP, DecisionKind.ASK)
    // Every step is done, so only the closing answer or a question remains.
    BrainPhase.FINISH -> setOf(DecisionKind.ANSWER, DecisionKind.ASK)
    BrainPhase.PLAN, BrainPhase.EXECUTE -> {
        val canEnter = run.configuration.workflow?.enabled == true && run.summary.workflowId == null
        val always = setOf(DecisionKind.ANSWER, DecisionKind.ASK, DecisionKind.DELEGATE)
        if (canEnter) always + DecisionKind.ENTER_WORKFLOW else always
    }
    BrainPhase.THREAD_REPLY -> THREAD_REPLY_DECISIONS
}

/**
 * A conversation's thread reply: forward it to that thread's specialist, answer it in the thread, or
 * delegate a new task, which opens a new thread. Nothing else, so a reply never steers a workflow.
 */
internal val THREAD_REPLY_DECISIONS = setOf(DecisionKind.FORWARD, DecisionKind.ANSWER, DecisionKind.DELEGATE)

/** Expressive acknowledgements; never an approval or an execution instruction. */
internal val COORDINATOR_REACTIONS = setOf("👍", "❤️", "🎉", "🤔", "👀", "🙌", "💡", "✨", "😊", "🙏")
