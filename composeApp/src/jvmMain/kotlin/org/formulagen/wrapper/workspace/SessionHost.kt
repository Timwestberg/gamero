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

data class SessionSpec(val projectId: String, val title: String, val folder: Path, val launch: Launch, val size: TermSize)

interface SessionHost : AutoCloseable {
    fun start(spec: SessionSpec): Session
    /** Ends every session of the project. Used when its window closes. */
    fun closeProject(projectId: String)
}

interface Session {
    val title: String
    /** True when the foreground process is not an idle shell or a catalog tool. */
    fun busy(): Boolean
    fun attach(viewer: SessionViewer): Attachment
    fun close()
}

/** Pushed output. The window adapts this to JediTerm's TtyConnector. */
interface SessionViewer {
    /** [bytes] holds [length] new bytes and is reused after the call returns: copy what you keep. */
    fun output(bytes: ByteArray, length: Int)
    fun exited(code: Int)
}

interface Attachment : AutoCloseable {
    fun input(bytes: ByteArray)
    fun resize(size: TermSize)
}
