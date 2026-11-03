package org.formulagen.wrapper.workspace

import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Workspace windows: closing ends their tabs' processes whatever state their start is in; and closing
 * a tab or window, or quitting, asks first exactly when something is busy.
 */
class WorkspaceWindowsTest {
    private val folder: Path = createTempDirectory("hovel-windows")
    private val other: Path = createTempDirectory("hovel-windows-other")
    private val host = FakeHost()
    private val asked = CopyOnWriteArrayList<Confirmation>()
    @Volatile private var answer = false
    private val workspaces = WorkspaceWindows(confirmer = { _, question -> asked.add(question); answer }) { host }

    @AfterTest fun cleanUp() {
        host.release.countDown()
        workspaces.close()
        folder.toFile().deleteRecursively()
        other.toFile().deleteRecursively()
    }

    private class FakeSession : Session {
        val closed = CountDownLatch(1)
        @Volatile var busy: Busy? = null
        @Volatile var viewer: SessionViewer? = null
        override val title = "Shell"
        override fun busy() = busy
        override fun attach(viewer: SessionViewer): Attachment {
            this.viewer = viewer
            return object : Attachment {
                override fun input(bytes: ByteArray) {}
                override fun resize(size: TermSize) {}
                override fun close() {}
            }
        }
        override fun close() { closed.countDown() }
    }

    /**
     * A host whose [start] waits for [release] (open by default), and whose [closeProject] knows no
     * sessions, as when a start registers its session only after the project was closed: the window
     * must end it.
     */
    private class FakeHost : SessionHost {
        val starting = CountDownLatch(1)
        @Volatile var release = CountDownLatch(0)
        val sessions = CopyOnWriteArrayList<FakeSession>()
        override fun start(spec: SessionSpec): Session {
            starting.countDown()
            release.await()
            return FakeSession().also(sessions::add)
        }
        override fun closeProject(projectId: String) {}
        override fun close() {}
    }

    private fun open(at: Path = folder): WorkspaceWindowState = onAwtNow { workspaces.open(at) }

    private fun <T> onAwtNow(read: () -> T): T {
        var value: Result<T>? = null
        EventQueue.invokeAndWait { value = runCatching(read) }
        return value!!.getOrThrow()
    }

