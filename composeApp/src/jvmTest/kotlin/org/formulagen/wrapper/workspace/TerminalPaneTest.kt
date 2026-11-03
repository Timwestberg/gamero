package org.formulagen.wrapper.workspace

import java.awt.EventQueue
import java.awt.event.InputEvent.ALT_DOWN_MASK
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.InputEvent.META_DOWN_MASK
import java.awt.event.InputEvent.SHIFT_DOWN_MASK
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.text.AttributedString
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A tab's terminal as the player types into it: Option, the input method, window shortcuts, the font. */
class TerminalPaneTest {
    /** A session that records what the terminal sends it. */
    private class RecordingSession : Session {
        val input = CopyOnWriteArrayList<String>()
        override val title = "Shell"
        override fun busy(): Busy? = null
        override fun close() {}
        override fun attach(viewer: SessionViewer): Attachment = object : Attachment {
            override fun input(bytes: ByteArray) { input.add(bytes.decodeToString()) }
            override fun resize(size: TermSize) {}
            override fun close() {}
        }
    }

    private val session = RecordingSession()
    private val shortcuts = CopyOnWriteArrayList<WorkspaceAction>()
    private val connector = SessionTtyConnector("Shell").also { it.connect(session) }
    private val view: TerminalView = onAwtNow { TerminalView(connector, TermSize(80, 24), onShortcut = { shortcuts.add(it) }) }

    @AfterTest fun cleanUp() {
        connector.exited(0)
        EventQueue.invokeAndWait { view.close() }
    }

    private fun <T> onAwtNow(read: () -> T): T {
        var value: Result<T>? = null
        EventQueue.invokeAndWait { value = runCatching(read) }
        return value!!.getOrThrow()
    }

    /**
     * A key as macOS delivers it to the focused terminal: press, typed (for a character), release.
     * Straight to the panel's handler: with no window showing, AWT's focus manager would drop them.
     */
    private fun press(code: Int, char: Char, modifiers: Int = 0) = EventQueue.invokeAndWait {
        val panel = view.panel
        val now = System.currentTimeMillis()
        panel.processKeyEvent(KeyEvent(panel, KeyEvent.KEY_PRESSED, now, modifiers, code, char))
        if (char != KeyEvent.CHAR_UNDEFINED) panel.processKeyEvent(KeyEvent(panel, KeyEvent.KEY_TYPED, now, modifiers, KeyEvent.VK_UNDEFINED, char))
        panel.processKeyEvent(KeyEvent(panel, KeyEvent.KEY_RELEASED, now, modifiers, code, char))
    }

    /** Committed text, as macOS's input method sends a dead key's result or an IME's. */
    private fun commit(text: String) = EventQueue.invokeAndWait {
        val panel = view.panel
        panel.dispatchEvent(InputMethodEvent(panel, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString(text).iterator, text.length, null, null))
    }

