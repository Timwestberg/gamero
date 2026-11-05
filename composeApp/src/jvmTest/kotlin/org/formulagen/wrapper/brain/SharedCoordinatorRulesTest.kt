package org.formulagen.wrapper.brain

import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.conversations.CONVERSATION_PROTOCOL
import org.formulagen.wrapper.conversations.brain
import org.formulagen.wrapper.conversation.MemberMode

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
        val steps = configuration.workflows.single().steps
        assertEquals(listOf(steps[0]), stagesFor(configuration, implementer, steps[0]))
        assertEquals(listOf(steps[1]), stagesFor(configuration, reviewer, null), "ad hoc: every step the role runs")
        assertFalse(asksBeforeStarting(configuration, reviewer, null))
        val strict = configuration.copy(workflows = listOf(configuration.workflows.single().copy(steps = steps.map {
            if (it.specialistId == reviewer.roleId) it.copy(askBeforeStarting = true) else it
        })))
        assertTrue(asksBeforeStarting(strict, reviewer, null))
        assertFalse(asksBeforeStarting(strict, implementer, null))
        val off = strict.copy(workflows = strict.workflows.map { it.copy(enabled = false) })
        assertEquals(emptyList(), stagesFor(off, reviewer, null), "a disabled workflow asks nothing of ad hoc work")
    }

    @Test fun adHocWorkHonorsTheStrictestStepInEveryEnabledWorkflow() {
        val brain = testWorkflowsConfiguration()
        val (writer, editor) = brain.specialists.map { BrainRoleSnapshot(it.id, it.name, checkNotNull(it.binding), "", it.mode) }
        val strict = brain.copy(workflows = brain.workflows.map { workflow ->
            if (workflow.name == "Writing") workflow else workflow.copy(steps = workflow.steps.map { it.copy(askBeforeStarting = true) })
        })
        val (writing, editing) = strict.workflows
        assertEquals(listOf(writing.steps[1], editing.steps[0]), stagesFor(strict, editor, null), "the Editor's step in each enabled workflow")
        assertTrue(asksBeforeStarting(strict, editor, null), "the Editor's step in Editing asks")
        assertFalse(asksBeforeStarting(strict, writer, null), "the Writer's asking step is in the disabled Archive")
    }

    /** The coordinator is shown the workflows it may follow, the default first, each with its Use it for and steps. */
    @Test fun theCoordinatorSeesTheEnabledWorkflowsDefaultFirst() {
        val brain = testWorkflowsConfiguration()
        val (writing, editing) = brain.workflows
        fun request(configuration: BrainConfiguration, workflowId: String? = null): JsonObject {
            val run = RunRecord(BrainRun(brainId(), 1, 1, workflowId, BrainRunState.PLANNING, null), configuration, null, "Tighten it",
                emptyList(), phase = if (workflowId == null) BrainPhase.PLAN else BrainPhase.ADVANCE)
            return brainJson.parseToJsonElement(coordinatorRequest(brainId(), run, allowedDecisions(run), "", "", emptyList())).jsonObject
        }
        val shown = request(brain).getValue("configuration").jsonObject
        val workflows = shown.getValue("workflows").jsonArray.map { it.jsonObject }
        assertEquals(listOf(editing.name, writing.name), workflows.map { it.getValue("name").jsonPrimitive.content }, "enabled only, the default first")
        assertEquals(listOf(editing.useFor, writing.useFor), workflows.map { it.getValue("useFor").jsonPrimitive.content })
        assertEquals(writing.steps.map { it.id }, workflows[1].getValue("steps").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertFalse("skills" in shown, "the brain's skills wait for contract 12")
        assertEquals(JsonPrimitive(editing.steps[0].id), request(brain, editing.id).getValue("nextStepId"), "the next step of the followed workflow")
        // A version 1 brain shows its one workflow the same way.
        val single = testConfiguration(workflow = true)
        val one = request(single).getValue("configuration").jsonObject
        assertFalse("workflow" in one)
        assertEquals(listOf(single.workflows.single().id), one.getValue("workflows").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val off = single.copy(workflows = single.workflows.map { it.copy(enabled = false) })
        assertEquals(0, request(off).getValue("configuration").jsonObject.getValue("workflows").jsonArray.size, "a disabled workflow stays out")
        assertTrue("if several match, use the default" in CONVERSATION_PROTOCOL)
    }
}
