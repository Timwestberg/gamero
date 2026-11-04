package org.formulagen.wrapper.workspace

import androidx.compose.ui.input.key.nativeKeyCode
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.HyperlinkStyle
import com.jediterm.terminal.ui.TerminalAction
import java.awt.Component
import java.awt.DefaultKeyboardFocusManager
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.InputEvent.ALT_DOWN_MASK
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.InputEvent.META_DOWN_MASK
import java.awt.event.InputEvent.SHIFT_DOWN_MASK
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.net.URI
import java.text.AttributedString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.RepaintManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.conversations.eventually

/** A tab's terminal as the player uses it: Option, the input method, window shortcuts, pasting, links, the font. */
class TerminalPaneTest {
    private val session = FakeSession()
    private val shortcuts = CopyOnWriteArrayList<WorkspaceAction>()
    private val connector = SessionTtyConnector("Shell").also { it.connect(session) }
    private val pasteQuestions = CopyOnWriteArrayList<NativeDialog<Boolean>>()
    @Volatile private var pasteAnswer = false
    private val clipboard = FakeClipboard()
    private val view: TerminalView = onAwtNow {
        TerminalView(connector, TermSize(80, 24), onShortcut = { shortcuts.add(it) }, confirmPaste = { pasteQuestions.add(it); pasteAnswer })
    }
    private val panel: HovelTerminalPanel get() = view.panel as HovelTerminalPanel

    init { panel.clipboard = clipboard }

