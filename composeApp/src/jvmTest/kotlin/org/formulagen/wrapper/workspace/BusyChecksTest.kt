package org.formulagen.wrapper.workspace

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isExecutable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assume.assumeTrue

/** Neovim's answers, parsed and from a real Neovim; and the names a confirmation shows. */
class BusyChecksTest {
    // Short: a Unix socket's whole path must fit in 103 bytes.
    private val root: Path = createTempDirectory("hv")
    private val sockets = root.resolve("nvim")
    private val hosts = mutableListOf<PtySessionHost>()

    @AfterTest fun cleanUp() {
        hosts.forEach { it.close() }
        root.toFile().deleteRecursively()
    }

    @Test fun anAnswerListsUnsavedFilesThenATerminal() {
        assertNull(parseNeovimState("Neovim", "hovel-state"))
        assertNull(parseNeovimState("Neovim", "hovel-state\n"))
        assertEquals(Busy.Unsaved("Neovim", listOf("a.txt")), parseNeovimState("Neovim", "hovel-state\nM a.txt\nT"))
        assertEquals(Busy.Unsaved("Neovim", emptyList()), parseNeovimState("Neovim", "hovel-state\nM "), "an unnamed buffer")
        assertEquals(Busy.EditorTerminal("Neovim"), parseNeovimState("Neovim", "hovel-state\nT"))
        val many = parseNeovimState("Neovim", "hovel-state\n" + (1..5).joinToString("\n") { "M f$it.kt" }) as Busy.Unsaved
        assertEquals(listOf("f1.kt", "f2.kt", "f3.kt", "2 more"), many.files)
        assertEquals("Neovim has unsaved changes in f1.kt, f2.kt, f3.kt, 2 more", many.reason)
    }

    @Test fun anythingButAnAnswerIsNoAnswer() {
        assertEquals(Busy.NoAnswer("Neovim"), parseNeovimState("Neovim", ""))
        assertEquals(Busy.NoAnswer("Neovim"), parseNeovimState("Neovim", "0"))
        assertEquals(Busy.NoAnswer("Neovim"), askNeovim("/usr/bin/false", root.resolve("missing.sock"), "Neovim"))
    }

