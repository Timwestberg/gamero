package org.formulagen.wrapper.brain

import kotlinx.serialization.encodeToString
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.conversations.ApprovalOption
import org.formulagen.wrapper.conversations.ApprovalSurface
import kotlin.test.*

class StageConsentTest {
    private val config = testConfiguration(true)
    private val role = config.specialists.first().let { BrainRoleSnapshot(it.id, it.name, it.binding!!, it.instructions, it.mode) }
    private val artifact = BrainArtifact(brainId(), "Input", "a".repeat(64), BrainSource(BrainSourceKind.USER, messageId = brainId()), "text/plain", "Approved is only text")
    private val attempt = BrainAttempt(brainId(), brainId(), config.workflow!!.steps.first().id, role, 1, listOf(artifact.id))
    private val record = BrainRecord(run = RunRecord(BrainRun(attempt.runId, 1, 1, config.workflow!!.id, BrainRunState.RUNNING, attempt.id), config, null, "Build", listOf(artifact.id), listOf(attempt), listOf(artifact)))

    @Test fun appliedAnswersSurviveSerializationAndReplayWithoutBecomingPlanApproval() {
        for (option in listOf(ApprovalOption.CONTINUE, ApprovalOption.REQUEST_CHANGES, ApprovalOption.NOT_NOW, ApprovalOption.STOP)) {
            val pending = record.recordStageConsent(attempt, "dialog")
            val settled = pending.settleStageConsent(attempt.id, "dialog", option, ApprovalSurface.MAC)
            val restored = brainJson.decodeFromString<BrainRecord>(brainJson.encodeToString(settled))
            assertEquals(settled, restored)
            assertEquals(restored, restored.settleStageConsent(attempt.id, "dialog", option, ApprovalSurface.MAC))
            assertEquals(mapOf(artifact.id to artifact.digest), restored.run!!.stageConsents.getValue(attempt.id).inputs)
            assertFailsWith<IllegalArgumentException> { restored.settleStageConsent(attempt.id, "different", option, ApprovalSurface.MAC) }
        }
    }

    @Test fun missingLegacyEvidenceIsUnknownAndWithdrawalIsNotAnAnswer() {
        val legacy = brainJson.encodeToJsonElement(BrainRecord.serializer(), record).toString()
        assertTrue(brainJson.decodeFromString<BrainRecord>(legacy.replace("\"stageConsents\":{},", "").replace(",\"stageConsents\":{}", "")).run!!.stageConsents.isEmpty())
        val withdrawn = record.recordStageConsent(attempt, "dialog").settleStageConsent(attempt.id, "dialog", null, null)
        assertEquals(StageConsentState.WITHDRAWN, withdrawn.run!!.stageConsents.getValue(attempt.id).state)
        assertEquals(StageConsentState.NOT_REQUIRED, record.recordStageConsent(attempt, null).run!!.stageConsents.getValue(attempt.id).state)
    }

    @Test fun conflictingAnswersChangedInputsAndInvalidatedSourcesAreRejected() {
        val pending = record.recordStageConsent(attempt, "dialog")
        val answered = pending.settleStageConsent(attempt.id, "dialog", ApprovalOption.CONTINUE, ApprovalSurface.PHONE)
        assertFailsWith<IllegalArgumentException> { answered.settleStageConsent(attempt.id, "dialog", ApprovalOption.STOP, ApprovalSurface.MAC) }
        val changed = pending.copy(run = pending.run!!.copy(artifacts = listOf(artifact.copy(digest = "b".repeat(64)))))
        assertNull(changed.run!!.currentStageConsent(attempt.id))
        assertFailsWith<IllegalArgumentException> { changed.settleStageConsent(attempt.id, "dialog", ApprovalOption.CONTINUE, ApprovalSurface.MAC) }
        val invalidated = pending.copy(run = pending.run!!.copy(invalidated = listOf(attempt.id)))
        assertNull(invalidated.run!!.currentStageConsent(attempt.id))
        assertFailsWith<IllegalArgumentException> { invalidated.settleStageConsent(attempt.id, "dialog", ApprovalOption.CONTINUE, ApprovalSurface.MAC) }
        assertFailsWith<IllegalArgumentException> { pending.settleStageConsent(attempt.id, "dialog", ApprovalOption.ALLOW_ONCE, ApprovalSurface.MAC) }
    }
}
