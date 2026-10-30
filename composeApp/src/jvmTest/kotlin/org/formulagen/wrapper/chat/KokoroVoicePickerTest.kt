package org.formulagen.wrapper.chat

import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.voice.HearingDuplex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoicePickerStateTest {
    private val voices = listOf(VoiceChoice("adam70-michael30", "Sam"))

    @Test fun theRowHidesWithoutAConversationOrKokoro() {
        assertFalse(voicePickerState(chatOpen = false, kokoro = true, voices = voices, selectedId = "adam70-michael30", owned = true).visible)
        assertFalse(voicePickerState(chatOpen = true, kokoro = false, voices = emptyList(), selectedId = "adam70-michael30", owned = true).visible)
    }

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
