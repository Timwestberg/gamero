package org.formulagen.wrapper.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toArgb
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.ui.settings.SettingsProvider
import java.awt.CardLayout
import java.awt.Color
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
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.ui.fgBlue500
import org.formulagen.wrapper.ui.fgInk300
import org.formulagen.wrapper.ui.fgInk950
import org.formulagen.wrapper.util.loggingDaemonThreads

private const val TAG = "workspace-terminal"

/**
 * JediTerm's settings for workspace tabs: Hovel's colors, the scrollback guard, mouse reporting, ⌘C
 * and ⌘V, the terminal font at [fontSize] points, and, as in Terminal, Option typing characters.
 * The cursor's period stays JediTerm's: the panel draws every cursor shape steady ([steadyCursor]).
 */
internal open class HovelTerminalSettings(@Volatile var fontSize: Int = TerminalFont.DEFAULT_SIZE) : DefaultSettingsProvider() {
    override fun getBufferMaxLinesCount(): Int = WorkspaceGuards.SCROLLBACK_LINES
    override fun enableMouseReporting(): Boolean = true
    override fun getTerminalFont(): Font = Font(TerminalFont.family, Font.PLAIN, fontSize)
    override fun getTerminalFontSize(): Float = fontSize.toFloat()
    // Option types characters (⌥e e is é), as in Terminal with "Use Option as Meta key" off. ⌥← and
    // ⌥→ still move by word: JediTerm sends ESC b and ESC f for them on a Mac either way.
    override fun altSendsEscape(): Boolean = false
    override fun getDefaultForeground(): TerminalColor = terminalColor(fgInk300.toArgb())
    override fun getDefaultBackground(): TerminalColor = terminalColor(fgInk950.toArgb())
    // Deprecated in JediTerm, but JediTermWidget 3.76 still builds its default cell style from it
    // (black on white unless overridden), so erased cells would paint white.
    @Deprecated("JediTerm still reads it; keep it in step with the two colors above")
    override fun getDefaultStyle(): TextStyle = TextStyle(getDefaultForeground(), getDefaultBackground())
    override fun getSelectionColor(): TextStyle = TextStyle(TerminalColor.WHITE, terminalColor(fgBlue500.toArgb()))
    // DefaultSettingsProvider binds Copy to ⌘C and Paste to ⌘V on macOS (getCopyActionPresentation).
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

    internal fun resolveFamily(available: Set<String>): String = if (PREFERRED in available) PREFERRED else FALLBACK

    private fun availableFamilies(): Set<String> = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    } catch (error: Throwable) {
        rethrowIfFatal(error)
        emptySet()
    }

    /** [size] moved by [steps] points, within the limits. */
    fun zoomed(size: Int, steps: Int): Int = (size + steps).coerceIn(MIN_SIZE, MAX_SIZE)
}

private fun terminalColor(argb: Int): TerminalColor = Color(argb).let { TerminalColor.rgb(it.red, it.green, it.blue) }

/** [shape] without its blink: a steady cursor, as Terminal's default profile has, never repainted to blink. */
internal fun steadyCursor(shape: CursorShape): CursorShape = when (shape) {
    CursorShape.BLINK_BLOCK -> CursorShape.STEADY_BLOCK
    CursorShape.BLINK_UNDERLINE -> CursorShape.STEADY_UNDERLINE
    CursorShape.BLINK_VERTICAL_BAR -> CursorShape.STEADY_VERTICAL_BAR
    CursorShape.STEADY_BLOCK, CursorShape.STEADY_UNDERLINE, CursorShape.STEADY_VERTICAL_BAR -> shape
}

/**
 * A [TerminalPanel] whose AWT-thread entry points catch and log: its event handlers, its paint, and
 * the callbacks of JediTerm's own repaint timer (cursor blink, scrolling, repaint), which it
 * re-registers wrapped ([guardRepaintTimer]). An exception escaping to the event thread reaches the
 * app's default handler, which ends Hovel.
 *
 * Key presses that are window shortcuts ([workspaceShortcut]) go to [onShortcut] instead of the terminal.
 */
