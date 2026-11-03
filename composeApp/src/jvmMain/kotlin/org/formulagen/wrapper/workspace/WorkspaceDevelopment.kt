package org.formulagen.wrapper.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import java.awt.EventQueue
import java.awt.Frame
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.formulagen.wrapper.conversations.NativeFolderPicker
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.util.loggingDaemonThreads

/*
 * The terminal spike's development entry points. Only a development build has a [WorkspaceWindows]
 * (`projectWorkspaceEnabled`), so none of this exists in a release:
 *
 * - Develop → Open Workspace… in the main window picks a folder and opens a window with a Shell tab.
 * - HOVEL_WORKSPACE_FOLDER opens one at launch.
 * - HOVEL_WORKSPACE_PROBE, with HOVEL_WORKSPACE_FOLDER, runs a steps file against that window and
 *   writes screen text and images beside it: the spike's rendering and signing checks when no one
 *   is at the screen. See [WorkspaceProbe].
 */

private const val TAG = "workspace-development"

/** Develop → Open Workspace…: the native folder picker, then a window on the chosen folder. AWT thread. */
internal fun openWorkspaceFromPicker(workspaces: WorkspaceWindows, owner: Frame) {
    try {
        NativeFolderPicker { owner }.choose("Open a workspace on a folder", null)?.let(workspaces::open)
    } catch (error: Exception) {
        log(LogLevel.Error, TAG, "Could not open a workspace", error)
    }
}

/** Opens HOVEL_WORKSPACE_FOLDER at launch and runs HOVEL_WORKSPACE_PROBE against it. [exit] ends the app. */
@Composable
internal fun DevelopmentWorkspaceLaunch(workspaces: WorkspaceWindows, environment: Map<String, String>, exit: () -> Unit) {
    LaunchedEffect(Unit) {
        val folder = environment["HOVEL_WORKSPACE_FOLDER"]?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val window = try { workspaces.open(Path.of(folder)) } catch (error: Exception) {
            log(LogLevel.Error, TAG, "Could not open HOVEL_WORKSPACE_FOLDER", error)
            return@LaunchedEffect
        }
        environment["HOVEL_WORKSPACE_PROBE"]?.takeIf { it.isNotBlank() }?.let { WorkspaceProbe(window, Path.of(it), exit).start() }
    }
}

/**
 * Runs a steps file against one workspace window, one step per line (`#` starts a comment):
 *
 * - `send <text>`: bytes to the selected tab's session, as typed. Escapes: `\r \n \t \e \\ \xNN`.
 * - `click <column> <row>`: a left click on that cell (zero-based), through JediTerm's mouse handling.
 * - `key <mods>+<char>`: a key press, release and typed event on the selected terminal, through
 *   JediTerm's own key handling. Mods: `meta`, `ctrl`, `alt`, `shift`, joined with `+`.
 * - `wait <ms>`; `await <ms> <text>`: wait until the selected screen shows the text.
 * - `snap <name>`: the selected tab's state, busy flag and screen as `<name>.txt`, and its terminal
 *   painted as `<name>.png`, beside the steps file.
 * - `tab <shell|neovim|lazygit|yazi>`: open a tab and select it. `select <index>`. `close`.
 * - `size <width>x<height>`: resize the window, in points.
 * - `quit`: end the app.
 *
 * Each step and its result go to `probe.log` beside the steps file.
 */
internal class WorkspaceProbe(private val window: WorkspaceWindowState, private val steps: Path, private val exit: () -> Unit) {
    private val out: Path = steps.toAbsolutePath().parent
    private val logFile: Path = out.resolve("probe.log")

