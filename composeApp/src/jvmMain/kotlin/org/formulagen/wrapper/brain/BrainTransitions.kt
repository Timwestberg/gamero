package org.formulagen.wrapper.brain

import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.ChatFailure

/*
 * The run state machine as pure functions of a record, shared by both coordinators: the brain's (`DesktopBrain`) and
 * a conversation's (`CoordinatorEngine`). Each applies one transition under its lock and persists the result as one
 * revision ([nextRevision]), so a decision that does not fit the run applies nothing. [newId] names each new entry
 * and attempt.
 */

/** The most `brain.send`, `brain.resume` and `brain.retry` receipts a record keeps. */
internal const val REQUEST_RECEIPTS = 128

/** A provider ended a turn with [failure]; [context] names the native conversation it ran in. */
internal class ProviderFailure(val failure: ChatFailure, val runtime: RuntimeId, val context: String, val coordinator: Boolean) :
    Exception("Provider turn failed: ${failure::class.simpleName}")

/** Whether [requestId] already ran with these arguments ([digest]). Reusing it for different arguments fails. */
internal fun BrainRecord.replays(requestId: String, digest: String): Boolean {
    val previous = receipts[requestId] ?: return false
    require(previous == digest) { "Request ID reused with different content" }
    return true
}

/** Remember [requestId], keeping the newest [REQUEST_RECEIPTS]. */
internal fun BrainRecord.withReceipt(requestId: String, digest: String) =
    copy(receipts = (receipts + (requestId to digest)).entries.toList().takeLast(REQUEST_RECEIPTS).associate { it.toPair() })

/** The record as one more revision of its run, which the page echoes back to resume or retry it. */
internal fun BrainRecord.nextRevision() = copy(run = run?.let { it.copy(summary = it.summary.copy(revision = it.summary.revision + 1)) })

internal fun BrainRecord.withEntry(
    id: String,
    author: BrainAuthor,
    text: String,
    role: BrainRoleSnapshot? = null,
    activity: Activity? = null,
) = copy(messages = (messages + BrainEntry(id, author, text.take(BrainLimits.OUTPUT), role, activity)).takeLast(BrainLimits.HISTORY))

private fun BrainRecord.withRun(run: RunRecord) = copy(run = run)
private fun BrainRecord.requireRun() = checkNotNull(run)

/** Every runtime this record can show: the run's pinned brain and each role its conversation and attempts name. */
internal fun BrainRecord.runtimeIds(): Set<RuntimeId> {
    val roles = messages.mapNotNull { it.role } + run?.attempts.orEmpty().map { it.role }
    return run?.configuration?.runtimeIds().orEmpty() + roles.map { it.binding.runtimeId }
}

/** Replace the run's last attempt, the one in flight. */
internal fun RunRecord.withLast(attempt: BrainAttempt) = copy(attempts = attempts.dropLast(1) + attempt)

private fun RunRecord.withAttempt(id: String, transform: (BrainAttempt) -> BrainAttempt) =
    copy(attempts = attempts.map { if (it.id == id) transform(it) else it })

/** Stop the run at [state]. A running attempt is marked interrupted, since its effects are uncertain. */
internal fun RunRecord.halted(state: BrainRunState) = copy(
    summary = summary.copy(state = state),
    attempts = attempts.map { if (it.state == BrainAttemptState.RUNNING) it.copy(state = BrainAttemptState.INTERRUPTED) else it },
)

/** Unfinished work that closing or reopening chat pauses; a run waiting for the person's message keeps waiting. */
internal val RunRecord.pausesWithChat get() = !summary.state.terminal && summary.state != BrainRunState.AWAITING_INPUT

/** The run used its allowance of returns or dispatches; continuing needs an explicit resume. */
internal val RunRecord.overAllowance get() = summary.returnsUsed > returnsAllowance || summary.dispatchesUsed >= dispatchesAllowance

/**
 * Launch nothing more until the person acts. Kind [BrainWaitingKind.INPUT] waits for their next message
 * (`awaiting-input`), which the coordinator re-plans with; every other kind pauses until an explicit resume.
 */
internal fun BrainRecord.paused(message: String, kind: BrainWaitingKind = BrainWaitingKind.RECOVERY) = copy(
    run = run?.halted(if (kind == BrainWaitingKind.INPUT) BrainRunState.AWAITING_INPUT else BrainRunState.PAUSED),
    waiting = BrainWaiting(kind, message.take(BrainLimits.DESCRIPTION)),
    notice = message.take(BrainLimits.DESCRIPTION),
)

