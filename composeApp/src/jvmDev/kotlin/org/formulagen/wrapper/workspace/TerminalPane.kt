package org.formulagen.wrapper.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toArgb
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.DefaultTerminalCopyPasteHandler
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TerminalCopyPasteHandler
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.SelectionUtil
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalAction
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.ui.settings.SettingsProvider
import java.awt.CardLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.HierarchyEvent
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.lang.reflect.Field
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.Timer
import kotlin.math.ceil
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.ui.fgBlue500
import org.formulagen.wrapper.ui.fgInk300
import org.formulagen.wrapper.ui.fgInk950
import org.formulagen.wrapper.util.loggingDaemonThreads

private const val TAG = "workspace-terminal"

/**
 * JediTerm's settings for workspace tabs: Hovel's colors, the scrollback guard, mouse reporting, the
 * terminal font at [fontSize] points, and Option typing characters. ⌘C and ⌘V are
 * [DefaultSettingsProvider]'s on macOS; the cursor's period stays its own ([steadyCursor]).
 */
internal open class HovelTerminalSettings(@Volatile var fontSize: Int = TerminalFont.DEFAULT_SIZE) : DefaultSettingsProvider() {
    override fun getBufferMaxLinesCount(): Int = WorkspaceGuards.SCROLLBACK_LINES
    override fun enableMouseReporting(): Boolean = true
    override fun getTerminalFont(): Font = Font(TerminalFont.family, Font.PLAIN, fontSize)
    override fun getTerminalFontSize(): Float = fontSize.toFloat()
    /** Option types characters (⌥e e is é), as in Terminal; ⌥← and ⌥→ still send ESC b and ESC f. */
    override fun altSendsEscape(): Boolean = false
    /** Never: a middle click would paste past [HovelTerminalPanel]'s question (docs/desktop.md, "Pasting"). */
    override fun pasteOnMiddleMouseClick(): Boolean = false
    override fun getDefaultForeground(): TerminalColor = terminalColor(fgInk300.toArgb())
    override fun getDefaultBackground(): TerminalColor = terminalColor(fgInk950.toArgb())
    /** The two colors above: JediTermWidget 3.76 still builds erased cells' style from this, else white. */
    @Deprecated("JediTerm still reads it; keep it in step with the two colors above")
    override fun getDefaultStyle(): TextStyle = TextStyle(getDefaultForeground(), getDefaultBackground())
    override fun getSelectionColor(): TextStyle = TextStyle(TerminalColor.WHITE, terminalColor(fgBlue500.toArgb()))
}

/** The terminal font, its size and the zoom limits. */
internal object TerminalFont {
    /** The owner's choice: SF Mono when AWT can see it by name, else Menlo. Never loaded from a file. */
    const val PREFERRED = "SF Mono"
    const val FALLBACK = "Menlo"
    const val DEFAULT_SIZE = 13
    const val MIN_SIZE = 9
    const val MAX_SIZE = 36

    /** The family terminals use on this Mac, looked up once. */
    val family: String by lazy { resolveFamily(availableFamilies()) }

    /** [PREFERRED] when [available] has it, else [FALLBACK]. */
    internal fun resolveFamily(available: Set<String>): String = if (PREFERRED in available) PREFERRED else FALLBACK

    /** The font families AWT can see by name, or none when it cannot say. */
    private fun availableFamilies(): Set<String> = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    } catch (error: Throwable) {
        rethrowIfFatal(error)
        emptySet()
    }

    /** [size] moved by [steps] points, within the limits. */
    fun zoomed(size: Int, steps: Int): Int = (size + steps).coerceIn(MIN_SIZE, MAX_SIZE)
}

/** [argb] as a JediTerm color. */
private fun terminalColor(argb: Int): TerminalColor = Color(argb).let { TerminalColor.rgb(it.red, it.green, it.blue) }

/** [shape] without its blink: a steady cursor, as Terminal's default profile has, never repainted to blink. */
internal fun steadyCursor(shape: CursorShape): CursorShape = when (shape) {
    CursorShape.BLINK_BLOCK -> CursorShape.STEADY_BLOCK
    CursorShape.BLINK_UNDERLINE -> CursorShape.STEADY_UNDERLINE
    CursorShape.BLINK_VERTICAL_BAR -> CursorShape.STEADY_VERTICAL_BAR
    CursorShape.STEADY_BLOCK, CursorShape.STEADY_UNDERLINE, CursorShape.STEADY_VERTICAL_BAR -> shape
}

