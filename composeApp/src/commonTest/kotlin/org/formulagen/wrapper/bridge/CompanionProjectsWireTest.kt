package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The project folder and conversation voice results, as native code encodes them, equal
 * event-contracts' envelope fixtures, and the companion limits native code mirrors equal limits.json.
 */
class CompanionProjectsWireTest {
    private fun result(name: String): JsonElement =
        Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue(name)).jsonObject.getValue("value").jsonObject.getValue("result")

    @Test fun projectFolderStatusRoundTripsTheFixtures() {
        for (name in listOf("success-project-folder-status.json", "success-project-folder-status-talk-only.json")) {
            val decoded = bridgeJson.decodeFromJsonElement(ProjectFoldersStatus.serializer(), result(name))
            assertEquals(result(name), bridgeJson.encodeToJsonElement(ProjectFoldersStatus.serializer(), decoded), name)
        }
    }

    @Test fun conversationVoiceStatusWritesANullConversation() {
        for (name in listOf(
            "success-conversation-voice-idle.json",
            "success-conversation-voice-dictation.json",
            "success-conversation-voice-talk.json",
        )) {
            val decoded = bridgeJson.decodeFromJsonElement(ConversationVoiceStatus.serializer(), result(name))
            assertEquals(result(name), bridgeJson.encodeToJsonElement(ConversationVoiceStatus.serializer(), decoded), name)
        }
        assertEquals(result("success-conversation-voice-idle.json"),
            bridgeJson.encodeToJsonElement(ConversationVoiceStatus.serializer(), ConversationVoiceStatus(null)))
    }

    @Test fun companionLimitsMirrorTheContract() {
        val limits = Json.parseToJsonElement(HostBridgeFixtures.LIMITS).jsonObject.getValue("COMPANION_LIMITS").jsonObject
        assertEquals(limits.getValue("contextFolders").jsonPrimitive.int, CompanionLimits.CONTEXT_FOLDERS)
        assertEquals(limits.getValue("folderName").jsonPrimitive.int, CompanionLimits.FOLDER_NAME)
    }
}
