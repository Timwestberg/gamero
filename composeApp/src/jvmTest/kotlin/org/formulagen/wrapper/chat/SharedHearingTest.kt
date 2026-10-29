package org.formulagen.wrapper.chat

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.formulagen.wrapper.voice.HearingResult

class SharedHearingTest {
    /** A microphone that records what reached it, and fails a capture on [fail]. */
    private class Ear : Hearing {
        val calls = CopyOnWriteArrayList<String>()
        var running = false
        var failed: ((String) -> Unit)? = null
        override fun down(onPartial: (String) -> Unit, onFailed: (String) -> Unit): Boolean {
            if (running) return false
            running = true; failed = onFailed; calls += "down"; return true
        }
        override fun up(onResult: (HearingResult) -> Unit): Boolean {
            running = false; calls += "up"; onResult(HearingResult("heard", 900, 1.0)); return true
        }
        override fun startSession(silenceMs: Int, onPartial: (String) -> Unit, onUtterance: (HearingResult) -> Unit, onFailed: (String) -> Unit,
            onFrame: (voicedMs: Int, chunkMs: Int, vadProb: Float) -> Unit): Boolean {
            if (running) return false
            running = true; failed = onFailed; calls += "session"; return true
        }
        override fun setPaused(value: Boolean) { calls += "paused=$value" }
        override fun restartUtterance() { calls += "restart" }
        override fun noteEmberSpeaking(text: String) { calls += "speaking" }
        override fun noteEmberSilent(text: String) { calls += "silent" }
        override fun endSession() { running = false; calls += "end" }
        override fun awaitStopped(timeoutMs: Long) {}
        override fun close() { calls += "close" }
        fun fail() { running = false; failed?.invoke("The microphone did not open.") }
    }

    private val ear = Ear()
    private val shared = SharedHearing(ear)
    private val chat = shared.user()
    private val conversations = shared.user()

    @Test fun oneUserCapturesAtATimeAndTheOthersControlsReachNothing() {
        assertTrue(chat.startSession(1_000, {}, {}, {}))
        assertTrue(conversations.busyElsewhere)
        assertFalse(chat.busyElsewhere)
        assertFalse(conversations.startSession(1_000, {}, {}, {}))
        assertFalse(conversations.down({}, {}))
        ear.calls.clear()
        conversations.setPaused(true)
        conversations.restartUtterance()
        conversations.noteEmberSpeaking("Hi.")
        conversations.endSession()
        conversations.close()
        assertFalse(conversations.up {})
        assertEquals(emptyList(), ear.calls, "another user's controls never touch this capture")
        chat.setPaused(true)
        chat.endSession()
        assertEquals(listOf("paused=true", "end"), ear.calls)
        assertFalse(conversations.busyElsewhere)
        assertTrue(conversations.down({}, {}))
        var heard = ""
        assertTrue(conversations.up { heard = it.text })
        assertEquals("heard", heard)
        assertTrue(chat.startSession(1_000, {}, {}, {}), "a delivered hold lets go")
    }

    @Test fun aFailedCaptureLetsGoAndClosingAUserKeepsTheRecognizer() {
        val failures = CopyOnWriteArrayList<String>()
        assertTrue(chat.startSession(1_000, {}, {}, { failures += it }))
        ear.fail()
        assertEquals(listOf("The microphone did not open."), failures)
        assertFalse(conversations.busyElsewhere)
        assertTrue(conversations.startSession(1_000, {}, {}, {}))
        conversations.close()
        assertFalse("close" in ear.calls, "a user's close only ends its own capture")
        assertTrue(chat.startSession(1_000, {}, {}, {}))
        shared.close()
        assertTrue("close" in ear.calls)
    }
}
