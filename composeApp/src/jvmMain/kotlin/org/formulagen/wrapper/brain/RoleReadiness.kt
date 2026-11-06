package org.formulagen.wrapper.brain

import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.conversation.MemberMode

/*
 * Rules a conversation's coordinator (CoordinatorEngine) applies to its roles, as pure functions.
 */

/** The catalog model this binding runs on: the runtime's default for [SharedRules.RUNTIME_DEFAULT_MODEL], else the one named. */
internal fun BrainBinding.model(models: List<ProviderModel>) =
    models.find { if (modelId == SharedRules.RUNTIME_DEFAULT_MODEL) it.isDefault else it.modelId == modelId }

/**
 * Why a role cannot run now, by the brain's readiness reasons: no binding; its provider not signed in ([connected]);
 * its model or effort not in the provider's [models] (null when they could not be read); for a working role, a
 * runtime that may not work here ([canWork]) or no project folder ([hasProject]); and each of its provider [skills]
 * it cannot use ([unusableSkill]), given the skills its runtime has [installed] here and readable now. Hovel skills
 * never block. Catalog checks never substitute a model or a skill.
 */
internal fun roleIssues(
    roleId: String,
    binding: BrainBinding?,
    mode: MemberMode,
    connected: Boolean,
    models: List<ProviderModel>?,
    canWork: Boolean,
    hasProject: Boolean,
    skills: List<SkillRef> = emptyList(),
    installed: Set<String> = emptySet(),
): List<BrainIssue> {
    if (binding == null) return listOf(BrainIssue(BrainIssueReason.MISSING_BINDING, roleId))
    if (!connected) return listOf(BrainIssue(BrainIssueReason.UNAVAILABLE_PROVIDER, roleId))
    val issues = mutableListOf<BrainIssue>()
    val model = models?.let { binding.model(it) }
    if (model == null) issues += BrainIssue(BrainIssueReason.UNAVAILABLE_MODEL, roleId)
    if (binding.effort != null && model?.effortLevels?.contains(binding.effort) != true) issues += BrainIssue(BrainIssueReason.UNSUPPORTED_EFFORT, roleId)
    if (mode == MemberMode.WORK) {
        if (!canWork) issues += BrainIssue(BrainIssueReason.UNSUPPORTED_PROJECT_WORK, roleId)
        if (!hasProject) issues += BrainIssue(BrainIssueReason.MISSING_PROJECT, roleId)
    }
    for (skill in skills.filterIsInstance<SkillRef.Provider>()) {
        if (unusableSkill(skill, binding, installed) != null) issues += BrainIssue(BrainIssueReason.MISSING_SKILL, roleId, skill = skill)
    }
    return issues
}

/**
 * Why a role cannot use a provider skill ([unusableSkill]). The Mac gives a specialist its provider skills' text, so
 * a talk-only one can use them too.
 */
internal enum class SkillProblem {
    /** The skill is another provider's than the one the role runs on. */
    OTHER_PROVIDER,
    /** Its runtime has no skill of that name on this Mac, or its `SKILL.md` cannot be read now. */
    NOT_INSTALLED,
}

/** Why a role on [binding] cannot use provider [skill], given its runtime's [installed] skills; null when it can. */
internal fun unusableSkill(skill: SkillRef.Provider, binding: BrainBinding, installed: Set<String>): SkillProblem? = when {
    skill.runtimeId != binding.runtimeId -> SkillProblem.OTHER_PROVIDER
    skill.name !in installed -> SkillProblem.NOT_INSTALLED
    else -> null
}

/**
 * The workflow steps whose stage approval [role]'s attempt at [step] honors: that step, or for ad hoc work, every
 * step the role runs in the enabled workflows, so the strictest of them applies.
 */
internal fun stagesFor(configuration: BrainConfiguration, role: BrainRoleSnapshot, step: WorkflowStep?): List<WorkflowStep> =
    step?.let(::listOf) ?: configuration.enabledWorkflows.flatMap { it.steps }.filter { it.specialistId == role.roleId }

/** Whether [role]'s attempt at [step] asks before it starts ([stagesFor]). */
internal fun asksBeforeStarting(configuration: BrainConfiguration, role: BrainRoleSnapshot, step: WorkflowStep?): Boolean =
    stagesFor(configuration, role, step).any { it.askBeforeStarting }

/**
 * The companion's brain asks for something this Mac cannot run as configured, such as a role with no provider. The
 * person fixes it in Configure brain, so [message] is the line the conversation shows them, never a retry hint.
 */
internal class BrainConfigurationException(message: String) : IllegalStateException(message) {
    companion object {
        /** A role, the coordinator included, with no provider assigned. */
        fun unassigned(roleName: String) = BrainConfigurationException("Assign a provider to $roleName in Configure brain.")

        /**
         * A role bound to a runtime ([product]) this Mac does not run for it: not offered here at all, or, for a
         * working role ([working]), not one that may work in projects here.
         */
        fun notOnThisMac(roleName: String, product: String, working: Boolean) = BrainConfigurationException(
            (if (working) "$product can't work in projects on this Mac." else "$product doesn't run on this Mac.") +
                " Choose another provider for $roleName in Configure brain.")

        /** The coordinator's name in that line. */
        const val COORDINATOR = "the coordinator"
    }
}
