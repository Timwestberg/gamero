package org.formulagen.wrapper.brain

import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.NativeConversation
import kotlin.test.*

/** The pure run transitions, one table row per case: a record and an input in, the next record (or a refusal) out. */
class BrainTransitionsTest {
    private val configuration = testConfiguration(workflow = true).let { config ->
        // The Editor's step returns to the Writer's when changes are needed.
        val steps = config.workflow!!.steps
        config.copy(workflow = config.workflow.copy(steps = listOf(steps[0], steps[1].copy(returnTo = BrainReturn(steps[0].id, "Changes needed")))))
    }
    private val writer = configuration.specialists[0]
    private val editor = configuration.specialists[1]
    private val steps = configuration.workflow!!.steps
    private val coordinator = checkNotNull(configuration.coordinator.snapshot())
    private var ids = 0
    private fun newId() = "20000000-0000-4000-8000-" + (++ids).toString().padStart(12, '0')

    private fun role(specialist: SpecialistRole) = BrainRoleSnapshot(specialist.id, specialist.name, specialist.binding!!, specialist.instructions, specialist.mode)
    private fun attempt(specialist: SpecialistRole, step: WorkflowStep?, state: BrainAttemptState, outputs: List<String> = emptyList()) =
        BrainAttempt(newId(), RUN, step?.id, role(specialist), 1, emptyList(), outputs, state)
    private fun record(
        phase: BrainPhase = BrainPhase.PLAN,
        nextStep: Int = 0,
        attempts: List<BrainAttempt> = emptyList(),
        inWorkflow: Boolean = false,
        state: BrainRunState = BrainRunState.PLANNING,
        config: BrainConfiguration = configuration,
    ): BrainRecord {
        val summary = BrainRun(RUN, 5, 1, if (inWorkflow) config.workflow!!.id else null, state, attempts.lastOrNull()?.id)
        return BrainRecord(run = RunRecord(summary, config, null, "Write it", emptyList(), attempts, nextStep = nextStep, phase = phase))
    }
    private val BrainRecord.r get() = checkNotNull(run)

    private class Case(val name: String, val before: BrainRecord, val decision: CoordinatorDecision, val expect: ((BrainRecord) -> Unit)?)