internal class HovelTerminalPanel(settings: SettingsProvider, buffer: TerminalTextBuffer, style: StyleState) :
    TerminalPanel(settings, buffer, style) {
    /** Development probe and tests. One character cell, in points. */
    val cell: java.awt.Dimension get() = java.awt.Dimension(myCharSize)

    /** A window shortcut was pressed in this terminal. AWT thread. */
    @Volatile var onShortcut: (WorkspaceAction) -> Unit = {}

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

    /** Development probe only: called after each paint with the screen text that paint drew. AWT thread. */
    @Volatile var paintObserver: ((String) -> Unit)? = null

    /**
     * JediTerm paints while holding its text buffer's lock, and a paint straight to the screen can
     * wait on the Metal pipeline: the JDK presents each frame on the AppKit thread under the AWT
     * lock, and waits up to a second for a drawable while the window is not on screen (the Mac is
     * locked, say). The emulator, and through back-pressure the tab's own process, would wait with it.
     * So JediTerm paints into an image in memory, which never waits on the screen, and the image
     * goes to the screen after the lock is released.
     */
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
                // The text this paint draws, read under the same lock.
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
        // ⌘ is never terminal input on a Mac. JediTerm runs its ⌘ actions (copy, paste, clear, find)
        // on the key press, but would type a ⌘-letter's character ("t" for an unbound ⌘T) on the typed event.
        if (e.id == KeyEvent.KEY_TYPED && (e.isMetaDown || shortcutTyped)) { shortcutTyped = false; e.consume() }
        else super.processKeyEvent(e)
    }
    override fun processMouseEvent(e: MouseEvent) = guard("mouse") { super.processMouseEvent(e) }
    override fun processMouseMotionEvent(e: MouseEvent) = guard("mouse motion") { super.processMouseMotionEvent(e) }
    override fun processMouseWheelEvent(e: MouseWheelEvent) = guard("mouse wheel") { super.processMouseWheelEvent(e) }
    override fun processInputMethodEvent(e: InputMethodEvent) = guard("input method") { super.processInputMethodEvent(e) }

    /**
     * JediTerm drives the cursor blink, scrolling and repaint from a Swing timer of its own
     * (`TerminalPanel.myRepaintTimer`, created in [init]), whose callback catches only the repaint
     * call. No override reaches it, so its listeners are re-registered here, each wrapped in [guard].
     * The listener stays JediTerm's weak one, so the panel can still be collected. If a JediTerm
     * update renames the field, this logs and leaves the timer as it is; [TerminalTimerGuardTest] fails.
     */
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
 * One tab's terminal: a JediTerm widget reading from [connector], which is already attached to the
 * tab's session (output waits in it until the widget starts). It lives as long as its tab, whether
 * or not the tab is showing, so switching tabs keeps each screen. AWT thread only, except
 * [screenText]. [onShortcut] gets the window shortcuts pressed in it, on the AWT thread; [onTitle]
 * gets the latest title the program set (OSC 0 or 2), on the AWT thread, once per hop to it.
 */
internal class TerminalView(
    private val connector: SessionTtyConnector,
    size: TermSize,
    fontSize: Int = TerminalFont.DEFAULT_SIZE,
    onShortcut: (WorkspaceAction) -> Unit = {},
    onTitle: (String) -> Unit = {},
) {
    private val settings = HovelTerminalSettings(fontSize)
    private val widget: JediTermWidget = HovelTermWidget(size, settings)
    private val guarded = widget.terminalPanel as HovelTerminalPanel
    /** The latest title waiting for the AWT thread, or null when none waits. */
    private val pendingTitle = AtomicReference<String?>(null)

    init {
        guarded.onShortcut = onShortcut
        // One hop to the AWT thread per burst.
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

    /** Development probe only. Bytes to the session as if typed, past JediTerm's key handling. */
    fun send(bytes: ByteArray) = connector.write(bytes)

    val component: JComponent get() = widget

    /** Development probe and tests. The panel JediTerm draws into, for snapshots and synthetic events. */
    val panel: TerminalPanel get() = widget.terminalPanel

    /** Development probe and tests. The centre of the cell at zero-based [column] and [row], in the panel's points. */
    fun cellCenter(column: Int, row: Int): java.awt.Point {
        val cell = guarded.cell
        return java.awt.Point(column * cell.width + cell.width / 2, row * cell.height + cell.height / 2)
    }

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

    fun add(id: String, view: TerminalView) {
        views[id]?.close()
        views[id] = view
        panel.add(view.component, id)
        panel.revalidate()
    }

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
        // After the card swap is laid out, so the newly visible panel can take focus.
        onAwt { view.focus() }
    }

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
    // The deck panel paints Hovel's terminal background itself (TerminalDeck.panel).
    SwingPanel(
        factory = { deck.panel },
        modifier = modifier,
        update = { selected?.let(deck::show) },
    )
}
