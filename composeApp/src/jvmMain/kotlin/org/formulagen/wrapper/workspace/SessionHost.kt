package org.formulagen.wrapper.workspace

import java.nio.file.Path

/*
 * The seam that keeps a workspace tab's session apart from the window that shows it
 * (project-workspace.ARCH.md, "Sessions"). In v1 the window is the only viewer; a paired phone
 * attaches the same way later. [PtySessionHost] is the implementation on pty4j.
 */

data class TermSize(val columns: Int, val rows: Int)

sealed interface Launch {
    /** The player's login shell, with Hovel's integration. */
    data object Shell : Launch
    /** A catalog tool, started through the shell so the player's PATH and Hovel's order apply. */
    data class Tool(val tool: WorkspaceTool) : Launch
    /** A read-only git diff of recorded files, piped through delta, built natively. */
    data class Diff(val file: String, val history: Boolean) : Launch
}

/** True for the tab kind whose busy state comes from Neovim itself. */
internal fun Launch.isEditor(): Boolean = this is Launch.Tool && tool == WorkspaceTool.NEOVIM

data class SessionSpec(val projectId: String, val title: String, val folder: Path, val launch: Launch, val size: TermSize)

/** The exit code viewers hear when [Session.close] ended a session before its process's own exit was seen. */
const val CLOSED_EXIT_CODE = -1

/** Starts and ends sessions. Sessions belong to their project, not to a window. */
interface SessionHost : AutoCloseable {
    /** Start [spec]'s process. Throws when it cannot start, or once the host is closed. */
    fun start(spec: SessionSpec): Session

    /**
     * Ends every session of the project, as [Session.close] does, and returns when they have ended.
     * Used when its window closes. A session whose [start] is still running when this is called may
     * survive it: a window that closes also closes the sessions it was handed.
     */
    fun closeProject(projectId: String)

    /** Ends every session of every project and returns when they have ended. Later [start] calls fail. */
    override fun close()
}

/**
 * What a session is doing that closing it would end, as data for the close and quit confirmations,
 * which word it ([Confirmations.reason]). A session with nothing to lose answers null instead.
 */
sealed interface Busy {
    /** A program the player started: a Shell tab's foreground job, or a child of a tool. */
    data class Running(val command: String) : Busy

    /** A job the player suspended in a Shell tab (⌃Z): closing the tab ends it. */
    data class Stopped(val command: String) : Busy

    /** An editor with modified buffers. [files] names some of them: none when they are unnamed. */
    data class Unsaved(val editor: String, val files: List<String>) : Busy

    /** An editor whose own terminal is still running a job. */
    data class EditorTerminal(val editor: String) : Busy

    /** The session could not be asked in time, so it counts as busy: closing it might lose work. */
    data class NoAnswer(val what: String) : Busy
}

interface Session {
    val title: String

    /**
     * What closing the session would end, or null when it holds nothing to lose: an idle shell
     * (background jobs and prompt helpers do not count), a catalog tool with no work of its own, an
     * editor with every buffer saved, or a session that has ended. Bounded in time: about 3 seconds at
     * most, when Neovim does not answer (its 1-second limit, then up to 2 seconds to stop the client
     * or read its answer). Call it off the AWT thread.
     */
    fun busy(): Busy?

    /**
     * Adds [viewer]. It gets the output read from now on, never earlier output: the reader starts
     * with the first attach, so the first viewer gets everything since the process started, and a
     * later one only what is read after it attaches. A viewer attaching after the session ended hears
     * [SessionViewer.exited] before this returns. Closing the [Attachment] detaches the viewer and
     * leaves the session running.
     */
    fun attach(viewer: SessionViewer): Attachment

    /**
     * Ends the process, its process group and its descendants (a process that has already left the
     * tree, such as a daemon, is not one), and returns once they are gone or force-killed: about 2.5
     * seconds at most. Every attached viewer has heard [SessionViewer.exited] by then. Any thread; a
     * second call returns at once, even while the first is still ending the process.
     */
    fun close()

    /** Development probe only. The session's process and its live descendants; none once it has exited. */
    fun processes(): List<ProcessHandle> = emptyList()
}

/**
 * A window, or later a paired phone, showing a session. The session pushes to it; the window adapts
 * this to JediTerm's pull-based TtyConnector ([SessionTtyConnector]).
 */
interface SessionViewer {
    /**
     * New output. Called on the session's one reader thread, for each attached viewer in turn, so it
     * must not block, except to wait for room in the viewer's own bounded buffer: that wait is the
     * session's only back-pressure. While one viewer waits, the others get nothing, and once the
     * pseudo-terminal fills, the process waits too.
     * [bytes] holds [length] new bytes and is reused after the call returns: copy what you keep.
     */
    fun output(bytes: ByteArray, length: Int)

    /**
     * The session ended: [code] is the process's exit status, or [CLOSED_EXIT_CODE]. Called once per
     * viewer, after the reader has delivered the process's last output or a short wait for it has
     * passed: on the session's exit thread, on the thread in [Session.close], or inside
     * [Session.attach] for a viewer that attaches after the end.
     */
    fun exited(code: Int)
}

/** One viewer's way back into its session. Any thread; calls after the session ended are ignored. */
interface Attachment : AutoCloseable {
    /** Bytes to the process, as typed. */
    fun input(bytes: ByteArray)
    fun resize(size: TermSize)
    /** Detaches the viewer: no more output or exit. The session keeps running. */
    override fun close()
}
