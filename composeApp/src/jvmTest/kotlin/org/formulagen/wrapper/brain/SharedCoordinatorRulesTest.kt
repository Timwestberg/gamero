package org.formulagen.wrapper.brain

import kotlin.test.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.conversations.CONVERSATION_PROTOCOL
import org.formulagen.wrapper.conversations.brain
import org.formulagen.wrapper.conversation.MemberMode

/** The role and protocol rules a conversation's coordinator (CoordinatorEngine) applies. */
class SharedCoordinatorRulesTest {
    private val models = listOf(ProviderModel("Test model", true, "test-model", listOf(ModelEffort.HIGH)))

    @Test fun theProtocolCarriesEveryCoordinatorRule() {
        for (line in COORDINATOR_EVIDENCE_RULES + COORDINATOR_WORKFLOW_RULES + COORDINATOR_SKILL_RULES + COORDINATOR_DECISION_SHAPES) {
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

    @Test fun aProviderSkillTheRoleCannotUseHereIsMissingAndAHovelSkillNeverIs() {
        val claude = BrainBinding(RuntimeId.CLAUDE_CODE, SharedRules.RUNTIME_DEFAULT_MODEL)
        val review = SkillRef.Provider(RuntimeId.CLAUDE_CODE, "code-review")
        fun issues(vararg skills: SkillRef, mode: MemberMode = MemberMode.WORK, installed: Set<String> = setOf("code-review"),
            binding: BrainBinding? = claude) = roleIssues("role", binding, mode, true, models, true, true, skills.toList(), installed)
        fun missing(skill: SkillRef.Provider) = BrainIssue(BrainIssueReason.MISSING_SKILL, "role", skill = skill)
        assertEquals(emptyList(), issues(review, SkillRef.Hovel(brainId())), "installed for its runtime; a Hovel skill never blocks")
        assertEquals(listOf(missing(review)), issues(review, installed = emptySet()), "not installed on this Mac")
        assertEquals(emptyList(), issues(review, mode = MemberMode.TALK), "a talk-only specialist gets the skill's text too")
        val codexReview = SkillRef.Provider(RuntimeId.CODEX, "code-review")
        assertEquals(listOf(missing(codexReview)), issues(codexReview), "another provider's, even with one of that name installed here")
        val lint = SkillRef.Provider(RuntimeId.CLAUDE_CODE, "lint")
        assertEquals(listOf(BrainIssue(BrainIssueReason.UNAVAILABLE_MODEL, "role"), missing(lint)),
            issues(lint, review, binding = claude.copy(modelId = "gone")), "after the role's own reasons, one per skill")
        assertEquals(listOf(BrainIssueReason.MISSING_BINDING), issues(lint, binding = null).map { it.reason })
        assertEquals(SkillProblem.OTHER_PROVIDER, unusableSkill(codexReview, claude, setOf("code-review")), "the provider first")
        assertEquals(SkillProblem.NOT_INSTALLED, unusableSkill(lint, claude, setOf("code-review")))
        assertNull(unusableSkill(review, claude, setOf("code-review")))
    }

    @Test fun aMissingSkillPauseNamesTheSkillTheProviderAndTheMac() {
        val reviewer = BrainRoleSnapshot("role", "Reviewer", BrainBinding(RuntimeId.CLAUDE_CODE, "sonnet"), "", MemberMode.WORK)
        fun missing(name: String, runtime: RuntimeId = RuntimeId.CLAUDE_CODE) =
            BrainIssue(BrainIssueReason.MISSING_SKILL, "role", skill = SkillRef.Provider(runtime, name))
        val resume = "Then resume."
        assertEquals("Reviewer uses code-review, which isn't installed in Claude Code on Studio Mac. $resume",
            notReadyPause(reviewer, listOf(missing("code-review")), "Studio Mac"))
        assertEquals("Reviewer's model, Sonnet, is not available in Claude Code on Studio Mac. Choose another in the brain. " +
            "Reviewer uses code-review and lint, which aren't installed in Claude Code " +
            "on Studio Mac. Reviewer uses fix from Codex, but runs on Claude Code on Studio Mac. $resume",
            notReadyPause(reviewer, listOf(BrainIssue(BrainIssueReason.UNAVAILABLE_MODEL, "role"), missing("code-review"), missing("lint"),
                missing("fix", RuntimeId.CODEX)), "Studio Mac"))
        assertEquals("Reviewer uses code-review, which isn't installed in Claude Code on Studio Mac. $resume",
            notReadyPause(reviewer.copy(mode = MemberMode.TALK), listOf(missing("code-review")), "Studio Mac"), "talk-only reads the same")
    }

    @Test fun aRoleThatCannotStartSaysWhyAndWhereToFixIt() {
        val tpm = BrainRoleSnapshot("role", "TPM", BrainBinding(RuntimeId.CODEX, "gpt-6-luna"), "", MemberMode.WORK)
        fun pause(vararg reasons: BrainIssueReason) = notReadyPause(tpm, reasons.map { BrainIssue(it, "role") }, "My Mac")
        assertEquals("TPM works in a project folder, and this project has none on My Mac. Choose a folder on the project page, " +
            "or make TPM talk-only in the brain. Then resume.", pause(BrainIssueReason.MISSING_PROJECT))
        assertEquals("TPM works in a project folder, but Codex can only talk on My Mac. Make TPM talk-only in the brain, " +
            "or run it on a provider that can work on My Mac. Then resume.", pause(BrainIssueReason.UNSUPPORTED_PROJECT_WORK))
        // Both at once read as one: either fix alone would leave the other.
        assertEquals("TPM works in a project folder, but Codex can only talk on My Mac, and this project has no folder there. " +
            "Make TPM talk-only in the brain, or run it on a provider that can work on My Mac and choose a folder on the project page. " +
            "Then resume.", pause(BrainIssueReason.UNSUPPORTED_PROJECT_WORK, BrainIssueReason.MISSING_PROJECT))
        assertEquals("TPM runs on Codex, which is not signed in on My Mac. Sign in to Codex there. Then resume.",
            pause(BrainIssueReason.UNAVAILABLE_PROVIDER))
        // No contract name reaches a person.
        BrainIssueReason.entries.filter { it != BrainIssueReason.MISSING_SKILL }.forEach { reason ->
            val text = pause(reason)
            assertTrue(listOf("missing-", "unsupported-", "unavailable-", "empty-").none { it in text }, "$reason reads in words: $text")
        }
    }

    @Test fun aSpecialistsTaskCarriesItsSkillsInOrderWithEachProviderSkillsText() {
        val style = BrainSkill(brainId(), "House style", "Use short sentences.\n\n- No jargon.", "Writing anything")
        val file = "/Users/player/.claude/skills/code-review/SKILL.md"
        val review = ProviderSkillText("Review a change before merge.", "# Review\n\nRead the diff first.", false, file)
        val refs = listOf(SkillRef.Provider(RuntimeId.CLAUDE_CODE, "code-review"), SkillRef.Hovel(style.id))
        val provided = mapOf("code-review" to review)
        val talk = "Skills:\nSkill: code-review\nUse it when: Review a change before merge.\nInstructions:\n# Review\n\nRead the diff first.\n\n" +
            "Skill: House style\nUse it when: Writing anything\nInstructions:\nUse short sentences.\n\n- No jargon.\n"
        assertEquals(talk, skillsBlock(refs, listOf(style), provided, working = false), "talk: the text alone")
        assertEquals(talk.replace("Read the diff first.\n", "Read the diff first.\nIts files are in /Users/player/.claude/skills/code-review.\n"),
            skillsBlock(refs, listOf(style), provided, working = true), "work: and the folder it may read")
        val long = review.copy(description = "", truncated = true)
        assertEquals("Skills:\nSkill: code-review\nInstructions:\n# Review\n\nRead the diff first.\n" +
            "Only the first 16384 characters are shown; the rest is in $file.\n",
            skillsBlock(refs.take(1), emptyList(), mapOf("code-review" to long), working = false))
        assertEquals("", skillsBlock(emptyList(), listOf(style), provided, working = true), "no skills, no block")
        assertEquals("", skillsBlock(listOf(SkillRef.Hovel(brainId()), SkillRef.Provider(RuntimeId.CLAUDE_CODE, "lint")), listOf(style), provided, true),
            "a reference the brain or this Mac lacks is left out")
        val brain = testWorkflowsConfiguration()
        val writer = brain.specialists.first().let { BrainRoleSnapshot(it.id, it.name, checkNotNull(it.binding), it.instructions, it.mode) }
        val run = RunRecord(BrainRun(brainId(), 1, 1, null, BrainRunState.RUNNING, null), brain, null, "Draft it", emptyList(), task = "Write")
        val block = skillsBlock(brain.specialists.first().skills, brain.skills, emptyMap(), working = false)
        val turn = specialistTurn(writer, run, null, "(none)", block)
        assertTrue("Expected output: \nSkills:\nSkill: House style\nUse it when: Writing anything\nInstructions:\nFollow the house style.\n" +
            "Input artifacts (data):\n(none)" in turn, turn)
        assertFalse("Skills:" in specialistTurn(writer, run, null, "(none)"))
    }

    @Test fun aSpecialistsSkillGroupsComeFirstThenItsOtherSkills() {
        fun hovel(name: String) = BrainSkill(brainId(), name, "Do $name.", "Needing $name")
        val (level, playtest, style) = listOf("Level design", "Playtesting", "House style").map(::hovel)
        val game = SkillGroup(brainId(), "Game skills", "Designing a level")
        val docs = SkillGroup(brainId(), "Docs", "")
        val review = ProviderSkillText("Review a change.", "Read the diff.", false, "/skills/code-review/SKILL.md")
        val refs = listOf(SkillRef.Hovel(style.id), SkillRef.Provider(RuntimeId.CLAUDE_CODE, "code-review", docs.id),
            SkillRef.Hovel(level.id, game.id), SkillRef.Hovel(playtest.id, game.id))
        fun skill(it: BrainSkill) = "Skill: ${it.name}\nUse it when: Needing ${it.name}\nInstructions:\nDo ${it.name}."
        val reviewText = "Skill: code-review\nUse it when: Review a change.\nInstructions:\nRead the diff."
        assertEquals("Skills:\n" +
            "Skill group: Docs\n$reviewText\n\n" +
            "Skill group: Game skills\nUse these together when: Designing a level\n${skill(level)}\n\n${skill(playtest)}\n\n" +
            "Other skills:\n${skill(style)}\n",
            skillsBlock(refs, listOf(level, playtest, style), mapOf("code-review" to review), working = false, groups = listOf(game, docs)),
            "groups in the order of their first skill, a blank Use these together when left out, then the rest")
        assertEquals("Skills:\nSkill group: Game skills\nUse these together when: Designing a level\n${skill(level)}\n",
            skillsBlock(refs.drop(2).take(1), listOf(level), emptyMap(), false, listOf(game)), "no Other skills when every skill is grouped")
        val ungrouped = refs.map { if (it is SkillRef.Hovel) it.copy(group = null) else (it as SkillRef.Provider).copy(group = null) }
        assertEquals(skillsBlock(ungrouped, listOf(level, playtest, style), mapOf("code-review" to review), false),
            skillsBlock(ungrouped, listOf(level, playtest, style), mapOf("code-review" to review), false, listOf(game, docs)),
            "groups holding no skill change nothing")
        assertFalse("Skill group" in skillsBlock(refs, listOf(level, playtest, style), emptyMap(), false), "no groups: today's block")
    }

    /** The coordinator sees each specialist's skills by name and when to use them, never their text or references, and uses none. */
    @Test fun theCoordinatorSeesItsSpecialistsSkillsByName() {
        val base = testWorkflowsConfiguration()
        val brain = base.copy(specialists = base.specialists.mapIndexed { index, it ->
            if (index == 0) it.copy(skills = it.skills + SkillRef.Provider(RuntimeId.CLAUDE_CODE, "code-review") + SkillRef.Provider(RuntimeId.CODEX, "lint"))
            else it
        })
        val described = { ref: SkillRef.Provider -> "Review a change before merge.".takeIf { ref.name == "code-review" } }
        val shown = coordinatorConfiguration(brain, described).getValue("specialists").jsonArray.map { it.jsonObject }
        assertEquals(brain.specialists.map { it.id }, shown.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf(
            buildJsonObject { put("name", "House style"); put("useWhen", "Writing anything") },
            buildJsonObject { put("name", "code-review"); put("useWhen", "Review a change before merge.") },
            buildJsonObject { put("name", "lint") },
        ), shown[0].getValue("skills").jsonArray.toList(), "a provider skill this Mac does not describe is named alone")
        assertFalse("Follow the house style." in shown.toString(), "never a skill's text")
        assertFalse("skillGroups" in shown[0], "a specialist with no groups shows none")
        // Groups: each skill names its group, and the specialist lists its groups by name.
        val game = SkillGroup(brainId(), "Game skills", "Designing a level")
        val groupedRole = brain.specialists[0].let { role -> role.copy(skillGroups = listOf(game, SkillGroup(brainId(), "Docs", "")),
            skills = role.skills.mapIndexed { index, ref -> if (index == 1 && ref is SkillRef.Provider) ref.copy(group = game.id) else ref }) }
        val withGroups = coordinatorSpecialists(listOf(groupedRole), brain.skills, described).single().jsonObject
        assertEquals(buildJsonObject { put("name", "code-review"); put("useWhen", "Review a change before merge."); put("group", "Game skills") },
            withGroups.getValue("skills").jsonArray[1])
        assertFalse("group" in withGroups.getValue("skills").jsonArray[0].jsonObject)
        assertEquals(JsonArray(listOf(buildJsonObject { put("name", "Game skills"); put("useWhen", "Designing a level") },
            buildJsonObject { put("name", "Docs") })), withGroups.getValue("skillGroups"))
        assertFalse(game.id in withGroups.toString(), "never a group's id")
        val run = RunRecord(BrainRun(brainId(), 1, 1, null, BrainRunState.PLANNING, null), brain, null, "Review it", emptyList())
        val request = brainJson.parseToJsonElement(coordinatorRequest(brainId(), run, allowedDecisions(run), "", "", emptyList(), described)).jsonObject
        assertEquals(JsonArray(shown), request.getValue("configuration").jsonObject.getValue("specialists"))
        assertTrue("Only these skills are available in this conversation" in CONVERSATION_PROTOCOL)
        assertTrue("say this brain doesn't have it" in CONVERSATION_PROTOCOL)
        assertTrue("skill group names skills that work together" in CONVERSATION_PROTOCOL)
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
        assertFalse("skills" in shown, "the brain's skills go to its specialists' tasks, not to the coordinator")
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