/** [text] as a paste sends it: ESC, every other C0 control but tab, newline and return, DEL and the C1 controls removed. */
internal fun pasteText(text: String): String = text.filter { !Character.isISOControl(it) || it == '\t' || it == '\n' || it == '\r' }

/** How many lines pasting [text] would enter, or 0 when it has no line break (`\n`, `\r` or both). */
internal fun pastedLines(text: String): Int {
    if ('\n' !in text && '\r' !in text) return 0
    val lines = text.split("\r\n", "\n", "\r")
    return if (lines.last().isEmpty()) lines.size - 1 else lines.size
}

/** [text] as a web page to open: an http or https URI with a host that parses, else null (docs/desktop.md, "Links"). */
internal fun webLink(text: String): URI? {
    val uri = try { URI(text.trim()) } catch (_: URISyntaxException) { return null }
    val web = uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
    return uri.takeIf { web && !it.host.isNullOrEmpty() }
}

/** A clipboard whose pasted text is cleaned by [pasteText]; copying is [clipboard]'s own. */
internal class CleanPasteHandler(private val clipboard: () -> TerminalCopyPasteHandler) : TerminalCopyPasteHandler {
    override fun setContents(text: String, useSystemSelectionClipboardIfAvailable: Boolean) =
        clipboard().setContents(text, useSystemSelectionClipboardIfAvailable)

    override fun getContents(useSystemSelectionClipboardIfAvailable: Boolean): String? =
        clipboard().getContents(useSystemSelectionClipboardIfAvailable)?.let(::pasteText)
}

/**
 * JediTerm's panel, painting off screen, guarding its AWT-thread entry points and repaint timer,
 * sending window shortcuts to [onShortcut], pasting safely, and opening only web links (docs/desktop.md, "The terminal").
 */
