package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.formulagen.wrapper.conversation.DictationState

/**
 * The project folder, conversation voice and contract results, as native code encodes them, equal
 * event-contracts' envelope fixtures; the folder rules refuse what the contract refuses; and the
 * companion limits native code mirrors equal limits.json.
 */
class CompanionProjectsWireTest {
    private fun result(name: String): JsonElement =
        Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue(name)).jsonObject.getValue("value").jsonObject.getValue("result")

    private val working = ProjectFolderStatus(true, "70a0000b-0000-4000-8000-000000000002", "hovel-notes", emptyList(), FolderRole.WORKING)
    private fun context(index: Int, name: String = "design-docs-$index") =
        ProjectFolderStatus(true, "70a0000b-0000-4000-8000-00000000001$index", name, listOf(RuntimeId.CODEX), FolderRole.CONTEXT)

    @Test fun projectFolderStatusRoundTripsTheFixtures() {
        for (name in listOf("success-project-folder-status.json", "success-project-folder-status-talk-only.json")) {
            val decoded = bridgeJson.decodeFromJsonElement(ProjectFoldersStatus.serializer(), result(name))
            assertEquals(result(name), bridgeJson.encodeToJsonElement(ProjectFoldersStatus.serializer(), decoded), name)
        }
    }

    @Test fun folderStatusRefusesWhatTheContractRefuses() {
        ProjectFoldersStatus(listOf(working, context(1), context(2), context(3), context(4)))
        ProjectFoldersStatus(listOf(context(1)))
        assertFails { ProjectFoldersStatus(listOf(working, working.copy(id = "70a0000b-0000-4000-8000-000000000009", name = "other"))) }
        assertFails { ProjectFoldersStatus(listOf(working, context(1), context(2), context(3), context(4), context(5))) }
        assertFails { ProjectFoldersStatus(listOf(context(1), context(2).copy(id = context(1).id))) }
        assertFails { ProjectFoldersStatus(listOf(working.copy(name = "Docs"), context(1, name = "docs"))) }
        assertFails { working.copy(readableBy = listOf(RuntimeId.CODEX)) }
        assertFails { context(1).copy(readableBy = listOf(RuntimeId.CODEX, RuntimeId.CODEX)) }
        assertFails { working.copy(id = working.id.uppercase()) }
        for (name in listOf("", " notes", "notes ", "Users/person", "~notes", "no\ntes", "x".repeat(CompanionLimits.FOLDER_NAME + 1))) {
            assertFails(name) { working.copy(name = name) }
        }
        val path = Json.parseToJsonElement("""{"folders":[{"exists":true,"id":"${working.id}","name":"hovel-notes","path":"/Users/p","readableBy":[],"role":"working"}]}""")
        assertFails { bridgeJson.decodeFromJsonElement(ProjectFoldersStatus.serializer(), path) }
    }

    @Test fun displayNamesAreValidCutAndToldApart() {
        assertEquals("hovel-notes", ProjectFolderNames.display("hovel-notes"))
        assertEquals("notes", ProjectFolderNames.display("  ~~ ~notes\n"))
        assertEquals("a-b c", ProjectFolderNames.display("a/b\tc"))
        assertEquals("folder", ProjectFolderNames.display(" ~ "))
        assertEquals("docs (2)", ProjectFolderNames.display("docs", listOf("Docs")))
        assertEquals("docs (3)", ProjectFolderNames.display("DOCS", listOf("docs", "docs (2)")).lowercase())
        val long = ProjectFolderNames.display("x".repeat(200))
        assertEquals(CompanionLimits.FOLDER_NAME, long.length)
        val second = ProjectFolderNames.display("x".repeat(200), listOf(long))
        assertEquals(CompanionLimits.FOLDER_NAME, second.length)
        assertTrue(second.endsWith(" (2)"))
        val emoji = ProjectFolderNames.display("a".repeat(CompanionLimits.FOLDER_NAME - 1) + "😀")
        assertFalse(emoji.last().isHighSurrogate(), "a cut never splits a surrogate pair")
        for (name in listOf("hovel-notes", "  ~~ ~notes\n", "a/b\tc", " ~ ", "x".repeat(200), "a".repeat(79) + "😀")) {
            assertTrue(ProjectFolderNames.isValid(ProjectFolderNames.display(name)), name)
        }
    }

    @Test fun conversationVoiceStatusWritesANullConversationAndTakesMessageLongText() {
        for (name in listOf(
            "success-conversation-voice-idle.json",
            "success-conversation-voice-dictation.json",
            "success-conversation-voice-long-dictation.json",
            "success-conversation-voice-talk.json",
        )) {
            val decoded = bridgeJson.decodeFromJsonElement(ConversationVoiceStatus.serializer(), result(name))
            assertEquals(result(name), bridgeJson.encodeToJsonElement(ConversationVoiceStatus.serializer(), decoded), name)
        }
        assertEquals(result("success-conversation-voice-idle.json"),
            bridgeJson.encodeToJsonElement(ConversationVoiceStatus.serializer(), ConversationVoiceStatus(null)))
        assertFails { ConversationDictation(DictationState.DONE, text = "x".repeat(CompanionLimits.MESSAGE + 1)) }
    }

    @Test fun theContractHandshakeTakesAWholeNumber() {
        assertEquals(result("success-host-contract.json"), bridgeJson.encodeToJsonElement(HostContract.serializer(), HostContract(1)))
        fun args(json: String) = bridgeJson.decodeFromJsonElement(HostContractArgs.serializer(), Json.parseToJsonElement(json) as JsonObject)
        assertEquals(1, args("""{"contract":1}""").value())
        assertNull(args("""{"contract":"1"}""").value())
        assertNull(args("""{"contract":0}""").value())
        assertNull(args("""{"contract":1.5}""").value())
    }

    @Test fun companionLimitsMirrorTheContract() {
        val limits = Json.parseToJsonElement(HostBridgeFixtures.LIMITS).jsonObject.getValue("COMPANION_LIMITS").jsonObject
        assertEquals(limits.getValue("contextFolders").jsonPrimitive.int, CompanionLimits.CONTEXT_FOLDERS)
        assertEquals(limits.getValue("folderName").jsonPrimitive.int, CompanionLimits.FOLDER_NAME)
        assertEquals(limits.getValue("message").jsonPrimitive.int, CompanionLimits.MESSAGE)
    }
}
