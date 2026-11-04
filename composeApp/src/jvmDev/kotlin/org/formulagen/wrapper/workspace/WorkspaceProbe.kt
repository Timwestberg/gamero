package org.formulagen.wrapper.workspace

import com.jediterm.terminal.ui.TerminalPanel
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.util.loggingDaemonThreads

/*
 * The workspace probe, a lasting development tool: HOVEL_WORKSPACE_PROBE names a steps file that
 * drives the HOVEL_WORKSPACE_FOLDER window and answers its questions, for checks with no one at the
 * screen (docs/desktop.md, "The probe"). It is in src/jvmDev, which a release never compiles, and a
 * release build fails if any of it reaches the app (release-excluded.txt).
 */

private const val TAG = "workspace-probe"

/**
 * The probe's answers to the workspace's questions: the sheet until an `answer` step sets one.
 * Built before [WorkspaceWindows], which asks through it, so production code never swaps confirmers.
 */
internal class ProbeConfirmer(private val sheet: Confirmer = SheetConfirmer) : Confirmer {
    /** The `answer` step's choice, or null to ask with the sheet. */
    @Volatile var answer: Boolean? = null
    /** Where each answered question is logged: the probe's log once it starts. */
    @Volatile var record: (String) -> Unit = {}

    override fun confirm(owner: Window?, question: NativeDialog<Boolean>): Boolean {
        val yes = answer ?: return sheet.confirm(owner, question)
        record("  asked \"${question.title}\": ${question.lines.joinToString(" | ")} -> ${question.buttons.first { it.second == yes }.first}")
        return yes
    }
}

/** A [ProbeConfirmer] when HOVEL_WORKSPACE_PROBE names a steps file, else null. */
internal fun probeConfirmer(environment: Map<String, String>): ProbeConfirmer? =
    environment["HOVEL_WORKSPACE_PROBE"]?.takeIf { it.isNotBlank() }?.let { ProbeConfirmer() }

/**
 * Runs a steps file against one workspace window, one step per line (`#` starts a comment):
 *
 * - `send <text>`: bytes to the selected tab's session, as typed. Escapes: `\r \n \t \e \\ \xNN`.
 * - `click <column> <row>`: a left click on that cell (zero-based), through JediTerm's mouse handling.
 * - `key <mods>+<char>`: a key press, release and typed event on the selected terminal, through
 *   JediTerm's own key handling and the window shortcuts. Mods: `meta`, `ctrl`, `alt`, `shift`,
 *   joined with `+`. `<char>` is one character, or `tab`, `]` or `[`.
 * - `ime <text>`: committed input-method text on the selected terminal, as macOS sends a dead key's
 *   result (⌥e then e is `ime é`).
 * - `wait <ms>`; `await <ms> <text>`: wait until the selected screen shows the text.
 * - `snap <name>`: the selected tab's state, busy state and screen as `<name>.txt`, and its terminal
 *   painted at the screen's scale (2 on Retina) as `<name>.png`, beside the steps file.
 * - `tab <shell|tool id>`: open a tab (`shell`, or a catalog id such as `neovim`, `lazygit` or
 *   `yazi`) and select it. `select <index>`. `close`: close the selected tab at once.
 * - `answer <yes|no>`: from now on, answer the workspace's questions so, logging each one. The
 *   folder opens at the first step other than `answer` and `wait`, so `answer yes` first answers
 *   its trust question; without one, the sheet asks.
 * - `request-close`: ⌘W's path for the selected tab; `request-close-window`: the close button's;
 *   `request-quit`: ⌘Q's.
 * - `size <width>x<height>`: resize the window, in points.
 * - `run <timeout ms> <command>`: type the command at the selected Shell tab's prompt, followed by
 *   a marker, and log how long until the marker shows on the screen: the time to run it with the
 *   terminal drawing its output.
 * - `typing <count>`: type that many letters at the prompt, one at a time through JediTerm's key
 *   handling, and log the time from each key event to the first paint that shows its echo; then
 *   clear the line with ⌃U.
 * - `cpu <ms> <label>`: log this app's CPU use over that time, and how late the AWT thread runs a
 *   task posted to it (sampled every 50 ms).
 * - `quit`: end the app.
 *
 * Each step and its result go to `probe.log` beside the steps file.
 */
