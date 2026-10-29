package org.formulagen.wrapper.chat

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.formulagen.wrapper.bridge.ChatEntry
import org.formulagen.wrapper.bridge.ChatFailureReport
import org.formulagen.wrapper.bridge.ChatScope
import org.formulagen.wrapper.bridge.ChatSnapshot
import org.formulagen.wrapper.bridge.ChatState
import org.formulagen.wrapper.bridge.ChatVoice
import org.formulagen.wrapper.bridge.ChatWaiting
import org.formulagen.wrapper.bridge.HostBridgeFixtures
import org.formulagen.wrapper.bridge.bridgeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalog page decodes `chat.open` with the strict host-bridge v2 snapshot.
 * These keys are the result objects in the v2 envelope fixtures the page ships with.
 */
private val HOST_BRIDGE_V2_CHAT_RESULT_KEYS = setOf("state", "messages", "notice", "voice", "failure", "waiting")

/** The v2 result the page accepts. An unknown key, including `hearing`, fails the way the page's strict object fails. */
@Serializable
private data class HostBridgeV2ChatResult(
    val state: ChatState = ChatState.READY,
    val messages: List<ChatEntry> = emptyList(),
    val notice: String? = null,
    val voice: ChatVoice = ChatVoice(),
    val failure: ChatFailureReport? = null,
    val waiting: ChatWaiting? = null,
)

class ChatOpenWireTest {
    @Test fun aCatalogOpenMatchesTheV2SnapshotThePageDecodes() = runBlocking {
        val fixtures = listOf(
            "success-chat-snapshot.json",
            "success-chat-snapshot-unanswered.json",
            "success-chat-snapshot-working.json",
        ).map { name ->
            Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue(name)).jsonObject
                .getValue("value").jsonObject.getValue("result").jsonObject
        }
        fixtures.forEach { result ->
            assertTrue(result.keys.all { it in HOST_BRIDGE_V2_CHAT_RESULT_KEYS }, result.keys.toString())
            assertNull(result["hearing"])
            val decoded = bridgeJson.decodeFromJsonElement(HostBridgeV2ChatResult.serializer(), result)
            assertEquals(result.containsKey("failure"), decoded.failure != null)
        }

        val scope = ChatScope(
            "local",
            "10000000-0000-4000-8000-000000000001",
            "Ember",
            "20000000-0000-4000-8000-000000000002",
        )
        DesktopChat(
            Files.createTempDirectory("v2-open"),
            emptyList(),
            object : ChatSpeech {
                override fun status() = ChatVoice()
                override fun speak(text: String) {}
                override fun stop() {}
                override fun close() {}
            },
        ).use { chat ->
            val opened = chat.open(scope)
            val encoded = bridgeJson.encodeToJsonElement(ChatSnapshot.serializer(), opened).jsonObject
            assertNull(encoded["hearing"], "hearing is not a host-bridge v2 field")
            assertTrue(encoded.keys.all { it in HOST_BRIDGE_V2_CHAT_RESULT_KEYS }, encoded.keys.toString())
            val decoded = bridgeJson.decodeFromJsonElement(HostBridgeV2ChatResult.serializer(), encoded)
            assertEquals(opened.copy(hearing = null).state, decoded.state)
            assertEquals(opened.messages, decoded.messages)
            assertEquals(opened.voice, decoded.voice)
            val again = chat.snapshot(scope)
            val polled = bridgeJson.encodeToJsonElement(ChatSnapshot.serializer(), again).jsonObject
            assertNull(polled["hearing"])
            val polledResult = bridgeJson.decodeFromJsonElement(HostBridgeV2ChatResult.serializer(), polled)
            assertEquals(again.messages, polledResult.messages)
        }
    }

    @Test fun hearingOnTheResultIsRejectedByTheV2SchemaUntilThePageAsks() = runBlocking {
        val scope = ChatScope(
            "local",
            "10000000-0000-4000-8000-000000000011",
            "Ember",
            "20000000-0000-4000-8000-000000000012",
            hearing = true,
        )
        DesktopChat(Files.createTempDirectory("v2-hearing"), emptyList(), object : ChatSpeech {
            override fun status() = ChatVoice()
            override fun speak(text: String) {}
            override fun stop() {}
            override fun close() {}
        }).use { chat ->
            val encoded = bridgeJson.encodeToJsonElement(ChatSnapshot.serializer(), chat.open(scope)).jsonObject
            assertTrue(encoded.containsKey("hearing"))
            val rejected = assertFails { bridgeJson.decodeFromJsonElement(HostBridgeV2ChatResult.serializer(), encoded) }
            assertTrue(rejected.message?.contains("hearing") == true || rejected is kotlinx.serialization.SerializationException)
        }
    }
}
