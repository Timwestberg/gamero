package org.formulagen.wrapper.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

/** Typed chat states keep the exact wire names of event-contracts `host-bridge/chat.ts`. */
class ChatWireTest {
    @Test fun snapshotsEncodeTheContractNames() {
        val snapshot = ChatSnapshot(ChatState.RESPONDING,
            listOf(ChatMessage("10000000-0000-4000-8000-000000000001", ChatRole.USER, "Hi"),
                ChatMessage("10000000-0000-4000-8000-000000000002", ChatRole.ASSISTANT, "")),
            voice = ChatVoice(VoiceState.UNAVAILABLE, "Local voice is not installed."))
        val wire = """{"state":"responding","messages":[{"id":"10000000-0000-4000-8000-000000000001","role":"user","text":"Hi"},""" +
            """{"id":"10000000-0000-4000-8000-000000000002","role":"assistant","text":""}],"voice":{"state":"unavailable","notice":"Local voice is not installed."}}"""
        assertEquals(wire, bridgeJson.encodeToString(ChatSnapshot.serializer(), snapshot))
        assertEquals(snapshot, bridgeJson.decodeFromString(ChatSnapshot.serializer(), wire))
        assertEquals("""{"state":"ready","messages":[],"voice":{"state":"idle"}}""", bridgeJson.encodeToString(ChatSnapshot.serializer(), ChatSnapshot()))
        assertEquals(listOf("checking", "ready", "responding", "cancelled", "error", "disconnected"),
            ChatState.entries.map { bridgeJson.encodeToString(ChatState.serializer(), it).trim('"') })
        assertEquals(listOf("idle", "preparing", "speaking", "unavailable", "error"),
            VoiceState.entries.map { bridgeJson.encodeToString(VoiceState.serializer(), it).trim('"') })
    }
}