internal class HovelTerminalPanel(private val settings: SettingsProvider, buffer: TerminalTextBuffer, style: StyleState) :
    TerminalPanel(settings, buffer, style) {
    /** Development probe and tests. One character cell, in points. */
    val cell: java.awt.Dimension get() = java.awt.Dimension(myCharSize)

    /** A window shortcut was pressed in this terminal. AWT thread. */
    @Volatile var onShortcut: (WorkspaceAction) -> Unit = {}

    /** Asks before a paste that could run line by line; Cancel, and so no paste, until set. AWT thread. */
    @Volatile var confirmPaste: (NativeDialog<Boolean>) -> Boolean = { false }

    /** The tab's kind, as the paste question names it: never a title the program set. */
    @Volatile var pasteTab: String = "this tab"

    /** Where pastes read from, raw: the system clipboard. Tests set their own. */
    @Volatile var clipboard: TerminalCopyPasteHandler = DefaultTerminalCopyPasteHandler()

    /** Opens a web page. Tests set their own. */
    @Volatile var browse: (URI) -> Unit = { Desktop.getDesktop().browse(it) }

    /** The program has turned on bracketed paste (`CSI ? 2004 h`). Set on the emulator thread. */
    @Volatile var bracketedPaste = false
        private set

    /** The typed event that follows a shortcut's key press is the shortcut's, not the terminal's. */
    private var shortcutTyped = false

    override fun init(scrollBar: JScrollBar) {
        super.init(scrollBar)
        guardRepaintTimer()
    }

    /** Pick up a new font size from the settings: re-measure the cells and resize the terminal. */
    fun refreshFont() = guard("font") { reinitFontAndResize(); repaint() }

    /**
     * The frame JediTerm draws into, at the screen's scale: see [paintComponent]. Dropped while the
     * terminal is not showing (another tab is selected), so hidden tabs hold no frame. AWT thread.
     */
    private var frame: BufferedImage? = null

    init {
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !isShowing) frame = null
        }
        setDefaultCursorShape(CursorShape.STEADY_BLOCK)
    }

    /** A program's cursor shape (DECSCUSR), drawn steady: JediTerm reads `CSI 0 q` as a blinking block. */
    override fun setCursorShape(cursorShape: CursorShape?) = super.setCursorShape(cursorShape?.let(::steadyCursor))

    /** The program's bracketed-paste mode, which JediTerm keeps, followed here for [paste]. */
    override fun setBracketedPasteMode(enabled: Boolean) {
        bracketedPaste = enabled
        super.setBracketedPasteMode(enabled)
    }

    /** JediTerm's own clipboard: [clipboard], read through [pasteText]. Called before this class's fields are set. */
    override fun createCopyPasteHandler(): TerminalCopyPasteHandler = CleanPasteHandler { clipboard }

    /** JediTerm's actions, with Paste (⌘V and the context menu) replaced by [paste]. */
    override fun getActions(): List<TerminalAction> {
        val pasteName = settings.pasteActionPresentation.name
        return super.getActions().map { action ->
            if (action.name != pasteName) action
            else TerminalAction(action.presentation) { paste(); true }.withMnemonicKey(KeyEvent.VK_P).withEnabledSupplier { clipboard.getContents(false) != null }
        }
    }

    /** The context menu's Open as URL: the selection only as a web page ([webLink]), else nothing. */
    override fun openSelectionAsURL(): Boolean {
        guard("open link") { selectedText()?.let(::webLink)?.let(browse) }
        return false
    }

    /** The selected text, as JediTerm reads it for its own actions. */
    private fun selectedText(): String? {
        val (start, end) = selection?.pointsForRun(terminalTextBuffer.width) ?: return null
        return SelectionUtil.getSelectionText(start, end, terminalTextBuffer)
    }

    /** The clipboard's text through [pasteText], asked about first when it could run line by line (docs/desktop.md, "Pasting"). */
    private fun paste() = guard("paste") {
        val text = clipboard.getContents(false)?.let(::pasteText) ?: return@guard
        val lines = pastedLines(text)
        if (lines > 0 && !bracketedPaste && !confirmPaste(Confirmations.paste(lines, pasteTab))) return@guard
        // As JediTerm sends it.
        val sent = text.replace("\r\n", "\n").replace('\n', '\r')
        terminalOutputStream?.sendString(if (bracketedPaste) "\u001b[200~$sent\u001b[201~" else sent, true)
    }

    /** Development probe only: called after each paint with the screen text that paint drew. AWT thread. */
    @Volatile var paintObserver: ((String) -> Unit)? = null

    /** JediTerm's paint into [frame], then [frame] to the screen, outside the buffer's lock (docs/desktop.md, "The terminal"). */
    override fun paintComponent(g: Graphics) = guard("paint") {
        val screen = g as? Graphics2D
        if (screen == null || width <= 0 || height <= 0) {
            super.paintComponent(g)
            return@guard
        }
        val scaleX = screen.transform.scaleX
        val scaleY = screen.transform.scaleY
        val pixelWidth = ceil(width * scaleX).toInt()
        val pixelHeight = ceil(height * scaleY).toInt()
        val image = frame?.takeIf { it.width == pixelWidth && it.height == pixelHeight }
            ?: BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB).also { frame = it }
        val offscreen = image.createGraphics()
        var drawn: String? = null
        try {
            offscreen.scale(scaleX, scaleY)
            screen.clipBounds?.let(offscreen::clip)
            val observer = paintObserver
            if (observer == null) super.paintComponent(offscreen) else {
                // Read the drawn text under the lock.
                terminalTextBuffer.lock()
                try {
                    drawn = terminalTextBuffer.getScreenLines()
                    super.paintComponent(offscreen)
                } finally { terminalTextBuffer.unlock() }
            }
        } finally {
            offscreen.dispose()
        }
        screen.drawImage(image, 0, 0, width, height, null)
        drawn?.let { text -> paintObserver?.invoke(text) }
    }

    /** A window shortcut to [onShortcut]; any other key to JediTerm, except ⌘ typed events (JediTerm acts on the press). */
    override fun processKeyEvent(e: KeyEvent) = guard("key") {
        if (e.id == KeyEvent.KEY_PRESSED) {
            shortcutTyped = false
            val action = workspaceShortcut(e)
            if (action != null) {
                e.consume()
                shortcutTyped = true
                onShortcut(action)
                return@guard
            }
        }
        // ⌘ typed events are never input.
        if (e.id == KeyEvent.KEY_TYPED && (e.isMetaDown || shortcutTyped)) { shortcutTyped = false; e.consume() }
        else super.processKeyEvent(e)
    }
    override fun processMouseEvent(e: MouseEvent) = guard("mouse") { super.processMouseEvent(e) }
    override fun processMouseMotionEvent(e: MouseEvent) = guard("mouse motion") { super.processMouseMotionEvent(e) }
    override fun processMouseWheelEvent(e: MouseWheelEvent) = guard("mouse wheel") { super.processMouseWheelEvent(e) }
    override fun processInputMethodEvent(e: InputMethodEvent) = guard("input method") { super.processInputMethodEvent(e) }

    /** Re-register JediTerm's repaint-timer listeners wrapped, so none can throw (docs/desktop.md, "The terminal"). */
    private fun guardRepaintTimer() {
        val timer = repaintTimer()
        if (timer == null) {
            log(LogLevel.Warn, TAG, "JediTerm's repaint timer was not found; its callbacks are not guarded")
            return
        }
        val failures = RepeatedFailureLog("The terminal's repaint timer")
        for (listener in timer.actionListeners) {
            if (listener is GuardedTimerListener) continue
            timer.removeActionListener(listener)
            timer.addActionListener(GuardedTimerListener(listener, failures))
        }
    }

    /** Development probe and tests. The timer [guardRepaintTimer] wrapped. */
    internal fun repaintTimer(): Timer? = try { REPAINT_TIMER?.get(this) as? Timer } catch (_: Exception) { null }

    /** One of JediTerm's timer callbacks, catching what it throws into [failures]. */
    private class GuardedTimerListener(private val target: ActionListener, private val failures: RepeatedFailureLog) : ActionListener {
        override fun actionPerformed(event: ActionEvent) {
            try { target.actionPerformed(event) } catch (error: Throwable) {
                rethrowIfFatal(error)
                failures.failed(error)
            }
        }
    }

    /** Run [block], logging what it throws: an AWT-thread escape ends the app. */
    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            rethrowIfFatal(error)
            log(LogLevel.Error, TAG, "The terminal failed on $what", error)
        }
    }

    private companion object {
        val REPAINT_TIMER: Field? = try {
            TerminalPanel::class.java.getDeclaredField("myRepaintTimer").apply { isAccessible = true }
        } catch (error: Exception) { null }
    }
}

