package org.formulagen.wrapper.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toArgb
import com.jediterm.core.util.TermSize as JediTermSize
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.ui.settings.SettingsProvider
import java.awt.CardLayout
import java.awt.Color
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.ui.fgBlue500
import org.formulagen.wrapper.ui.fgInk300
import org.formulagen.wrapper.ui.fgInk950
import org.formulagen.wrapper.util.loggingDaemonThreads

private const val TAG = "workspace-terminal"

/** Scrollback per terminal tab. Moves to `WorkspaceGuards.SCROLLBACK_LINES` when `WorkspaceLimits.kt` lands. */
private const val SCROLLBACK_LINES = 10_000

/** Output chunks (up to [PTY_READ_BYTES] each) waiting for the emulator. A full queue holds the reader, and so the process. */
private const val QUEUED_CHUNKS = 64

/**
 * Adapts a [Session]'s pushed output to the pull-based [TtyConnector] JediTerm reads from.
 *
 * The session's reader thread hands over bytes ([output]); JediTerm's emulator thread pulls
 * characters ([read]). Decoding happens on the emulator thread with one stateful UTF-8 decoder, so
 * a character split across two reads stays whole and malformed bytes become U+FFFD. Keys and
 * resizes go to the session's [Attachment]. [onExit] runs once, on the session's exit thread.
 */
internal class SessionTtyConnector(private val name: String, private val onExit: (Int) -> Unit = {}) : TtyConnector, SessionViewer {
    private val chunks = ArrayBlockingQueue<ByteArray>(QUEUED_CHUNKS)
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    /** The start of a character whose remaining bytes have not arrived yet. Emulator thread only. */
    private var carried: ByteBuffer = EMPTY_BYTES
    /** Decoded characters JediTerm has not read yet. Emulator thread only. */
    private var decoded: CharBuffer = EMPTY_CHARS
    private var flushed = false
    private val ended = CountDownLatch(1)
    @Volatile private var exitCode = 0
    private val closed = AtomicBoolean(false)
    @Volatile private var attachment: Attachment? = null

    /** Attach to [session]. Output from now on reaches JediTerm. */
    fun connect(session: Session) {
        attachment = session.attach(this)
    }

    override fun output(bytes: ByteArray, length: Int) {
        val copy = bytes.copyOf(length)
        while (!closed.get()) {
            if (chunks.offer(copy, POLL_MS, MILLISECONDS)) return
        }
    }

    override fun exited(code: Int) {
        if (ended.count == 0L) return
        exitCode = code
        ended.countDown()
        try { onExit(code) } catch (error: Exception) { log(LogLevel.Error, TAG, "The exit handler of $name failed", error) }
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        while (true) {
            if (decoded.hasRemaining()) {
                val count = minOf(length, decoded.remaining())
                decoded.get(buf, offset, count)
                return count
            }
            val chunk = nextChunk()
            if (chunk != null) decode(chunk, endOfInput = false)
            else if (!flushed) { flushed = true; decode(ByteArray(0), endOfInput = true) }
            else return -1
        }
    }

    /** The next chunk, or null once the session has exited (or the connector closed) and nothing is left. */
    private fun nextChunk(): ByteArray? {
        while (true) {
            chunks.poll()?.let { return it }
            if (closed.get() || ended.count == 0L) return null
            chunks.poll(POLL_MS, MILLISECONDS)?.let { return it }
        }
    }

    private fun decode(chunk: ByteArray, endOfInput: Boolean) {
        val input = if (carried.hasRemaining()) {
            ByteBuffer.allocate(carried.remaining() + chunk.size).put(carried).put(chunk).flip()
        } else ByteBuffer.wrap(chunk)
        // UTF-8 never decodes to more characters than bytes, plus one replacement for a cut-off end.
        val output = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, output, endOfInput)
        if (endOfInput) decoder.flush(output)
        carried = if (input.hasRemaining()) ByteBuffer.allocate(input.remaining()).put(input).flip() else EMPTY_BYTES
        decoded = output.flip()
    }

    override fun write(bytes: ByteArray) {
        try { attachment?.input(bytes) } catch (error: Exception) { log(LogLevel.Error, TAG, "Could not send keys to $name", error) }
    }

    override fun write(string: String) = write(string.toByteArray(Charsets.UTF_8))

    override fun resize(termSize: JediTermSize) {
        try { attachment?.resize(TermSize(termSize.columns, termSize.rows)) } catch (error: Exception) {
            log(LogLevel.Error, TAG, "Could not resize $name", error)
        }
    }

    override fun isConnected(): Boolean = !closed.get() && ended.count > 0

    override fun waitFor(): Int {
        ended.await()
        return exitCode
    }

    override fun ready(): Boolean = decoded.hasRemaining() || chunks.isNotEmpty()

    override fun getName(): String = name

    /** Detach from the session; JediTerm calls this when its emulator stops. The session keeps running. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { attachment?.close() } catch (error: Exception) { log(LogLevel.Error, TAG, "Could not detach $name", error) }
        attachment = null
    }

    private companion object {
        const val POLL_MS = 50L
        val EMPTY_BYTES: ByteBuffer = ByteBuffer.allocate(0)
        val EMPTY_CHARS: CharBuffer = CharBuffer.allocate(0)
    }
}

/** JediTerm's settings for workspace tabs: Hovel's colors, the scrollback guard, mouse reporting, ⌘C and ⌘V. */
internal open class HovelTerminalSettings : DefaultSettingsProvider() {
    override fun getBufferMaxLinesCount(): Int = SCROLLBACK_LINES
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
    /** One character cell, in points. */
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
        try { block() } catch (error: Exception) { log(LogLevel.Error, TAG, "The terminal failed on $what", error) }
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

    /** Bytes to the session as if typed, past JediTerm's key handling. Development probes only. */
    fun send(bytes: ByteArray) = connector.write(bytes)

    val component: JComponent get() = widget

    /** The panel JediTerm draws into, for focus and for development snapshots. */
    val panel: TerminalPanel get() = widget.terminalPanel

    /** The centre of the cell at zero-based [column] and [row], in the panel's points. */
    fun cellCenter(column: Int, row: Int): java.awt.Point {
        val cell = (widget.terminalPanel as GuardedTerminalPanel).cell
        return java.awt.Point(column * cell.width + cell.width / 2, row * cell.height + cell.height / 2)
    }

    fun focus() {
        widget.requestFocusInWindow()
    }

    /** The visible screen as text, one line per row. */
    fun screenText(): String = widget.terminalTextBuffer.let { buffer ->
        buffer.lock()
        try { buffer.getScreenLines() } finally { buffer.unlock() }
    }

    /** Stop drawing and detach from the session. Closing the session is the window's call. */
    fun close() {
        try { widget.close() } catch (error: Exception) { log(LogLevel.Error, TAG, "Could not close a terminal", error) }
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

    fun view(id: String): TerminalView? = views[id]

    /** Show tab [id] and give it the keyboard. Repeated calls for the tab already showing do nothing. */
    fun show(id: String) {
        val view = views[id] ?: return
        if (showing == id) return
        showing = id
        cards.show(panel, id)
        // After the card swap is laid out, so the newly visible panel can take focus.
        EventQueue.invokeLater { try { view.focus() } catch (error: Exception) { log(LogLevel.Error, TAG, "Could not focus a terminal", error) } }
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