    @AfterTest fun cleanUp() {
        connector.exited(0)
        EventQueue.invokeAndWait { view.close() }
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

    /** ⌘V, as macOS delivers it to the focused terminal. */
    private fun pasteKey() = press(KeyEvent.VK_V, 'v', META_DOWN_MASK)

    /** [output] from the program, once the emulator has read it all. */
    private fun program(output: String) {
        val marker = "read-${System.nanoTime()}"
        val bytes = (output + marker).toByteArray()
        connector.output(bytes, bytes.size)
        eventually("the output was read") { marker in view.screenText() }
    }

    @Test fun pastedTextLosesEveryControlButTabAndLineBreaks() {
        assertEquals("[201~rm -rf ~\n", pasteText("\u001b[201~rm -rf ~\n"))
        assertEquals("abcdef\tg\rh\ni", pasteText("a\u0000b\u0007c\u007fd\u009be\u0085f\tg\rh\ni"))
        assertEquals("café 😀 日本", pasteText("café 😀 日本"))
        assertEquals("[201~rm -rf ~\n", CleanPasteHandler { FakeClipboard("\u001b[201~rm -rf ~\n") }.getContents(false))
        assertNull(CleanPasteHandler { FakeClipboard(null) }.getContents(false))
        assertEquals(0, pastedLines("one line"))
        assertEquals(1, pastedLines("ls\n"))
        assertEquals(2, pastedLines("a\r\nb"))
        assertEquals(3, pastedLines("a\rb\nc\n"))
    }

    @Test fun aSingleLinePastesWithoutAskingAndWithoutItsControls() {
        clipboard.text = "echo \u001b[31mhi\u0007"
        pasteKey()
        assertEquals("echo [31mhi", sent("echo [31mhi"))
        assertTrue(pasteQuestions.isEmpty())
    }

    @Test fun withBracketedPasteOnAMultiLinePasteIsWrappedWithoutAsking() {
        program("\u001b[?2004h")
        assertTrue(panel.bracketedPaste)
        clipboard.text = "safe\u001b[201~rm -rf ~\nls\r\n"
        pasteKey()
        val expected = "\u001b[200~safe[201~rm -rf ~\rls\r\u001b[201~"
        assertEquals(expected, sent(expected), "the pasted end marker lost its ESC, so the paste cannot end early")
        assertTrue(pasteQuestions.isEmpty())
        program("\u001b[?2004l")
        assertFalse(panel.bracketedPaste)
    }

    @Test fun withBracketedPasteOffAMultiLinePasteAsksAndCancelSendsNothing() {
        clipboard.text = "echo one\necho two\n"
        pasteKey()
        assertEquals(
            NativeDialog(
                "Paste 2 lines into Shell?",
                listOf("The program in this tab has not turned on safe pasting, so each line may run as soon as it arrives."),
                listOf("Paste" to true, "Cancel" to false),
                false,
            ),
            pasteQuestions.single(),
        )
        Thread.sleep(300)
        assertTrue(session.input.isEmpty(), "Cancel pastes nothing")
        pasteAnswer = true
        pasteKey()
        assertEquals("echo one\recho two\r", sent("echo two\r"))
        assertEquals(2, pasteQuestions.size)
    }

    @Test fun theContextMenusPasteAsksToo() {
        clipboard.text = "a\nb"
        val item = onAwtNow { JPopupMenu().also { TerminalAction.fillMenu(it, panel) }.components.filterIsInstance<JMenuItem>().single { it.text == "Paste" } }
        assertTrue(item.isEnabled)
        EventQueue.invokeAndWait { item.doClick() }
        assertEquals(1, pasteQuestions.size, "the menu's Paste is the same as ⌘V")
        clipboard.text = null
        assertFalse(onAwtNow { panel.actions.single { it.name == "Paste" }.isEnabled(null) }, "nothing to paste")
    }

    @Test fun aMiddleClickNeverPastes() {
        assertFalse(HovelTerminalSettings().pasteOnMiddleMouseClick())
        clipboard.text = "ls\n"
        EventQueue.invokeAndWait {
            val now = System.currentTimeMillis()
            for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                val mask = if (id == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON2_DOWN_MASK else 0
                panel.dispatchEvent(MouseEvent(panel, id, now, mask, 10, 10, 1, false, MouseEvent.BUTTON2))
            }
        }
        Thread.sleep(300)
        assertTrue(session.input.isEmpty())
        assertTrue(pasteQuestions.isEmpty())
    }

    /** Select row [row] as a triple click does. */
    private fun selectRow(row: Int) = EventQueue.invokeAndWait {
        val point = view.cellCenter(1, row)
        panel.dispatchEvent(MouseEvent(panel, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, point.x, point.y, 3, false, MouseEvent.BUTTON1))
    }

    @Test fun openAsUrlOpensOnlyWebPages() {
        val opened = CopyOnWriteArrayList<URI>()
        panel.browse = { opened.add(it) }
        val links = listOf(
            "https://example.com/path?q=1", "HTTP://EXAMPLE.ORG",
            "file:///Applications/Calculator.app", "x-apple.systempreferences:com.apple.preference.security",
            "javascript:alert(1)", "smb://server/share", "https://exa mple.com", "http://[unparseable",
        )
        program(links.joinToString("\r\n", postfix = "\r\n"))
        val open = onAwtNow { panel.actions.single { it.name == "Open as URL" } }
        for (row in links.indices) {
            selectRow(row)
            onAwtNow { open.actionPerformed(null) }
        }
        assertEquals(listOf(URI("https://example.com/path?q=1"), URI("HTTP://EXAMPLE.ORG")), opened.toList())
    }

    @Test fun aWebLinkIsHttpOrHttpsWithAHost() {
        assertEquals(URI("https://example.com/a"), webLink(" https://example.com/a \n"))
        val others = listOf(
            "file:///etc/passwd", "https:///no-host", "https:opaque", "mailto:a@example.com", "ftp://example.com",
            "x-apple.systempreferences:", "javascript:alert(1)", "example.com", "", "http://exa mple.com",
        )
        for (other in others) assertNull(webLink(other), other)
    }

    @Test fun noLinkInAProgramsOutputIsClickable() {
        // OSC 8 and bare URLs: no filter is installed.
        program("\u001b]8;;file:///Applications/Calculator.app\u001b\\Calculator\u001b]8;;\u001b\\ https://example.com ")
        Thread.sleep(300)
        val styles = onAwtNow {
            val buffer = panel.terminalTextBuffer
            buffer.lock()
            try { (0 until 40).mapNotNull { buffer.getLine(0).getStyleAt(it) } } finally { buffer.unlock() }
        }
        assertTrue(styles.isNotEmpty())
        assertTrue(styles.none { it is HyperlinkStyle }, "$styles")
        assertTrue(view.screenText().startsWith("Calculator https://example.com"))
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
            assertNull(key(code, modifiers), "$code with $modifiers")
        }
        assertNull(workspaceShortcut(KeyEvent(source, KeyEvent.KEY_TYPED, 0, META_DOWN_MASK, KeyEvent.VK_UNDEFINED, '1')))
    }

