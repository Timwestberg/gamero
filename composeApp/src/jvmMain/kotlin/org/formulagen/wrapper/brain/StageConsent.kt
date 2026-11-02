package org.formulagen.wrapper.brain

import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.BrainAttempt
import org.formulagen.wrapper.conversations.ApprovalOption
import org.formulagen.wrapper.conversations.ApprovalSurface

/** Observational consent for one attempt, never permission to skip a native dialog. */
@Serializable
internal data class StageConsentRecord(
    val runId: String,
    val configurationRevision: Long,
    val attemptId: String,
    val stepId: String?,
    val inputs: Map<String, String>,
    val approvalId: String?,
    val state: StageConsentState,
    val option: ApprovalOption? = null,
    val surface: ApprovalSurface? = null,
)

@Serializable
internal enum class StageConsentState { NOT_REQUIRED, PENDING, ANSWERED, WITHDRAWN }

/** Bind consent to the pinned attempt and exact artifact contents; missing inputs are invalid. */
internal fun RunRecord.stageConsent(attempt: BrainAttempt, approvalId: String?, state: StageConsentState): StageConsentRecord {
    require(attempt.runId == summary.id && attempts.any { it == attempt })
    require(attempt.id !in invalidated)
    val inputs = attempt.inputIds.associateWith { id ->
        val artifact = artifacts.single { it.id == id }
        artifact.digest
    }
    return StageConsentRecord(summary.id, attempt.configurationRevision, attempt.id, attempt.stepId, inputs, approvalId, state)
}

/** Persist the pending dialog before showing it, or record that the pinned configuration needs none. */
internal fun BrainRecord.recordStageConsent(attempt: BrainAttempt, approvalId: String?): BrainRecord {
    val current = checkNotNull(run)
    val stored = current.attempts.single { it.id == attempt.id }
    if (approvalId == null) {
        val step = current.configuration.workflow?.steps?.find { it.id == stored.stepId }
        require(!asksBeforeStarting(current.configuration, stored.role, step))
    }
    val consent = current.stageConsent(stored, approvalId,
        if (approvalId == null) StageConsentState.NOT_REQUIRED else StageConsentState.PENDING)
    return copy(run = current.copy(stageConsents = current.stageConsents + (attempt.id to consent)))
}

/** Record the applied answer only for the matching pending dialog and unchanged inputs. */
internal fun BrainRecord.settleStageConsent(attemptId: String, approvalId: String, option: ApprovalOption?, surface: ApprovalSurface?): BrainRecord {
    val current = checkNotNull(run)
    val before = checkNotNull(current.stageConsents[attemptId])
    require(before.approvalId == approvalId)
    require(option == null || option in listOf(ApprovalOption.CONTINUE, ApprovalOption.REQUEST_CHANGES, ApprovalOption.NOT_NOW, ApprovalOption.STOP))
    require((option == null) == (surface == null))
    val attempt = current.attempts.single { it.id == attemptId }
    require(current.stageConsent(attempt, approvalId, before.state) == before.copy(option = null, surface = null))
    val after = before.copy(state = if (option == null) StageConsentState.WITHDRAWN else StageConsentState.ANSWERED, option = option, surface = surface)
    require(before.state == StageConsentState.PENDING || before == after)
    return copy(run = current.copy(stageConsents = current.stageConsents + (attemptId to after)))
}

/** Return only evidence whose attempt and inputs still match; never grant execution permission. */
internal fun RunRecord.currentStageConsent(attemptId: String): StageConsentRecord? {
    val stored = stageConsents[attemptId] ?: return null
    val attempt = attempts.singleOrNull { it.id == attemptId } ?: return null
    if (attempt.configurationRevision != summary.configurationRevision) return null
    if (artifacts.any { it.id in attempt.inputIds && it.source.attemptId in invalidated }) return null
    val binding = runCatching { stageConsent(attempt, stored.approvalId, stored.state) }.getOrNull() ?: return null
    return stored.takeIf { binding == it.copy(option = null, surface = null) }
}