internal class WorkspaceProbe(
    private val workspaces: WorkspaceWindows,
    private val folder: Path,
    private val steps: Path,
    private val confirmer: ProbeConfirmer,
    private val exit: () -> Unit,
    private val requestQuit: () -> Unit,
) {
    private val out: Path = steps.toAbsolutePath().parent
    private val logFile: Path = out.resolve("probe.log")
    private var markers = 0
    /** [folder]'s window, once [openFolder] has opened it. */
    private var opened: WorkspaceWindowState? = null
    private val window: WorkspaceWindowState get() = checkNotNull(opened) { "The workspace is not open" }

    fun start() {
        confirmer.record = ::record
        loggingDaemonThreads("hovel-workspace-probe").newThread(::run).start()
    }

    private fun run() {
        Files.writeString(logFile, "")
        try {
            for (raw in Files.readAllLines(steps)) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                record("> $line")
                step(line.substringBefore(' '), line.substringAfter(' ', ""))
            }
            if (opened == null) openFolder()
            record("done")
        } catch (error: Exception) {
            record("failed: ${error.javaClass.simpleName}: ${error.message}")
            log(LogLevel.Error, TAG, "The workspace probe failed", error)
        }
    }

    /** Open [folder] through the windows, so its trust question goes to [confirmer]. */
    private fun openFolder() {
        opened = awtValue { workspaces.open(folder) } ?: error("The workspace was not opened: its folder was not trusted")
        record("font ${TerminalFont.family} ${window.fontSize}pt")
    }

    private fun step(command: String, argument: String) {
        if (opened == null && command != "answer" && command != "wait") openFolder()
        when (command) {
            "send" -> awt { connector().write(unescape(argument).toByteArray(Charsets.UTF_8)) }
            "key" -> awt { key(argument) }
            "ime" -> awt { ime(argument) }
            "click" -> awt { click(argument.split(' ').map(String::toInt)) }
            "wait" -> Thread.sleep(argument.toLong())
            "await" -> await(argument.substringBefore(' ').toLong(), argument.substringAfter(' '))
            "snap" -> snap(argument)
            "tab" -> awt { window.addTab(launchNamed(argument)) }
            "select" -> awt { window.select(window.tabs[argument.toInt()]) }
            "close" -> awt { window.selectedTab()?.let(window::closeTab) }
            "answer" -> answer(argument == "yes")
            "request-close" -> awt { window.selectedTab()?.let(window::requestCloseTab) }
            "request-close-window" -> awt { workspaces.requestClose(window) }
            "request-quit" -> awt { requestQuit() }
            "size" -> awt {
                val (width, height) = argument.split('x').map(String::toInt)
                requireNotNull(window.awtWindow) { "The window is not showing" }.setSize(width, height)
            }
            "run" -> runTimed(argument.substringBefore(' ').toLong(), argument.substringAfter(' '))
            "typing" -> typing(argument.toInt())
            "cpu" -> cpu(argument.substringBefore(' ').toLong(), argument.substringAfter(' ', ""))
            "quit" -> exit()
            else -> error("Unknown step: $command")
        }
    }

    private fun answer(yes: Boolean) {
        confirmer.answer = yes
    }

    private fun await(timeoutMs: Long, text: String) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (text in screen()) { record("  seen \"$text\""); return }
            Thread.sleep(100)
        }
        record("  TIMEOUT waiting for \"$text\"")
    }

    /** Type [command] and a marker at the prompt; the time until the marker shows is the run's. */
    private fun runTimed(timeoutMs: Long, command: String) {
        val view = awtValue { view() }
        val connector = awtValue { connector() }
        val marker = "PROBE_${++markers}_DONE"
        // The marker reaches the screen only through %s, so the typed line never matches it.
        val line = "$command; printf 'PROBE_%s_DONE\\n' $markers\r"
        val started = System.nanoTime()
        awt { connector.write(line.toByteArray(Charsets.UTF_8)) }
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            // Off the AWT thread: the text buffer has its own lock.
            if (marker in view.screenText()) {
                record("  ran in ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms")
                return
            }
            Thread.sleep(10)
        }
        record("  TIMEOUT after $timeoutMs ms")
    }

    /** Key event to the first paint showing its echo, per letter: the paint reports the text it drew. */
    private fun typing(count: Int) {
        val view = awtValue { view() }
        val connector = awtValue { connector() }
        val letters = "hovelprobetyping"
        // A prefix no screen holds yet, so each key's echo is told apart.
        val typed = StringBuilder("zq")
        for (char in typed) { awt { typeKey(view, char) } }
        awaitScreen(view, typed.toString(), 5_000)
        val latencies = mutableListOf<Long>()
        try {
            repeat(count) { index ->
                val char = letters[index % letters.length]
                typed.append(char)
                val expected = typed.toString()
                val shown = java.util.concurrent.CompletableFuture<Long>()
                view.paintObserver = { drawn -> if (expected in drawn) shown.complete(System.nanoTime()) }
                val started = System.nanoTime()
                awt { typeKey(view, char) }
                val painted = try { shown.get(5, TimeUnit.SECONDS) } catch (_: java.util.concurrent.TimeoutException) {
                    record("  TIMEOUT waiting for a paint showing key ${index + 1}"); return
                }
                latencies += painted - started
                Thread.sleep(30)
            }
        } finally {
            view.paintObserver = null
            awt { connector.write(byteArrayOf(0x15)) }
        }
        val sorted = latencies.sorted().map { it / 1_000 / 1_000.0 }
        fun at(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()]
        record("  key to paint over ${sorted.size} keys: median %.1f ms, p90 %.1f ms, max %.1f ms".format(at(0.5), at(0.9), sorted.last()))
    }

    private fun awaitScreen(view: TerminalView, text: String, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (text in view.screenText()) return true
            Thread.sleep(0, 200_000)
        }
        return false
    }

    private fun cpu(millis: Long, label: String) {
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val cpuBefore = os?.processCpuTime ?: -1
        val started = System.nanoTime()
        var worstAwt = 0L
        var samples = 0
        var lateSamples = 0
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(millis)
        while (System.nanoTime() < deadline) {
            val posted = System.nanoTime()
            val ran = java.util.concurrent.CompletableFuture<Long>()
            EventQueue.invokeLater { ran.complete(System.nanoTime()) }
            val late = ran.get(10, TimeUnit.SECONDS) - posted
            worstAwt = maxOf(worstAwt, late)
            if (late > TimeUnit.MILLISECONDS.toNanos(100)) lateSamples++
            samples++
            Thread.sleep(50)
        }
        val wall = System.nanoTime() - started
        val cpu = (os?.processCpuTime ?: -1) - cpuBefore
        record("  $label: app CPU %.1f%% of one core over %d ms; AWT thread at most %.1f ms late, %d of %d samples over 100 ms"
            .format(if (cpuBefore < 0) Double.NaN else cpu * 100.0 / wall, TimeUnit.NANOSECONDS.toMillis(wall), worstAwt / 1e6, lateSamples, samples))
    }

    private fun snap(name: String) {
        var text = ""
        var image: BufferedImage? = null
        val tab = awtValue { window.selectedTab() }
        // Off the AWT thread: Neovim's answer can take a moment.
        val busy = tab?.session?.busy()?.let(Confirmations::reason) ?: "none"
        awt {
            val view = view()
            val tree = tab?.session?.processes()?.joinToString { "${it.pid()}:${it.info().command().orElse("?")}" }
            text = "tab=${tab?.title} label=${tab?.label} state=${tab?.state} busy=$busy focused=${view.panel.isFocusOwner} font=${TerminalFont.family} ${window.fontSize}pt tree=[$tree]\n" + view.screenText()
            val panel = view.panel
            // At the screen's scale, as Swing's back buffer draws it on a Retina display.
            val scale = panel.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
            image = BufferedImage((panel.width * scale).toInt().coerceAtLeast(1), (panel.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB).also { target ->
                val graphics = target.createGraphics()
                try { graphics.scale(scale, scale); panel.paint(graphics) } finally { graphics.dispose() }
            }
        }
        Files.writeString(out.resolve("$name.txt"), text)
        image?.let { ImageIO.write(it, "png", out.resolve("$name.png").toFile()) }
        record("  wrote $name.txt and $name.png (${image?.width}x${image?.height})")
    }

    private fun key(spec: String) {
        val parts = spec.split('+')
        val last = parts.last()
        val modifiers = parts.dropLast(1).fold(0) { mask, name ->
            mask or when (name) {
                "meta" -> InputEvent.META_DOWN_MASK
                "ctrl" -> InputEvent.CTRL_DOWN_MASK
                "alt" -> InputEvent.ALT_DOWN_MASK
                "shift" -> InputEvent.SHIFT_DOWN_MASK
                else -> error("Unknown modifier: $name")
            }
        }
        val char = if (last == "tab") '\t' else last.single()
        val code = when (char) {
            '\t' -> KeyEvent.VK_TAB
            ']' -> KeyEvent.VK_CLOSE_BRACKET
            '[' -> KeyEvent.VK_OPEN_BRACKET
            '=' -> KeyEvent.VK_EQUALS
            '-' -> KeyEvent.VK_MINUS
            else -> KeyEvent.getExtendedKeyCodeForChar(char.code)
        }
        deliver(view().panel, code, char, modifiers)
    }

    private fun typeKey(view: TerminalView, char: Char) = deliver(view.panel, KeyEvent.getExtendedKeyCodeForChar(char.code), char, 0)

    /**
     * Press, typed and release, straight to the terminal's key handler, as the focused terminal gets
     * them: AWT's focus manager would drop synthetic keys while the window is not the active one.
     */
    private fun deliver(panel: TerminalPanel, code: Int, char: Char, modifiers: Int) {
        val now = System.currentTimeMillis()
        panel.processKeyEvent(KeyEvent(panel, KeyEvent.KEY_PRESSED, now, modifiers, code, char))
        panel.processKeyEvent(KeyEvent(panel, KeyEvent.KEY_TYPED, now, modifiers, KeyEvent.VK_UNDEFINED, char))
        panel.processKeyEvent(KeyEvent(panel, KeyEvent.KEY_RELEASED, now, modifiers, code, char))
    }

    /** Committed text from the input method, as macOS sends the result of a dead key or an IME. */
    private fun ime(text: String) {
        val panel = view().panel
        val attributed = java.text.AttributedString(text).iterator
        panel.dispatchEvent(java.awt.event.InputMethodEvent(panel, java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, attributed, text.length, null, null))
    }

    /** A left click on the cell at zero-based column and row, through JediTerm's mouse handling. */
    private fun click(cell: List<Int>) {
        val view = view()
        val point = view.cellCenter(cell[0], cell[1])
        val panel = view.panel
        val now = System.currentTimeMillis()
        for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            val mask = if (id == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON1_DOWN_MASK else 0
            panel.dispatchEvent(MouseEvent(panel, id, now, mask, point.x, point.y, 1, false, MouseEvent.BUTTON1))
        }
    }

    private fun screen(): String {
        var text = ""
        awt { text = window.selectedTab()?.let { window.deck.view(it.id) }?.screenText().orEmpty() }
        return text
    }

    private fun view(): TerminalView = requireNotNull(window.selectedTab()?.let { window.deck.view(it.id) }) { "The selected tab has no terminal yet" }

    /** The selected tab's connector: bytes written to it reach the session as typed, past JediTerm's key handling. */
    private fun connector(): SessionTtyConnector = requireNotNull(window.selectedTab()?.connector) { "The selected tab has no terminal yet" }

    private fun launchNamed(name: String): Launch =
        if (name == "shell") Launch.Shell
        else Launch.Tool(WorkspaceTool.entries.singleOrNull { it.id == name } ?: error("Unknown tab: $name"))

    private fun awt(block: () -> Unit) {
        var failure: Throwable? = null
        EventQueue.invokeAndWait { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun <T> awtValue(block: () -> T): T {
        var value: Result<T>? = null
        EventQueue.invokeAndWait { value = runCatching(block) }
        return value!!.getOrThrow()
    }

    private fun record(line: String) {
        Files.writeString(logFile, line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
}

/** The probe's `send` escapes: `\r \n \t \e \\ \xNN`. */
internal fun unescape(text: String): String = buildString {
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char != '\\' || index == text.length - 1) { append(char); index++; continue }
        when (val next = text[index + 1]) {
            'r' -> append('\r')
            'n' -> append('\n')
            't' -> append('\t')
            'e' -> append('\u001b')
            '\\' -> append('\\')
            'x' -> { append(text.substring(index + 2, index + 4).toInt(16).toChar()); index += 2 }
            else -> { append('\\'); append(next) }
        }
        index += 2
    }
}
