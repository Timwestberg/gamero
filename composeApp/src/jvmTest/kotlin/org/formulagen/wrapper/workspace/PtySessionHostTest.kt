package org.formulagen.wrapper.workspace

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Collects a session's output as text and its exit code. */
internal class CollectingViewer : SessionViewer {
    private val bytes = ByteArrayOutputStream()
    val exit = CompletableFuture<Int>()

    override fun output(bytes: ByteArray, length: Int) { synchronized(this.bytes) { this.bytes.write(bytes, 0, length) } }
    override fun exited(code: Int) { exit.complete(code) }

    fun text(): String = synchronized(bytes) { bytes.toByteArray().decodeToString() }

    /** Wait until the output matches [pattern]; the match, or a failure naming what arrived. */
    fun await(pattern: Regex, timeoutMs: Long = 10_000): MatchResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pattern.find(text())?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("No match for $pattern in:\n${text()}")
    }

    fun await(text: String, timeoutMs: Long = 10_000) = await(Regex(Regex.escape(text)), timeoutMs)
}

class PtySessionHostTest {
    private val folder: Path = createTempDirectory("hovel-pty")
    private var scripts = mutableMapOf<Launch, List<String>>()
    private val host = PtySessionHost { spec -> ShellCommand(scripts.getValue(spec.launch), mapOf("PATH" to "/usr/bin:/bin", "TERM" to "xterm-256color", "LANG" to "en_US.UTF-8")) }

    @AfterTest fun cleanUp() {
        host.close()
        folder.toFile().deleteRecursively()
    }

    private fun start(script: String, project: String = "p1"): Session {
        assumeTrue("Pseudo-terminal tests run on macOS", System.getProperty("os.name").contains("Mac"))
        scripts[Launch.Shell] = listOf("/bin/sh", "-c", script)
        return host.start(SessionSpec(project, "Shell", folder, Launch.Shell, TermSize(80, 24)))
    }

    @Test fun outputFromBeforeTheFirstViewerIsNotLost() {
        val session = start("printf 'héllo wörld\\n'; exit 3")
        Thread.sleep(300)
        val viewer = CollectingViewer()
        session.attach(viewer)
        viewer.await("héllo wörld")
        assertEquals(3, viewer.exit.get(5, TimeUnit.SECONDS))
    }

    @Test fun runsInTheFolderOnATerminalOfTheSpecsSize() {
        val session = start("pwd; stty size; [ -t 0 ] && echo tty")
        val viewer = CollectingViewer().also { session.attach(it) }
        viewer.await(folder.toRealPath().toString())
        viewer.await("24 80")
        viewer.await("tty")
    }

    @Test fun keysReachTheProcessAndResizesReachItsTerminal() {
        val session = start("read line; echo \"got:\$line\"; read _; stty size")
        val viewer = CollectingViewer()
        val attachment = session.attach(viewer)
        attachment.input("abc\r".toByteArray())
        viewer.await("got:abc")
        attachment.resize(TermSize(132, 50))
        attachment.input("\r".toByteArray())
        viewer.await("50 132")
    }

    @Test fun aLateViewerHearsAnEarlierExit() {
        val session = start("exit 7")
        val first = CollectingViewer().also { session.attach(it) }
        assertEquals(7, first.exit.get(5, TimeUnit.SECONDS))
        val late = CollectingViewer().also { session.attach(it) }
        assertEquals(7, late.exit.get(1, TimeUnit.SECONDS))
    }

    @Test fun everyViewerHearsTheEndOnce() {
        class Counting : SessionViewer {
            val exits = CopyOnWriteArrayList<Int>()
            override fun output(bytes: ByteArray, length: Int) {}
            override fun exited(code: Int) { exits.add(code) }
        }
        val session = start("read _")
        val early = Counting().also { session.attach(it) }
        // SIGHUP ends the shell, so the exit watcher and close both report: each viewer hears one of them.
        session.close()
        val late = Counting().also { session.attach(it) }
        Thread.sleep(200)
        assertEquals(1, early.exits.size, "${early.exits}")
        assertEquals(early.exits, late.exits)
    }

    @Test fun processesListsTheRunningTreeForTheProbe() {
        val session = start("sleep 30 & wait")
        session.attach(CollectingViewer())
        Thread.sleep(200)
        val commands = session.processes().map { it.info().command().orElse("?") }
        assertTrue(commands.size >= 2 && commands.any { it.endsWith("/sleep") }, "$commands")
    }