    @Test fun everyMenuShortcutMapsBackToItsAction() {
        val actions = listOf(
            WorkspaceAction.NewTab, WorkspaceAction.CloseTab, WorkspaceAction.SelectLastTab, WorkspaceAction.NextTab,
            WorkspaceAction.PreviousTab, WorkspaceAction.ZoomIn, WorkspaceAction.ZoomOut, WorkspaceAction.ZoomReset,
        ) + (0 until WorkspaceSettingsLimits.TABS).map { WorkspaceAction.SelectTab(it) }
        val source = JPanel()
        for (action in actions) {
            val menu = assertNotNull(menuKey(action), "$action")
            val modifiers = if (menu.shift) META_DOWN_MASK or SHIFT_DOWN_MASK else META_DOWN_MASK
            val event = KeyEvent(source, KeyEvent.KEY_PRESSED, 0, modifiers, menu.key.nativeKeyCode, KeyEvent.CHAR_UNDEFINED)
            assertEquals(action, workspaceShortcut(event), "the menu's $menu")
        }
        assertNull(menuKey(WorkspaceAction.SelectTab(WorkspaceSettingsLimits.TABS)), "no key past the last tab")
    }

    @Test fun thereIsATabKeyForEveryTab() {
        assertEquals(WorkspaceSettingsLimits.TABS, TAB_KEYS.size)
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
        assertTrue(HovelTerminalSettings().caretBlinkingMs() > 0, "JediTerm's own period: 0 repaints the cursor on every tick")
    }

    @Test fun everyCursorShapeIsDrawnSteady() {
        assertEquals(CursorShape.STEADY_BLOCK, steadyCursor(CursorShape.BLINK_BLOCK), "CSI 0 q and CSI 1 q")
        assertEquals(CursorShape.STEADY_UNDERLINE, steadyCursor(CursorShape.BLINK_UNDERLINE))
        assertEquals(CursorShape.STEADY_VERTICAL_BAR, steadyCursor(CursorShape.BLINK_VERTICAL_BAR))
        for (steady in listOf(CursorShape.STEADY_BLOCK, CursorShape.STEADY_UNDERLINE, CursorShape.STEADY_VERTICAL_BAR)) assertEquals(steady, steadyCursor(steady))
    }

    @Test fun aFocusedIdleTerminalRepaintsOnlyAFewTimesASecond() {
        val panel = view.panel
        val repaints = AtomicInteger()
        val previousRepaints = onAwtNow { RepaintManager.currentManager(panel) }
        val previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        try {
            EventQueue.invokeAndWait {
                RepaintManager.setCurrentManager(object : RepaintManager() {
                    override fun addDirtyRegion(c: JComponent, x: Int, y: Int, w: Int, h: Int) {
                        if (c === panel) repaints.incrementAndGet()
                        super.addDirtyRegion(c, x, y, w, h)
                    }
                })
                // JediTerm's cursor ticks only in the focused tab.
                KeyboardFocusManager.setCurrentKeyboardFocusManager(object : DefaultKeyboardFocusManager() {
                    override fun getFocusOwner(): Component = panel
                })
            }
            Thread.sleep(500)
            repaints.set(0)
            Thread.sleep(2_000)
            assertTrue(repaints.get() <= 6, "${repaints.get()} repaints in 2 s while idle; a blink period of 0 gives about 70")
        } finally {
            EventQueue.invokeAndWait {
                RepaintManager.setCurrentManager(previousRepaints)
                KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocus)
            }
        }
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

    @Test fun aBurstOfTitlesReachesTheWindowAsItsLatest() {
        val titles = CopyOnWriteArrayList<String>()
        val titledConnector = SessionTtyConnector("Shell").also { it.connect(FakeSession()) }
        val titled = onAwtNow { TerminalView(titledConnector, TermSize(80, 24), onTitle = { titles.add(it) }) }
        try {
            // Hold the AWT thread while the whole burst arrives.
            val held = CountDownLatch(1)
            EventQueue.invokeLater { held.await(10, TimeUnit.SECONDS) }
            val burst = ((1..200).joinToString("") { "\u001b]2;title $it\u0007" } + "ready").toByteArray()
            titledConnector.output(burst, burst.size)
            eventually("the burst was read") { "ready" in titled.screenText() }
            held.countDown()
            eventually("a title arrived") { titles.isNotEmpty() }
            onAwtNow {}
            assertEquals(listOf("title 200"), titles.toList(), "one hop to the AWT thread, with the latest title")
        } finally {
            titledConnector.exited(0)
            EventQueue.invokeAndWait { titled.close() }
        }
    }

    @Test fun zoomRedrawsTheCellsAtTheNewSize() {
        val small = onAwtNow { view.cellCenter(1, 1) }
        onAwtNow { view.setFontSize(26) }
        val large = onAwtNow { view.cellCenter(1, 1) }
        assertTrue(large.x > small.x * 3 / 2 && large.y > small.y * 3 / 2, "$small -> $large")
    }
}
