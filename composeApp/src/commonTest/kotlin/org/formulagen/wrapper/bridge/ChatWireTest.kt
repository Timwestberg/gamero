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
        assertEquals(listOf("checking", "ready", "responding", "handing-over", "cancelled", "error", "disconnected"),
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

    @Test fun providerStatusesNameTheProviderOfTheirRuntime() {
        assertEquals("""{"runtimeId":"claude-code","state":"connected","plan":"Max","providerId":"anthropic"}""",
            bridgeJson.encodeToString(ProviderStatus.serializer(), ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.CONNECTED, plan = "Max")))
        assertEquals(RuntimeProviderId.OPENAI, ProviderStatus(RuntimeId.CODEX, ProviderState.DISCONNECTED).providerId)
        kotlin.test.assertFails { ProviderStatus(RuntimeId.CODEX, ProviderState.DISCONNECTED, providerId = RuntimeProviderId.ANTHROPIC) }
    }
}
