package org.formulagen.wrapper.chat

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.ChatScope
import org.formulagen.wrapper.bridge.ChatVoice
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

private val scope = ChatScope(
    "local",
    "10000000-0000-4000-8000-000000000001",
    "Ember",
    "20000000-0000-4000-8000-000000000002",
)