    @Test fun eachCoordinatorDecisionMovesTheRunAsTheWorkflowAllows() {
        val draft = attempt(writer, steps[0], BrainAttemptState.COMPLETED, listOf(newId()))
        val edit = attempt(editor, steps[1], BrainAttemptState.COMPLETED, listOf(newId()))
        val adHoc = attempt(writer, null, BrainAttemptState.COMPLETED, listOf(newId()))
        val unbound = configuration.copy(specialists = listOf(writer.copy(binding = null), editor))
        val cases = listOf(
            Case("answer completes an ad hoc request", record(), CoordinatorDecision.Answer("Done.")) {
                assertEquals(BrainRunState.COMPLETED, it.r.summary.state)
                assertEquals(BrainAuthor.COORDINATOR to "Done.", it.messages.single().let { entry -> entry.author to entry.text })
                assertEquals(coordinator, it.messages.single().role)
            },
            Case("answer cannot end an unfinished workflow", record(BrainPhase.ADVANCE, inWorkflow = true), CoordinatorDecision.Answer("Done."), null),
            Case("answer after the last step completes the workflow", record(BrainPhase.FINISH, 2, inWorkflow = true), CoordinatorDecision.Answer("Done.")) {
                assertEquals(BrainRunState.COMPLETED, it.r.summary.state)
            },
            Case("ask waits for the next message", record(), CoordinatorDecision.Ask("Which one?")) {
                assertEquals(BrainRunState.AWAITING_INPUT, it.r.summary.state)
                assertEquals(BrainWaiting(BrainWaitingKind.INPUT, "Which one?"), it.waiting)
                assertNull(it.notice)
            },
            Case("delegate queues an ad hoc attempt", record(), CoordinatorDecision.Delegate(editor.id, "Edit it", emptyList())) {
                val queued = it.r.attempts.single()
                assertEquals(listOf(editor.id, null, BrainAttemptState.QUEUED), listOf(queued.role.roleId, queued.stepId, queued.state))
                assertEquals(listOf(BrainPhase.EXECUTE, BrainRunState.RUNNING, "Edit it", queued.id), listOf(it.r.phase, it.r.summary.state, it.r.task, it.r.summary.currentAttemptId))
            },
            Case("delegate cannot bypass an entered workflow", record(BrainPhase.PLAN, inWorkflow = true), CoordinatorDecision.Delegate(editor.id, "Edit", emptyList()), null),
            Case("delegate names only a saved specialist", record(), CoordinatorDecision.Delegate(newId(), "Edit", emptyList()), null),
            Case("delegate passes only inputs of this run", record(), CoordinatorDecision.Delegate(editor.id, "Edit", listOf(newId())), null),
            Case("delegate to an unbound specialist waits for a binding", record(config = unbound), CoordinatorDecision.Delegate(writer.id, "Write", emptyList())) {
                assertTrue(it.r.attempts.isEmpty())
                assertEquals(BrainRunState.AWAITING_INPUT, it.r.summary.state)
                assertEquals("Assign a provider to Writer in Configure brain.", it.waiting?.summary)
                assertEquals(it.waiting?.summary, it.notice)
            },
            Case("enter-workflow at the first step queues it", record(), CoordinatorDecision.EnterWorkflow(steps[0].id, emptyList(), emptyList(), "Start writing.")) {
                assertEquals(configuration.workflow!!.id, it.r.summary.workflowId)
                assertEquals(listOf(steps[0].id), it.r.attempts.map { attempt -> attempt.stepId })
                assertEquals(listOf(0, BrainPhase.EXECUTE, steps[0].instructions), listOf(it.r.nextStep, it.r.phase, it.r.task))
                assertEquals(BrainAuthor.SYSTEM to "Start writing.", it.messages.single().let { entry -> entry.author to entry.text })
            },
            Case("enter-workflow later records every earlier skip",
                record(), CoordinatorDecision.EnterWorkflow(steps[1].id, emptyList(), listOf(BrainSkip(steps[0].id, "Drafted.")), "Edit it.")) {
                assertEquals(listOf(BrainSkip(steps[0].id, "Drafted.")), it.r.summary.skipped)
                assertEquals(listOf(steps[1].id), it.r.attempts.map { attempt -> attempt.stepId })
            },
            Case("enter-workflow refuses an unexplained skip", record(), CoordinatorDecision.EnterWorkflow(steps[1].id, emptyList(), emptyList(), "Edit it."), null),
            Case("enter-workflow refuses an unknown step", record(), CoordinatorDecision.EnterWorkflow(newId(), emptyList(), emptyList(), "Go."), null),
            Case("start-step launches only the next step", record(BrainPhase.ADVANCE, 1, listOf(draft), true), CoordinatorDecision.StartStep(steps[1].id, emptyList())) {
                val queued = it.r.attempts.last()
                assertEquals(listOf(editor.id, steps[1].id), listOf(queued.role.roleId, queued.stepId))
                // The next attempt receives the latest applicable output.
                assertEquals(draft.outputIds, queued.inputIds)
            },
            Case("start-step refuses a step that is not next", record(BrainPhase.ADVANCE, 1, listOf(draft), true), CoordinatorDecision.StartStep(steps[0].id, emptyList()), null),
            Case("skip-step advances past the next step", record(BrainPhase.ADVANCE, 0, inWorkflow = true), CoordinatorDecision.SkipStep(steps[0].id, "Drafted.")) {
                assertEquals(listOf(1, BrainPhase.ADVANCE), listOf(it.r.nextStep, it.r.phase))
                assertEquals(listOf(BrainSkip(steps[0].id, "Drafted.")), it.r.summary.skipped)
                assertEquals("Skipped Writer: Drafted.", it.messages.single().text)
            },
            Case("skip-step of the last step finishes", record(BrainPhase.ADVANCE, 1, listOf(draft), true), CoordinatorDecision.SkipStep(steps[1].id, "Fine as is.")) {
                assertEquals(listOf(2, BrainPhase.FINISH), listOf(it.r.nextStep, it.r.phase))
            },
            Case("ready advances to the next step", record(BrainPhase.EVALUATE, 0, listOf(draft), true),
                CoordinatorDecision.StepResult(draft.id, StepOutcome.READY, "Drafted.")) {
                assertEquals(BrainAttemptOutcome.READY, it.r.attempts.single().outcome)
                assertEquals(listOf(1, BrainPhase.ADVANCE, BrainRunState.PLANNING), listOf(it.r.nextStep, it.r.phase, it.r.summary.state))
                assertEquals(BrainAuthor.COORDINATOR to "Drafted.", it.messages.single().let { entry -> entry.author to entry.text })
            },
            Case("ready on the last step finishes", record(BrainPhase.EVALUATE, 1, listOf(draft, edit), true),
                CoordinatorDecision.StepResult(edit.id, StepOutcome.READY, "Edited.")) {
                assertEquals(listOf(2, BrainPhase.FINISH), listOf(it.r.nextStep, it.r.phase))
            },
            Case("blocked waits for the person and re-plans the step", record(BrainPhase.EVALUATE, 0, listOf(draft), true),
                CoordinatorDecision.StepResult(draft.id, StepOutcome.BLOCKED, "A skill is missing.")) {
                assertEquals(BrainAttemptState.BLOCKED, it.r.attempts.single().state)
                assertNull(it.r.attempts.single().outcome)
                assertEquals(listOf(BrainRunState.AWAITING_INPUT, BrainPhase.ADVANCE), listOf(it.r.summary.state, it.r.phase))
                assertEquals(BrainWaiting(BrainWaitingKind.INPUT, "A skill is missing."), it.waiting)
            },
            Case("blocked ad hoc work goes back to planning", record(BrainPhase.EVALUATE, attempts = listOf(adHoc)),
                CoordinatorDecision.StepResult(adHoc.id, StepOutcome.BLOCKED, "No.")) {
                assertEquals(BrainPhase.PLAN, it.r.phase)
            },
            Case("changes needed return to the earlier step and make later results stale", record(BrainPhase.EVALUATE, 1, listOf(draft, edit), true),
                CoordinatorDecision.StepResult(edit.id, StepOutcome.CHANGES_NEEDED, "Too long.")) {
                assertEquals(listOf(0, BrainPhase.ADVANCE, 1), listOf(it.r.nextStep, it.r.phase, it.r.summary.returnsUsed))
                assertEquals(listOf(draft.id, edit.id), it.r.invalidated)
                assertEquals(edit.outputIds, it.r.inputs)
                assertEquals(BrainAttemptOutcome.CHANGES_NEEDED, it.r.attempts.last().outcome)
                assertEquals(listOf("Too long.", "Return to Writer: Too long."), it.messages.map { entry -> entry.text })
            },
            Case("changes needed without a return path wait for the person", record(BrainPhase.EVALUATE, 0, listOf(draft), true),
                CoordinatorDecision.StepResult(draft.id, StepOutcome.CHANGES_NEEDED, "Too long.")) {
                assertEquals(BrainRunState.AWAITING_INPUT, it.r.summary.state)
                assertEquals("Changes are needed, but this step has no return path.", it.notice)
                assertEquals(BrainAttemptOutcome.CHANGES_NEEDED, it.r.attempts.single().outcome)
            },
            Case("ready ad hoc work goes back to planning", record(BrainPhase.EVALUATE, attempts = listOf(adHoc)),
                CoordinatorDecision.StepResult(adHoc.id, StepOutcome.READY, "Good.")) {
                assertEquals(listOf(BrainPhase.PLAN, BrainRunState.PLANNING), listOf(it.r.phase, it.r.summary.state))
            },
            Case("a result grades only the last attempt", record(BrainPhase.EVALUATE, 1, listOf(draft, edit), true),
                CoordinatorDecision.StepResult(draft.id, StepOutcome.READY, "Good."), null),
        )
        for (case in cases) {
            val expect = case.expect
            if (expect == null) assertFails(case.name) { case.before.decided(case.decision, coordinator, ::newId) }
            else expect(runCatching { case.before.decided(case.decision, coordinator, ::newId) }.getOrElse { throw AssertionError(case.name, it) })
        }
    }