/** [attempt] will not go on as it is: record it blocked, and let the coordinator re-plan its step or task with the next message. */
internal fun BrainRecord.declined(attempt: BrainAttempt, message: String): BrainRecord {
    val run = requireRun()
    return copy(
        run = run.copy(
            summary = run.summary.copy(state = BrainRunState.AWAITING_INPUT),
            phase = if (attempt.stepId == null) BrainPhase.PLAN else BrainPhase.ADVANCE,
        ).withAttempt(attempt.id) { it.copy(state = BrainAttemptState.BLOCKED) },
        waiting = BrainWaiting(BrainWaitingKind.INPUT, message.take(BrainLimits.DESCRIPTION)),
    )
}

/** On open: unfinished work pauses for review, since nothing of it is in flight any more. */
internal fun BrainRecord.reopened(): BrainRecord {
    val run = run?.takeIf { it.pausesWithChat } ?: return this
    val waiting = BrainWaiting(BrainWaitingKind.RECOVERY, "Work is paused. Review completed outputs before resuming.")
    return copy(run = run.halted(BrainRunState.PAUSED), waiting = waiting)
}

/** When chat closes ([cancelled] false) or Stop ends the run ([cancelled]); null when there is nothing to end. */
internal fun BrainRecord.ended(cancelled: Boolean): BrainRecord? {
    val run = run?.takeIf { !it.summary.state.terminal && (cancelled || it.pausesWithChat) } ?: return null
    val waiting = if (cancelled) null else BrainWaiting(BrainWaitingKind.RECOVERY, "Work paused when chat closed. Review before resuming.")
    return copy(run = run.halted(if (cancelled) BrainRunState.CANCELLED else BrainRunState.PAUSED), waiting = waiting).nextRevision()
}

/**
 * The person's message [text] answers a run awaiting input: it joins the request as a clarification with its
 * [attached] inputs, and the coordinator plans again.
 */
internal fun RunRecord.answered(text: String, inputIds: List<String>, attached: List<BrainArtifact>) = copy(
    request = "$request\nUser clarification: $text".takeLast(BrainLimits.INPUT),
    inputs = (inputs + inputIds).distinct(),
    artifacts = (artifacts + attached).distinctBy { it.id },
    summary = summary.copy(state = BrainRunState.PLANNING),
)

/**
 * The person sent [text] as request [requestId] ([digest]), continuing or starting [run]: the message joins the
 * conversation, its inputs leave the staging area, and nothing waits any more.
 */
internal fun BrainRecord.sent(requestId: String, digest: String, text: String, inputIds: List<String>, run: RunRecord) =
    withEntry(requestId, BrainAuthor.USER, text)
        .copy(run = run, waiting = null, notice = null, staged = staged.filter { it.id !in inputIds })
        .withReceipt(requestId, digest)

/** A send named a revision the brain has moved past; nothing runs until the person reviews the current coordinator. */
internal fun BrainRecord.staleSend() = copy(notice = "The brain changed. Review the current coordinator and send again.")

/** The person chose Not now on [attempt]'s stage dialog for [subject]: it stays queued, unstarted, until a resume. */
internal fun BrainRecord.stagePostponed(attempt: BrainAttempt, subject: String) =
    copy(run = requireRun().withLast(attempt.copy(state = BrainAttemptState.QUEUED)))
        .paused("$subject is waiting for approval. No specialist was started. Resume to review it again.")

/** A resume would adopt a revision whose roles or workflow structure differ ([adoptable]); the run stays as it is. */
internal fun BrainRecord.adoptionRefused() = copy(
    notice = "These changes alter roles or workflow structure. " +
        "Stop this run and start a new request to use them; finished outputs are preserved.",
)

/** The native resume review is open: the run waits on it. */
internal fun BrainRecord.reviewing(): BrainRecord {
    val run = requireRun()
    return copy(
        waiting = BrainWaiting(BrainWaitingKind.RECOVERY, "Review the native resume dialog."),
        run = run.copy(summary = run.summary.copy(state = BrainRunState.AWAITING_APPROVAL)),
    )
}

/** Continue in the resume review: the run goes on from [from], as it was when the person asked ([RunRecord.resumed]). */
internal fun BrainRecord.reviewApproved(from: RunRecord, rerun: Boolean, target: BrainDocument?, newId: () -> String) =
    copy(waiting = null, notice = null, run = from.resumed(rerun, target, requireRun().summary.revision, newId))

/** Keep paused in the resume review: the run waits as it did before, in [state] and for [waiting]. */
internal fun BrainRecord.reviewDeclined(waiting: BrainWaiting?, state: BrainRunState): BrainRecord {
    val run = requireRun()
    return copy(waiting = waiting, run = run.copy(summary = run.summary.copy(state = state)))
}

