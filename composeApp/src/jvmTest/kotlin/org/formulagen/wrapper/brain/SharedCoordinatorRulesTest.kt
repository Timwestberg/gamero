package org.formulagen.wrapper.brain

import kotlin.test.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.conversations.CONVERSATION_PROTOCOL
import org.formulagen.wrapper.conversations.brain

/** The role and protocol rules a conversation's coordinator (CoordinatorEngine) applies. */
class SharedCoordinatorRulesTest {
    private val models = listOf(ProviderModel("Test model", true, "test-model", listOf(ModelEffort.HIGH)))

    @Test fun theProtocolCarriesEveryCoordinatorRule() {
        for (line in COORDINATOR_EVIDENCE_RULES + COORDINATOR_WORKFLOW_RULES + COORDINATOR_DECISION_SHAPES) {
            assertTrue(line in CONVERSATION_PROTOCOL, line)
        }
    }

    @Test fun aRoleIsReadyOnlyWhenEveryReasonIsMet() {
        val binding = BrainBinding(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)
        fun issues(binding: BrainBinding?, mode: MemberMode = MemberMode.TALK, connected: Boolean = true, models: List<ProviderModel>? = this.models,
            canWork: Boolean = true, hasProject: Boolean = true) = roleIssues("role", binding, mode, connected, models, canWork, hasProject).map { it.reason }
        assertEquals(emptyList(), issues(binding))
        assertEquals(listOf(BrainIssueReason.MISSING_BINDING), issues(null))
        assertEquals(listOf(BrainIssueReason.UNAVAILABLE_PROVIDER), issues(binding, connected = false))
        assertEquals(listOf(BrainIssueReason.UNAVAILABLE_MODEL), issues(binding, models = null), "an unread catalog never substitutes a model")
        assertEquals(listOf(BrainIssueReason.UNAVAILABLE_MODEL), issues(binding.copy(modelId = "gone")))
        assertEquals(listOf(BrainIssueReason.UNSUPPORTED_EFFORT), issues(binding.copy(effort = ModelEffort.LOW)))
        assertEquals(emptyList(), issues(binding.copy(effort = ModelEffort.HIGH)))
        assertEquals(emptyList(), issues(binding, canWork = false, hasProject = false), "a talk role needs no project")
        assertEquals(listOf(BrainIssueReason.UNSUPPORTED_PROJECT_WORK, BrainIssueReason.MISSING_PROJECT),
            issues(binding, MemberMode.WORK, canWork = false, hasProject = false))
    }

    @Test fun adHocWorkHonorsTheStrictestStepTheRoleRuns() {
        val document = brain("Implementer" to MemberMode.WORK, "Reviewer" to MemberMode.TALK, workflow = true)
        val configuration = document.configuration
        val (implementer, reviewer) = configuration.specialists.map { BrainRoleSnapshot(it.id, it.name, checkNotNull(it.binding), "", it.mode) }
        val steps = configuration.workflow!!.steps
        assertEquals(listOf(steps[0]), stagesFor(configuration, implementer, steps[0]))
        assertEquals(listOf(steps[1]), stagesFor(configuration, reviewer, null), "ad hoc: every step the role runs")
        assertFalse(asksBeforeStarting(configuration, reviewer, null))
        val strict = configuration.copy(workflow = configuration.workflow!!.copy(steps = steps.map {
            if (it.specialistId == reviewer.roleId) it.copy(askBeforeStarting = true) else it
        }))
        assertTrue(asksBeforeStarting(strict, reviewer, null))
        assertFalse(asksBeforeStarting(strict, implementer, null))
        val off = strict.copy(workflow = strict.workflow!!.copy(enabled = false))
        assertEquals(emptyList(), stagesFor(off, reviewer, null), "a disabled workflow asks nothing of ad hoc work")
    }
}