    @Test fun closingReopeningAndStoppingPauseOnlyUnfinishedWork() {
        val running = attempt(writer, null, BrainAttemptState.RUNNING)
        // (state before, cancelled) to the state after; null leaves the record as it was.
        val rows = listOf(
            Triple(BrainRunState.RUNNING, false, BrainRunState.PAUSED),
            Triple(BrainRunState.RUNNING, true, BrainRunState.CANCELLED),
            Triple(BrainRunState.AWAITING_APPROVAL, false, BrainRunState.PAUSED),
            Triple(BrainRunState.AWAITING_INPUT, false, null),
            Triple(BrainRunState.AWAITING_INPUT, true, BrainRunState.CANCELLED),
            Triple(BrainRunState.PAUSED, false, BrainRunState.PAUSED),
            Triple(BrainRunState.COMPLETED, true, null),
            Triple(BrainRunState.FAILED, false, null),
        )
        for ((state, cancelled, after) in rows) {
            val before = record(attempts = listOf(running), state = state)
            val ended = before.ended(cancelled)
            assertEquals(after, ended?.r?.summary?.state, "$state cancelled=$cancelled")
            if (ended != null) {
                assertEquals(BrainAttemptState.INTERRUPTED, ended.r.attempts.single().state)
                assertEquals(6L, ended.r.summary.revision)
                assertEquals(if (cancelled) null else BrainWaitingKind.RECOVERY, ended.waiting?.kind)
            }
            // Reopening pauses the same runs, without a revision of its own; a cancelled run is never reopened.
            val reopened = before.reopened()
            if (!cancelled) assertEquals(after ?: state, reopened.r.summary.state, "reopen $state")
            if (after == null) assertSame(before, reopened)
        }
    }

