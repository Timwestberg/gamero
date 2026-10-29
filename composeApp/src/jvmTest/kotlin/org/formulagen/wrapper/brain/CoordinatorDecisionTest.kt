package org.formulagen.wrapper.brain

import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

class CoordinatorDecisionTest {
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
}
