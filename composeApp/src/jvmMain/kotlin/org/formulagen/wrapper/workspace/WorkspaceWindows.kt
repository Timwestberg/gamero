package org.formulagen.wrapper.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Desktop
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.util.loggingDaemonThreads

private const val TAG = "workspace-windows"

/** The size a tab's process starts at. JediTerm resizes it to the pane as soon as it lays out. */
private val INITIAL_SIZE = TermSize(columns = 100, rows = 30)

/** How long after a key press a menu item for the same action is taken as its echo (see [workspaceShortcut]). */
private val MENU_ECHO_NANOS = TimeUnit.MILLISECONDS.toNanos(500)

/**
 * The open workspace windows (project-workspace.ARCH.md, "Window and tabs"): one per folder, each
 * rendered by `main.kt` inside the app's one Compose application. The terminal spike opens them
 * only in development builds, on a folder, with one Shell tab; projects, the profile and the other
 * tab kinds come later. [host] is built on a worker when the first tab starts (it reads the
 * directory service and writes the shell files), so a launch that opens no workspace pays nothing.
 *
 * Closing a tab or a window, and quitting, ask first when something would be lost ([Busy]), through
 * [confirmer]: a sheet on the workspace window.
 */
internal class WorkspaceWindows(confirmer: Confirmer = SheetConfirmer, host: () -> SessionHost) : AutoCloseable {
    private val lazyHost = lazy(host)
    private val workers: ExecutorService = Executors.newCachedThreadPool(loggingDaemonThreads("hovel-workspace-window"))

    /** Asks before work ends. Development probe only: replaced to answer without a person. */
    @Volatile var confirmer: Confirmer = confirmer

    /** Windows in opening order. AWT thread. */
    val windows = mutableStateListOf<WorkspaceWindowState>()

    /** A quit is waiting for its busy checks or its question. AWT thread. */
    private var quitting = false

    /** Open a window on [folder] with a Shell tab, or bring forward the one already open there. AWT thread. */
    fun open(folder: Path): WorkspaceWindowState {
        val real = folder.toRealPath()
        require(Files.isDirectory(real)) { "Not a folder: $folder" }
        windows.firstOrNull { it.folder == real }?.let { it.bringForward(); return it }
        return WorkspaceWindowState("dev-${UUID.randomUUID()}", real, lazyHost, workers, { this.confirmer }).also { window ->
            windows.add(window)
            window.addTab(Launch.Shell)
        }
    }

    /**
     * The window's close button, or its last tab closing: ask first when a tab is busy, naming each
     * one, then [close]. AWT thread; the checks run on workers.
     */
    fun requestClose(window: WorkspaceWindowState) {
        if (window.closeRequested || window !in windows) return
        window.closeRequested = true
        window.busyTabs().whenComplete { busy, _ ->
            onAwt {
                window.closeRequested = false
                if (window !in windows) return@onAwt
                val question = Confirmations.closeWindow(window.title, busy.orEmpty())
                if (question == null || confirmer.confirm(window.awtWindow, question)) close(window)
            }
        }
    }

    /**
     * Close [window] and end every process in it: its project's sessions, and the ones its tabs hold
     * (a session the host had not registered yet when [SessionHost.closeProject] ran). A tab still
     * starting closes its own session when it finishes ([WorkspaceWindowState.start]). AWT thread;
     * the processes end on a worker.
     */
    fun close(window: WorkspaceWindowState) {
        if (!windows.remove(window)) return
        val held = window.close()
        workers.execute {
            lazyHost.value.closeProject(window.projectId)
            held.forEach { it.close() }
        }
    }

    /**
     * Before the app quits (⌘Q, or the main window closing): when any tab of any window is busy, ask
     * once, listing the windows and tabs, on the first busy window; then [quit], the app's normal
     * shutdown. With nothing busy, [quit] runs at once. AWT thread.
     */
    fun confirmQuit(appName: String, quit: () -> Unit) {
        if (quitting) return
        val open = windows.toList()
        if (open.isEmpty()) { quit(); return }
        quitting = true
        val checks = open.map { window -> window.busyTabs().thenApply { BusyWindow(window.title, it) } }
        CompletableFuture.allOf(*checks.toTypedArray()).whenComplete { _, _ ->
            onAwt {
                quitting = false
                val busy = checks.map { it.getNow(null) ?: BusyWindow("", emptyList()) }
                val question = Confirmations.quit(appName, busy) ?: return@onAwt quit()
                val owner = open[busy.indexOfFirst { it.tabs.isNotEmpty() }]
                owner.bringForward()
                if (confirmer.confirm(owner.awtWindow, question)) quit()
            }
        }
    }