    @Test fun aPauseInterruptsRunningWorkAndSaysWhy() {
        val before = record(attempts = listOf(attempt(writer, null, BrainAttemptState.RUNNING)), state = BrainRunState.RUNNING)
        for ((kind, state) in listOf(BrainWaitingKind.INPUT to BrainRunState.AWAITING_INPUT, BrainWaitingKind.LIMIT to BrainRunState.PAUSED,
            BrainWaitingKind.RECOVERY to BrainRunState.PAUSED)) {
            val paused = before.paused("x".repeat(BrainLimits.DESCRIPTION + 1), kind)
            assertEquals(state, paused.r.summary.state)
            assertEquals(BrainAttemptState.INTERRUPTED, paused.r.attempts.single().state)
            assertEquals(BrainWaiting(kind, "x".repeat(BrainLimits.DESCRIPTION)), paused.waiting)
            assertEquals(paused.waiting?.summary, paused.notice)
        }
    }

    @Test fun aFailedTurnIsUncertainOnlyForProjectWorkThatCouldHaveRun() {
        val talk = attempt(writer, null, BrainAttemptState.RUNNING)
        val work = talk.copy(role = talk.role.copy(mode = MemberMode.WORK))
        fun failure(kind: ChatFailure) = ProviderFailure(kind, RuntimeId.CODEX, "${writer.id}:codex", coordinator = false)
        // (attempt, failure) to the attempt's state after the pause.
        val rows = listOf(
            Triple(talk, null, BrainAttemptState.FAILED),
            Triple(talk, failure(ChatFailure.Temporary), BrainAttemptState.FAILED),
            Triple(work, failure(ChatFailure.UsageLimit(null)), BrainAttemptState.FAILED),
            Triple(work, failure(ChatFailure.Temporary), BrainAttemptState.INTERRUPTED),
            Triple(work, null, BrainAttemptState.INTERRUPTED),
        )
        for ((attempt, failure, after) in rows) {
            val paused = record(attempts = listOf(attempt), state = BrainRunState.RUNNING).afterFailure(failure, "Paused.")
            assertEquals(after, paused.r.attempts.single().state, "${attempt.role.mode} ${failure?.failure}")
            assertEquals(BrainRunState.PAUSED, paused.r.summary.state)
        }
        val context = NativeConversation("thread", "hash")
        val lost = record(attempts = listOf(talk)).let { it.copy(run = it.r.copy(contexts = mapOf("${writer.id}:codex" to context))) }
        assertTrue(lost.afterFailure(failure(ChatFailure.ConversationLost), "Paused.").r.contexts.isEmpty())
        assertEquals("Writer", lost.failingRole)
        assertEquals("the coordinator", record().failingRole)
    }

