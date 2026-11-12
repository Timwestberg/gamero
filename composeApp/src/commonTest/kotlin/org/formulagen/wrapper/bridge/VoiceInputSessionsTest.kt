package org.formulagen.wrapper.bridge
import kotlin.test.*

class VoiceInputSessionsTest {
    @Test fun releaseBeforeStartNeverClaimsMicrophone() {
        val sessions = VoiceInputSessions()
        sessions.update("one", "cancelled")
        assertEquals("cancelled", sessions.begin("one").state)
        assertNull(sessions.owner)
    }
    @Test fun terminalsCannotBeOverwrittenByLateRecognition() {
        val sessions = VoiceInputSessions()
        sessions.begin("one")
        sessions.update("one", "listening", partial = "buy milk")
        val result = sessions.update("one", "done", final = "buy milk")
        assertEquals(result, sessions.update("one", "listening", partial = "duplicate"))
        assertEquals(result, sessions.update("one", "cancelled"))
        assertEquals("starting", sessions.begin("two").state)
    }
    @Test fun competingCaptureCannotStealLease() {
        val sessions = VoiceInputSessions()
        sessions.begin("one")
        assertEquals("busy", sessions.begin("two").reason)
        assertEquals("one", sessions.owner)
        sessions.cancelAll()
        assertNull(sessions.owner)
    }
}
