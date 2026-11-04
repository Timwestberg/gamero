package org.formulagen.wrapper.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.EventQueue
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.util.loggingDaemonThreads

private const val TAG = "workspace-windows"

/** The size a tab's process starts at. JediTerm resizes it to the pane as soon as it lays out. */
private val INITIAL_SIZE = TermSize(columns = 100, rows = 30)

/** The busy dot asks an idle Neovim tab only every this many rounds: each ask starts a Neovim client. */
private const val EDITOR_FALLBACK_ROUNDS = 5

/** How long after a key press a menu item for the same action is taken as its echo (see [workspaceShortcut]). */
private val MENU_ECHO_NANOS = TimeUnit.MILLISECONDS.toNanos(500)

/** Threads for work the stopped workers refuse: ending a session at app exit. */
private val LATE_WORK = loggingDaemonThreads("hovel-workspace-late")

/** Run [task] on a worker, or on a thread of its own once the workers have stopped, so it always runs. */
internal fun ExecutorService.executeOrSpawn(task: () -> Unit) {
    try { execute(task) } catch (_: RejectedExecutionException) { LATE_WORK.newThread(task).start() }
}

/**
 * The windows' [SessionHost], built by [build] on first use (it reads the directory service and
 * writes files), that starts nothing once closed: a start racing the app's exit fails, not leaks.
 */
internal class WorkspaceHost(private val build: () -> SessionHost) : AutoCloseable {
    private var host: SessionHost? = null
    private var closed = false

    /** The host, built on first use. Throws once closed. Workers only. */
    @Synchronized fun get(): SessionHost {
        check(!closed) { "The workspace host is closed" }
        return host ?: build().also { host = it }
    }

    /** The host if it was built and is open, without building it. */
    @Synchronized fun built(): SessionHost? = host.takeUnless { closed }

    /** Build nothing more, and end every session of the host built, waiting for them. */
    override fun close() {
        val built = synchronized(this) { closed = true; host }
        built?.close()
    }
}

/**
 * The open workspace windows, one per folder, rendered by `main.kt` (project-workspace.ARCH.md,
 * "Window and tabs"). [host] is built on a worker when the first tab starts, so a launch that opens
 * no workspace pays nothing. Closing and quitting ask through [confirmer] when work would be lost.
 */
internal class WorkspaceWindows(private val confirmer: Confirmer = SheetConfirmer, host: () -> SessionHost) : AutoCloseable {
    private val sessionHost = WorkspaceHost(host)
    private val workers: ExecutorService = Executors.newCachedThreadPool(loggingDaemonThreads("hovel-workspace-window"))

    /** Windows in opening order. AWT thread. */
    val windows = mutableStateListOf<WorkspaceWindowState>()

    /** A quit is waiting for its busy checks or its question. AWT thread. */
    private var quitting = false

    /** The app's exit has started: quit requests are ignored from now on. */
    @Volatile private var exiting = false

    /** The cancels of quit requests waiting on the question: the person's Cancel runs them all. AWT thread. */
    private val pendingCancels = mutableListOf<() -> Unit>()

