package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Brain results encode exactly the nulls event-contracts `brain.schemas.ts` requires, and omit the optional ones; brain
 * arguments decode only in that form. Each case goes through the [BrainMethods] serializers the router uses.
 */
class BrainWireTest {
    private fun id(n: Int) = "10000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private fun fixture(name: String) = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue(name)).jsonObject.getValue("value")
    private fun envelope(snapshot: BrainSnapshot) = Json.parseToJsonElement(BridgeEnvelopes.success(BrainMethods.OPEN.encodeResult(snapshot)))
    private val leaseIds = buildJsonObject { put("profile", "local"); put("companionId", id(80)); put("name", "Ember"); put("sessionId", id(82)) }
    private fun lease(build: JsonObjectBuilder.() -> Unit) = JsonObject(leaseIds + buildJsonObject(build))

    /** An activity step without detail, an ad hoc attempt (`stepId: null`) and a run outside a workflow. */
    @Test fun theWorkingSnapshotEnvelopeMatchesTheContractFixture() {
        val fixture = fixture("success-brain-snapshot-working.json")
        val coordinator = BrainRoleSnapshot(id(1), "Coordinator", BrainBinding(RuntimeId.CODEX, "gpt-6.1-sol", ModelEffort.HIGH), "Help the user.", MemberMode.TALK)
        val writer = BrainRoleSnapshot(id(2), "Writer", BrainBinding(RuntimeId.CLAUDE_CODE, "sonnet"), "Write clearly.", MemberMode.WORK)
        val snapshot = BrainSnapshot(1, coordinator,
            listOf(
                BrainEntry(id(60), BrainAuthor.USER, "Draft the release notes."),
                BrainEntry(id(61), BrainAuthor.SPECIALIST, "Draft notes", writer, Activity(listOf(
                    ActivityStep(ActivityKind.SEARCH, "CHANGELOG", ActivityStatus.DONE, "3 matches"),
                    ActivityStep(ActivityKind.READ, "CHANGELOG.md", ActivityStatus.DONE)), truncated = false)),
                BrainEntry(id(62), BrainAuthor.COORDINATOR, "Which release should they cover?", coordinator),
            ),
            BrainRun(id(90), 4, 1, null, BrainRunState.AWAITING_INPUT, id(91), dispatchesUsed = 1),
            listOf(BrainAttempt(id(91), id(90), null, writer, 1, emptyList(), listOf(id(92)), BrainAttemptState.COMPLETED)),
            listOf(BrainArtifact(id(92), "Writer", "a".repeat(64), BrainSource(BrainSourceKind.ATTEMPT, attemptId = id(91)), "text/markdown", "Draft notes")),
            BrainWaiting(BrainWaitingKind.INPUT, "Which release should they cover?"), ChatVoice(), "4")
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
        val issue = brainWire(BrainIssue(BrainIssueReason.MISSING_BINDING, roleId = id(1))).jsonObject
        assertEquals(setOf("reason", "roleId"), issue.keys)
        val step = brainWire(ActivityStep(ActivityKind.COMMAND, "npm test", ActivityStatus.FAILED)).jsonObject
        assertFalse("detail" in step)
        val role = brainWire(SpecialistRole(id(2), "Writer", "", MemberMode.TALK, null, 0)).jsonObject
        assertEquals(JsonNull, role.getValue("binding"))
        assertFalse("notice" in brainWire(ChatVoice()).jsonObject)
    }

    @Test fun readAndSaveResultsKeepTheirStatusFirstAndDropOptionalNulls() {
        val document = BrainDocument(3, BrainConfiguration(1, CoordinatorRole(id(1), null, ""), emptyList(), null))
        val found = BrainMethods.READ.encodeResult(BrainReadResult.Found(document, listOf(BrainIssue(BrainIssueReason.MISSING_BINDING, roleId = id(1)))))
        assertEquals("""{"status":"found","document":{"revision":3,"configuration":{"version":1,"coordinator":{"id":"${id(1)}","binding":null,"instructions":""},"specialists":[],"workflow":null}},"issues":[{"reason":"missing-binding","roleId":"${id(1)}"}]}""", found.toString())
        assertEquals("""{"status":"absent"}""", BrainMethods.READ.encodeResult(BrainReadResult.Absent).toString())
        assertEquals("""{"status":"conflict","currentRevision":4}""", BrainMethods.SAVE.encodeResult(BrainSaveResult.Conflict(4)).toString())
        assertEquals("""{"status":"saved","document":${brainWire(document)},"issues":[{"reason":"empty-workflow"}]}""",
            BrainMethods.SAVE.encodeResult(BrainSaveResult.Saved(document, listOf(BrainIssue(BrainIssueReason.EMPTY_WORKFLOW)))).toString())
    }

    @Test fun anOutputsLastPageSendsItsNullCursorAndACancelledChoiceSendsNull() {
        assertEquals("""{"text":"done","nextCursor":null}""", BrainMethods.READ_OUTPUT.encodeResult(BrainOutputPage("done", null)).toString())
        assertEquals(JsonNull, BrainMethods.CHOOSE_INPUT.encodeResult(null))
        assertEquals("{}", BrainMethods.CLOSE.encodeResult(NoResult).toString())
    }

    /** As `z.strictObject` reads them: an optional field is omitted, never null, and nothing is coerced from text. */
    @Test fun argumentsDecodeOnlyInTheContractForm() {
        val run: JsonObjectBuilder.() -> Unit = { put("runId", id(84)); put("expectedRunRevision", 4); put("requestId", id(83)) }
        assertEquals(BrainResume("local", id(80), "Ember", id(82), id(84), 4, id(83)), BrainMethods.RESUME.decodeArgs(lease(run)))
        assertNull(BrainMethods.RESUME.decodeArgs(lease { run(); put("targetRevision", JsonNull) }))
        assertNull(BrainMethods.RESUME.decodeArgs(lease { run(); put("targetRevision", "2") }))
        assertNull(BrainMethods.OPEN.decodeArgs(lease { put("supportedRuntimes", JsonNull) }))
        val declared = lease { putJsonArray("supportedRuntimes") { add("codex"); add("future-runtime") } }
        assertEquals(listOf("codex", "future-runtime"), BrainMethods.OPEN.decodeArgs(declared)?.supportedRuntimes)
        val send: JsonObjectBuilder.() -> Unit = { put("requestId", id(83)); put("text", "Hello"); putJsonArray("inputIds") {} }
        assertEquals(true, BrainMethods.SEND.decodeArgs(lease { send(); put("expectedRevision", 1); put("speak", true) })?.speak)
        assertNull(BrainMethods.SEND.decodeArgs(lease { send(); put("expectedRevision", 1); put("speak", "true") }))
        assertNull(BrainMethods.SEND.decodeArgs(lease { send(); put("expectedRevision", "1"); put("speak", true) }))
        assertNull(BrainMethods.SEND.decodeArgs(lease { send(); put("expectedRevision", 0); put("speak", true) }))
        // The lease is the four IDs of `brainLease`; a chat scope's optional `hearing` is not one of them.
        assertNull(BrainMethods.STOP.decodeArgs(lease { put("hearing", false) }))
    }
}
