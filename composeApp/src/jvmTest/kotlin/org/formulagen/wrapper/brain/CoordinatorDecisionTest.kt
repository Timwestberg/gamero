package org.formulagen.wrapper.brain

import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

class CoordinatorDecisionTest {
    @Test fun coordinatorSkipReasonsMustBeTextBeforeDispatch() {
        val decision = """{"kind":"enter-workflow","entryStepId":"${brainId()}","inputIds":[],"skipped":[{"stepId":"${brainId()}","reason":false}],"reason":"Begin with the supplied plan"}"""
        assertFails { parseDecision(decision, setOf("enter-workflow")) }
    }
    @Test fun coordinatorEvidenceAcceptsItsLimitAndRejectsLongerExplanations() {
        fun decision(size: Int) = buildJsonObject {
            put("kind", "step-result"); put("attemptId", brainId()); put("outcome", "ready")
            put("explanation", "x".repeat(size))
        }.toString()
        assertEquals(BrainLimits.DESCRIPTION, parseDecision(decision(BrainLimits.DESCRIPTION), setOf("step-result")).getValue("explanation").jsonPrimitive.content.length)
        assertFails { parseDecision(decision(BrainLimits.DESCRIPTION + 1), setOf("step-result")) }
    }
    @Test fun aDecisionAttachesAtMostTheInputLimit() {
        fun decision(count: Int) = buildJsonObject {
            put("kind", "start-step"); put("stepId", brainId()); put("inputIds", JsonArray(List(count) { JsonPrimitive(brainId()) }))
        }.toString()
        parseDecision(decision(BrainLimits.INPUTS), setOf("start-step"))
        assertFails { parseDecision(decision(BrainLimits.INPUTS + 1), setOf("start-step")) }
    }
}
