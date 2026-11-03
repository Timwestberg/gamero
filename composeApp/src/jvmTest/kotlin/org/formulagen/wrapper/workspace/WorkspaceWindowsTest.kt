package org.formulagen.wrapper.workspace

import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Closing a workspace window ends its tabs' processes, whatever state their start is in. */
class WorkspaceWindowsTest {
    private val folder: Path = createTempDirectory("hovel-windows")
    private val host = GatedHost()
    private val workspaces = WorkspaceWindows { host }

    @AfterTest fun cleanUp() {
        host.release.countDown()
        workspaces.close()
        folder.toFile().deleteRecursively()
    }

    private class FakeSession : Session {
        val closed = CountDownLatch(1)
        override val title = "Shell"
        override fun busy() = false
        override fun attach(viewer: SessionViewer): Attachment = object : Attachment {
            override fun input(bytes: ByteArray) {}
            override fun resize(size: TermSize) {}
            override fun close() {}
        }
        override fun close() { closed.countDown() }
    }

    /**
     * A host whose [start] waits for [release], and whose [closeProject] knows no sessions, as when
     * a start registers its session only after the project was closed: the window must end it.
     */
    private class GatedHost : SessionHost {
        val starting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeSession()
        override fun start(spec: SessionSpec): Session {
            starting.countDown()
            release.await()
            return session
        }
        override fun closeProject(projectId: String) {}
        override fun close() {}
    }

    private fun open(): WorkspaceWindowState {
        lateinit var window: WorkspaceWindowState
        EventQueue.invokeAndWait { window = workspaces.open(folder) }
        return window
    }

    private fun <T> onAwtNow(read: () -> T): T {
        var value: Result<T>? = null
        EventQueue.invokeAndWait { value = runCatching(read) }
        return value!!.getOrThrow()
    }

    @Test fun closingTheWindowWhileATabStartsEndsTheSessionWhenItArrives() {
        val window = open()
        assertTrue(host.starting.await(5, SECONDS))
        EventQueue.invokeAndWait { workspaces.close(window) }
        host.release.countDown()
        assertTrue(host.session.closed.await(5, SECONDS), "the session that arrived after the close is ended")
        assertNull(onAwtNow { window.deck.view(window.tabs.single().id) }, "and never shown")
    }

    @Test fun closingTheWindowEndsTheSessionsItsTabsHold() {
        host.release.countDown()
        val window = open()
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (onAwtNow { window.tabs.single().state } != TabState.Running && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(TabState.Running, onAwtNow { window.tabs.single().state })
        EventQueue.invokeAndWait { workspaces.close(window) }
        assertTrue(host.session.closed.await(5, SECONDS), "the window ends its tab's session itself")
    }
}
