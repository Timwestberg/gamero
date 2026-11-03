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
import org.formulagen.wrapper.approvals.NativeDialog

/**
 * Workspace windows: closing ends their tabs' processes whatever state their start is in; closing a
 * tab or window, or quitting, asks first exactly when something is busy; and the window shortcuts.
 */
class WorkspaceWindowsTest {
    private val folder: Path = createTempDirectory("hovel-windows")
    private val other: Path = createTempDirectory("hovel-windows-other")
    private val host = FakeHost()
    private val asked = CopyOnWriteArrayList<NativeDialog<Boolean>>()
    @Volatile private var answer = false
    /** Runs while a question is up, as the sheet's modal loop would deliver more requests. */
    @Volatile private var duringQuestion: () -> Unit = {}
    private val workspaces = WorkspaceWindows(confirmer = { _, question -> asked.add(question); duringQuestion(); answer }) { host }

    @AfterTest fun cleanUp() {
        host.release.countDown()
        workspaces.close()
        folder.toFile().deleteRecursively()
        other.toFile().deleteRecursively()
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

    /** Wait until [condition] holds on the AWT thread, after the workers and posted updates have run. */
    private fun eventually(what: String, condition: () -> Boolean) = org.formulagen.wrapper.conversations.eventually(what) { onAwtNow(condition) }

    private fun running(window: WorkspaceWindowState, count: Int) =
        eventually("$count running tabs") { window.tabs.size == count && window.tabs.all { it.state == TabState.Running } }

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
        assertEquals(
            NativeDialog("Close this tab?", listOf("Neovim has unsaved changes in notes.txt. Closing the tab ends it."), listOf("Close Tab" to true, "Cancel" to false), false),
            asked.single(),
        )
        assertEquals(1, onAwtNow { window.tabs.size }, "no keeps the tab")
        assertFalse(host.sessions.single().isClosed())
        answer = true
        EventQueue.invokeAndWait { window.perform(WorkspaceAction.CloseTab) }
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
            listOf(
                "Closing the window ends what is running in it:",
                "• Shell (tab 1): “npm run dev” is running",
                "• Shell (tab 3): Neovim did not answer, so it may have unsaved work",
            ),
            question.lines,
        )
        assertEquals(listOf("Close Window" to true, "Cancel" to false), question.buttons)
        assertTrue(onAwtNow { window in workspaces.windows }, "no keeps the window")
        answer = true
        EventQueue.invokeAndWait { workspaces.requestClose(window) }
        eventually("closed on yes") { window !in workspaces.windows }
        host.sessions.forEach { assertTrue(it.closed.await(5, SECONDS)) }
    }

    @Test fun aCheckThatThrowsCountsAsBusyEverywhere() {
        val window = open()
        running(window, 1)
        host.sessions.single().busyFails = true
        EventQueue.invokeAndWait { window.requestCloseTab(window.tabs.single()) }
        eventually("asked to close the tab") { asked.size == 1 }
        assertEquals(listOf("Shell did not answer, so it may have unsaved work. Closing the tab ends it."), asked[0].lines)
        EventQueue.invokeAndWait { workspaces.requestClose(window) }
        eventually("asked to close the window") { asked.size == 2 }
        var quits = 0
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel") { quits++ } }
        eventually("asked to quit") { asked.size == 3 }
        assertEquals(0, quits, "no keeps the app")
        window.pollBusy()
        eventually("the tab bar shows it busy") { window.tabs.single().busy == Busy.NoAnswer("Shell") }
    }

    @Test fun aCheckTheWorkersRefuseCountsAsBusy() {
        val window = open()
        running(window, 1)
        workspaces.close()
        EventQueue.invokeAndWait { window.requestCloseTab(window.tabs.single()) }
        eventually("asked") { asked.size == 1 }
        assertEquals(1, onAwtNow { window.tabs.size })
    }

    @Test fun theDotAsksANeovimTabOnlyAfterActivityOrEveryFifthRound() {
        val window = open()
        running(window, 1)
        onAwtNow { window.addTab(Launch.Tool(WorkspaceTool.NEOVIM)) }
        running(window, 2)
        val (shell, editor) = host.sessions
        repeat(5) { window.pollBusy() }
        assertEquals(5, shell.busyCalls.get(), "other tabs are asked every round")
        assertEquals(2, editor.busyCalls.get(), "Neovim: the first round, then the fifth")
        (editor.viewer as SessionTtyConnector).output("x".toByteArray(), 1)
        window.pollBusy()
        assertEquals(3, editor.busyCalls.get(), "output since the last ask")
        window.pollBusy()
        assertEquals(3, editor.busyCalls.get())
        editor.busy = Busy.Unsaved("Neovim", emptyList())
        EventQueue.invokeAndWait { window.requestCloseTab(window.tabs[1]) }
        eventually("closing asks fresh") { asked.size == 1 }
        assertEquals(4, editor.busyCalls.get())
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
            NativeDialog(
                "Quit Hovel?",
                listOf(
                    "Quitting ends what is running in your workspaces:",
                    "", "In “${first.title}”:", "• Shell (tab 1): “sleep 30” is running",
                    "", "In “${second.title}”:", "• Shell (tab 1): Neovim has unsaved changes",
                ),
                listOf("Quit" to true, "Cancel" to false),
                false,
            ),
            asked.single(),
        )
        assertEquals(0, quits, "no keeps the app")
        answer = true
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel") { quits++ } }
        eventually("quit on yes") { quits == 1 }
        assertEquals(2, asked.size)
    }

    /** Let the workers and posted updates run, then return: nothing more should happen. */
    private fun settle() {
        Thread.sleep(300)
        onAwtNow {}
    }

    @Test fun aSecondRequestWhileAQuestionIsUpIsIgnored() {
        val window = open()
        running(window, 1)
        val tab = onAwtNow { window.tabs.single() }
        host.sessions.single().busy = Busy.Running("make")
        var quits = 0
        duringQuestion = { workspaces.confirmQuit("Hovel") { quits++ } }
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel") { quits++ } }
        eventually("asked to quit") { asked.size == 1 }
        settle()
        assertEquals(1, asked.size, "⌘Q during the quit question asks nothing more")
        duringQuestion = { window.requestCloseTab(tab) }
        EventQueue.invokeAndWait { window.requestCloseTab(tab) }
        eventually("asked to close the tab") { asked.size == 2 }
        settle()
        assertEquals(2, asked.size, "⌘W during the tab's question asks nothing more")
        duringQuestion = { workspaces.requestClose(window) }
        EventQueue.invokeAndWait { workspaces.requestClose(window) }
        eventually("asked to close the window") { asked.size == 3 }
        settle()
        assertEquals(3, asked.size, "the close button during the window's question asks nothing more")
        assertEquals(0, quits)
        answer = true
        duringQuestion = { workspaces.confirmQuit("Hovel") { quits++ } }
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel") { quits++ } }
        eventually("quit on yes") { quits == 1 }
        settle()
        assertEquals(1, quits, "the exit runs once")
    }

    @Test fun theSystemsQuitIsCancelledOnlyWhenThePersonKeepsTheApp() {
        val window = open()
        running(window, 1)
        host.sessions.single().busy = Busy.Running("make")
        var quits = 0
        var cancels = 0
        // A second system quit (a logout after ⌘Q, say) arrives while the question is up.
        duringQuestion = { workspaces.confirmQuit("Hovel", onCancel = { cancels += 10 }) { quits++ } }
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel", onCancel = { cancels++ }) { quits++ } }
        eventually("cancelled") { cancels == 11 }
        assertEquals(0, quits, "Cancel answers every waiting request")
        answer = true
        duringQuestion = {}
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel", onCancel = { cancels++ }) { quits++ } }
        eventually("quit") { quits == 1 }
        settle()
        assertEquals(11, cancels, "Quit leaves the system's request to the exit")
    }

    @Test fun withNothingBusyTheSystemsQuitIsNotCancelled() {
        open().also { running(it, 1) }
        var quits = 0
        var cancels = 0
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel", onCancel = { cancels++ }) { quits++ } }
        eventually("quit at once") { quits == 1 }
        assertEquals(0, cancels)
    }

    @Test fun onceTheExitHasStartedQuitRequestsAreIgnored() {
        val window = open()
        running(window, 1)
        host.sessions.single().busy = Busy.Running("make")
        answer = true
        var quits = 0
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel") { quits++ } }
        eventually("quit on yes") { quits == 1 }
        // As during the browser's shutdown: the workers are gone, every check would be no answer.
        workspaces.close()
        EventQueue.invokeAndWait { workspaces.confirmQuit("Hovel") { quits++ } }
        settle()
        assertEquals(1, asked.size, "no second question")
        assertEquals(1, quits)
        val other = WorkspaceWindows(confirmer = { _, question -> asked.add(question); true }) { host }
        onAwtNow { other.open(folder) }.also { running(it, 1) }
        other.exitStarted()
        EventQueue.invokeAndWait { other.confirmQuit("Hovel") { quits++ } }
        settle()
        assertEquals(1, quits, "an exit started by another path, such as a failure, counts too")
        other.close()
    }

    @Test fun tabShortcutsSelectByNumberAndWrapAround() {
        val window = open()
        running(window, 1)
        onAwtNow { repeat(3) { window.addTab(Launch.Shell) } }
        running(window, 4)
        val ids = onAwtNow { window.tabs.map { it.id } }
        fun selectedIndex() = onAwtNow { ids.indexOf(window.selected) }
        onAwtNow { window.perform(WorkspaceAction.SelectTab(1)) }
        assertEquals(1, selectedIndex())
        onAwtNow { window.perform(WorkspaceAction.SelectTab(7)) }
        assertEquals(1, selectedIndex(), "no eighth tab: nothing changes")
        onAwtNow { window.perform(WorkspaceAction.SelectLastTab) }
        assertEquals(3, selectedIndex())
        onAwtNow { window.perform(WorkspaceAction.NextTab) }
        assertEquals(0, selectedIndex(), "next wraps to the first")
        onAwtNow { window.perform(WorkspaceAction.PreviousTab) }
        assertEquals(3, selectedIndex(), "previous wraps to the last")
        onAwtNow { window.perform(WorkspaceAction.PreviousTab) }
        assertEquals(2, selectedIndex())
    }

    @Test fun aMenuItemRepeatingAKeyPressIsItsEchoAndIgnored() {
        var now = 0L
        val window = onAwtNow { WorkspaceWindowState("p", folder, lazy { host }, java.util.concurrent.Executors.newSingleThreadExecutor(), clock = { now }) }
        onAwtNow {
            window.performFromKey(WorkspaceAction.ZoomIn)
            window.performFromMenu(WorkspaceAction.ZoomIn)
        }
        assertEquals(TerminalFont.DEFAULT_SIZE + 1, window.fontSize, "the menu's echo of ⌘+ is dropped")
        onAwtNow { window.performFromMenu(WorkspaceAction.ZoomIn) }
        assertEquals(TerminalFont.DEFAULT_SIZE + 2, window.fontSize, "a menu click on its own counts")
        onAwtNow {
            window.performFromKey(WorkspaceAction.ZoomIn)
            now += SECONDS.toNanos(1)
            window.performFromMenu(WorkspaceAction.ZoomIn)
        }
        assertEquals(TerminalFont.DEFAULT_SIZE + 4, window.fontSize, "a late menu click is not an echo")
        onAwtNow {
            window.performFromKey(WorkspaceAction.ZoomOut)
            window.performFromMenu(WorkspaceAction.ZoomReset)
        }
        assertEquals(TerminalFont.DEFAULT_SIZE, window.fontSize, "another action is not an echo")
    }

    @Test fun zoomStaysWithinItsLimitsAndResets() {
        val window = open()
        running(window, 1)
        onAwtNow { repeat(100) { window.perform(WorkspaceAction.ZoomIn) } }
        assertEquals(TerminalFont.MAX_SIZE, window.fontSize)
        onAwtNow { repeat(100) { window.perform(WorkspaceAction.ZoomOut) } }
        assertEquals(TerminalFont.MIN_SIZE, window.fontSize)
        onAwtNow { window.perform(WorkspaceAction.ZoomReset) }
        assertEquals(TerminalFont.DEFAULT_SIZE, window.fontSize)
        assertEquals(TerminalFont.MAX_SIZE, TerminalFont.zoomed(TerminalFont.MAX_SIZE, 1))
        assertEquals(TerminalFont.MIN_SIZE, TerminalFont.zoomed(TerminalFont.MIN_SIZE, -1))
    }

    @Test fun aTabTitleFollowsTheProgramAndFallsBackToItsLabel() {
        val window = open()
        running(window, 1)
        val tab = onAwtNow { window.tabs.single() }
        val viewer = host.sessions.single().viewer as SessionTtyConnector
        fun title(sequence: String) = sequence.toByteArray().let { viewer.output(it, it.size) }
        title("\u001b]0;vim notes.txt\u0007")
        eventually("the program's title") { tab.title == "vim notes.txt" }
        title("\u001b]2;\u0007")
        eventually("an empty title falls back to the label") { tab.title == "Shell" }
        title("\u001b]2;make test\u001b\\")
        eventually("a title ended by ST") { tab.title == "make test" }
        title("\u001b]2; \u0007")
        eventually("a blank title falls back") { tab.title == "Shell" }
        assertEquals("Shell", tab.label)
        assertNull(programTitle(" \u0007 "))
        assertEquals(60, programTitle("x".repeat(200))!!.length)
    }

    @Test fun aMinimizedWindowIsRestoredBeforeItsQuestion() {
        val window = open()
        val frame = java.awt.Frame()
        try {
            onAwtNow {
                frame.extendedState = java.awt.Frame.ICONIFIED
                window.awtWindow = frame
                window.bringForward()
            }
            assertEquals(java.awt.Frame.NORMAL, frame.extendedState)
            assertEquals(1, window.frontRequests)
        } finally {
            onAwtNow { window.awtWindow = null; frame.dispose() }
        }
    }

    @Test fun oneTabEndingLeavesTheOthersAsTheyWere() {
        val window = open()
        running(window, 1)
        onAwtNow { window.addTab(Launch.Shell) }
        running(window, 2)
        val (first, second) = onAwtNow { window.tabs.toList() }
        assertEquals(second.id, onAwtNow { window.selected })
        host.sessions[0].viewer!!.exited(3)
        eventually("the first tab exited") { first.state == TabState.Exited(3) }
        assertEquals(TabState.Running, onAwtNow { second.state })
        assertEquals(second.id, onAwtNow { window.selected }, "the selection stays")
        assertFalse(host.sessions[1].isClosed())
        assertTrue(onAwtNow { window.deck.view(first.id) != null }, "the ended tab keeps its screen")
    }
}
