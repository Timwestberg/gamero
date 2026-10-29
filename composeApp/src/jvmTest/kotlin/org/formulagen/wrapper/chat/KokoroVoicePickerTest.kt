package org.formulagen.wrapper.chat

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.ChatScope
import org.formulagen.wrapper.bridge.ChatVoice
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.voice.HearingDuplex
import org.formulagen.wrapper.voice.kokoroVoices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KokoroVoicePickerTest {
    @Test fun theRowListsTheRecipeAndHidesWhenChatIsClosedOrNotKokoro() = runBlocking {
        val root = Files.createTempDirectory("voice-row")
        val model = VoicePickerModel()
        val said = java.util.concurrent.CopyOnWriteArrayList<String>()
        var selected = "adam70-michael30"
        val speech = object : ChatSpeech {
            override fun status() = ChatVoice()
            override fun speak(text: String) { said += text }
            override fun stop() {}
            override fun close() {}
            override fun kokoroActive() = true
            override fun voiceChoices() = kokoroVoices().map { VoiceChoice(it.id, it.name) }
            override fun currentVoiceId() = selected
            override fun previewVoice(id: String) {
                selected = id
                speak(voicePreviewLine(kokoroVoices().first { it.id == id }.name))
            }
        }
        try {
            assertFalse(voicePickerState(chatOpen = false, kokoro = true, voices = speech.voiceChoices(), selectedId = selected).visible)
            assertFalse(voicePickerState(chatOpen = true, kokoro = false, voices = emptyList(), selectedId = selected).visible)
            DesktopChat(root, emptyList(), speech, voices = model).use { chat ->
                assertFalse(model.state.value.visible)
                chat.open(scope)
                val shown = model.state.value
                assertTrue(shown.visible)
                assertEquals(listOf("Sam", "Jamie", "Riley", "Harper"), shown.voices.map { it.name })
                assertEquals("adam70-michael30", shown.selectedId)
                chat.chooseSpokenVoice("heart60-adam40")
                assertEquals("heart60-adam40", model.state.value.selectedId)
                assertEquals(listOf("Hi, I'm Harper."), said)
                chat.closeSession(scope)
                assertFalse(model.state.value.visible)
            }
            val pocket = object : ChatSpeech {
                override fun status() = ChatVoice()
                override fun speak(text: String) {}
                override fun stop() {}
                override fun close() {}
                override fun kokoroActive() = false
            }
            DesktopChat(root, emptyList(), pocket, voices = model).use { chat ->
                chat.open(scope)
                assertFalse(model.state.value.visible)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

class VoicePickerStateTest {
    private val voices = listOf(VoiceChoice("adam70-michael30", "Sam"))

    @Test fun theCatalogOwnsTeamChatsRowAndAConversationOwnsItsOwnInEveryProduct() {
        val half = HearingDuplex.HALF
        val catalog = voicePickerState(true, true, voices, "adam70-michael30", duplexMode = half, duplexReason = "aec-unavailable")
        assertTrue(catalog.visible && catalog.showTalk)
        assertEquals("Half duplex · aec-unavailable", catalog.duplexLabel)
        val paged = voicePickerState(true, true, voices, "adam70-michael30", pageOwnsTalk = true, duplexMode = HearingDuplex.FULL)
        assertTrue(paged.visible)
        assertFalse(paged.showTalk, "the page owns Talk")
        assertEquals("Full duplex", paged.duplexLabel)
        assertFalse(voicePickerState(true, true, voices, "adam70-michael30", product = HOVEL_PRODUCT).visible, "team chat in Hovel")
        val conversation = voicePickerState(true, true, voices, "adam70-michael30", product = HOVEL_PRODUCT, owned = true)
        assertTrue(conversation.visible && conversation.showTalk, "a conversation's row shows in Hovel")
    }
}

private val scope = ChatScope(
    "local",
    "10000000-0000-4000-8000-000000000001",
    "Ember",
    "20000000-0000-4000-8000-000000000002",
)
