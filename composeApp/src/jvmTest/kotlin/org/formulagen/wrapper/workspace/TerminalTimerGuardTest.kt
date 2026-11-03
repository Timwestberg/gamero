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
import kotlin.test.assertTrue

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

    private fun <T> onAwtNow(read: () -> T): T {
        var value: Result<T>? = null
        EventQueue.invokeAndWait { value = runCatching(read) }
        return value!!.getOrThrow()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test fun aThrowingRepaintTimerCallbackIsContained() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        try {
            // The control: JediTerm's own panel lets the exception escape to the default handler, which ends Hovel.
            val plainSettings = ThrowingSettings()
            val plain = onAwtNow { TerminalPanel(plainSettings, TerminalTextBuffer(80, 24, StyleState()), StyleState()).also { it.init(JScrollBar()) } }
            waitFor { uncaught.isNotEmpty() }
            onAwtNow { plain.dispose() }
            assertTrue(uncaught.any { it.message == "timer boom" }, "the unguarded timer's exception escapes: $uncaught")
            Thread.sleep(100)
            uncaught.clear()

            val settings = ThrowingSettings()
            val guarded = onAwtNow { GuardedTerminalPanel(settings, TerminalTextBuffer(80, 24, StyleState()), StyleState()).also { it.init(JScrollBar()) } }
            assertNotNull(guarded.repaintTimer(), "JediTerm's timer was found")
            waitFor { settings.calls.get() >= 5 }
            onAwtNow { guarded.dispose() }
            assertTrue(settings.calls.get() >= 5, "the timer kept firing and throwing: ${settings.calls.get()} calls")
            assertEquals(emptyList(), uncaught.toList(), "nothing reached the default handler")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
