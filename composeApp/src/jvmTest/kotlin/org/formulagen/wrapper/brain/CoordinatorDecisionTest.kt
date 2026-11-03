package org.formulagen.wrapper.brain

import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

class CoordinatorDecisionTest {
    @Test fun questionsAcceptExplicitChoicesAndRejectInvalidOnes() {
        assertEquals(CoordinatorDecision.Ask("Which?", listOf("Focused", "Detailed")), parseDecision("""{"kind":"ask","question":"Which?","choices":["Focused","Detailed"]}""", setOf(DecisionKind.ASK)))
        assertEquals(CoordinatorDecision.Ask("Which?"), parseDecision("""{"kind":"ask","question":"Which?"}""", setOf(DecisionKind.ASK)))
        for (choices in listOf(listOf(" "), listOf("A", "A"), List(5) { "$it" }, listOf("x".repeat(241)))) {
            val text = buildJsonObject { put("kind", "ask"); put("question", "Which?"); put("choices", JsonArray(choices.map(::JsonPrimitive))) }
            assertFails { parseDecision(text.toString(), setOf(DecisionKind.ASK)) }
        }
    }

    @Test fun coordinatorSkipReasonsMustBeTextBeforeDispatch() {
        val decision = """{"kind":"enter-workflow","entryStepId":"${brainId()}","inputIds":[],"skipped":[{"stepId":"${brainId()}","reason":false}],"reason":"Begin with the supplied plan"}"""
        assertFails { parseDecision(decision, setOf(DecisionKind.ENTER_WORKFLOW)) }
    }
    @Test fun coordinatorEvidenceAcceptsItsLimitAndRejectsLongerExplanations() {
        fun decision(size: Int) = buildJsonObject {
            put("kind", "step-result"); put("attemptId", brainId()); put("outcome", "ready")
            put("explanation", "x".repeat(size))
        }.toString()
        val parsed = parseDecision(decision(BrainLimits.DESCRIPTION), setOf(DecisionKind.STEP_RESULT)) as CoordinatorDecision.StepResult
        assertEquals(BrainLimits.DESCRIPTION, parsed.explanation.length)
        assertFails { parseDecision(decision(BrainLimits.DESCRIPTION + 1), setOf(DecisionKind.STEP_RESULT)) }
    }
    @Test fun aDecisionAttachesAtMostTheInputLimit() {
        fun decision(count: Int) = buildJsonObject {
            put("kind", "start-step"); put("stepId", brainId()); put("inputIds", JsonArray(List(count) { JsonPrimitive(brainId()) }))
        }.toString()
        parseDecision(decision(BrainLimits.INPUTS), setOf(DecisionKind.START_STEP))
        assertFails { parseDecision(decision(BrainLimits.INPUTS + 1), setOf(DecisionKind.START_STEP)) }
    }
    /** Fields in any order, but exactly the decision's fields and types: the model cannot add a choice or slip one past as text. */
    @Test fun aDecisionIsReadExactlyWhateverItsFieldOrder() {
        val step = brainId(); val attempt = brainId()
        assertEquals(CoordinatorDecision.SkipStep(step, "Done"), parseDecision("""{"reason":"Done","stepId":"$step","kind":"skip-step"}""", setOf(DecisionKind.SKIP_STEP)))
        for (text in listOf(
            """{"stepId":"$step","reason":true,"kind":"skip-step"}""",
            """{"stepId":"$step","reason":"Done","kind":"skip-step","allow":true}""",
            """{"attemptId":"$attempt","explanation":"Fine","outcome":"approved","kind":"step-result"}""",
            """{"inputIds":[1],"stepId":"$step","kind":"start-step"}""",
            """{"text":null,"kind":"answer"}""",
            """{"kind":"answer","text":"Hi"} trailing""",
        )) assertFails(text) { parseDecision(text, DecisionKind.entries.toSet()) }
        assertFails { parseDecision("""{"kind":"answer","text":"Hi"}""", setOf(DecisionKind.ASK)) }
    }

    /** A conversation's thread reply offers only forward, answer and delegate; a run never takes a forward. */
    @Test fun aThreadReplyForwardsAnswersOrDelegatesAndNothingElse() {
        val run = RunRecord(BrainRun(brainId(), 1, 1, null, BrainRunState.PLANNING, null), testConfiguration(), null, "Request", emptyList(),
            phase = BrainPhase.THREAD_REPLY)
        assertEquals(THREAD_REPLY_DECISIONS, allowedDecisions(run))
        assertEquals(setOf(DecisionKind.FORWARD, DecisionKind.ANSWER, DecisionKind.DELEGATE), THREAD_REPLY_DECISIONS)
        for (phase in BrainPhase.entries - BrainPhase.THREAD_REPLY) {
            assertFalse(DecisionKind.FORWARD in allowedDecisions(run.copy(phase = phase)), "$phase")
        }
        val thread = brainId()
        assertEquals(CoordinatorDecision.Forward(thread, "Context"),
            parseDecision("""{"kind":"forward","threadId":"$thread","note":"Context"}""", THREAD_REPLY_DECISIONS))
        assertFails { parseDecision("""{"kind":"forward","threadId":"$thread","note":"${"x".repeat(BrainLimits.STEP_INSTRUCTIONS + 1)}"}""", THREAD_REPLY_DECISIONS) }
        assertFails("a forward never applies to a run") {
            BrainRecord(run = run.copy(phase = BrainPhase.PLAN)).decided(CoordinatorDecision.Forward(thread), testConfiguration().coordinator.snapshot()!!, ::brainId)
        }
    }
}
