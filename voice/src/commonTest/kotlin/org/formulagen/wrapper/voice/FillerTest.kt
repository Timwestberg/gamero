package org.formulagen.wrapper.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FillerTest {
    @Test fun settingsDefaultOnAndTurnOffForZeroOffOrFalse() {
        assertEquals(FillerSettings(true, 450), readFillerSettings(emptyMap()))
        assertEquals(false, readFillerSettings(mapOf("HOVEL_VOICE_FILLERS" to "0")).enabled)
        assertEquals(false, readFillerSettings(mapOf("HOVEL_VOICE_FILLERS" to "off")).enabled)
        assertEquals(false, readFillerSettings(mapOf("HOVEL_VOICE_FILLERS" to "false")).enabled)
        assertEquals(true, readFillerSettings(mapOf("HOVEL_VOICE_FILLERS" to "1")).enabled)
        assertEquals(700, readFillerSettings(mapOf("HOVEL_VOICE_FILLER_MS" to "700")).thresholdMs)
        assertEquals(0, readFillerSettings(mapOf("HOVEL_VOICE_FILLER_MS" to "-5")).thresholdMs)
        assertEquals(10_000, readFillerSettings(mapOf("HOVEL_VOICE_FILLER_MS" to "99999")).thresholdMs)
    }

    @Test fun rotationDoesNotRepeatTheSameLineBackToBack() {
        val rotation = FillerRotation()
        val played = List(6) { rotation.next() }
        assertEquals(listOf("Hmm,", "Let me think,", "Okay, so", "Hmm,", "Let me think,", "Okay, so"), played)
        for (i in 1 until played.size) assertTrue(played[i] != played[i - 1])
    }

    @Test fun aCuePlaysOnceAfterTheThresholdAndNotWhenTheReplyHasStarted() {
        val turn = FillerTurn(FillerSettings(enabled = true, thresholdMs = 450))
        val start = 1_000_000_000L
        turn.begin()
        turn.arm(start)
        assertNull(turn.poll(start + 449L * 1_000_000))
        val cue = turn.poll(start + 450L * 1_000_000)
        assertEquals("Hmm,", cue?.line)
        assertEquals(450L, cue?.waitedMs)
        assertTrue(turn.playing)
        assertNull(turn.poll(start + 2_000L * 1_000_000))
    }

    @Test fun aReplyThatArrivesFirstIsNotCoveredByAFiller() {
        val turn = FillerTurn(FillerSettings(thresholdMs = 0))
        turn.begin()
        turn.replyArrived()
        turn.arm(0L)
        assertNull(turn.poll(5_000_000_000L))
    }

    @Test fun disabledAndCancelledTurnsStaySilent() {
        val off = FillerTurn(FillerSettings(enabled = false, thresholdMs = 0))
        off.begin(); off.arm(0L)
        assertNull(off.poll(1_000_000_000L))
        val cancelled = FillerTurn(FillerSettings(thresholdMs = 0))
        cancelled.begin(); cancelled.arm(0L); cancelled.cancel()
        assertNull(cancelled.poll(1_000_000_000L))
    }

    @Test fun theNextTurnUsesTheNextLine() {
        val rotation = FillerRotation()
        val settings = FillerSettings(thresholdMs = 0)
        val first = FillerTurn(settings, rotation)
        first.begin(); first.arm(0L)
        assertEquals("Hmm,", first.poll(0L)?.line)
        val second = FillerTurn(settings, rotation)
        second.begin(); second.arm(0L)
        assertEquals("Let me think,", second.poll(0L)?.line)
    }
}