    fun start() {
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
            record("done")
        } catch (error: Exception) {
            record("failed: ${error.javaClass.simpleName}: ${error.message}")
            log(LogLevel.Error, TAG, "The workspace probe failed", error)
        }
    }

    private fun step(command: String, argument: String) {
        when (command) {
            "send" -> awt { view().send(unescape(argument).toByteArray(Charsets.UTF_8)) }
            "key" -> awt { key(argument) }
            "click" -> awt { click(argument.split(' ').map(String::toInt)) }
            "wait" -> Thread.sleep(argument.toLong())
            "await" -> await(argument.substringBefore(' ').toLong(), argument.substringAfter(' '))
            "snap" -> snap(argument)
            "tab" -> awt { window.addTab(launchNamed(argument)) }
            "select" -> awt { window.select(window.tabs[argument.toInt()]) }
            "close" -> awt { window.selectedTab()?.let(window::closeTab) }
            "size" -> awt {
                val (width, height) = argument.split('x').map(String::toInt)
                requireNotNull(window.awtWindow) { "The window is not showing" }.setSize(width, height)
            }
            "quit" -> exit()
            else -> error("Unknown step: $command")
        }
    }

    private fun await(timeoutMs: Long, text: String) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (text in screen()) { record("  seen \"$text\""); return }
            Thread.sleep(100)
        }
        record("  TIMEOUT waiting for \"$text\"")
    }

    private fun snap(name: String) {
        var text = ""
        var image: BufferedImage? = null
        awt {
            val tab = window.selectedTab()
            val view = view()
            val tree = (tab?.session as? PtySession)?.let { session ->
                ProcessHandle.of(session.pid).map { root ->
                    (listOf(root) + root.descendants().toList()).joinToString { "${it.pid()}:${it.info().command().orElse("?")}" }
                }.orElse("exited")
            }
            text = "tab=${tab?.title} state=${tab?.state} busy=${tab?.session?.busy()} focused=${view.panel.isFocusOwner} tree=[$tree]\n" + view.screenText()
            val panel = view.panel
            image = BufferedImage(panel.width.coerceAtLeast(1), panel.height.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB).also { target ->
                val graphics = target.createGraphics()
                try { panel.paint(graphics) } finally { graphics.dispose() }
            }
        }
        Files.writeString(out.resolve("$name.txt"), text)
        image?.let { ImageIO.write(it, "png", out.resolve("$name.png").toFile()) }
        record("  wrote $name.txt and $name.png")
    }

    private fun key(spec: String) {
        val parts = spec.split('+')
        val char = parts.last().single()
        val modifiers = parts.dropLast(1).fold(0) { mask, name ->
            mask or when (name) {
                "meta" -> InputEvent.META_DOWN_MASK
                "ctrl" -> InputEvent.CTRL_DOWN_MASK
                "alt" -> InputEvent.ALT_DOWN_MASK
                "shift" -> InputEvent.SHIFT_DOWN_MASK
                else -> error("Unknown modifier: $name")
            }
        }
        val panel = view().panel
        val code = KeyEvent.getExtendedKeyCodeForChar(char.code)
        val now = System.currentTimeMillis()
        panel.dispatchEvent(KeyEvent(panel, KeyEvent.KEY_PRESSED, now, modifiers, code, char))
        panel.dispatchEvent(KeyEvent(panel, KeyEvent.KEY_TYPED, now, modifiers, KeyEvent.VK_UNDEFINED, char))
        panel.dispatchEvent(KeyEvent(panel, KeyEvent.KEY_RELEASED, now, modifiers, code, char))
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

    private fun launchNamed(name: String): Launch = when (name) {
        "shell" -> Launch.Shell
        "neovim" -> Launch.Tool(WorkspaceTool.NEOVIM)
        "lazygit" -> Launch.Tool(WorkspaceTool.LAZYGIT)
        "yazi" -> Launch.Tool(WorkspaceTool.YAZI)
        else -> error("Unknown tab: $name")
    }

    private fun awt(block: () -> Unit) {
        var failure: Throwable? = null
        EventQueue.invokeAndWait { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun record(line: String) {
        Files.writeString(logFile, line + "\n", java.nio.file.StandardOpenOption.APPEND)
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
