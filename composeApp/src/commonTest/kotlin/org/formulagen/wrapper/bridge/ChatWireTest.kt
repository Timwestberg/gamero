package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Typed chat states keep the exact wire names of event-contracts `host-bridge/chat.ts`. */
class ChatWireTest {
    private val codex = TeamMember(RuntimeId.CODEX, "runtime-default")
    private val sonnet = TeamMember(RuntimeId.CLAUDE_CODE, "sonnet")

    @Test fun snapshotsEncodeTheContractNames() {
        val snapshot = ChatSnapshot(ChatState.RESPONDING,
            listOf(ChatEntry.User("10000000-0000-4000-8000-000000000001", "Hi"),
                ChatEntry.Assistant("10000000-0000-4000-8000-000000000002", codex, "")),
            voice = ChatVoice(VoiceState.UNAVAILABLE, "Local voice is not installed."))
        val wire = """{"state":"responding","messages":[{"role":"user","id":"10000000-0000-4000-8000-000000000001","text":"Hi"},""" +
            """{"role":"assistant","id":"10000000-0000-4000-8000-000000000002","author":{"runtimeId":"codex","modelId":"runtime-default"},"text":""}],""" +
            """"voice":{"state":"unavailable","notice":"Local voice is not installed."}}"""
        assertEquals(wire, bridgeJson.encodeToString(ChatSnapshot.serializer(), snapshot))
        assertEquals(snapshot, bridgeJson.decodeFromString(ChatSnapshot.serializer(), wire))
        assertEquals("""{"state":"ready","messages":[],"voice":{"state":"idle"}}""", bridgeJson.encodeToString(ChatSnapshot.serializer(), ChatSnapshot()))
        assertEquals(listOf("checking", "ready", "responding", "awaiting-you", "handing-over", "cancelled", "error", "disconnected"),
            ChatState.entries.map { bridgeJson.encodeToString(ChatState.serializer(), it).trim('"') })
        assertEquals(listOf("idle", "preparing", "speaking", "unavailable", "error"),
            VoiceState.entries.map { bridgeJson.encodeToString(VoiceState.serializer(), it).trim('"') })
    }