/**
 * Logs a failure that may repeat on every tick: the first in full, then at most one count per
 * [periodNanos]. One thread at a time (the AWT thread, for the repaint timer).
 */
internal class RepeatedFailureLog(
    private val what: String,
    private val periodNanos: Long = TimeUnit.MINUTES.toNanos(1),
    private val clock: () -> Long = System::nanoTime,
    private val sink: (String, Throwable?) -> Unit = { message, error -> log(LogLevel.Error, TAG, message, error) },
) {
    private var reported = false
    private var reportedAt = 0L
    private var since = 0

    /** Record [error]: in full the first time, then counted into a summary once a period has passed. */
    fun failed(error: Throwable) {
        val now = clock()
        if (!reported) {
            reported = true
            reportedAt = now
            sink("$what failed", error)
            return
        }
        since++
        if (now - reportedAt < periodNanos) return
        sink("$what failed $since more times since its last report; the latest: ${error.javaClass.name}: ${error.message}", null)
        since = 0
        reportedAt = now
    }
}

/** JediTerm's emulator and debounce threads, as daemon threads that log what escapes them. JediTerm's own are neither. */
private class DaemonTerminalExecutors : TerminalExecutorServiceManager {
    private val fast: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-terminal-fast"))
    private val emulators: ExecutorService = Executors.newCachedThreadPool(loggingDaemonThreads("hovel-terminal-emulator"))
    override fun getSingleThreadScheduledExecutor(): ScheduledExecutorService = fast
    override fun getUnboundedExecutorService(): ExecutorService = emulators
    override fun shutdownWhenAllExecuted() {
        if (!fast.isShutdown) fast.execute(fast::shutdown)
        emulators.shutdown()
    }
}

private class HovelTermWidget(size: TermSize, settings: SettingsProvider) : JediTermWidget(size.columns, size.rows, settings) {
    override fun createTerminalPanel(settings: SettingsProvider, style: StyleState, buffer: TerminalTextBuffer): TerminalPanel =
        HovelTerminalPanel(settings, buffer, style)

    override fun createExecutorServiceManager(): TerminalExecutorServiceManager = DaemonTerminalExecutors()
}

/**
 * One tab's terminal, as long as its tab lives: a JediTerm widget reading from [connector], already
 * attached to the session. AWT thread only, except [screenText]. [onShortcut] gets the window shortcuts
 * pressed in it; [onTitle] the latest title the program set (OSC 0 or 2); [confirmPaste] asks before a
 * paste that could run line by line, naming the tab by [connector]'s name, its kind. All on the AWT thread.
 */