    @Test fun aNeovimThatNeverAnswersCountsAsBusyWithinTheTimeLimit() {
        val nvim = neovim()
        val socket = root.resolve("mute.sock")
        // Accepts the connection, then says nothing.
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            val accepting = Thread { runCatching { server.accept().use { Thread.sleep(10_000) } } }.apply { isDaemon = true; start() }
            val started = System.nanoTime()
            assertEquals(Busy.NoAnswer("Neovim"), askNeovim(nvim, socket, "Neovim", 400.milliseconds))
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs < 3_000, "bounded: $tookMs ms")
            accepting.interrupt()
        }
    }

    @Test fun aNeovimTabIsBusyWhileABufferIsModifiedOrItsTerminalRuns() {
        val nvim = neovim()
        val file = root.resolve("notes.txt").also { Files.writeString(it, "saved\n") }
        val (session, keys) = startNeovim(nvim, file, EditorSockets(sockets))
        val socket = awaitSocket()
        assertNull(awaitBusy(session) { it == null }, "every buffer saved")
        keys.input("ohello\u001b".toByteArray())
        assertEquals(Busy.Unsaved("Neovim", listOf("notes.txt")), awaitBusy(session) { it != null })
        keys.input(":w\r".toByteArray())
        assertNull(awaitBusy(session) { it == null }, "written")
        keys.input(":terminal sleep 30\r".toByteArray())
        assertEquals(Busy.EditorTerminal("Neovim"), awaitBusy(session) { it != null })
        session.close()
        assertFalse(Files.exists(socket), "the socket is removed when the session ends")
    }

    @Test fun aNeovimWithNoSocketAlwaysAsks() {
        val nvim = neovim()
        val (session, _) = startNeovim(nvim, root.resolve("x.txt"), sockets = null)
        assertEquals(Busy.NoAnswer("Neovim"), awaitBusy(session) { it != null })
        assertEquals("Neovim did not answer, so it may have unsaved work", session.busy()?.reason)
    }

    @Test fun beforeNeovimStartsNothingIsUnsaved() {
        assumeTrue(System.getProperty("os.name").contains("Mac"))
        // The login shell is still starting the tool: the tab's process is not Neovim yet.
        val host = PtySessionHost(EditorSockets(sockets)) { _, _ -> ShellCommand(listOf("/bin/sh", "-c", "read _"), mapOf("PATH" to "/usr/bin:/bin")) }.also(hosts::add)
        val session = host.start(SessionSpec("p", "Neovim", root, Launch.Tool(WorkspaceTool.NEOVIM), TermSize(80, 24)))
        session.attach(CollectingViewer())
        Thread.sleep(200)
        assertNull(session.busy())
    }

    @Test fun socketPathsFitAUnixSocketAndStayInTheirFolder() {
        val socket = EditorSockets(sockets).next()!!
        assertEquals(sockets.toAbsolutePath(), socket.parent)
        assertTrue(socket.toString().toByteArray().size <= EditorSockets.MAX_SOCKET_PATH_BYTES)
        assertTrue(socket.fileName.toString().matches(Regex("[0-9a-f]{12}\\.sock")))
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(sockets)))
        assertTrue(EditorSockets(sockets).next() != socket, "one per session")
        assertNull(EditorSockets(root.resolve("x".repeat(120))).next(), "too long for a socket")
        assertTrue(EditorSockets.defaultFolder("/Users/p").toString() == "/Users/p/Library/Caches/org.formulagen.wrapper/workspace/nvim")
    }

    @Test fun aRunningProgramIsNamedByItsScriptNotItsInterpreter() {
        assertEquals("npm run dev", commandLabel("/opt/homebrew/Cellar/node/22/bin/node", listOf("/opt/homebrew/bin/npm", "run", "dev")))
        assertEquals("manage.py runserver", commandLabel("/usr/bin/python3.12", listOf("/srv/app/manage.py", "runserver")))
        assertEquals("sleep 30", commandLabel("/bin/sleep", listOf("30")))
        assertEquals("node -e 1", commandLabel("/usr/local/bin/node", listOf("-e", "1")), "no script")
        assertEquals("a program", commandLabel(null, emptyList()))
        assertEquals(60, commandLabel("/bin/echo", listOf("x".repeat(100))).length)
        assertEquals("echo ab", commandLabel("/bin/echo", listOf("a\u001b\u0007b")), "control characters are dropped")
    }

    @Test fun busyReasonsNameWhatRuns() {
        assertEquals("“npm run dev” is running", Busy.Running("npm run dev").reason)
        assertEquals("Neovim has unsaved changes", Busy.Unsaved("Neovim", emptyList()).reason)
        assertEquals("Neovim has a terminal running", Busy.EditorTerminal("Neovim").reason)
    }

    private fun neovim(): String {
        assumeTrue(System.getProperty("os.name").contains("Mac"))
        val nvim = listOfNotNull(System.getenv("HOVEL_TEST_NVIM"), "/opt/homebrew/bin/nvim", "/usr/local/bin/nvim")
            .map(Path::of).firstOrNull { it.isExecutable() }
        assumeTrue("Neovim is installed (or HOVEL_TEST_NVIM names it)", nvim != null)
        return nvim!!.toRealPath().toString()
    }

    private fun startNeovim(nvim: String, file: Path, sockets: EditorSockets?): Pair<Session, Attachment> {
        val environment = mapOf("PATH" to "/usr/bin:/bin", "TERM" to "xterm-256color", "LANG" to "en_US.UTF-8", "HOME" to root.toString())
        val host = PtySessionHost(sockets) { _, toolArguments -> ShellCommand(listOf(nvim, "--clean", "-n", file.toString()) + toolArguments, environment) }
            .also(hosts::add)
        val session = host.start(SessionSpec("p", "Neovim", root, Launch.Tool(WorkspaceTool.NEOVIM), TermSize(100, 30)))
        val keys = session.attach(CollectingViewer())
        session.attach(TerminalReplies(keys))
        return session to keys
    }

    private fun awaitSocket(): Path {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            Files.list(sockets).use { files -> files.filter { it.fileName.toString().endsWith(".sock") }.findFirst().orElse(null) }?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("Neovim never listened in $sockets")
    }

    private fun awaitBusy(session: Session, expected: (Busy?) -> Boolean): Busy? {
        val deadline = System.currentTimeMillis() + 10_000
        var busy = session.busy()
        while (!expected(busy) && System.currentTimeMillis() < deadline) { Thread.sleep(100); busy = session.busy() }
        return busy
    }
}
