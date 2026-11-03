package org.formulagen.wrapper.workspace

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import com.pty4j.unix.UnixPtyProcess
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream
import org.formulagen.wrapper.companion.terminateProcessTree
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.util.loggingDaemonThreads

private const val TAG = "workspace-pty"

/** Bytes per read from a pseudo-terminal. */
internal const val PTY_READ_BYTES = 8 * 1024

/** How long [PtySession.close] waits after SIGHUP before stopping the process tree. */
private const val HANGUP_GRACE_MS = 500L

/** How long the exit watcher waits for the reader to deliver the last output before reporting the exit. */
private const val DRAIN_GRACE_MS = 1_000L

/**
 * [SessionHost] on pty4j. Each session is one process on its own pseudo-terminal (pty4j's spawn
 * helper makes it a session leader, so its process group is its pid), with one reader thread and one
 * exit watcher. [commands] builds each session's argv and environment from its spec (its [Launch],
 * and its project's `PATH` entries, [ShellLaunch.command]) and the arguments the host adds to a tool:
 * a Neovim tab's `--listen <socket>`, from [editorSockets], for its modified-buffer check. Without
 * [editorSockets] a running Neovim always counts as busy.
 *
 * Sessions belong to their project, not to a window: a viewer detaching leaves the process running,
 * and only [Session.close], [closeProject] or [close] end it.
 */
class PtySessionHost(
    private val editorSockets: EditorSockets? = null,
    private val commands: (SessionSpec, List<String>) -> ShellCommand,
) : SessionHost {
    private val sessions = ConcurrentHashMap<String, MutableSet<PtySession>>()
    private val threads = loggingDaemonThreads("hovel-workspace-session")
    private val counter = AtomicInteger()
    private val closed = AtomicBoolean(false)

    init {
        // Before pty4j's first use: JNA reads its settings once.
        usePackagedNatives()
    }

    override fun start(spec: SessionSpec): Session {
        check(!closed.get()) { "The session host is closed" }
        val socket = if (spec.launch.isEditor()) editorSockets?.next() else null
        val command = commands(spec, socket?.let { listOf("--listen", it.toString()) }.orEmpty())
        val process = try {
            PtyProcessBuilder(command.argv.toTypedArray())
                .setEnvironment(command.environment)
                .setDirectory(spec.folder.toString())
                .setInitialColumns(spec.size.columns)
                .setInitialRows(spec.size.rows)
                .setConsole(false)
                .start()
        } catch (error: Throwable) {
            socket?.let(EditorSockets::release)
            throw error
        }
        val session = PtySession(spec, process, "hovel-workspace-pty-${counter.incrementAndGet()}", command.argv.first(), socket) { ended ->
            sessions[spec.projectId]?.remove(ended)
        }
        sessions.computeIfAbsent(spec.projectId) { ConcurrentHashMap.newKeySet() }.add(session)
        session.begin(threads)
        return session
    }

    override fun closeProject(projectId: String) {
        closeAll(sessions.remove(projectId).orEmpty().toList())
    }

    /** Ends every session of every project. Called at app exit. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeAll(sessions.values.flatten())
        sessions.clear()
    }

    /** Close in parallel: each session's close waits up to its grace periods. */
    private fun closeAll(targets: List<PtySession>) {
        if (targets.isEmpty()) return
        val done = CountDownLatch(targets.size)
        targets.forEach { session -> threads.newThread { try { session.close() } finally { done.countDown() } }.start() }
        done.await()
    }
}

/**
 * One tab's process on a pseudo-terminal, and the viewers attached to it. [executable] is the
 * program it started (a Shell tab's shell); [editorSocket] is where a Neovim tab listens.
 */
