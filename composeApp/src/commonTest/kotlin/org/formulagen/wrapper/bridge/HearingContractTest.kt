package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.formulagen.wrapper.voice.HearingActions
import org.formulagen.wrapper.voice.HearingStates
import org.formulagen.wrapper.voice.VoiceConversation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The host-bridge mirror of ember-hearing.v1. An older snapshot still decodes, and the method takes no path or token. */
class HearingContractTest {
    @Test fun anOlderSnapshotWithoutHearingStillDecodes() {
        val wire = """{"state":"ready","messages":[],"voice":{"state":"idle"}}"""
        val decoded = bridgeJson.decodeFromString(ChatSnapshot.serializer(), wire)
        assertNull(decoded.hearing)
        assertEquals(wire, bridgeJson.encodeToString(ChatSnapshot.serializer(), ChatSnapshot()))
        val withHearing = ChatSnapshot(hearing = ChatHearing(HearingState.HEARING, partial = "hello"))
        val encoded = bridgeJson.encodeToString(ChatSnapshot.serializer(), withHearing)
        assertEquals(withHearing, bridgeJson.decodeFromString(ChatSnapshot.serializer(), encoded))
        assertFails { ChatHearing(HearingState.HEARING, partial = "x".repeat(ChatLimits.MESSAGE + 1)) }
    }

    @Test fun hearingStateAndActionNamesMatchTheSharedContract() {
        assertEquals(HearingStates.ALL, HearingState.entries.map { bridgeJson.encodeToString(HearingState.serializer(), it).trim('"') })
        assertEquals(HearingActions.ALL, HearingAction.entries.map { bridgeJson.encodeToString(HearingAction.serializer(), it).trim('"') })
        assertEquals(
            "Ember hears you on this Mac to turn what you say into a chat message. The recording is not saved.",
            VoiceConversation.PERMISSION_COPY,
        )
    }

    @Test fun chatHearingRejectsAPathATokenAndAnUnknownField() {
        assertNotNull(ChatMethods.HEARING.decodeArgs(request()))
        assertNotNull(ChatMethods.HEARING.decodeArgs(request(effort = "low")))
        assertNull(ChatMethods.HEARING.decodeArgs(request(extra = "path" to "/tmp/silero_vad.onnx")))
        assertNull(ChatMethods.HEARING.decodeArgs(request(extra = "token" to "secret-token")))
        assertNull(ChatMethods.HEARING.decodeArgs(request(extra = "modelFile" to "encoder.onnx")))
        assertNull(ChatMethods.HEARING.decodeArgs(request(effort = "/Users/tim/models/kokoro")))
        assertNull(ChatMethods.HEARING.decodeArgs(request(effort = "sk-token")))
        assertNull(ChatMethods.HEARING.decodeArgs(request(modelId = "silero_vad.onnx")))
        assertNull(ChatMethods.HEARING.decodeArgs(request(modelId = "models/encoder.onnx")))
        assertNull(ChatMethods.HEARING.decodeArgs(request(extra = "pcm" to "AAAA")))
    }

    private fun request(effort: String? = null, modelId: String = "runtime-default", extra: Pair<String, String>? = null) = buildJsonObject {
        put("profile", "local")
        put("companionId", "10000000-0000-4000-8000-000000000001")
        put("name", "Ember")
        put("sessionId", "20000000-0000-4000-8000-000000000002")
        put("action", "press")
        putJsonObject("member") {
            put("runtimeId", "codex")
            put("modelId", modelId)
        }
        if (effort != null) put("effort", effort)
        if (extra != null) put(extra.first, extra.second)
    }
}