/**
 * After a failed turn: a lost conversation is forgotten so the role's next turn starts fresh, and the running attempt
 * is `failed` when it cannot have changed anything (a refused turn, or a talk-only one); otherwise it stays to be
 * interrupted. Then the run pauses with [message].
 */
internal fun BrainRecord.afterFailure(failure: ProviderFailure?, message: String): BrainRecord {
    var record = this
    if (failure != null && failure.failure == ChatFailure.ConversationLost) record = if (failure.coordinator) {
        record.copy(coordinatorContexts = record.coordinatorContexts - failure.context)
    } else record.copy(run = record.run?.let { it.copy(contexts = it.contexts - failure.context) })
    val attempt = record.run?.attempts?.lastOrNull()?.takeIf { it.state == BrainAttemptState.RUNNING }
    val uncertain = attempt?.role?.mode == MemberMode.WORK &&
        (failure == null || failure.failure == ChatFailure.Temporary || failure.failure == ChatFailure.Unexpected)
    if (attempt != null && !uncertain) {
        record = record.withRun(record.requireRun().withAttempt(attempt.id) { it.copy(state = BrainAttemptState.FAILED) })
    }
    return record.paused(message)
}

/** The role whose turn failed, for the pause notice. */
internal val BrainRecord.failingRole
    get() = run?.attempts?.lastOrNull()?.takeIf { it.state == BrainAttemptState.RUNNING }?.role?.name ?: "the coordinator"

/**
 * The record after the coordinator's [decision], which [coordinator] wrote. Throws when it does not fit the run,
 * such as a step that is not next, an unknown specialist or input, or an early answer in a workflow.
 */
internal fun BrainRecord.decided(decision: CoordinatorDecision, coordinator: BrainRoleSnapshot, newId: () -> String): BrainRecord {
    val run = requireRun()
    return when (decision) {
        is CoordinatorDecision.Answer -> {
            require(run.summary.workflowId == null || run.phase == BrainPhase.FINISH)
            withEntry(newId(), BrainAuthor.COORDINATOR, decision.text, coordinator)
                .withRun(run.copy(summary = run.summary.copy(state = BrainRunState.COMPLETED)))
        }
        is CoordinatorDecision.Ask -> withEntry(newId(), BrainAuthor.COORDINATOR, decision.question, coordinator).copy(
            run = run.copy(summary = run.summary.copy(state = BrainRunState.AWAITING_INPUT)),
            waiting = BrainWaiting(BrainWaitingKind.INPUT, decision.question.take(BrainLimits.DESCRIPTION)),
        )
        is CoordinatorDecision.Delegate -> {
            require(run.summary.workflowId == null)
            queued(null, decision.specialistId, decision.task, decision.inputIds, newId)
        }
        is CoordinatorDecision.EnterWorkflow -> {
            val workflow = checkNotNull(run.configuration.workflow).also { require(it.enabled) }
            val index = workflow.steps.indexOfFirst { it.id == decision.entryStepId }
            require(index >= 0)
            // Every earlier step is skipped exactly once, with its reason.
            val earlier = workflow.steps.take(index).map { it.id }
            require(decision.skipped.map { it.stepId }.toSet() == earlier.toSet() && decision.skipped.size == index)
            val step = workflow.steps[index]
            val summary = run.summary.copy(workflowId = workflow.id, skipped = decision.skipped)
            withRun(run.copy(summary = summary, nextStep = index, phase = BrainPhase.ADVANCE))
                .withEntry(newId(), BrainAuthor.SYSTEM, decision.reason)
                .queued(step, checkNotNull(step.specialistId), step.instructions, decision.inputIds, newId)
        }
        is CoordinatorDecision.StartStep -> {
            val step = checkNotNull(run.configuration.workflow).steps[run.nextStep]
            require(step.id == decision.stepId)
            queued(step, checkNotNull(step.specialistId), step.instructions, decision.inputIds, newId)
        }
        is CoordinatorDecision.SkipStep -> {
            val workflow = checkNotNull(run.configuration.workflow)
            val step = workflow.steps[run.nextStep]
            require(step.id == decision.stepId)
            val skipped = run.summary.skipped.filter { it.stepId != step.id } + BrainSkip(step.id, decision.reason)
            withEntry(newId(), BrainAuthor.SYSTEM, "Skipped ${step.name}: ${decision.reason}")
                .withRun(run.copy(summary = run.summary.copy(skipped = skipped)).advanced(workflow))
        }
        is CoordinatorDecision.StepResult -> graded(decision, coordinator, newId)
        is CoordinatorDecision.Forward -> error("A forward answers a thread reply, never a run")
    }
}

