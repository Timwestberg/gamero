package org.formulagen.wrapper.chat

import org.formulagen.wrapper.voice.HearingDuplex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoicePickerStateTest {
    private val voices = listOf(VoiceChoice("adam70-michael30", "Sam"))

    @Test fun theRowHidesWithoutAConversationOrKokoro() {
        assertFalse(voicePickerState(chatOpen = false, kokoro = true, voices = voices, selectedId = "adam70-michael30").visible)
        assertFalse(voicePickerState(chatOpen = true, kokoro = false, voices = emptyList(), selectedId = "adam70-michael30").visible)
    }

    @Test fun theRowShowsTalkUntilThePageOwnsItAndSaysTheDuplexMode() {
        val half = HearingDuplex.HALF
        val row = voicePickerState(true, true, voices, "adam70-michael30", duplexMode = half, duplexReason = "aec-unavailable")
        assertTrue(row.visible && row.showTalk)
        assertEquals("Half duplex · aec-unavailable", row.duplexLabel)
        val paged = voicePickerState(true, true, voices, "adam70-michael30", pageOwnsTalk = true, duplexMode = HearingDuplex.FULL)
        assertTrue(paged.visible)
        assertFalse(paged.showTalk, "the page owns Talk")
        assertEquals("Full duplex", paged.duplexLabel)
    }
}