    /** At app exit: end every session, waiting for them. Off the AWT thread. */
    override fun close() {
        workers.shutdown()
        if (lazyHost.isInitialized()) lazyHost.value.close()
    }
}

/**
 * Route macOS's Quit (⌘Q, the Dock, logging out) through [onQuit] instead of the JDK's default,
 * which is `System.exit` and skips `main.kt`'s shutdown. The JDK's quit is cancelled at once and
 * [onQuit] decides: it asks when work would be lost, then runs the app's own exit. Installed only
 * when workspaces are enabled; a release keeps the JDK's default.
 */
internal fun installQuitHandler(onQuit: () -> Unit) {
    try {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) return
        Desktop.getDesktop().setQuitHandler { _, response ->
            response.cancelQuit()
            try { onQuit() } catch (error: Throwable) {
                rethrowIfFatal(error)
                log(LogLevel.Error, TAG, "The quit request failed", error)
            }
        }
    } catch (error: Throwable) {
        rethrowIfFatal(error)
        log(LogLevel.Error, TAG, "Could not install the quit handler", error)
    }
}

/** A tab's state as the window shows it. */
internal sealed interface TabState {
    data object Starting : TabState
    data object Running : TabState
    data class Failed(val message: String) : TabState
    data class Exited(val code: Int) : TabState
}

/** One tab. [label] names its kind ("Shell", "Neovim") and never changes; [title] follows the program. */
internal class WorkspaceTab(val launch: Launch, val label: String) {
    val id: String = UUID.randomUUID().toString()
    var state: TabState by mutableStateOf(TabState.Starting)
    /** What [session] reports from [Session.busy]; polled by the window for the tab bar. */
    var busy: Busy? by mutableStateOf(null)
    /** The title the program set (OSC 0 or 2), when it set one. */
    var programTitle: String? by mutableStateOf(null)
    /** The tab bar's name: the program's title, else [label]. */
    val title: String get() = programTitle ?: label
    @Volatile var session: Session? = null
    /** A close is waiting for its busy check or its question. AWT thread. */
    var closeRequested = false
}