    /** Wait until [condition] holds on the AWT thread, after the workers and posted updates have run. */
    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (onAwtNow(condition)) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out: $what")
    }

    private fun running(window: WorkspaceWindowState, count: Int) =
        eventually("$count running tabs") { window.tabs.size == count && window.tabs.all { it.state == TabState.Running } }

    private fun FakeSession.isClosed() = closed.count == 0L

    @Test fun closingTheWindowWhileATabStartsEndsTheSessionWhenItArrives() {
        host.release = CountDownLatch(1)
        val window = open()
        assertTrue(host.starting.await(5, SECONDS))
        EventQueue.invokeAndWait { workspaces.close(window) }
        host.release.countDown()
        eventually("the session arrives") { host.sessions.size == 1 }
        assertTrue(host.sessions.single().closed.await(5, SECONDS), "the session that arrived after the close is ended")
        assertNull(onAwtNow { window.deck.view(window.tabs.single().id) }, "and never shown")
    }

    @Test fun closingTheWindowEndsTheSessionsItsTabsHold() {
        val window = open()
        running(window, 1)
        EventQueue.invokeAndWait { workspaces.close(window) }
        assertTrue(host.sessions.single().closed.await(5, SECONDS), "the window ends its tab's session itself")
    }

    @Test fun anIdleTabClosesWithoutAsking() {
        val window = open()
        running(window, 1)
        EventQueue.invokeAndWait { window.requestCloseTab(window.tabs.single()) }
        eventually("the tab closed") { window.tabs.isEmpty() }
        assertTrue(asked.isEmpty())
        assertTrue(host.sessions.single().closed.await(5, SECONDS))
    }

    @Test fun aBusyTabAsksNamingWhatRunsAndClosesOnlyOnYes() {
        val window = open()
        running(window, 1)
        host.sessions.single().busy = Busy.Unsaved("Neovim", listOf("notes.txt"))
        EventQueue.invokeAndWait { window.requestCloseTab(window.tabs.single()) }
        eventually("asked") { asked.size == 1 }
        assertEquals(Confirmation("Close this tab?", "Neovim has unsaved changes in notes.txt. Closing the tab ends it.", "Close Tab"), asked.single())
        assertEquals(1, onAwtNow { window.tabs.size }, "no keeps the tab")
        assertFalse(host.sessions.single().isClosed())
        answer = true
        EventQueue.invokeAndWait { window.requestCloseTab(window.tabs.single()) }
        eventually("closed on yes") { window.tabs.isEmpty() }
        assertTrue(host.sessions.single().closed.await(5, SECONDS))
    }

    @Test fun closingABusyWindowListsItsBusyTabs() {
        val window = open()
        running(window, 1)
        onAwtNow { window.addTab(Launch.Shell); window.addTab(Launch.Shell) }
        running(window, 3)
        host.sessions[0].busy = Busy.Running("npm run dev")
        host.sessions[2].busy = Busy.NoAnswer("Neovim")
        EventQueue.invokeAndWait { workspaces.requestClose(window) }
        eventually("asked") { asked.size == 1 }
        val question = asked.single()
        assertEquals("Close “${window.title}”?", question.title)
        assertEquals(
            "Closing the window ends what is running in it:\n• Shell (tab 1): “npm run dev” is running\n" +
                "• Shell (tab 3): Neovim did not answer, so it may have unsaved work",
            question.message,
        )
        assertTrue(onAwtNow { window in workspaces.windows }, "no keeps the window")
        answer = true
        EventQueue.invokeAndWait { workspaces.requestClose(window) }
        eventually("closed on yes") { window !in workspaces.windows }
        host.sessions.forEach { assertTrue(it.closed.await(5, SECONDS)) }
    }

    @Test fun anIdleWindowClosesWithoutAsking() {
        val window = open()
        running(window, 1)
        EventQueue.invokeAndWait { workspaces.requestClose(window) }
        eventually("closed") { window !in workspaces.windows }
        assertTrue(asked.isEmpty())
    }

    @Test fun quittingWithNothingBusyQuitsAtOnce() {
        open().also { running(it, 1) }
        var quits = 0
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel") { quits++ } }
        eventually("quit") { quits == 1 }
        assertTrue(asked.isEmpty())
        // No window at all quits on the spot.
        val empty = WorkspaceWindows { host }
        var quit = false
        EventQueue.invokeAndWait { empty.confirmQuit("Hovel") { quit = true } }
        assertTrue(quit)
        empty.close()
    }

    @Test fun quittingAsksOnceListingEveryBusyWindowAndTab() {
        val first = open().also { running(it, 1) }
        val second = open(other).also { running(it, 1) }
        host.sessions[0].busy = Busy.Running("sleep 30")
        host.sessions[1].busy = Busy.Unsaved("Neovim", emptyList())
        var quits = 0
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel") { quits++ } }
        eventually("asked") { asked.size == 1 }
        assertEquals(
            Confirmation(
                "Quit Hovel?",
                "Quitting ends what is running in your workspaces:\n\nIn “${first.title}”:\n• Shell (tab 1): “sleep 30” is running" +
                    "\n\nIn “${second.title}”:\n• Shell (tab 1): Neovim has unsaved changes",
                "Quit",
            ),
            asked.single(),
        )
        assertEquals(0, quits, "no keeps the app")
        answer = true
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel") { quits++ } }
        eventually("quit on yes") { quits == 1 }
        assertEquals(2, asked.size)
    }

    @Test fun confirmationsAreAskedOnlyWhenSomethingIsBusy() {
        assertNull(Confirmations.closeTab(null))
        assertNull(Confirmations.closeWindow("w", emptyList()))
        assertNull(Confirmations.quit("Hovel", listOf(BusyWindow("w", emptyList()))))
        val busy = BusyTab("Shell (tab 1)", Busy.Running("make"))
        assertEquals("Close Window", Confirmations.closeWindow("w", listOf(busy))!!.proceed)
        assertEquals("Quit Hovel?", Confirmations.quit("Hovel", listOf(BusyWindow("w", emptyList()), BusyWindow("v", listOf(busy))))!!.title)
        val quit = Confirmations.quit("Hovel", listOf(BusyWindow("alpha", emptyList()), BusyWindow("beta", listOf(busy))))!!
        assertFalse("alpha" in quit.message, "only busy windows are listed")
        assertTrue("beta" in quit.message)
    }
}
