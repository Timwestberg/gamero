package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Brain snapshots encode exactly the nulls event-contracts `brain.schemas.ts` requires, and omit the optional ones. */
class BrainWireTest {
    private fun id(n: Int) = "10000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private fun fixture(name: String) = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue(name)).jsonObject.getValue("value")
    private fun envelope(snapshot: BrainSnapshot) = Json.parseToJsonElement(BridgeEnvelopes.success(brainWire(snapshot)))

    /** An activity step without detail, an ad hoc attempt (`stepId: null`) and a run outside a workflow. */
    @Test fun theWorkingSnapshotEnvelopeMatchesTheContractFixture() {
        val fixture = fixture("success-brain-snapshot-working.json")
        val coordinator = BrainRoleSnapshot(id(1), "Coordinator", BrainBinding(RuntimeId.CODEX, "gpt-6.1-sol", ModelEffort.HIGH), "Help the user.", MemberMode.TALK)
        val writer = BrainRoleSnapshot(id(2), "Writer", BrainBinding(RuntimeId.CLAUDE_CODE, "sonnet"), "Write clearly.", MemberMode.WORK)
        val snapshot = BrainSnapshot(1, coordinator,
            listOf(
                BrainEntry(id(60), "user", "Draft the release notes."),
                BrainEntry(id(61), "specialist", "Draft notes", writer, Activity(listOf(
                    ActivityStep(ActivityKind.SEARCH, "CHANGELOG", ActivityStatus.DONE, "3 matches"),
                    ActivityStep(ActivityKind.READ, "CHANGELOG.md", ActivityStatus.DONE)), truncated = false)),
                BrainEntry(id(62), "coordinator", "Which release should they cover?", coordinator),
            ),
            BrainRun(id(90), 4, 1, null, "awaiting-input", id(91), dispatchesUsed = 1),
            listOf(BrainAttempt(id(91), id(90), null, writer, 1, emptyList(), listOf(id(92)), "completed")),
            listOf(BrainArtifact(id(92), "Writer", "a".repeat(64), BrainSource("attempt", attemptId = id(91)), "text/markdown", "Draft notes")),
            BrainWaiting("input", "Which release should they cover?"), ChatVoice(), "4")
        assertEquals(fixture, envelope(snapshot))
        assertEquals(snapshot, brainJson.decodeFromJsonElement(BrainSnapshot.serializer(), fixture.jsonObject.getValue("result")))
    }

    /** Before a coordinator is saved: `coordinator`, `run` and `waiting` are null, never missing. */
    @Test fun theEmptySnapshotEnvelopeMatchesTheContractFixture() {
        val fixture = fixture("success-brain-snapshot-empty.json")
        val snapshot = BrainSnapshot(0, null, emptyList(), null, emptyList(), emptyList(), null, ChatVoice(), "0")
        assertEquals(fixture, envelope(snapshot))
        assertEquals(snapshot, brainJson.decodeFromJsonElement(BrainSnapshot.serializer(), fixture.jsonObject.getValue("result")))
    }

    @Test fun optionalNullsNeverReachTheWire() {
        val issue = brainWire(BrainIssue("missing-binding", roleId = id(1))).jsonObject
        assertEquals(setOf("reason", "roleId"), issue.keys)
        val step = brainWire(ActivityStep(ActivityKind.COMMAND, "npm test", ActivityStatus.FAILED)).jsonObject
        assertFalse("detail" in step)
        val role = brainWire(SpecialistRole(id(2), "Writer", "", MemberMode.TALK, null, 0)).jsonObject
        assertEquals(JsonNull, role.getValue("binding"))
        assertFalse("notice" in brainWire(ChatVoice()).jsonObject)
    }
}
