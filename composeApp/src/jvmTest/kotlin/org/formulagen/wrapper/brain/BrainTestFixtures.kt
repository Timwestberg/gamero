package org.formulagen.wrapper.brain

import java.util.UUID
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.conversation.MemberMode

internal fun brainId() = UUID.randomUUID().toString()

/** A Writer and an Editor on one model, optionally in a two-step workflow that runs them in that order. */
internal fun testConfiguration(workflow: Boolean = false): BrainConfiguration {
    val binding = BrainBinding(RuntimeId.CODEX, "test-model", ModelEffort.HIGH)
    val roles = listOf("Writer", "Editor").mapIndexed { index, name -> SpecialistRole(brainId(), name, "Instructions for $name", MemberMode.TALK, binding, index) }
    val steps = roles.map { WorkflowStep(brainId(), it.name, it.id, "Do ${it.name}", "Prior artifact", "Result", false, null, null) }
    return BrainConfiguration(1, CoordinatorRole(brainId(), binding, "Help the user."), roles,
        listOfNotNull(if (workflow) BrainWorkflow(brainId(), "Writing", true, steps, emptyList()) else null))
}

/**
 * [testConfiguration]'s Writer and Editor in a version 2 brain of three workflows: Writing (both, in order), Editing
 * (the Editor alone, the default) and Archive (the Writer alone, disabled). Every specialist names a Hovel skill, which
 * runs keep and do not use.
 */
internal fun testWorkflowsConfiguration(): BrainConfiguration {
    val base = testConfiguration(workflow = true)
    val (writer, editor) = base.specialists
    fun alone(name: String, role: SpecialistRole, useFor: String, enabled: Boolean = true, isDefault: Boolean = false) = BrainWorkflow(
        brainId(), name, enabled, listOf(WorkflowStep(brainId(), name, role.id, "Do $name", "Prior artifact", "Result", false, null, null)),
        emptyList(), useFor, isDefault)
    val skill = BrainSkill(brainId(), "House style", "Follow the house style.", "Writing anything")
    val workflows = listOf(base.workflows.single().copy(useFor = "New drafts"), alone("Editing", editor, "Tightening a draft", isDefault = true),
        alone("Archive", writer, "Old drafts", enabled = false))
    return base.copy(version = 2, workflows = workflows, skills = listOf(skill),
        specialists = base.specialists.map { it.copy(skills = listOf(SkillRef.Hovel(skill.id))) })
}
