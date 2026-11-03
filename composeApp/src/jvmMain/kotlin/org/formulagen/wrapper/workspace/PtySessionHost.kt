package org.formulagen.wrapper.workspace

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import com.pty4j.unix.UnixPtyProcess
import java.io.File
import java.io.IOException
import java.io.OutputStream
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

/** The exit code reported when the process was ended by [Session.close] before it exited by itself. */
internal const val CLOSED_EXIT_CODE = -1

/**
 * [SessionHost] on pty4j. Each session is one process on its own pseudo-terminal (pty4j's spawn
 * helper makes it a session leader, so its process group is its pid), with one reader thread and one
 * exit watcher. [commands] builds each [Launch]'s argv and environment ([ShellLaunch.command]).
 *
 * Sessions belong to their project, not to a window: a viewer detaching leaves the process running,
 * and only [Session.close], [closeProject] or [close] end it.
 */
class PtySessionHost(private val commands: (Launch) -> ShellCommand) : SessionHost {
    private val sessions = ConcurrentHashMap<String, MutableSet<PtySession>>()
    private val threads = loggingDaemonThreads("hovel-workspace-session")
    private val counter = AtomicInteger()
    private val closed = AtomicBoolean(false)

    override fun start(spec: SessionSpec): Session {
        check(!closed.get()) { "The session host is closed" }
        val command = commands(spec.launch)
        val process = PtyProcessBuilder(command.argv.toTypedArray())
            .setEnvironment(command.environment)
            .setDirectory(spec.folder.toString())
            .setInitialColumns(spec.size.columns)
            .setInitialRows(spec.size.rows)
            .setConsole(false)
            .start()
        val session = PtySession(spec, process, "hovel-workspace-pty-${counter.incrementAndGet()}") { ended ->
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

/** One tab's process on a pseudo-terminal, and the viewers attached to it. */
internal class PtySession(
    private val spec: SessionSpec,
    private val process: PtyProcess,
    private val name: String,
    private val onEnded: (PtySession) -> Unit,
) : Session {
    override val title: String get() = spec.title

    private val viewers = CopyOnWriteArrayList<SessionViewer>()
    private val input: OutputStream = process.outputStream
    /** The process as the JDK's process tools see it: pty4j's own has no [Process.toHandle]. */
    private val tree: Process = HandledProcess(process)
    private val closing = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)
    private val reading = AtomicBoolean(false)
    @Volatile private var exitCode: Int? = null
    @Volatile private var reader: Thread? = null
    private lateinit var threads: ThreadFactory

    val pid: Long get() = process.pid()

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
     * A live descendant makes the session busy. In a tool tab, a descendant running the tool's own
     * executable is part of the tool: Neovim's interface process always runs its server as a child
     * `nvim --embed`, and only what that server starts (a `:terminal` shell) counts.
     */
    override fun busy(): Boolean = try {
        if (!process.isAlive) false else {
            val tool = if (spec.launch is Launch.Tool) commandOf(tree.toHandle()) else null
            tree.descendants().use { children -> children.anyMatch { it.isAlive && (tool == null || commandOf(it) != tool) } }
        }
    } catch (error: Exception) {
        log(LogLevel.Warn, TAG, "Could not read the children of $name", error)
        false
    }

    /** The executable's real path: Neovim starts its server by its resolved path, not Homebrew's symlink. */
    private fun commandOf(handle: ProcessHandle): String? = handle.info().command().orElse(null)?.let { command ->
        try { File(command).toPath().toRealPath().toString() } catch (_: Exception) { command }
    }

    override fun attach(viewer: SessionViewer): Attachment {
        viewers.add(viewer)
        if (reading.compareAndSet(false, true)) reader = threads.newThread(::read).apply { name = "$name-reader"; start() }
        // A viewer that attaches late sees only new output. One that attaches after the exit hears it at once.
        exitCode?.takeIf { reported.get() }?.let { code -> notifyExit(viewer, code) }
        return PtyAttachment(viewer)
    }

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
                onEnded(this)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            log(LogLevel.Error, TAG, "The exit watcher of $name failed", error)
        }
    }

    private fun report(code: Int) {
        if (!reported.compareAndSet(false, true)) return
        exitCode = exitCode ?: code
        viewers.forEach { notifyExit(it, exitCode ?: code) }
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
 * hardened runtime refuses unless library validation is off. A packaged app has them in Compose's
 * app resources (`-Dcompose.application.resources.dir`, `Contents/app/resources/`): `pty4j/darwin/`
 * and `jna/`. Development runs have neither and keep each library's default. Call before pty4j's
 * first use: JNA reads its settings once.
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
