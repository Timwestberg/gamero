package org.formulagen.wrapper.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnCommitterTest {
    @Test fun lowLevelNoiseAfterSpeechSendsWithinAboutOneSecond() {
        val committer = TurnCommitter(silenceMs = 1000, calibrateMs = 300, floorMargin = 0.01f, textStableMs = 5_000)
        repeat(3) {
            assertEquals(EndpointDecision.LISTEN, committer.observe(vadVoiced = false, frameMs = 100, rms = 0.01f, text = "").decision)
        }
        assertTrue(committer.floorReady)
        repeat(4) {
            val event = committer.observe(vadVoiced = true, frameMs = 100, rms = 0.2f, text = "hello there")
            assertEquals(EndpointDecision.LISTEN, event.decision)
            assertTrue(event.speech)
        }
        var sentAt = -1
        var reason: EndpointReason? = null
        for (frame in 1..12) {
            val event = committer.observe(
                vadVoiced = true,
                frameMs = 100,
                rms = 0.012f,
                text = "hello there $frame",
            )
            if (event.decision == EndpointDecision.SEND) {
                sentAt = frame * 100
                reason = event.reason
                break
            }
        }
        assertTrue(sentAt in 1..1200, "sent at ${sentAt}ms reason=$reason")
        assertEquals(EndpointReason.VAD_SILENCE, reason)
    }

    @Test fun unchangedTextSendsEvenWhenTheVadStaysHot() {
        val committer = TurnCommitter(silenceMs = 5_000, textStableMs = 1000, calibrateMs = 100, floorMargin = 0.001f)
        committer.observe(false, 100, 0.01f, "")
        repeat(3) { committer.observe(true, 100, 0.3f, "what time is it") }
        var reason: EndpointReason? = null
        for (frame in 1..12) {
            val event = committer.observe(true, 100, 0.3f, "what time is it")
            if (event.decision == EndpointDecision.SEND) {
                reason = event.reason
                assertTrue(frame * 100 <= 1200)
                break
            }
        }
        assertEquals(EndpointReason.TEXT_STABLE, reason)
    }

    @Test fun fifteenSecondsOfSpeechSendsOnTheCap() {
        val committer = TurnCommitter(silenceMs = 5_000, textStableMs = 60_000, calibrateMs = 100, maxSpeechMs = 15_000)
        committer.observe(false, 100, 0.01f, "")
        val event = committer.observe(true, 15_000, 0.4f, "still going")
        assertEquals(EndpointDecision.SEND, event.decision)
        assertEquals(EndpointReason.CAP, event.reason)
    }
}