/** Move past the current step: on to the next, or to the closing answer after the last. */
private fun RunRecord.advanced(workflow: BrainWorkflow) =
    copy(nextStep = nextStep + 1, phase = if (nextStep + 1 == workflow.steps.size) BrainPhase.FINISH else BrainPhase.ADVANCE)

/**
 * Queue an attempt of [specialistId] for [task], or of [step] when it is a workflow step. Its inputs are the run's,
 * the [inputs] the coordinator named and the latest applicable output. A role without a binding waits for one.
 */
private fun BrainRecord.queued(
    step: WorkflowStep?,
    specialistId: String,
    task: String,
    inputs: List<String>,
    newId: () -> String,
): BrainRecord {
    val run = requireRun()
    val role = run.configuration.specialists.find { it.id == specialistId } ?: error("Unknown specialist")
    // Only an artifact of this run, and not one a return made stale, can feed the next attempt.
    val current = run.artifacts.filter { it.source.attemptId == null || it.source.attemptId !in run.invalidated }.map { it.id }
    require(current.containsAll(inputs))
    val last = run.attempts.lastOrNull { it.id !in run.invalidated && it.state == BrainAttemptState.COMPLETED }
    val handoff = (run.inputs + inputs + (last?.outputIds ?: emptyList())).distinct()
    val binding = role.binding ?: return paused("Assign a provider to ${role.name} in Configure brain.", BrainWaitingKind.INPUT)
    val snapshot = BrainRoleSnapshot(role.id, role.name, binding, role.instructions, role.mode)
    val attempt = BrainAttempt(newId(), run.summary.id, step?.id, snapshot, run.summary.configurationRevision, handoff)
    val summary = run.summary.copy(currentAttemptId = attempt.id, state = BrainRunState.RUNNING)
    return withRun(run.copy(attempts = run.attempts + attempt, summary = summary, phase = BrainPhase.EXECUTE, task = task))
}

/**
 * The coordinator graded the last attempt. Ready advances; changes needed follows the step's return, making later
 * results stale for the new pass; blocked waits for the person. Ad hoc work goes back to planning.
 */
private fun BrainRecord.graded(decision: CoordinatorDecision.StepResult, coordinator: BrainRoleSnapshot, newId: () -> String): BrainRecord {
    val run = requireRun()
    val attempt = run.attempts.last()
    require(attempt.id == decision.attemptId && attempt.state == BrainAttemptState.COMPLETED)
    val explained = withEntry(newId(), BrainAuthor.COORDINATOR, decision.explanation, coordinator)
    val outcome = decision.outcome.graded ?: return explained.declined(attempt, decision.explanation)
    val graded = run.withAttempt(attempt.id) { it.copy(outcome = outcome) }
    val planning = graded.summary.copy(state = BrainRunState.PLANNING)
    if (attempt.stepId == null) return explained.withRun(graded.copy(phase = BrainPhase.PLAN, summary = planning))
    val workflow = checkNotNull(run.configuration.workflow)
    if (outcome == BrainAttemptOutcome.READY) return explained.withRun(graded.copy(summary = planning).advanced(workflow))
    val target = workflow.steps[run.nextStep].returnTo
        ?: return explained.withRun(graded).paused("Changes are needed, but this step has no return path.", BrainWaitingKind.INPUT)
    val index = workflow.steps.indexOfFirst { it.id == target.stepId }
    require(index in 0 until run.nextStep)
    fun position(stepId: String?) = workflow.steps.indexOfFirst { it.id == stepId }
    val stale = run.attempts.filter { position(it.stepId) >= index }.map { it.id }
    val summary = planning.copy(returnsUsed = planning.returnsUsed + 1, skipped = planning.skipped.filter { position(it.stepId) < index })
    val returned = graded.copy(
        nextStep = index,
        phase = BrainPhase.ADVANCE,
        inputs = (graded.inputs + attempt.outputIds).distinct(),
        invalidated = (graded.invalidated + stale).distinct(),
        summary = summary,
    )
    val note = "Return to ${workflow.steps[index].name}: ${decision.explanation}"
    return explained.withRun(returned).withEntry(newId(), BrainAuthor.SYSTEM, note)
}

