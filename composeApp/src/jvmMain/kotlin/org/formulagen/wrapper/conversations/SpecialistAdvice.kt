package org.formulagen.wrapper.conversations

/** Reject stale, foreign or ineligible advice before it enters coordinator context. */
internal fun validSpecialistAdvice(claim: ClaimResult, now: Long = System.currentTimeMillis()): SpecialistAdvice? {
    val advice = claim.specialistAdvice ?: return null
    val config = claim.brain.configuration
    return advice.takeIf {
        config.specialistAdvice == true && config.decisionModel != org.formulagen.wrapper.bridge.DecisionModelSelection.LAYA &&
            config.workflow?.enabled != true && claim.message.threadId == null &&
            it.messageId == claim.message.id && it.configurationRevision == claim.brain.revision &&
            it.model == "jev-1.14.0" && it.confidence.isFinite() && it.confidence in 0.8..1.0 &&
            it.expiresAt > now && it.expiresAt - now <= 30_000 &&
            config.specialists.any { role -> role.id == it.roleId && role.binding != null }
    }
}