internal class PtySession(
    private val spec: SessionSpec,
    private val process: PtyProcess,
    private val name: String,
    private val executable: String,
    private val editorSocket: Path?,
    private val onEnded: (PtySession) -> Unit,
) : Session {
    override val title: String get() = spec.title

    private val viewers = CopyOnWriteArrayList<SessionViewer>()
    private val input: OutputStream = process.outputStream
    /** The process as the JDK's process tools see it: pty4j's own has no [Process.toHandle]. */
    private val tree: Process = HandledProcess(process)
    private val closing = AtomicBoolean(false)
    private val reading = AtomicBoolean(false)
    @Volatile private var exitCode: Int? = null
    /** The code every viewer hears, once set. Set, and read with a new viewer, under [viewers]' lock. */
    private var reportedCode: Int? = null
    @Volatile private var reader: Thread? = null
    private lateinit var threads: ThreadFactory

    /**
     * Start the exit watcher. The reader starts with the first viewer: until then the
     * pseudo-terminal holds the output (and a process that fills it waits), so a shell's first
     * prompt is never read before anyone can see it.
     */
    fun begin(threads: ThreadFactory) {
        this.threads = threads
        threads.newThread(::watch).apply { name = "$name-exit"; start() }
    }

    /**
     * By tab kind (project-workspace.ARCH.md, "Sessions and the terminal"):
     * - a Shell tab is busy while its terminal's foreground process group is not the shell's own, so
     *   background jobs and prompt helpers (powerlevel10k's `gitstatusd`, zsh-async workers) do not count,
     *   and while a descendant is stopped (a job suspended with ⌃Z), or its state cannot be read;
     * - Neovim is busy while a buffer is modified or its `:terminal` runs a job, as it answers on its
     *   `--listen` socket; no answer in time counts as busy. It is asked whether it is the tab's
     *   process or a descendant (a wrapper that does not `exec`), with its own executable as the
     *   client. Its LSP servers and its own `nvim --embed` server are descendants, so the
     *   descendant rule cannot be used for it; a tab with no Neovim in it gets the tool rule;
     * - any other tool is busy while it has a live descendant that is not running the tool's own
     *   executable (by real path), which would be a helper of the tool's.
     */
    override fun busy(): Busy? {
        if (closing.get() || !process.isAlive) return null
        return try {
            when (val launch = spec.launch) {
                Launch.Shell -> foregroundJob()
                is Launch.Tool -> if (launch.isEditor()) editorState(launch.tool.label) else toolChild()
                is Launch.Diff -> toolChild()
            }
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            log(LogLevel.Warn, TAG, "Could not tell whether $name is busy", error)
            Busy.NoAnswer(spec.title)
        }
    }

    /**
     * The foreground job of a Shell tab, else a job it suspended; when the terminal cannot be asked,
     * any live child. A shell replaced by `exec` with something that is not a shell is that program
     * (by name: `/bin/sh` re-executes another shell on a Mac); one whose name cannot be read, such as
     * `exec sudo -s`, is a program too.
     */
    private fun foregroundJob(): Busy? {
        val group = foregroundGroup() ?: return anyChild()
        if (group == process.pid()) {
            val root = tree.toHandle()
            val running = root.info().command().orElse(null)?.let { File(it).name } ?: return Busy.Running(describe(root))
            return if (running in SHELL_NAMES || running == File(executable).name) suspendedJob() else Busy.Running(describe(root))
        }
        // The job's leader names it best. It may have exited while the rest of its group runs on.
        val program = ProcessHandle.of(group).filter(ProcessHandle::isAlive).orElse(null) ?: firstChild { true } ?: return null
        return Busy.Running(describe(program))
    }

    /** The foreground process group of the session's terminal, or null when it cannot be read. */
    private fun foregroundGroup(): Long? {
        val fd = (process as? UnixPtyProcess)?.pty?.masterFD?.takeIf { it >= 0 } ?: return null
        return foregroundProcessGroup(fd)
    }

    private fun anyChild(): Busy? = firstChild { true }?.let { Busy.Running(describe(it)) }

    /** A stopped descendant, or one whose state cannot be read; daemons and prompt helpers are never stopped. */
    private fun suspendedJob(): Busy? {
        val child = firstChild { isStopped(it) != false } ?: return null
        return if (isStopped(child) == true) Busy.Stopped(describe(child)) else Busy.Running(describe(child))
    }

    private fun toolChild(): Busy? {
        val tool = commandOf(tree.toHandle())
        return firstChild { tool == null || commandOf(it) != tool }?.let { Busy.Running(describe(it)) }
    }

    private fun firstChild(counts: (ProcessHandle) -> Boolean): ProcessHandle? =
        tree.descendants().use { children -> children.filter { it.isAlive && counts(it) }.findFirst().orElse(null) }

    /** Neovim's own answer, from the tab's process or a Neovim it started; with no Neovim, the tool rule. */
    private fun editorState(editor: String): Busy? {
        val root = commandOf(tree.toHandle()) ?: return Busy.NoAnswer(editor)
        val neovim = root.takeIf(::isNeovim) ?: firstChild { commandOf(it)?.let(::isNeovim) == true }?.let(::commandOf) ?: return toolChild()
        val socket = editorSocket ?: return Busy.NoAnswer(editor)
        return askNeovim(neovim, socket, editor)
    }

    /** True for a Neovim executable, by its real path's name. */
    private fun isNeovim(executable: String): Boolean = File(executable).name == NEOVIM_EXECUTABLE

    private fun describe(handle: ProcessHandle): String = handle.info().let { info ->
        commandLabel(info.command().orElse(null), info.arguments().map { it.toList() }.orElse(emptyList()))
    }

    /** The executable's real path: Neovim starts its server by its resolved path, not Homebrew's symlink. */
    private fun commandOf(handle: ProcessHandle): String? = handle.info().command().orElse(null)?.let(::realPath)

    private fun realPath(command: String): String = try { File(command).toPath().toRealPath().toString() } catch (_: Exception) { command }

    private companion object {
        /** A Shell tab's process still counts as its shell under any of these names (`exec bash`, say). */
        val SHELL_NAMES = setOf("zsh", "bash", "fish", "sh", "dash", "ksh", "mksh", "tcsh", "csh", "nu", "elvish", "xonsh")
    }

    override fun attach(viewer: SessionViewer): Attachment {
        // Under the lock, so the viewer hears the exit from [report] or from here, never both.
        val ended = synchronized(viewers) { viewers.add(viewer); reportedCode }
        if (reading.compareAndSet(false, true)) reader = threads.newThread(::read).apply { name = "$name-reader"; start() }
        // A viewer that attaches late sees only new output. One that attaches after the exit hears it at once.
        ended?.let { code -> notifyExit(viewer, code) }
        return PtyAttachment(viewer)
    }

    override fun processes(): List<ProcessHandle> = ProcessHandle.of(process.pid()).map { root ->
        listOf(root) + root.descendants().use { it.toList() }
    }.orElse(emptyList())

    /**
     * SIGHUP to the process group, as a terminal does when its window closes; a short wait; then
     * [terminateProcessTree] for whatever is left, including children the shell started that are
     * no longer its descendants once it exits. Blocks up to about 2.5 seconds; idempotent.
     */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        try {
            val observed = descendants()
            if (process.isAlive) {
                (process as? UnixPtyProcess)?.hangup()
                process.waitFor(HANGUP_GRACE_MS, TimeUnit.MILLISECONDS)
            }
            terminateProcessTree(tree, observed)
        } catch (error: Exception) {
            log(LogLevel.Error, TAG, "Could not end $name", error)
        } finally {
            closeStreams()
            report(exitCode ?: CLOSED_EXIT_CODE)
            editorSocket?.let(EditorSockets::release)
            onEnded(this)
        }
    }

    private fun descendants(): Set<ProcessHandle> = try {
        tree.descendants().use { children -> children.toList().toSet() }
    } catch (_: Exception) { emptySet() }

    /** The reader: 8 KiB at a time, to every attached viewer, until the pseudo-terminal closes. */
    private fun read() {
        val buffer = ByteArray(PTY_READ_BYTES)
        try {
            val output = process.inputStream
            while (true) {
                val count = output.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                for (viewer in viewers) {
                    try { viewer.output(buffer, count) } catch (error: Exception) {
                        log(LogLevel.Error, TAG, "A viewer of $name failed on output", error)
                    }
                }
            }
        } catch (error: IOException) {
            if (!closing.get()) log(LogLevel.Info, TAG, "The terminal of $name closed: ${error.javaClass.simpleName}")
        } catch (error: Exception) {
            log(LogLevel.Error, TAG, "The reader of $name failed", error)
        }
    }

    /** The exit watcher: waits for the process, lets the reader deliver its last output, then reports. */
    private fun watch() {
        try {
            val code = process.waitFor()
            exitCode = code
            reader?.join(DRAIN_GRACE_MS)
            report(code)
            if (!closing.get()) {
                closeStreams()
                editorSocket?.let(EditorSockets::release)
                onEnded(this)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            log(LogLevel.Error, TAG, "The exit watcher of $name failed", error)
        }
    }

    private fun report(code: Int) {
        val (final, targets) = synchronized(viewers) {
            if (reportedCode != null) return
            val final = exitCode ?: code
            reportedCode = final
            final to viewers.toList()
        }
        targets.forEach { notifyExit(it, final) }
    }

    private fun notifyExit(viewer: SessionViewer, code: Int) {
        try { viewer.exited(code) } catch (error: Exception) { log(LogLevel.Error, TAG, "A viewer of $name failed on exit", error) }
    }

    private fun closeStreams() {
        for (stream in listOf(process.inputStream, process.outputStream, process.errorStream)) {
            try { stream.close() } catch (_: IOException) {}
        }
    }

    private inner class PtyAttachment(private val viewer: SessionViewer) : Attachment {
        private val detached = AtomicBoolean(false)

        override fun input(bytes: ByteArray) {
            if (detached.get() || !process.isAlive) return
            try {
                synchronized(input) {
                    input.write(bytes)
                    input.flush()
                }
            } catch (error: IOException) {
                log(LogLevel.Warn, TAG, "Could not write to $name: ${error.javaClass.simpleName}")
            }
        }

        override fun resize(size: TermSize) {
            if (detached.get() || !process.isAlive || size.columns <= 0 || size.rows <= 0) return
            try { process.winSize = WinSize(size.columns, size.rows) } catch (error: Exception) {
                log(LogLevel.Warn, TAG, "Could not resize $name: ${error.javaClass.simpleName}")
            }
        }

        /** Detaches this viewer. The session keeps running: it belongs to its project, not to a window. */
        override fun close() {
            if (detached.compareAndSet(false, true)) viewers.remove(viewer)
        }
    }
}