    @Test fun busyMeansTheProcessHasLiveChildren() {
        val idle = start("read _")
        idle.attach(CollectingViewer())
        Thread.sleep(200)
        assertFalse(idle.busy(), "a process waiting for input with no children is idle")
        val working = start("sleep 30 & wait")
        working.attach(CollectingViewer())
        Thread.sleep(200)
        assertTrue(working.busy(), "a running child makes it busy")
    }

    @Test fun aToolsOwnHelperProcessIsNotWork() {
        assumeTrue(System.getProperty("os.name").contains("Mac"))
        // Like Neovim's interface process and its `nvim --embed` server: a child running the same
        // executable, waiting, with nothing of its own running.
        val script = "/bin/sh -c 'read x < /dev/tty; :' & wait"
        val tool = Launch.Tool(WorkspaceTool.NEOVIM)
        scripts[tool] = listOf("/bin/sh", "-c", script)
        val editor = host.start(SessionSpec("p1", "Neovim", folder, tool, TermSize(80, 24)))
        editor.attach(CollectingViewer())
        val shell = start(script)
        shell.attach(CollectingViewer())
        Thread.sleep(300)
        assertFalse(editor.busy(), "a tool's own helper is part of the tool")
        assertTrue(shell.busy(), "in a shell tab, any child is work")
    }

    @Test fun closeEndsTheProcessGroupAndChildrenThatOutliveTheShell() {
        // The child ignores SIGHUP and is in its own process group, so only terminateProcessTree ends it.
        val session = start("trap '' HUP; (trap '' HUP; exec sleep 60) & echo child:\$!; wait")
        val viewer = CollectingViewer().also { session.attach(it) }
        val child = ProcessHandle.of(viewer.await(Regex("child:(\\d+)")).groupValues[1].toLong()).orElseThrow()
        assertTrue(child.isAlive)
        val started = System.nanoTime()
        session.close()
        assertFalse(child.isAlive, "the child is gone")
        assertTrue(viewer.exit.isDone, "viewers hear the end")
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5)
        session.close() // idempotent
    }

    @Test fun anIdleShellClosesQuickly() {
        val session = start("read _")
        val viewer = CollectingViewer().also { session.attach(it) }
        Thread.sleep(100)
        val started = System.nanoTime()
        session.close()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000, "SIGHUP ends it without the force-kill wait")
        assertTrue(viewer.exit.isDone)
    }

    @Test fun closeProjectEndsOnlyThatProject() {
        val mine = start("read _", project = "mine")
        val theirs = start("read _", project = "theirs")
        val mineViewer = CollectingViewer().also { mine.attach(it) }
        val theirsViewer = CollectingViewer().also { theirs.attach(it) }
        host.closeProject("mine")
        assertTrue(mineViewer.exit.isDone)
        assertFalse(theirsViewer.exit.isDone)
        theirs.close()
    }

    @Test fun aDetachedViewerStopsReceivingAndTheSessionKeepsRunning() {
        val session = start("read a; echo one; read b; echo two; read _")
        val first = CollectingViewer()
        val attachment = session.attach(first)
        val second = CollectingViewer()
        val other = session.attach(second)
        attachment.input("\r".toByteArray())
        first.await("one")
        attachment.close()
        other.input("\r".toByteArray())
        second.await("two")
        assertFalse("two" in first.text())
        assertFalse(first.exit.isDone)
        session.close()
    }

    @Test fun packagedNativesAreUsedOnlyWhenPresent() {
        val keys = listOf(PTY4J_NATIVE_FOLDER, JNA_BOOT_PATH, "jna.nounpack")
        val previous = keys.associateWith { System.getProperty(it) }
        try {
            keys.forEach(System::clearProperty)
            usePackagedNatives(null)
            usePackagedNatives(folder.toString())
            keys.forEach { assertEquals(null, System.getProperty(it), "no natives, no change to $it") }
            folder.resolve("pty4j/darwin").toFile().mkdirs()
            folder.resolve("pty4j/darwin/libpty.dylib").toFile().writeText("")
            folder.resolve("jna").toFile().mkdirs()
            folder.resolve("jna/libjnidispatch.jnilib").toFile().writeText("")
            usePackagedNatives(folder.toString())
            assertEquals(folder.resolve("pty4j").toFile().absolutePath, System.getProperty(PTY4J_NATIVE_FOLDER))
            assertEquals(folder.resolve("jna").toFile().absolutePath, System.getProperty(JNA_BOOT_PATH))
            assertEquals("true", System.getProperty("jna.nounpack"))
        } finally {
            previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        }
    }
}
