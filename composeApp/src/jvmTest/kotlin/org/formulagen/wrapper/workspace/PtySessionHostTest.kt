package org.formulagen.wrapper.workspace

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.formulagen.wrapper.conversations.eventually
import org.junit.Assume.assumeTrue

class PtySessionHostTest {
    private val folder: Path = createTempDirectory("hovel-pty")
    private var scripts = mutableMapOf<Launch, List<String>>()
    private var environment = mapOf("PATH" to "/usr/bin:/bin", "TERM" to "xterm-256color", "LANG" to "en_US.UTF-8")
    private val host = PtySessionHost { spec, toolArguments -> ShellCommand(scripts.getValue(spec.launch) + toolArguments, environment) }

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

    @Test fun aShellWithNoJobControlIsIdleWhileItsChildrenShareItsGroup() {
        // /bin/sh -c runs its children in its own process group, the terminal's foreground one, so
        // the foreground rule sees no job: a known limit, as no Shell tab runs without job control.
        val idle = start("read _")
        idle.attach(CollectingViewer())
        Thread.sleep(200)
        assertNull(idle.busy(), "a process waiting for input with no children is idle")
        val working = start("sleep 30 & wait")
        working.attach(CollectingViewer())
        Thread.sleep(200)
        assertNull(working.busy(), "its children are in the shell's own group, so they are not a foreground job")
    }

    /** An interactive zsh with job control and only this test's `.zshrc`, as a Shell tab runs a login shell. */
    private fun interactiveShell(rc: String = ""): Pair<Session, Attachment> {
        assumeTrue("Pseudo-terminal tests run on macOS", System.getProperty("os.name").contains("Mac"))
        folder.resolve(".zshrc").toFile().writeText("PS1='READY> '\n$rc\n")
        environment = environment + ("ZDOTDIR" to folder.toString())
        scripts[Launch.Shell] = listOf("/bin/zsh", "-i")
        val session = host.start(SessionSpec("p1", "Shell", folder, Launch.Shell, TermSize(80, 24)))
        val viewer = CollectingViewer()
        val keys = session.attach(viewer)
        viewer.await("READY> ")
        return session to keys
    }

    @Test fun aShellTabIsBusyOnlyWhileAForegroundJobRuns() {
        val (shell, keys) = interactiveShell()
        assertNull(shell.busy(), "an idle prompt")
        keys.input("sleep 30 &\r".toByteArray())
        Thread.sleep(300)
        assertNull(awaitBusy(shell) { it == null }, "a background job is not the foreground")
        keys.input("sleep 31\r".toByteArray())
        assertEquals(Busy.Running("sleep 31"), awaitBusy(shell) { it != null }, "a foreground job names itself")
        keys.input(byteArrayOf(0x03))
        assertNull(awaitBusy(shell) { it == null }, "back at the prompt")
    }

    @Test fun aSuspendedJobKeepsAShellTabBusy() {
        val (shell, keys) = interactiveShell()
        keys.input("sleep 30\r".toByteArray())
        assertEquals(Busy.Running("sleep 30"), awaitBusy(shell) { it != null })
        keys.input(byteArrayOf(0x1a))
        assertEquals(Busy.Stopped("sleep 30"), awaitBusy(shell) { it is Busy.Stopped }, "⌃Z: the shell has the terminal back, the job waits")
        keys.input("fg\r".toByteArray())
        assertEquals(Busy.Running("sleep 30"), awaitBusy(shell) { it is Busy.Running })
        keys.input(byteArrayOf(0x03))
        assertNull(awaitBusy(shell) { it == null }, "back at the prompt with no job")
        // The reported case: an editor suspends itself on ⌃Z, from raw mode.
        keys.input("vi -n -u NONE -i NONE notes.txt\r".toByteArray())
        assertEquals(Busy.Running("vi -n -u NONE -i NONE notes.txt"), awaitBusy(shell) { it != null })
        keys.input("ihello\u001b".toByteArray())
        keys.input(byteArrayOf(0x1a))
        assertEquals(Busy.Stopped("vi -n -u NONE -i NONE notes.txt"), awaitBusy(shell) { it is Busy.Stopped })
    }

    @Test fun aShellReplacedByExecIsTheProgramThatReplacedIt() {
        val (shell, keys) = interactiveShell()
        keys.input("exec sleep 32\r".toByteArray())
        assertEquals(Busy.Running("sleep 32"), awaitBusy(shell) { it != null })
    }

    @Test fun aShellReplacedByAProgramItCannotReadIsBusy() {
        // Like `exec sudo -s`: a setuid program's command line cannot be read. login waits at its prompt.
        val (shell, keys) = interactiveShell()
        keys.input("exec /usr/bin/login\r".toByteArray())
        assertEquals(Busy.Running("a program"), awaitBusy(shell) { it != null })
    }

    @Test fun promptHelpersDoNotMakeAShellTabBusy() {
        // Like powerlevel10k's gitstatusd (a disowned daemon) and zsh-async's workers (a zpty, its own terminal).
        val (shell, _) = interactiveShell("sleep 30 &!\nzmodload zsh/zpty && zpty hovel_worker sleep 30")
        Thread.sleep(300)
        val helpers = shell.processes().map { it.info().command().orElse("?") }.filter { it.endsWith("/sleep") }
        assertEquals(2, helpers.size, "both helpers run: ${shell.processes().map { it.info().command() }}")
        assertNull(shell.busy(), "a prompt with helpers running is idle")
    }

    @Test fun aToolsOwnHelperProcessIsNotWork() {
        // Like a tool whose interface process runs a server of itself: a child running the same
        // executable, waiting, with nothing of its own running.
        val script = "/bin/sh -c 'read x < /dev/tty; :' & wait"
        val tool = Launch.Tool(WorkspaceTool.LAZYGIT)
        assumeTrue("Pseudo-terminal tests run on macOS", System.getProperty("os.name").contains("Mac"))
        scripts[tool] = listOf("/bin/sh", "-c", script)
        val lazygit = host.start(SessionSpec("p1", "lazygit", folder, tool, TermSize(80, 24)))
        lazygit.attach(CollectingViewer())
        eventually("the helper runs") { lazygit.processes().size == 2 }
        // Settled: macOS's /bin/sh re-executes itself as another shell.
        assertNull(awaitBusy(lazygit) { it == null }, "a tool's own helper is part of the tool")
        scripts[tool] = listOf("/bin/sh", "-c", "sleep 30 & wait")
        val working = host.start(SessionSpec("p1", "lazygit", folder, tool, TermSize(80, 24)))
        working.attach(CollectingViewer())
        assertEquals(Busy.Running("sleep 30"), awaitBusy(working) { it != null }, "anything else it starts is work")
    }

    @Test fun anEndedSessionIsNotBusy() {
        val session = start("sleep 30 & wait")
        session.attach(CollectingViewer())
        session.close()
        assertNull(session.busy())
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