/**
 * A pty4j process with the [toHandle] it lacks, so [Process.descendants] and
 * [terminateProcessTree] work on it. Everything else is pty4j's: its destroy signals the process group.
 */
private class HandledProcess(private val pty: PtyProcess) : Process() {
    override fun getOutputStream(): OutputStream = pty.outputStream
    override fun getInputStream() = pty.inputStream
    override fun getErrorStream() = pty.errorStream
    override fun waitFor(): Int = pty.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = pty.waitFor(timeout, unit)
    override fun exitValue(): Int = pty.exitValue()
    override fun isAlive(): Boolean = pty.isAlive
    override fun destroy() = pty.destroy()
    override fun destroyForcibly(): Process { pty.destroyForcibly(); return this }
    override fun pid(): Long = pty.pid()
    override fun toHandle(): ProcessHandle = ProcessHandle.of(pty.pid()).orElseThrow { UnsupportedOperationException("Process ${pty.pid()} has exited") }
    /** None once the process has exited, so [terminateProcessTree] still stops the children observed before. */
    override fun descendants(): Stream<ProcessHandle> =
        if (pty.isAlive) ProcessHandle.of(pty.pid()).map { it.descendants() }.orElseGet { Stream.empty() } else Stream.empty()
}

/**
 * Point pty4j, and JNA under it, at natives shipped inside the app bundle and signed with it,
 * instead of letting each unpack its own copies to a temporary folder at run time, which the
 * hardened runtime refuses unless library validation is off. A packaged development build for Apple
 * Silicon has them in Compose's app resources (`-Dcompose.application.resources.dir`,
 * `Contents/app/resources/`): `pty4j/darwin/` and `jna/`. Gradle runs and release builds ship
 * neither, and keep each library's default.
 * [PtySessionHost]'s init calls it, before pty4j's first use: JNA reads its settings once.
 */
internal fun usePackagedNatives(resources: String? = System.getProperty("compose.application.resources.dir")) {
    if (resources == null) return
    val pty4j = File(resources, "pty4j")
    if (System.getProperty(PTY4J_NATIVE_FOLDER) == null && File(pty4j, "darwin/libpty.dylib").isFile) {
        System.setProperty(PTY4J_NATIVE_FOLDER, pty4j.absolutePath)
    }
    val jna = File(resources, "jna")
    if (System.getProperty(JNA_BOOT_PATH) == null && File(jna, "libjnidispatch.jnilib").isFile) {
        System.setProperty(JNA_BOOT_PATH, jna.absolutePath)
        // Fail loudly rather than fall back to unpacking an unsigned copy.
        System.setProperty("jna.nounpack", "true")
    }
}

/** pty4j's `PtyUtil.PREFERRED_NATIVE_FOLDER_KEY`. */
internal const val PTY4J_NATIVE_FOLDER = "pty4j.preferred.native.folder"

/** Where JNA looks for its `libjnidispatch` before unpacking one. */
internal const val JNA_BOOT_PATH = "jna.boot.library.path"