    @Test fun anApprovedResumeReplacesWorkThatNeverStartedAndAppendsWorkThatDid() {
        // (last attempt's state, retry) to (attempt count after, whether the queued attempt kept its ID).
        val rows = listOf(
            Triple(BrainAttemptState.QUEUED, false, 1 to true),
            Triple(BrainAttemptState.AWAITING_APPROVAL, false, 1 to true),
            Triple(BrainAttemptState.INTERRUPTED, false, 2 to false),
            Triple(BrainAttemptState.FAILED, false, 2 to false),
            Triple(BrainAttemptState.BLOCKED, true, 2 to false),
        )
        for ((state, retry, expected) in rows) {
            val last = attempt(writer, steps[0], state)
            val before = record(BrainPhase.EXECUTE, attempts = listOf(last), inWorkflow = true, state = BrainRunState.PAUSED).r
            val rerun = retry || state.dispatchedWithoutResult
            val resumed = before.resumed(rerun, null, 9, ::newId)
            assertEquals(expected.first, resumed.attempts.size, "$state")
            assertEquals(expected.second, resumed.attempts.last().id == last.id, "$state")
            assertEquals(BrainAttemptState.QUEUED, resumed.attempts.last().state)
            assertEquals(listOf(9L, BrainRunState.PLANNING), listOf(resumed.summary.revision, resumed.summary.state))
            assertEquals(steps[0].instructions, resumed.task)
            assertEquals(before.summary.dispatchesUsed + BrainLimits.DISPATCHES, resumed.dispatchesAllowance)
        }
        // Adopting a newer revision's settings rebinds the queued attempt to them.
        val adopted = configuration.copy(specialists = configuration.specialists.map { it.copy(instructions = "Adopted") })
        val before = record(BrainPhase.EXECUTE, attempts = listOf(attempt(writer, steps[0], BrainAttemptState.QUEUED)), inWorkflow = true).r
        val resumed = before.resumed(true, BrainDocument(2, adopted), 9, ::newId)
        assertEquals(listOf(2L, 2L), listOf(resumed.summary.configurationRevision, resumed.attempts.single().configurationRevision))
        assertEquals("Adopted", resumed.attempts.single().role.instructions)
    }

    @Test fun receiptsReplayTheSameRequestAndRefuseItsReuse() {
        val record = BrainRecord().withReceipt("a", "digest")
        assertTrue(record.replays("a", "digest"))
        assertFalse(record.replays("b", "digest"))
        assertFails { record.replays("a", "other") }
        val full = (1..RECEIPTS + 1).fold(BrainRecord()) { value, n -> value.withReceipt("$n", "d$n") }
        assertEquals(RECEIPTS, full.receipts.size)
        assertFalse("1" in full.receipts)
    }

    private companion object {
        const val RUN = "30000000-0000-4000-8000-000000000001"
    }
}