internal class TerminalView(
    private val connector: SessionTtyConnector,
    size: TermSize,
    fontSize: Int = TerminalFont.DEFAULT_SIZE,
    onShortcut: (WorkspaceAction) -> Unit = {},
    onTitle: (String) -> Unit = {},
    confirmPaste: (NativeDialog<Boolean>) -> Boolean = { false },
) {
    private val settings = HovelTerminalSettings(fontSize)
    private val widget: JediTermWidget = HovelTermWidget(size, settings)
    private val guarded = widget.terminalPanel as HovelTerminalPanel
    /** The latest title waiting for the AWT thread, or null when none waits. */
    private val pendingTitle = AtomicReference<String?>(null)

    init {
        guarded.onShortcut = onShortcut
        guarded.confirmPaste = confirmPaste
        guarded.pasteTab = connector.name
        // One AWT hop per burst.
        widget.terminal.addApplicationTitleListener { title ->
            if (pendingTitle.getAndSet(title) == null) onAwt { pendingTitle.getAndSet(null)?.let(onTitle) }
        }
        widget.setTtyConnector(connector)
        widget.start()
    }

    /** Draw at [size] points: the window's zoom. */
    fun setFontSize(size: Int) {
        if (settings.fontSize == size) return
        settings.fontSize = size
        guarded.refreshFont()
    }

    /** Development probe only. Called on the AWT thread after each paint, with the screen text it drew. */
    var paintObserver: ((String) -> Unit)?
        get() = guarded.paintObserver
        set(value) { guarded.paintObserver = value }

    val component: JComponent get() = widget

    /** Development probe and tests. The panel JediTerm draws into, for snapshots and synthetic events. */
    val panel: TerminalPanel get() = widget.terminalPanel

    /** Development probe and tests. The centre of the cell at zero-based [column] and [row], in the panel's points. */
    fun cellCenter(column: Int, row: Int): java.awt.Point {
        val cell = guarded.cell
        return java.awt.Point(column * cell.width + cell.width / 2, row * cell.height + cell.height / 2)
    }

    /** Give this terminal the keyboard. */
    fun focus() {
        widget.requestFocusInWindow()
    }

    /** Development probe and tests. The visible screen as text, one line per row. */
    fun screenText(): String = widget.terminalTextBuffer.let { buffer ->
        buffer.lock()
        try { buffer.getScreenLines() } finally { buffer.unlock() }
    }

    /** Stop drawing and detach from the session. Closing the session is the window's call. */
    fun close() {
        try { widget.close() } catch (error: Throwable) {
            rethrowIfFatal(error)
            log(LogLevel.Error, TAG, "Could not close a terminal", error)
        }
    }
}

/**
 * Every tab's [TerminalView] in one Swing panel, showing the one named by [show]. One [SwingPanel]
 * per window: hidden tabs keep their widget and screen. AWT thread only.
 */
internal class TerminalDeck {
    private val cards = CardLayout()
    val panel = JPanel(cards).apply { background = Color(fgInk950.toArgb()) }
    private val views = LinkedHashMap<String, TerminalView>()

    /** Hold [view] as tab [id]'s terminal, closing one it replaces. */
    fun add(id: String, view: TerminalView) {
        views[id]?.close()
        views[id] = view
        panel.add(view.component, id)
        panel.revalidate()
    }

    /** Close and drop tab [id]'s terminal. */
    fun remove(id: String) {
        val view = views.remove(id) ?: return
        if (showing == id) showing = null
        panel.remove(view.component)
        view.close()
        panel.revalidate()
        panel.repaint()
    }

    private var showing: String? = null

    /** Development probe and tests. Tab [id]'s terminal, when it has one. */
    fun view(id: String): TerminalView? = views[id]

    /** Every tab's terminal at [size] points. */
    fun setFontSize(size: Int) {
        views.values.forEach { it.setFontSize(size) }
    }

    /** Show tab [id] and give it the keyboard. Repeated calls for the tab already showing do nothing. */
    fun show(id: String) {
        val view = views[id] ?: return
        if (showing == id) return
        showing = id
        cards.show(panel, id)
        // Focus once the swap is laid out.
        onAwt { view.focus() }
    }

    /** Close and drop every terminal. */
    fun closeAll() {
        views.values.forEach { it.close() }
        views.clear()
        showing = null
        panel.removeAll()
    }
}

/** The window's terminal area: [deck] in Compose, showing tab [selected]. */
@Composable
internal fun TerminalPane(deck: TerminalDeck, selected: String?, modifier: Modifier = Modifier) {
    // The deck paints the background itself.
    SwingPanel(
        factory = { deck.panel },
        modifier = modifier,
        update = { selected?.let(deck::show) },
    )
}
