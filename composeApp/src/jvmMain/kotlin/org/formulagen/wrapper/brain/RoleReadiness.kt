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
 * its model or effort not in the provider's [models] (null when they could not be read); and for a working role,
 * a runtime that may not work here ([canWork]) or no project folder ([hasProject]). Catalog checks never
 * substitute a model.
 */
internal fun roleIssues(
    roleId: String,
    binding: BrainBinding?,
    mode: MemberMode,
    connected: Boolean,
    models: List<ProviderModel>?,
    canWork: Boolean,
    hasProject: Boolean,
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
    return issues
}

/**
 * The workflow steps whose stage approval [role]'s attempt at [step] honors: that step, or for ad hoc work, every
 * step of the enabled workflow the role runs, so the strictest of them applies.
 */
internal fun stagesFor(configuration: BrainConfiguration, role: BrainRoleSnapshot, step: WorkflowStep?): List<WorkflowStep> =
    step?.let(::listOf) ?: configuration.workflow?.takeIf { it.enabled }?.steps?.filter { it.specialistId == role.roleId }.orEmpty()

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