    /** Every entry role, and a handover from no one, which the contract requires as `"from": null`. */
    @Test fun theSnapshotEnvelopeMatchesTheContractFixture() {
        val fixture = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue("success-chat-snapshot.json")).jsonObject.getValue("value")
        val snapshot = ChatSnapshot(ChatState.READY, listOf(
            ChatEntry.Handover("60000000-0000-4000-8000-000000000001", "2026-10-27T12:00:00Z", null, codex, HandoverMethod.FRESH, HandoverReason.MANUAL),
            ChatEntry.User("60000000-0000-4000-8000-000000000002", "Hello"),
            ChatEntry.Assistant("60000000-0000-4000-8000-000000000003", codex, "Hi! What are we doing today?"),
            ChatEntry.Handover("60000000-0000-4000-8000-000000000004", "2026-10-27T12:05:00.250Z", codex, sonnet, HandoverMethod.SUMMARY,
                HandoverReason.MANUAL, "The person said hello, and Ember asked about their plans for today."),
            ChatEntry.Assistant("60000000-0000-4000-8000-000000000005", sonnet, "I'm caught up. What would you like to do first?"),
        ))
        assertEquals(fixture, Json.parseToJsonElement(BridgeEnvelopes.success(bridgeJson.encodeToJsonElement(ChatSnapshot.serializer(), snapshot))))
        assertEquals(snapshot, bridgeJson.decodeFromJsonElement(ChatSnapshot.serializer(), fixture.jsonObject.getValue("result")))
    }

    /** An unanswered message, the failure behind it, and every handover method and reason. */
    @Test fun theUnansweredSnapshotEnvelopeMatchesTheContractFixture() {
        val fixture = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue("success-chat-snapshot-unanswered.json")).jsonObject.getValue("value")
        val sol = TeamMember(RuntimeId.CODEX, "gpt-6.1-sol"); val opus = TeamMember(RuntimeId.CLAUDE_CODE, "opus")
        fun id(n: Int) = "60000000-0000-4000-8000-0000000000" + n.toString().padStart(2, '0')
        val snapshot = ChatSnapshot(ChatState.ERROR, listOf(
            ChatEntry.Handover(id(1), "2026-10-27T12:00:00Z", null, codex, HandoverMethod.FRESH, HandoverReason.HOST),
            ChatEntry.User(id(2), "Hello"),
            ChatEntry.Assistant(id(3), codex, "Hi! What are we doing today?"),
            ChatEntry.Handover(id(4), "2026-10-27T12:01:00Z", codex, sonnet, HandoverMethod.SUMMARY, HandoverReason.MANUAL,
                "The person said hello, and Ember asked about their plans for today."),
            ChatEntry.Handover(id(5), "2026-10-27T12:02:00Z", sonnet, codex, HandoverMethod.UP_TO_DATE, HandoverReason.MANUAL),
            ChatEntry.Handover(id(6), "2026-10-27T12:03:00Z", codex, sol, HandoverMethod.SAME_CONVERSATION, HandoverReason.HOST),
            ChatEntry.User(id(7), "Tell me a story."),
            ChatEntry.Assistant(id(8), sol, "Once, a small robot found a lantern in the hovel."),
            ChatEntry.Handover(id(9), "2026-10-27T12:04:00Z", sol, opus, HandoverMethod.RECENT_MESSAGES, HandoverReason.ENGINE,
                engine = HandoverEngine("hovel-rules", "1.0.0")),
            ChatEntry.User(id(10), "And then?", UserStatus.UNANSWERED),
        ), notice = "You've reached your Claude usage limit. It resets at 3:00 PM.",
            failure = ChatFailureReport(ChatFailureKind.USAGE_LIMIT, opus, "2026-10-27T15:00:00Z"))
        assertEquals(fixture, Json.parseToJsonElement(BridgeEnvelopes.success(bridgeJson.encodeToJsonElement(ChatSnapshot.serializer(), snapshot))))
        assertEquals(snapshot, bridgeJson.decodeFromJsonElement(ChatSnapshot.serializer(), fixture.jsonObject.getValue("result")))
    }

    /** A working reply's activity, a work log in a handover, and the awaiting-you state with what the native dialog asks. */
    @Test fun theWorkingSnapshotEnvelopeMatchesTheContractFixture() {
        val fixture = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue("success-chat-snapshot-working.json")).jsonObject.getValue("value")
        fun id(n: Int) = "60000000-0000-4000-8000-00000000000$n"
        val steps = listOf(
            ActivityStep(ActivityKind.SEARCH, "parseDate", ActivityStatus.DONE, "2 files"),
            ActivityStep(ActivityKind.READ, "src/date.ts", ActivityStatus.DONE),
            ActivityStep(ActivityKind.COMMAND, "npm test", ActivityStatus.FAILED, "1 failing: parses an ISO date with an offset"),
            ActivityStep(ActivityKind.EDIT, "src/date.ts", ActivityStatus.DONE, "+3 -1"),
            ActivityStep(ActivityKind.COMMAND, "npm test", ActivityStatus.DONE, "12 passing"),
            ActivityStep(ActivityKind.COMMAND, "git push", ActivityStatus.DENIED),
        )
        val snapshot = ChatSnapshot(ChatState.AWAITING_YOU, listOf(
            ChatEntry.Handover(id(1), "2026-10-28T09:00:00Z", null, sonnet, HandoverMethod.FRESH, HandoverReason.HOST),
            ChatEntry.User(id(2), "Fix the failing date test, please."),
            ChatEntry.Assistant(id(3), sonnet, "The parser dropped the offset. I fixed it and the tests pass. I didn't push, since you said no.",
                Activity(steps, truncated = false)),
            ChatEntry.Handover(id(4), "2026-10-28T09:10:00Z", sonnet, codex, HandoverMethod.SUMMARY, HandoverReason.MANUAL,
                "The person asked for the failing date test to be fixed. Ember fixed the parser.",
                workLog = WorkLog(changed = listOf("src/date.ts"), commands = steps.filter { it.kind == ActivityKind.COMMAND }, denied = listOf("git push"),
                    stoppedAt = "The parser dropped the offset. I fixed it and the tests pass.")),
            ChatEntry.User(id(5), "Add a test for leap years."),
        ), waiting = ChatWaiting(WaitingKind.APPROVAL, "Codex wants to edit tests/date.test.ts in hovel-notes."))
        assertEquals(fixture, Json.parseToJsonElement(BridgeEnvelopes.success(bridgeJson.encodeToJsonElement(ChatSnapshot.serializer(), snapshot))))
        assertEquals(snapshot, bridgeJson.decodeFromJsonElement(ChatSnapshot.serializer(), fixture.jsonObject.getValue("result")))
    }

    /** A page that opted in to hearing reads the host microphone's state and the live partial transcript. */
    @Test fun theHearingSnapshotEnvelopeMatchesTheContractFixture() {
        val fixture = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue("success-chat-hearing.json")).jsonObject.getValue("value")
        val snapshot = ChatSnapshot(ChatState.READY, listOf(
            ChatEntry.User("60000000-0000-4000-8000-000000000001", "Hello"),
            ChatEntry.Assistant("60000000-0000-4000-8000-000000000002", codex, "Hi! What are we doing today?"),
        ), hearing = ChatHearing(HearingState.HEARING, partial = "what time is it"))
        assertEquals(fixture, Json.parseToJsonElement(BridgeEnvelopes.success(bridgeJson.encodeToJsonElement(ChatSnapshot.serializer(), snapshot))))
        assertEquals(snapshot, bridgeJson.decodeFromJsonElement(ChatSnapshot.serializer(), fixture.jsonObject.getValue("result")))
    }

    /** A folder's name and whether it exists, and `"folder": null` (never a missing key) when none is chosen. */
    @Test fun workspaceStatusesMatchTheContract() {
        val fixture = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue("success-workspace-status.json")).jsonObject.getValue("value")
        val chosen = WorkspaceStatus(WorkspaceFolder(exists = true, name = "hovel-notes"))
        assertEquals(fixture, Json.parseToJsonElement(BridgeEnvelopes.success(bridgeJson.encodeToJsonElement(WorkspaceStatus.serializer(), chosen))))
        assertEquals(chosen, bridgeJson.decodeFromJsonElement(WorkspaceStatus.serializer(), fixture.jsonObject.getValue("result")))
        assertEquals("""{"folder":null}""", bridgeJson.encodeToString(WorkspaceStatus.serializer(), WorkspaceStatus(null)))
        assertEquals(WorkspaceStatus(null), bridgeJson.decodeFromString(WorkspaceStatus.serializer(), """{"folder":null}"""))
        assertEquals(listOf("talk", "work"), MemberMode.entries.map { bridgeJson.encodeToString(MemberMode.serializer(), it).trim('"') })
    }

    @Test fun theProviderListNamesTheRuntimesThatMayWork() {
        val fixture = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue("success-providers-list.json")).jsonObject.getValue("value")
        val list = ProviderList(listOf(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE), working = listOf(RuntimeId.CLAUDE_CODE))
        assertEquals(fixture, Json.parseToJsonElement(BridgeEnvelopes.success(bridgeJson.encodeToJsonElement(ProviderList.serializer(), list))))
        kotlin.test.assertFails { ProviderList(listOf(RuntimeId.CODEX), working = listOf(RuntimeId.CLAUDE_CODE)) }
    }

    @Test fun providerStatusesNameTheProviderOfTheirRuntime() {
        assertEquals("""{"runtimeId":"claude-code","state":"connected","plan":"Max","providerId":"anthropic"}""",
            bridgeJson.encodeToString(ProviderStatus.serializer(), ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.CONNECTED, plan = "Max")))
        assertEquals(RuntimeProviderId.OPENAI, ProviderStatus(RuntimeId.CODEX, ProviderState.DISCONNECTED).providerId)
        kotlin.test.assertFails { ProviderStatus(RuntimeId.CODEX, ProviderState.DISCONNECTED, providerId = RuntimeProviderId.ANTHROPIC) }
        val signedOut = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue("success-providers-status-claude-signed-out.json")).jsonObject.getValue("value")
        assertEquals(signedOut, Json.parseToJsonElement(BridgeEnvelopes.success(bridgeJson.encodeToJsonElement(ProviderStatus.serializer(),
            ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.DISCONNECTED,
                message = "Sign in to Claude Code with your Claude subscription: run `claude auth login` in Terminal, then choose Check again.",
                detail = ProviderDetail.SIGNED_OUT)))))
    }
}
