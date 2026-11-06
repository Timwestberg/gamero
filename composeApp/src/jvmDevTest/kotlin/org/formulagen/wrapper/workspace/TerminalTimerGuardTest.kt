package org.formulagen.wrapper.workspace

import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.TerminalPanel
import java.awt.EventQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JScrollBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.formulagen.wrapper.conversations.eventually

/** JediTerm's repaint timer (cursor blink, scrolling, repaint) throwing must not reach the app's default handler. */
class TerminalTimerGuardTest {
    /** Throws from inside every repaint timer callback: the timer reads this setting on each tick. */
    private class ThrowingSettings : HovelTerminalSettings() {
        val calls = AtomicInteger()
        override fun enableTextBlinking(): Boolean {
            calls.incrementAndGet()
            throw IllegalStateException("timer boom")
        }
    }

    @Test fun aThrowingRepaintTimerCallbackIsContained() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        try {
            // The control: JediTerm's own panel lets the exception escape to the default handler, which ends Hovel.
            val plainSettings = ThrowingSettings()
            val plain = onAwtNow { TerminalPanel(plainSettings, TerminalTextBuffer(80, 24, StyleState()), StyleState()).also { it.init(JScrollBar()) } }
            eventually("the unguarded timer threw") { uncaught.isNotEmpty() }
            onAwtNow { plain.dispose() }
            assertTrue(uncaught.any { it.message == "timer boom" }, "the unguarded timer's exception escapes: $uncaught")
            Thread.sleep(100)
            uncaught.clear()

            val settings = ThrowingSettings()
            val guarded = onAwtNow { HovelTerminalPanel(settings, TerminalTextBuffer(80, 24, StyleState()), StyleState()).also { it.init(JScrollBar()) } }
            assertNotNull(guarded.repaintTimer(), "JediTerm's timer was found")
            eventually("the guarded timer kept firing") { settings.calls.get() >= 5 }
            onAwtNow { guarded.dispose() }
            assertTrue(settings.calls.get() >= 5, "the timer kept firing and throwing: ${settings.calls.get()} calls")
            assertEquals(emptyList(), uncaught.toList(), "nothing reached the default handler")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun aFailureOnEveryTickIsLoggedOnceThenCountedOncePerMinute() {
        var now = 0L
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val failures = RepeatedFailureLog("The timer", periodNanos = 60_000, clock = { now }) { message, error -> logged += message to error }
        val first = IllegalStateException("boom")
        failures.failed(first)
        assertEquals(listOf("The timer failed" to first), logged.toList(), "the first in full")
        repeat(2_999) { now += 20; failures.failed(IllegalStateException("boom")) }
        assertEquals(1, logged.size, "a minute of 20 ms ticks adds nothing yet")
        now += 20
        failures.failed(IllegalStateException("boom"))
        assertEquals(2, logged.size)
        assertEquals("The timer failed 3000 more times since its last report; the latest: java.lang.IllegalStateException: boom", logged[1].first)
        assertNull(logged[1].second, "a count, not another stack trace")
        repeat(10) { now += 20; failures.failed(IllegalStateException("boom")) }
        assertEquals(2, logged.size, "then at most one a minute")
    }
}