    /** What reached the session, once [expected] has. JediTerm sends from its own thread. */
    private fun sent(expected: String): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val all = session.input.joinToString("")
            if (expected in all) return all
            Thread.sleep(20)
        }
        return session.input.joinToString("")
    }

    @Test fun optionTypesCharactersAsInTerminal() {
        // ⌥o types ø on a US keyboard: the character, not ESC o.
        press(KeyEvent.VK_O, 'ø', ALT_DOWN_MASK)
        // ⌥e then e, and ⌥n then n: dead keys, which macOS's input method commits as é and ñ.
        commit("é")
        commit("ñ")
        val all = sent("øéñ")
        assertEquals("øéñ", all, "Option characters reach the shell as typed")
        assertFalse("\u001b" in all)
    }

    @Test fun optionArrowsStillMoveByWord() {
        press(KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED, ALT_DOWN_MASK)
        press(KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED, ALT_DOWN_MASK)
        assertEquals("\u001bb\u001bf", sent("\u001bb\u001bf"))
    }

    @Test fun windowShortcutsGoToTheWindowNotTheTerminal() {
        press(KeyEvent.VK_1, '1', META_DOWN_MASK)
        press(KeyEvent.VK_CLOSE_BRACKET, '}', META_DOWN_MASK or SHIFT_DOWN_MASK)
        press(KeyEvent.VK_TAB, '\t', CTRL_DOWN_MASK)
        press(KeyEvent.VK_EQUALS, '=', META_DOWN_MASK)
        press(KeyEvent.VK_A, 'a')
        assertEquals("a", sent("a"), "only the plain key is terminal input")
        assertEquals(listOf(WorkspaceAction.SelectTab(0), WorkspaceAction.NextTab, WorkspaceAction.NextTab, WorkspaceAction.ZoomIn), shortcuts)
    }

    @Test fun shortcutsMapLikeTerminal() {
        val source = JPanel()
        fun key(code: Int, modifiers: Int, char: Char = KeyEvent.CHAR_UNDEFINED) =
            workspaceShortcut(KeyEvent(source, KeyEvent.KEY_PRESSED, 0, modifiers, code, char))
        assertEquals(WorkspaceAction.NewTab, key(KeyEvent.VK_T, META_DOWN_MASK))
        assertEquals(WorkspaceAction.CloseTab, key(KeyEvent.VK_W, META_DOWN_MASK))
        for (n in 1..8) assertEquals(WorkspaceAction.SelectTab(n - 1), key(KeyEvent.VK_0 + n, META_DOWN_MASK))
        assertEquals(WorkspaceAction.SelectLastTab, key(KeyEvent.VK_9, META_DOWN_MASK))
        assertEquals(WorkspaceAction.NextTab, key(KeyEvent.VK_CLOSE_BRACKET, META_DOWN_MASK or SHIFT_DOWN_MASK))
        assertEquals(WorkspaceAction.PreviousTab, key(KeyEvent.VK_OPEN_BRACKET, META_DOWN_MASK or SHIFT_DOWN_MASK))
        assertEquals(WorkspaceAction.PreviousTab, key(KeyEvent.VK_TAB, CTRL_DOWN_MASK or SHIFT_DOWN_MASK))
        assertEquals(WorkspaceAction.NextTab, key(KeyEvent.VK_UNDEFINED, META_DOWN_MASK or SHIFT_DOWN_MASK, '}'), "by character on other layouts")
        assertEquals(WorkspaceAction.ZoomIn, key(KeyEvent.VK_EQUALS, META_DOWN_MASK or SHIFT_DOWN_MASK), "⌘+ is ⌘⇧= on a US keyboard")
        assertEquals(WorkspaceAction.ZoomIn, key(KeyEvent.VK_EQUALS, META_DOWN_MASK))
        assertEquals(WorkspaceAction.ZoomOut, key(KeyEvent.VK_MINUS, META_DOWN_MASK))
        assertEquals(WorkspaceAction.ZoomReset, key(KeyEvent.VK_0, META_DOWN_MASK))
        // The terminal's own: copy, paste, a plain digit, ⌥1, ⌃1, and typed events.
        for ((code, modifiers) in listOf(KeyEvent.VK_C to META_DOWN_MASK, KeyEvent.VK_V to META_DOWN_MASK, KeyEvent.VK_1 to 0,
            KeyEvent.VK_1 to ALT_DOWN_MASK, KeyEvent.VK_1 to CTRL_DOWN_MASK, KeyEvent.VK_TAB to 0)) {
            assertEquals(null, key(code, modifiers), "$code with $modifiers")
        }
        assertEquals(null, workspaceShortcut(KeyEvent(source, KeyEvent.KEY_TYPED, 0, META_DOWN_MASK, KeyEvent.VK_UNDEFINED, '1')))
    }

    @Test fun theFontIsSFMonoWhenAwtSeesItElseMenlo() {
        assertEquals("SF Mono", TerminalFont.resolveFamily(setOf("Menlo", "SF Mono")))
        assertEquals("Menlo", TerminalFont.resolveFamily(setOf("Menlo", "Monaco")))
        assertTrue(TerminalFont.family == "SF Mono" || TerminalFont.family == "Menlo")
        val font = HovelTerminalSettings(15).terminalFont
        assertEquals(15, font.size)
        assertEquals(TerminalFont.family, font.name)
        assertEquals(13, HovelTerminalSettings().terminalFont.size)
        assertFalse(HovelTerminalSettings().altSendsEscape())
        assertEquals(0, HovelTerminalSettings().caretBlinkingMs(), "a steady cursor")
    }

    /**
     * A known JediTerm 3.76 limit, pinned so an upgrade that changes it is noticed. JediTerm gives
     * every UTF-16 character its own cell (and a filler cell after a wide BMP character); only its
     * renderer joins a grapheme cluster into one glyph. So a ZWJ sequence takes 5 cells and draws
     * one 2-cell glyph in them. zsh's line editor counts 4 (wcwidth), Neovim counts 2: no width
     * JediTerm could use agrees with both, and it offers no setting or hook for one.
     */
    @Test fun jediTermGivesEachUtf16CharacterItsOwnCell() {
        fun cells(text: String): Int {
            val bytes = "\r${text}|".toByteArray()
            connector.output(bytes, bytes.size)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val line = view.screenText().lines().first()
                if (line.startsWith(text.take(1)) && '|' in line) return line.indexOf('|')
                Thread.sleep(20)
            }
            error("no output")
        }
        assertEquals(2, cells("😀"))
        assertEquals(2, cells("日"))
        assertEquals(5, cells("👩\u200d💻"), "ZWJ sequence: zsh counts 4, Neovim 2")
        assertEquals(2, cells("❤\ufe0f"), "with VS16: zsh counts 1, Neovim 2")
    }

    @Test fun zoomRedrawsTheCellsAtTheNewSize() {
        val small = onAwtNow { view.cellCenter(1, 1) }
        onAwtNow { view.setFontSize(26) }
        val large = onAwtNow { view.cellCenter(1, 1) }
        assertTrue(large.x > small.x * 3 / 2 && large.y > small.y * 3 / 2, "$small -> $large")
    }
}
