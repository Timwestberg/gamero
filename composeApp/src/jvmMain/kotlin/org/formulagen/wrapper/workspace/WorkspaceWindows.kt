package org.formulagen.wrapper.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.util.loggingDaemonThreads

private const val TAG = "workspace-windows"

/** Tabs per window. Moves to `WorkspaceSettingsLimits.TABS` when `WorkspaceLimits.kt` lands. */
private const val MAX_TABS = 8

/** The size a tab's process starts at. JediTerm resizes it to the pane as soon as it lays out. */
private val INITIAL_SIZE = TermSize(columns = 100, rows = 30)

/**
 * The open workspace windows (project-workspace.ARCH.md, "Window and tabs"): one per folder, each
 * rendered by `main.kt` inside the app's one Compose application. The terminal spike opens them
 * only in development builds, on a folder, with one Shell tab; projects, the profile and the other
 * tab kinds come later. [host] is built on a worker when the first tab starts (it reads the
 * directory service and writes the shell files), so a launch that opens no workspace pays nothing.
 */
internal class WorkspaceWindows(host: () -> SessionHost) : AutoCloseable {
    private val lazyHost = lazy(host)
    private val workers: ExecutorService = Executors.newCachedThreadPool(loggingDaemonThreads("hovel-workspace-window"))

    /** Windows in opening order. AWT thread. */
    val windows = mutableStateListOf<WorkspaceWindowState>()

    /** Open a window on [folder] with a Shell tab, or bring forward the one already open there. AWT thread. */
    fun open(folder: Path): WorkspaceWindowState {
        val real = folder.toRealPath()
        require(Files.isDirectory(real)) { "Not a folder: $folder" }
        windows.firstOrNull { it.folder == real }?.let { it.bringForward(); return it }
        return WorkspaceWindowState("dev-${UUID.randomUUID()}", real, lazyHost, workers).also { window ->
            windows.add(window)
            window.addTab(Launch.Shell)
        }
    }

    /** Close [window] and end every process in it. AWT thread; the processes end on a worker. */
    fun close(window: WorkspaceWindowState) {
        if (!windows.remove(window)) return
        window.deck.closeAll()
        workers.execute { lazyHost.value.closeProject(window.projectId) }
    }

    /** At app exit: end every session, waiting for them. Off the AWT thread. */
    override fun close() {
        workers.shutdown()
        if (lazyHost.isInitialized()) lazyHost.value.close()
    }
}

/** A tab's state as the window shows it. */
internal sealed interface TabState {
    data object Starting : TabState
    data object Running : TabState
    data class Failed(val message: String) : TabState
    data class Exited(val code: Int) : TabState
}

internal class WorkspaceTab(val launch: Launch, val title: String) {
    val id: String = UUID.randomUUID().toString()
    var state: TabState by mutableStateOf(TabState.Starting)
    /** True while [session] reports [Session.busy]; polled by the window. */
    var busy: Boolean by mutableStateOf(false)
    @Volatile var session: Session? = null
}