    /** Open a window on [folder] with a Shell tab, or bring forward the one already open there. AWT thread. */
    fun open(folder: Path): WorkspaceWindowState {
        val real = folder.toRealPath()
        require(Files.isDirectory(real)) { "Not a folder: $folder" }
        windows.firstOrNull { it.folder == real }?.let { it.bringForward(); return it }
        return WorkspaceWindowState("dev-${UUID.randomUUID()}", real, sessionHost, workers, confirmer).also { window ->
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
        window.busyTabs().thenAccept { busy ->
            onAwt {
                try {
                    if (window !in windows) return@onAwt
                    val question = Confirmations.closeWindow(window.title, busy)
                    if (question == null || confirmer.confirm(window.awtWindow, question)) close(window)
                } finally { window.closeRequested = false }
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
        workers.executeOrSpawn {
            sessionHost.built()?.closeProject(window.projectId)
            held.forEach { it.close() }
        }
    }

    /**
     * Before the app quits: ask once, on the first busy window, when any tab is busy; then [quit], the
     * app's shutdown. [onCancel] runs if the person keeps the app, also for a request that arrives
     * while the question is up; once the exit has started, requests are ignored (docs/desktop.md, "Quitting").
     */
    fun confirmQuit(appName: String, onCancel: () -> Unit = {}, quit: () -> Unit) {
        if (exiting) return
        pendingCancels += onCancel
        if (quitting) return
        val exit = { exitStarted(); pendingCancels.clear(); quit() }
        val open = windows.toList()
        if (open.isEmpty()) { exit(); return }
        quitting = true
        val checks = open.map { window -> window.busyTabs().thenApply { BusyWindow(window.title, it) } }
        CompletableFuture.allOf(*checks.toTypedArray()).thenRun {
            onAwt {
                try {
                    if (exiting) return@onAwt
                    val busy = checks.map { it.join() }
                    val question = Confirmations.quit(appName, busy) ?: return@onAwt exit()
                    val owner = open[busy.indexOfFirst { it.tabs.isNotEmpty() }]
                    owner.bringForward()
                    if (confirmer.confirm(owner.awtWindow, question)) exit()
                } finally {
                    quitting = false
                    // Kept, or the question failed.
                    if (!exiting) kept()
                }
            }
        }
    }

    /** The app's exit has started, by any path: later quit requests are ignored. Any thread. */
    fun exitStarted() { exiting = true }

    /** The app stays: every quit request waiting on the question is cancelled. */
    private fun kept() {
        val cancels = pendingCancels.toList()
        pendingCancels.clear()
        cancels.forEach { it() }
    }

    /**
     * At app exit: end every session, waiting for them, then stop the workers. In that order, so a
     * start still running is refused or ended by the host. Off the AWT thread.
     */
    override fun close() {
        sessionHost.close()
        workers.shutdown()
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
    /** The terminal's connector to [session], whose activity the busy dot follows. */
    @Volatile var connector: SessionTtyConnector? = null
    /** A close is waiting for its busy check or its question. AWT thread. */
    var closeRequested = false
    /** The connector's activity at the dot's last ask. Poll thread only. */
    private var askedAt = -1L

    /** Whether the dot asks this round: after input or output since its last ask, or every few rounds. Poll thread. */
    fun dueForAsk(round: Int): Boolean {
        val activity = connector?.activity ?: 0
        if (activity == askedAt && round % EDITOR_FALLBACK_ROUNDS != 0) return false
        askedAt = activity
        return true
    }
}

/** One workspace window: its folder, its tabs and their terminals. AWT thread unless noted. */
internal class WorkspaceWindowState(
    val projectId: String,
    val folder: Path,
    /** Read only on a worker: building it reads the directory service and writes files. */
    private val host: WorkspaceHost,
    private val workers: ExecutorService,
    private val confirmer: Confirmer = SheetConfirmer,
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
    /** How many times [pollBusy] has run. Poll thread only. */
    private var pollRound = 0
    /** The action the last window shortcut performed from the keyboard, and when ([performFromMenu]). */
    private var keyed: WorkspaceAction? = null
    private var keyedAt = 0L

    /** Restore the window if it is minimized, then ask it to come to the front: a sheet needs it on screen. */
    fun bringForward() {
        (awtWindow as? Frame)?.takeIf { it.extendedState and Frame.ICONIFIED != 0 }?.let { it.extendedState = it.extendedState and Frame.ICONIFIED.inv() }
        frontRequests++
    }

    val canAddTab: Boolean get() = tabs.size < WorkspaceSettingsLimits.TABS

    /** Add a [launch] tab, select it and start it; null when the window holds its most. */
    fun addTab(launch: Launch): WorkspaceTab? {
        if (!canAddTab) return null
        val tab = WorkspaceTab(launch, tabLabel(launch))
        tabs.add(tab)
        selected = tab.id
        start(tab)
        return tab
    }

    /** Show [tab]. */
    fun select(tab: WorkspaceTab) { selected = tab.id }

    /** The tab showing, if any. */
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

    /** Do what a window shortcut or menu item stands for. */
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

    /** Every tab's terminal at [size] points. */
    private fun setTerminalFontSize(size: Int) {
        if (size == fontSize) return
        fontSize = size
        deck.setFontSize(size)
    }

    /**
     * Start [tab]'s process on a worker, then its terminal on the AWT thread. Any failure, including
     * a terminal library that will not load ([LinkageError]), ends the process it started and leaves
     * the tab [TabState.Failed] with Retry.
     */
    fun start(tab: WorkspaceTab) {
        if (closed) return
        tab.state = TabState.Starting
        tab.programTitle = null
        try {
            workers.execute { startNow(tab) }
        } catch (error: RejectedExecutionException) {
            failed(tab, null, error)
        }
    }

    /** [start]'s work on a worker: the process, then its connector, then the terminal on the AWT thread. */
    private fun startNow(tab: WorkspaceTab) {
        var started: Session? = null
        try {
            val session = host.get().start(SessionSpec(projectId, tab.label, folder, tab.launch, INITIAL_SIZE))
            started = session
            tab.session = session
            // Attach first: the connector holds output.
            val connector = SessionTtyConnector(tab.label) { code -> onAwt { if (tab.session === session) tab.state = TabState.Exited(code) } }
            tab.connector = connector
            connector.connect(session)
            onAwt { show(tab, session, connector) }
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            failed(tab, started, error)
        }
    }

    /** [tab]'s terminal, once its process runs; a window closed meanwhile ends it unshown. AWT thread. */
    private fun show(tab: WorkspaceTab, session: Session, connector: SessionTtyConnector) {
        if (closed || tab !in tabs) { workers.executeOrSpawn { session.close() }; return }
        val view = try {
            TerminalView(connector, INITIAL_SIZE, fontSize,
                onShortcut = ::performFromKey,
                onTitle = { title -> if (tab.session === session) tab.programTitle = programTitle(title) })
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            failed(tab, session, error)
            return
        }
        deck.add(tab.id, view)
        if (tab.state == TabState.Starting) tab.state = TabState.Running
        if (selected == tab.id) deck.show(tab.id)
    }

    /** [tab] could not start: end the [session] it started, if any, and offer Retry. Any thread. */
    private fun failed(tab: WorkspaceTab, session: Session?, error: Throwable) {
        log(LogLevel.Error, TAG, "Could not start ${tab.label}", error)
        if (session != null && tab.session === session) {
            tab.session = null
            tab.connector = null
        }
        session?.let { workers.executeOrSpawn { it.close() } }
        onAwt { tab.state = TabState.Failed(error.message ?: error.javaClass.simpleName) }
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
        tab.session?.let { session -> workers.executeOrSpawn { session.close() } }
        tab.session = null
        tab.connector = null
        start(tab)
    }

    /**
     * ⌘W, or the tab's close button: ask first when the tab is busy, naming what runs in it, then
     * [closeTab]. AWT thread; the check runs on a worker.
     */
    fun requestCloseTab(tab: WorkspaceTab) {
        if (tab.closeRequested || tab !in tabs) return
        tab.closeRequested = true
        checkBusy(tab).thenAccept { busy ->
            onAwt {
                try {
                    if (tab !in tabs) return@onAwt
                    val question = Confirmations.closeTab(busy)
                    if (question == null || confirmer.confirm(awtWindow, question)) closeTab(tab)
                } finally { tab.closeRequested = false }
            }
        }
    }

    /** Close [tab] now and end its process. */
    fun closeTab(tab: WorkspaceTab) {
        val index = tabs.indexOf(tab).takeIf { it >= 0 } ?: return
        tabs.removeAt(index)
        deck.remove(tab.id)
        tab.session?.let { session -> workers.executeOrSpawn { session.close() } }
        if (selected == tab.id) selected = tabs.getOrNull(index.coerceAtMost(tabs.size - 1))?.id
    }

    /**
     * Every busy tab, checked in parallel on the workers: what a window close or a quit would end.
     * Called on the AWT thread; completes on a worker, and never fails ([checkBusy]).
     */
    fun busyTabs(): CompletableFuture<List<BusyTab>> {
        val checks = tabs.mapIndexed { index, tab ->
            val name = "${tab.label} (tab ${index + 1})"
            checkBusy(tab).thenApply { busy -> busy?.let { BusyTab(name, it) } }
        }
        return CompletableFuture.allOf(*checks.toTypedArray()).thenApply { checks.mapNotNull { it.join() } }
    }

    /**
     * Refresh each running tab's busy state for the tab bar: a Neovim tab only when [WorkspaceTab.dueForAsk].
     * Off the AWT thread, one call at a time; never throws but fatally.
     */
    fun pollBusy() {
        val round = ++pollRound
        for (tab in tabs.toList()) {
            val running = tab.state == TabState.Running
            if (running && tab.launch.isEditor() && !tab.dueForAsk(round)) continue
            val busy = if (running) askBusy(tab) else null
            if (busy != tab.busy) onAwt { tab.busy = busy }
        }
    }

    /** [tab]'s busy state, asked on this thread. A check that fails counts as busy. */
    private fun askBusy(tab: WorkspaceTab): Busy? = try {
        tab.session?.busy()
    } catch (error: Throwable) {
        rethrowIfFatal(error)
        log(LogLevel.Warn, TAG, "Could not ask ${tab.label} whether it is busy", error)
        Busy.NoAnswer(tab.label)
    }

    /** [askBusy] on a worker. The future never fails: a refused or failed check counts as busy. */
    private fun checkBusy(tab: WorkspaceTab): CompletableFuture<Busy?> = try {
        CompletableFuture.supplyAsync({ askBusy(tab) }, workers).exceptionally { Busy.NoAnswer(tab.label) }
    } catch (_: RejectedExecutionException) {
        CompletableFuture.completedFuture(Busy.NoAnswer(tab.label))
    }

    /** A tab's kind as its label. */
    private fun tabLabel(launch: Launch): String = when (launch) {
        Launch.Shell -> "Shell"
        is Launch.Tool -> launch.tool.label
        is Launch.Diff -> launch.file
    }
}

/** A program's window title as the tab bar and Window menu show it, cleaned by [clip]; blank is none. */
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
