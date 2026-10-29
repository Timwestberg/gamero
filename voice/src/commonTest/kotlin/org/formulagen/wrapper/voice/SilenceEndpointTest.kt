package org.formulagen.wrapper.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class SilenceEndpointTest {
    @Test fun tenTurnsEachEndAfterOneSecondOfQuiet() {
        val endpoint = SilenceEndpoint(silenceMs = 1000, minSpeechMs = 240)
        repeat(10) { turn ->
            assertEquals(EndpointDecision.LISTEN, endpoint.frame(true, 500), "speech $turn")
            assertEquals(EndpointDecision.LISTEN, endpoint.frame(false, 999), "almost $turn")
            assertEquals(EndpointDecision.SEND, endpoint.frame(false, 1), "end $turn")
        }
    }

    @Test fun silenceOnlyNeverSends() {
        val endpoint = SilenceEndpoint()
        repeat(20) {
            assertEquals(EndpointDecision.LISTEN, endpoint.frame(false, 500))
        }
    }

    @Test fun aShortNoiseBurstIsDroppedAndTheNextTurnStillSends() {
        val endpoint = SilenceEndpoint(silenceMs = 1000, minSpeechMs = 240)
        assertEquals(EndpointDecision.LISTEN, endpoint.frame(true, 100))
        assertEquals(EndpointDecision.DROP, endpoint.frame(false, 1000))
        assertEquals(EndpointDecision.LISTEN, endpoint.frame(true, 400))
        assertEquals(EndpointDecision.SEND, endpoint.frame(false, 1000))
    }
}