/** One workspace window: its folder, its tabs and their terminals. AWT thread unless noted. */
internal class WorkspaceWindowState(
    val projectId: String,
    val folder: Path,
    /** Read only on a worker: building it reads the directory service and writes files. */
    private val host: Lazy<SessionHost>,
    private val workers: ExecutorService,
) {
    val title: String = "${folder.fileName ?: folder} · workspace"
    val tabs = mutableStateListOf<WorkspaceTab>()
    var selected: String? by mutableStateOf(null)
    val deck = TerminalDeck()
    /** Bumped to ask the window to come to the front. */
    var frontRequests: Int by mutableIntStateOf(0)
        private set
    /** The AWT window while it is showing; for development probes. */
    @Volatile var awtWindow: java.awt.Window? = null

    fun bringForward() { frontRequests++ }

    val canAddTab: Boolean get() = tabs.size < MAX_TABS

    fun addTab(launch: Launch): WorkspaceTab? {
        if (!canAddTab) return null
        val tab = WorkspaceTab(launch, tabTitle(launch))
        tabs.add(tab)
        selected = tab.id
        start(tab)
        return tab
    }

    fun select(tab: WorkspaceTab) { selected = tab.id }

    fun selectedTab(): WorkspaceTab? = tabs.firstOrNull { it.id == selected }

    /** Start [tab]'s process on a worker, then its terminal on the AWT thread. */
    fun start(tab: WorkspaceTab) {
        tab.state = TabState.Starting
        workers.execute {
            try {
                val session = host.value.start(SessionSpec(projectId, tab.title, folder, tab.launch, INITIAL_SIZE))
                tab.session = session
                // Attached before the terminal exists: output waits in the connector, not lost.
                val connector = SessionTtyConnector(tab.title) { code -> onAwt { tab.state = TabState.Exited(code) } }
                connector.connect(session)
                onAwt {
                    if (tab !in tabs) { workers.execute { session.close() }; return@onAwt }
                    deck.add(tab.id, TerminalView(connector, INITIAL_SIZE))
                    if (tab.state == TabState.Starting) tab.state = TabState.Running
                    if (selected == tab.id) deck.show(tab.id)
                }
            } catch (error: Exception) {
                log(LogLevel.Error, TAG, "Could not start ${tab.title}", error)
                onAwt { tab.state = TabState.Failed(error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    /** Start the tab's process again after it exited or failed. */
    fun restart(tab: WorkspaceTab) {
        deck.remove(tab.id)
        tab.session?.let { session -> workers.execute { session.close() } }
        tab.session = null
        start(tab)
    }

    fun closeTab(tab: WorkspaceTab) {
        val index = tabs.indexOf(tab).takeIf { it >= 0 } ?: return
        tabs.removeAt(index)
        deck.remove(tab.id)
        tab.session?.let { session -> workers.execute { session.close() } }
        if (selected == tab.id) selected = tabs.getOrNull(index.coerceAtMost(tabs.size - 1))?.id
    }

    /** Refresh each running tab's busy flag. Off the AWT thread. */
    fun pollBusy() {
        for (tab in tabs.toList()) {
            val busy = tab.session?.takeIf { tab.state == TabState.Running }?.busy() ?: false
            if (busy != tab.busy) onAwt { tab.busy = busy }
        }
    }

    private fun tabTitle(launch: Launch): String = when (launch) {
        Launch.Shell -> "Shell"
        is Launch.Tool -> when (launch.tool) {
            WorkspaceTool.NEOVIM -> "Neovim"
            WorkspaceTool.LAZYGIT -> "lazygit"
            WorkspaceTool.YAZI -> "yazi"
            else -> launch.tool.id
        }
        is Launch.Diff -> launch.file
    }
}

/**
 * The terminal spike's session host: the player's login shell, Hovel's startup files in the Caches
 * folder, and one `bin` folder kept first in `PATH`. That folder is `HOVEL_WORKSPACE_BIN` when set
 * (development), else `<support>/workspace/bin`, where the tool manager will write its wrappers.
 * `HOVEL_WORKSPACE_SHELL` (development) tries another shell in place of the login shell.
 */
internal fun spikeSessionHost(environment: Map<String, String>, support: Path): SessionHost {
    usePackagedNatives()
    val shell = environment["HOVEL_WORKSPACE_SHELL"]?.let { loginShell(null, mapOf("SHELL" to it)).takeIf { found -> found == it } }
        ?: loginShell(readDirectoryShell(), environment)
    val files = ShellFiles.install(ShellFiles.defaultFolder())
    val bin = environment["HOVEL_WORKSPACE_BIN"]?.takeIf { Path.of(it).isAbsolute } ?: support.resolve("workspace/bin").toString()
    log(LogLevel.Info, TAG, "Workspace shell ${ShellKind.of(shell)}; natives ${System.getProperty(PTY4J_NATIVE_FOLDER) ?: "unpacked by pty4j"} and ${System.getProperty(JNA_BOOT_PATH) ?: "unpacked by JNA"}")
    return PtySessionHost(ShellLaunch(shell, environment, files, listOf(bin))::command)
}

/** Run [block] on the AWT thread, logging anything it throws: an escape there ends the app. */
internal fun onAwt(block: () -> Unit) {
    EventQueue.invokeLater {
        try { block() } catch (error: Exception) { log(LogLevel.Error, TAG, "A workspace update failed", error) }
    }
}
