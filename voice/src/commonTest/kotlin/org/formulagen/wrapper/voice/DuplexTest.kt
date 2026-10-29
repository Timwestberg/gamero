package org.formulagen.wrapper.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplexTest {
    @Test fun halfIsForcedAndAMissingOrFailedGraphStaysHalf() {
        assertEquals(HearingDuplex.HALF, chooseDuplex(DuplexRequest.HALF, aecAvailable = true, SelfCheck.PASS).mode)
        assertEquals("forced", chooseDuplex(DuplexRequest.HALF, true, SelfCheck.PASS).reason)
        assertEquals(HearingDuplex.HALF, chooseDuplex(DuplexRequest.AUTO, aecAvailable = false, SelfCheck.PASS).mode)
        assertEquals("aec-unavailable", chooseDuplex(DuplexRequest.FULL, false, SelfCheck.ABSENT).reason)
        assertEquals(HearingDuplex.HALF, chooseDuplex(DuplexRequest.AUTO, true, SelfCheck.FAIL).mode)
        assertEquals("self-check-failed", chooseDuplex(DuplexRequest.FULL, true, SelfCheck.FAIL).reason)
        assertEquals(HearingDuplex.FULL, chooseDuplex(DuplexRequest.AUTO, true, SelfCheck.PASS).mode)
        assertEquals("aec-unverified", chooseDuplex(DuplexRequest.AUTO, true, SelfCheck.ABSENT).reason)
        assertEquals(DuplexRequest.HALF, readDuplexRequest(mapOf("HOVEL_HEARING_DUPLEX" to "half")))
        assertEquals(DuplexRequest.AUTO, readDuplexRequest(emptyMap()))
        assertEquals(250, hearingBargeMs(emptyMap()))
        assertEquals(200, hearingBargeMs(mapOf("HOVEL_HEARING_BARGE_MS" to "50")))
        assertEquals(300, hearingBargeMs(mapOf("HOVEL_HEARING_BARGE_MS" to "900")))
        assertEquals(SelfCheck.FAIL, readSelfCheck("fail\n0.0\n"))
        assertEquals(SelfCheck.ABSENT, readSelfCheck(null))
    }

    @Test fun bargeInNeedsSustainedSpeechAndForgetsACough() {
        val guard = BargeInGuard(sustainMs = 250, gapResetMs = 80)
        assertFalse(guard.frame(true, 100))
        assertFalse(guard.frame(true, 100))
        assertFalse(guard.frame(false, 80))
        assertFalse(guard.frame(true, 200))
        assertTrue(guard.frame(true, 50))
        assertFalse(guard.frame(true, 400))
        guard.reset()
        assertFalse(guard.frame(true, 200))
        assertTrue(guard.frame(true, 50))
    }

    @Test fun tenEchoesStayBelowTheBargeAndARealTalkCrossesIt() {
        val guard = BargeInGuard(sustainMs = 250)
        val echo = FloatArray(1600) { 0.4f }
        repeat(10) {
            val cleaned = cancelEcho(echo, echo, gain = 1f)
            assertTrue(cleaned.all { abs(it) < 0.001f })
            assertFalse(guard.frame(voiced = false, frameMs = 100))
        }
        assertFalse(guard.frame(true, 200))
        assertTrue(guard.frame(true, 50))
    }

    @Test fun erlePassesAQuietMicAndFailsWhenTheClipOrItsWordsRemain() {
        val reference = FloatArray(1600) { if (it % 20 < 10) 0.5f else -0.5f }
        val quiet = FloatArray(1600) { reference[it] * 0.01f }
        val clean = echoVerdict(reference, quiet, transcript = "hello there")
        assertTrue(clean.erleDb >= ECHO_ERLE_PASS_DB)
        assertTrue(clean.passed)
        val leaked = echoVerdict(reference, quiet, transcript = "Ember checking something")
        assertTrue(leaked.leaked)
        assertFalse(leaked.passed)
        val open = echoVerdict(reference, reference, transcript = "")
        assertTrue(open.erleDb < ECHO_ERLE_PASS_DB)
        assertFalse(open.passed)
    }
}
