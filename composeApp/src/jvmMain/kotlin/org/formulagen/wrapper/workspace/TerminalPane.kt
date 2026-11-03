package org.formulagen.wrapper.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toArgb
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
import java.awt.Graphics
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.swing.JComponent
import javax.swing.JPanel
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.ui.fgBlue500
import org.formulagen.wrapper.ui.fgInk300
import org.formulagen.wrapper.ui.fgInk950
import org.formulagen.wrapper.util.loggingDaemonThreads

private const val TAG = "workspace-terminal"

/** JediTerm's settings for workspace tabs: Hovel's colors, the scrollback guard, mouse reporting, ⌘C and ⌘V. */
internal open class HovelTerminalSettings : DefaultSettingsProvider() {
    override fun getBufferMaxLinesCount(): Int = WorkspaceGuards.SCROLLBACK_LINES
    override fun enableMouseReporting(): Boolean = true
    override fun getDefaultForeground(): TerminalColor = terminalColor(fgInk300.toArgb())
    override fun getDefaultBackground(): TerminalColor = terminalColor(fgInk950.toArgb())
    // Deprecated in JediTerm, but JediTermWidget 3.76 still builds its default cell style from it
    // (black on white unless overridden), so erased cells would paint white.
    @Deprecated("JediTerm still reads it; keep it in step with the two colors above")
    override fun getDefaultStyle(): TextStyle = TextStyle(getDefaultForeground(), getDefaultBackground())
    override fun getSelectionColor(): TextStyle = TextStyle(TerminalColor.WHITE, terminalColor(fgBlue500.toArgb()))
    // DefaultSettingsProvider binds Copy to ⌘C and Paste to ⌘V on macOS (getCopyActionPresentation).
}

private fun terminalColor(argb: Int): TerminalColor = Color(argb).let { TerminalColor.rgb(it.red, it.green, it.blue) }

/**
 * A [TerminalPanel] whose AWT-thread entry points catch and log. An exception escaping to the event
 * thread reaches the app's default handler, which ends Hovel.
 */
private class GuardedTerminalPanel(settings: SettingsProvider, buffer: TerminalTextBuffer, style: StyleState) :
    TerminalPanel(settings, buffer, style) {
    /** Development probe only. One character cell, in points. */
    val cell: java.awt.Dimension get() = java.awt.Dimension(myCharSize)

    override fun paintComponent(g: Graphics) = guard("paint") { super.paintComponent(g) }
    override fun processKeyEvent(e: KeyEvent) = guard("key") {
        // ⌘ is never terminal input on a Mac. JediTerm runs its ⌘ actions (copy, paste, clear, find)
        // on the key press, but would type a ⌘-letter's character ("t" for an unbound ⌘T) on the typed event.
        if (e.id == KeyEvent.KEY_TYPED && e.isMetaDown) e.consume() else super.processKeyEvent(e)
    }
    override fun processMouseEvent(e: MouseEvent) = guard("mouse") { super.processMouseEvent(e) }
    override fun processMouseMotionEvent(e: MouseEvent) = guard("mouse motion") { super.processMouseMotionEvent(e) }
    override fun processMouseWheelEvent(e: MouseWheelEvent) = guard("mouse wheel") { super.processMouseWheelEvent(e) }
    override fun processInputMethodEvent(e: InputMethodEvent) = guard("input method") { super.processInputMethodEvent(e) }

    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            rethrowIfFatal(error)
            log(LogLevel.Error, TAG, "The terminal failed on $what", error)
        }
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
        GuardedTerminalPanel(settings, buffer, style)

    override fun createExecutorServiceManager(): TerminalExecutorServiceManager = DaemonTerminalExecutors()
}

/**
 * One tab's terminal: a JediTerm widget reading from [connector], which is already attached to the
 * tab's session (output waits in it until the widget starts). It lives as long as its tab, whether
 * or not the tab is showing, so switching tabs keeps each screen. AWT thread only, except [screenText].
 */
internal class TerminalView(private val connector: SessionTtyConnector, size: TermSize) {
    private val widget: JediTermWidget = HovelTermWidget(size, HovelTerminalSettings())

    init {
        widget.setTtyConnector(connector)
        widget.start()
    }

    /** Development probe only. Bytes to the session as if typed, past JediTerm's key handling. */
    fun send(bytes: ByteArray) = connector.write(bytes)

    val component: JComponent get() = widget

    /** Development probe only. The panel JediTerm draws into, for snapshots and synthetic events. */
    val panel: TerminalPanel get() = widget.terminalPanel

    /** Development probe only. The centre of the cell at zero-based [column] and [row], in the panel's points. */
    fun cellCenter(column: Int, row: Int): java.awt.Point {
        val cell = (widget.terminalPanel as GuardedTerminalPanel).cell
        return java.awt.Point(column * cell.width + cell.width / 2, row * cell.height + cell.height / 2)
    }

    fun focus() {
        widget.requestFocusInWindow()
    }

    /** Development probe only. The visible screen as text, one line per row. */
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

    /** Development probe only. */
    fun view(id: String): TerminalView? = views[id]

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