/** One workspace window: its folder, its tabs and their terminals. AWT thread unless noted. */
internal class WorkspaceWindowState(
    val projectId: String,
    val folder: Path,
    /** Read only on a worker: building it reads the directory service and writes files. */
    private val host: Lazy<SessionHost>,
    private val workers: ExecutorService,
    private val confirmer: () -> Confirmer = { SheetConfirmer },
    private val clock: () -> Long = System::nanoTime,
) {
    val title: String = "${folder.fileName ?: folder} · workspace"
    val tabs = mutableStateListOf<WorkspaceTab>()
    var selected: String? by mutableStateOf(null)
    val deck = TerminalDeck()
    /** Bumped to ask the window to come to the front. */
    var frontRequests: Int by mutableIntStateOf(0)
        private set
    /** The terminal font size, in points, for every tab of this window: ⌘+, ⌘− and ⌘0. */
    var fontSize: Int by mutableIntStateOf(TerminalFont.DEFAULT_SIZE)
        private set
    /** The AWT window while it is showing: the owner of this window's questions. */
    @Volatile var awtWindow: java.awt.Window? = null
    /** A window close is waiting for its busy checks or its question. AWT thread. */
    var closeRequested = false
    /** The window has closed: no tab starts or shows again. AWT thread. */
    private var closed = false
    /** The action the last window shortcut performed from the keyboard, and when ([performFromMenu]). */
    private var keyed: WorkspaceAction? = null
    private var keyedAt = 0L

    fun bringForward() { frontRequests++ }

    val canAddTab: Boolean get() = tabs.size < WorkspaceSettingsLimits.TABS

    fun addTab(launch: Launch): WorkspaceTab? {
        if (!canAddTab) return null
        val tab = WorkspaceTab(launch, tabLabel(launch))
        tabs.add(tab)
        selected = tab.id
        start(tab)
        return tab
    }

    fun select(tab: WorkspaceTab) { selected = tab.id }

    fun selectedTab(): WorkspaceTab? = tabs.firstOrNull { it.id == selected }

    /** A window shortcut pressed in a terminal ([workspaceShortcut]). */
    fun performFromKey(action: WorkspaceAction) {
        keyed = action
        keyedAt = clock()
        perform(action)
    }

    /**
     * A menu item. macOS hands a ⌘ shortcut to the menu bar after the terminal has handled it, so
     * an item that repeats the key press's action within [MENU_ECHO_NANOS] is that echo, and ignored.
     */
    fun performFromMenu(action: WorkspaceAction) {
        val echo = keyed == action && clock() - keyedAt < MENU_ECHO_NANOS
        keyed = null
        if (!echo) perform(action)
    }

    fun perform(action: WorkspaceAction) {
        when (action) {
            WorkspaceAction.NewTab -> addTab(Launch.Shell)
            WorkspaceAction.CloseTab -> selectedTab()?.let(::requestCloseTab)
            is WorkspaceAction.SelectTab -> tabs.getOrNull(action.index)?.let(::select)
            WorkspaceAction.SelectLastTab -> tabs.lastOrNull()?.let(::select)
            WorkspaceAction.NextTab -> selectBy(1)
            WorkspaceAction.PreviousTab -> selectBy(-1)
            WorkspaceAction.ZoomIn -> setTerminalFontSize(TerminalFont.zoomed(fontSize, 1))
            WorkspaceAction.ZoomOut -> setTerminalFontSize(TerminalFont.zoomed(fontSize, -1))
            WorkspaceAction.ZoomReset -> setTerminalFontSize(TerminalFont.DEFAULT_SIZE)
        }
    }

    /** The next or previous tab, wrapping around, as in Terminal. */
    private fun selectBy(step: Int) {
        if (tabs.isEmpty()) return
        val index = tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        select(tabs[Math.floorMod(index + step, tabs.size)])
    }

    private fun setTerminalFontSize(size: Int) {
        if (size == fontSize) return
        fontSize = size
        deck.setFontSize(size)
    }

    /**
     * Start [tab]'s process on a worker, then its terminal on the AWT thread. Any failure, including
     * a terminal library that will not load ([LinkageError]), leaves the tab [TabState.Failed] with Retry.
     */
    fun start(tab: WorkspaceTab) {
        if (closed) return
        tab.state = TabState.Starting
        tab.programTitle = null
        workers.execute {
            try {
                val session = host.value.start(SessionSpec(projectId, tab.label, folder, tab.launch, INITIAL_SIZE))
                tab.session = session
                // Attached before the terminal exists: output waits in the connector, not lost.
                val connector = SessionTtyConnector(tab.label) { code -> onAwt { tab.state = TabState.Exited(code) } }
                connector.connect(session)
                onAwt {
                    // The tab, or its whole window, closed while the process started: nothing will show it.
                    if (closed || tab !in tabs) { workers.execute { session.close() }; return@onAwt }
                    val view = TerminalView(connector, INITIAL_SIZE, fontSize,
                        onShortcut = ::performFromKey,
                        onTitle = { title -> onAwt { if (tab.session === session) tab.programTitle = programTitle(title) } })
                    deck.add(tab.id, view)
                    if (tab.state == TabState.Starting) tab.state = TabState.Running
                    if (selected == tab.id) deck.show(tab.id)
                }
            } catch (error: Throwable) {
                rethrowIfFatal(error)
                log(LogLevel.Error, TAG, "Could not start ${tab.label}", error)
                onAwt { tab.state = TabState.Failed(error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    /**
     * The window closed: stop drawing its terminals, and return the sessions its tabs hold, for
     * [WorkspaceWindows.close] to end. A start still running sees [closed] and ends its own. AWT thread.
     */
    fun close(): List<Session> {
        closed = true
        deck.closeAll()
        return tabs.mapNotNull { it.session }
    }

    /** Start the tab's process again after it exited or failed. */
    fun restart(tab: WorkspaceTab) {
        deck.remove(tab.id)
        tab.session?.let { session -> workers.execute { session.close() } }
        tab.session = null
        start(tab)
    }

    /**
     * ⌘W, or the tab's close button: ask first when the tab is busy, naming what runs in it, then
     * [closeTab]. AWT thread; the check runs on a worker.
     */
    fun requestCloseTab(tab: WorkspaceTab) {
        if (tab.closeRequested || tab !in tabs) return
        tab.closeRequested = true
        val session = tab.session
        val check = try {
            CompletableFuture.supplyAsync({ session?.busy() }, workers)
        } catch (error: Exception) {
            CompletableFuture.completedFuture<Busy?>(Busy.NoAnswer(tab.label))
        }
        check.whenComplete { busy, failure ->
            onAwt {
                tab.closeRequested = false
                if (tab !in tabs) return@onAwt
                val question = Confirmations.closeTab(if (failure != null) Busy.NoAnswer(tab.label) else busy)
                if (question == null || confirmer().confirm(awtWindow, question)) closeTab(tab)
            }
        }
    }

    /** Close [tab] now and end its process. */
    fun closeTab(tab: WorkspaceTab) {
        val index = tabs.indexOf(tab).takeIf { it >= 0 } ?: return
        tabs.removeAt(index)
        deck.remove(tab.id)
        tab.session?.let { session -> workers.execute { session.close() } }
        if (selected == tab.id) selected = tabs.getOrNull(index.coerceAtMost(tabs.size - 1))?.id
    }

    /**
     * Every busy tab, checked in parallel on the workers: what a window close or a quit would end.
     * Called on the AWT thread; completes on a worker. A check that fails counts as busy.
     */
    fun busyTabs(): CompletableFuture<List<BusyTab>> {
        val checks = tabs.mapIndexed { index, tab ->
            val name = "${tab.label} (tab ${index + 1})"
            val session = tab.session
            try {
                CompletableFuture.supplyAsync({ session?.busy()?.let { BusyTab(name, it) } }, workers)
                    .exceptionally { BusyTab(name, Busy.NoAnswer(tab.label)) }
            } catch (error: Exception) {
                CompletableFuture.completedFuture(BusyTab(name, Busy.NoAnswer(tab.label)))
            }
        }
        return CompletableFuture.allOf(*checks.toTypedArray()).thenApply { checks.mapNotNull { it.join() } }
    }

    /** Refresh each running tab's busy state for the tab bar. Off the AWT thread. */
    fun pollBusy() {
        for (tab in tabs.toList()) {
            val busy = tab.session?.takeIf { tab.state == TabState.Running }?.busy()
            if (busy != tab.busy) onAwt { tab.busy = busy }
        }
    }

    private fun tabLabel(launch: Launch): String = when (launch) {
        Launch.Shell -> "Shell"
        is Launch.Tool -> launch.tool.label
        is Launch.Diff -> launch.file
    }
}

/** A program's window title as a tab shows it: no control characters, at most 60 characters; blank is none. */
internal fun programTitle(title: String): String? = clip(title, 60).takeIf { it.isNotEmpty() }

/** Run [block] on the AWT thread, logging anything it throws: an escape there ends the app. */
internal fun onAwt(block: () -> Unit) {
    EventQueue.invokeLater {
        try { block() } catch (error: Throwable) {
            rethrowIfFatal(error)
            log(LogLevel.Error, TAG, "A workspace update failed", error)
        }
    }
}

/**
 * For a workspace edge that contains and logs what it catches: rethrows [error] when the JVM itself
 * is failing (out of memory, a stack overflow, an internal error), which should still end the app.
 * Anything else is contained, including a library that will not load ([LinkageError]:
 * `UnsatisfiedLinkError`, `ExceptionInInitializerError`, `NoClassDefFoundError`).
 */
internal fun rethrowIfFatal(error: Throwable) {
    if (error is VirtualMachineError) throw error
}