/** A stage dialog is open for [attempt]: the run waits on it, and the snapshot names the approval. */
internal fun BrainRecord.awaitingStage(attempt: BrainAttempt, waiting: BrainWaiting): BrainRecord {
    val run = requireRun()
    val awaiting = run.copy(summary = run.summary.copy(state = BrainRunState.AWAITING_APPROVAL))
    return copy(run = awaiting.withLast(attempt.copy(state = BrainAttemptState.AWAITING_APPROVAL)), waiting = waiting)
}

/** [attempt] is dispatched to its provider: it runs, and counts against the dispatch allowance. */
internal fun BrainRecord.dispatched(attempt: BrainAttempt): BrainRecord {
    val run = requireRun()
    val summary = run.summary.copy(state = BrainRunState.RUNNING, dispatchesUsed = run.summary.dispatchesUsed + 1)
    return copy(waiting = null, run = run.copy(summary = summary).withLast(attempt.copy(state = BrainAttemptState.RUNNING)))
}

/**
 * [attempt] returned [output], published as [artifact] and attributed to its role, for the coordinator to evaluate.
 * When the role reports failed or denied work, the run waits for the person instead.
 */
internal fun BrainRecord.completed(
    attempt: BrainAttempt,
    artifact: BrainArtifact,
    output: String,
    activity: Activity?,
    entryId: String,
): BrainRecord {
    val run = requireRun()
    val done = attempt.copy(state = BrainAttemptState.COMPLETED, outputIds = listOf(artifact.id))
    val summary = run.summary.copy(state = BrainRunState.PLANNING)
    val evaluated = run.copy(artifacts = run.artifacts + artifact, phase = BrainPhase.EVALUATE, summary = summary).withLast(done)
    val record = withRun(evaluated).withEntry(entryId, BrainAuthor.SPECIALIST, output, attempt.role, activity)
    val troubled = activity?.steps?.any { it.status != ActivityStatus.DONE } == true
    if (!troubled) return record
    return record.paused("${attempt.role.name} reported failed or denied work. Review it before continuing.", BrainWaitingKind.INPUT)
}

/**
 * The run continues after an approved resume of [this], the run as it was when the person asked. [rerun] queues its
 * last attempt again: in place when it never started, as a new attempt otherwise. [target] adopts a newer saved
 * revision's settings, and a fresh allowance is added. [revision] is the run's current revision.
 */
internal fun RunRecord.resumed(rerun: Boolean, target: BrainDocument?, revision: Long, newId: () -> String): RunRecord {
    val configuration = target?.configuration ?: this.configuration
    val configurationRevision = target?.revision ?: summary.configurationRevision
    val phase = if (rerun) BrainPhase.EXECUTE else phase
    val last = attempts.lastOrNull()
    val attempts = if (phase == BrainPhase.EXECUTE && last != null) {
        val specialist = configuration.specialists.first { it.id == last.role.roleId }
        val role = last.role.copy(binding = checkNotNull(specialist.binding), instructions = specialist.instructions)
        val next = last.copy(
            state = BrainAttemptState.QUEUED,
            outputIds = emptyList(),
            outcome = null,
            role = role,
            configurationRevision = configurationRevision,
        )
        // One that never started is replaced in place; one that started stays in the history.
        if (last.state.notStarted) attempts.dropLast(1) + next else attempts + next.copy(id = newId())
    } else attempts
    // A rerun step takes that step's instructions from the configuration it now runs with.
    val step = last?.stepId?.takeIf { phase == BrainPhase.EXECUTE }?.let { id -> configuration.workflow?.steps?.find { it.id == id } }
    val task = step?.instructions ?: task
    return copy(
        configuration = configuration,
        phase = phase,
        attempts = attempts,
        task = task,
        summary = summary.copy(
            revision = revision,
            configurationRevision = configurationRevision,
            state = BrainRunState.PLANNING,
            currentAttemptId = attempts.lastOrNull()?.id,
        ),
        returnsAllowance = summary.returnsUsed + BrainLimits.RETURNS,
        dispatchesAllowance = summary.dispatchesUsed + BrainLimits.DISPATCHES,
    )
}

/** Paused runs may adopt settings, never silently change their workflow structure. */
internal fun adoptable(before: BrainConfiguration, after: BrainConfiguration): Boolean {
    fun structure(value: BrainConfiguration) = value.copy(
        coordinator = value.coordinator.copy(binding = null, instructions = ""),
        specialists = value.specialists.map { it.copy(binding = null, instructions = "") },
        workflow = value.workflow?.let { workflow ->
            workflow.copy(steps = workflow.steps.map { it.copy(instructions = "", askBeforeStarting = false) })
        },
    )
    return structure(before) == structure(after)
}
