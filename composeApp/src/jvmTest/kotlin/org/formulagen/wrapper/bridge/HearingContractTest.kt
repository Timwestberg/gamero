package org.formulagen.wrapper.bridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertTrue

/** The host's mirror of ember-hearing.v1 and `chat.hearing`, checked against the generated contract fixtures. */
class HearingContractTest {
    private val contract = Json.parseToJsonElement(EmberHearingFixture.JSON).jsonObject
    private fun contractNames(key: String) = contract.getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun <T> wire(serializer: KSerializer<T>, value: T) = bridgeJson.encodeToString(serializer, value).trim('"')

    @Test fun hearingStatesAreTheContractStates() {
        assertEquals(contractNames("states"), HearingState.entries.map { wire(HearingState.serializer(), it) })
        assertEquals(contractNames("states"), HearingStates.ALL)
    }

    @Test fun hearingActionsAreTheContractActions() {
        assertEquals(contractNames("actions"), HearingAction.entries.map { wire(HearingAction.serializer(), it) })
        assertEquals(contractNames("actions"), HearingActions.ALL)
    }

    @Test fun thePermissionSentenceIsTheContractCopy() {
        assertEquals(contract.getValue("permissionCopy").jsonPrimitive.content, VoiceConversation.PERMISSION_COPY)
    }

    @Test fun aSnapshotWithoutHearingIsTheHostBridgeV2Wire() {
        val wire = """{"state":"ready","messages":[],"voice":{"state":"idle"}}"""
        assertEquals(wire, bridgeJson.encodeToString(ChatSnapshot.serializer(), ChatSnapshot()))
        assertNull(bridgeJson.decodeFromString(ChatSnapshot.serializer(), wire).hearing)
    }

    @Test fun aSnapshotWithHearingRoundTrips() {
        val snapshot = ChatSnapshot(hearing = ChatHearing(HearingState.HEARING, partial = "hello"))
        assertEquals(snapshot, bridgeJson.decodeFromString(ChatSnapshot.serializer(), bridgeJson.encodeToString(ChatSnapshot.serializer(), snapshot)))
    }

    @Test fun aPartialTranscriptLongerThanAMessageIsRefused() {
        assertFails { ChatHearing(HearingState.HEARING, partial = "x".repeat(ChatLimits.MESSAGE + 1)) }
    }

    @Test fun chatHearingTakesTheLeaseAnActionAndAMember() {
        assertNotNull(ChatMethods.HEARING.decodeArgs(hearing()))
    }

    @Test fun chatHearingTakesOnlyTheEffortsChatSendTakes() {
        for (effort in ModelEffort.entries) assertNotNull(ChatMethods.HEARING.decodeArgs(hearing(effort = effort.value)), effort.value)
        for (effort in listOf("fast", "/Users/person/models/kokoro", "sk-token")) assertNull(ChatMethods.HEARING.decodeArgs(hearing(effort = effort)), effort)
    }

    @Test fun chatHearingRejectsAnUnknownArgument() {
        assertNull(ChatMethods.HEARING.decodeArgs(hearing(extra = "modelFile" to "silero_vad.onnx")))
    }

    /** The heard turn is a `chat.send`, so a member is valid for one exactly when it is valid for the other. */
    @Test fun chatHearingTakesExactlyTheMembersChatSendTakes() {
        val members = listOf(
            "codex" to "runtime-default", "claude-code" to "sonnet", "codex" to "org/model", "codex" to "silero_vad.onnx",
            "claude-code" to "opus[1m]", "codex" to "-p", "codex" to "--model=encoder.onnx", "claude-code" to "Sonnet",
            "codex" to "", "codex" to "a b", "gemini-cli" to "gemini",
        )
        val accepted = members.map { (runtime, model) ->
            val send = ChatMethods.SEND.decodeArgs(send(runtime, model)) != null
            assertEquals(send, ChatMethods.HEARING.decodeArgs(hearing(runtime, model)) != null, "$runtime $model")
            send
        }
        assertTrue(true in accepted && false in accepted, "the table has members both methods take and refuse")
    }

    /** The lease and [member] as JSON, then [more]. */
    private fun args(runtime: String, model: String, more: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("profile", "local")
        put("companionId", "10000000-0000-4000-8000-000000000001")
        put("name", "Ember")
        put("sessionId", "20000000-0000-4000-8000-000000000002")
        putJsonObject("member") {
            put("runtimeId", runtime)
            put("modelId", model)
        }
        more()
    }

    private fun hearing(runtime: String = "codex", model: String = "runtime-default", effort: String? = null,
        extra: Pair<String, String>? = null) = args(runtime, model) {
        put("action", "press")
        if (effort != null) put("effort", effort)
        if (extra != null) put(extra.first, extra.second)
    }

    private fun send(runtime: String, model: String) = args(runtime, model) {
        put("requestId", "30000000-0000-4000-8000-000000000003")
        put("text", "Hello")
        put("speak", true)
    }
}
