package org.formulagen.wrapper.workspace

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * The Mac's folder picker, behind an interface so [WorkspaceStore] is testable without a screen.
 * Blocks until the person chooses or cancels, so callers run it off the bridge thread.
 */
internal fun interface FolderPicker {
    /** The folder the person chose, or null when they cancelled. [title] says what it is for; [current] is where it opens. */
    fun choose(title: String, current: Path?): Path?
}

/** The Mac's file picker, behind an interface so a companion's attached inputs are testable without a screen. Blocks like [FolderPicker]. */
internal fun interface FilePicker {
    /** The file the person chose, or null when they cancelled. [title] says what it is for. */
    fun choose(title: String): Path?
}

/**
 * The native picker on the AWT event thread, in front of [owner] (Hovel's window, brought forward
 * first). On macOS it is the system's folder panel (`FileDialog` with
 * `apple.awt.fileDialogForDirectories`); elsewhere a Swing chooser limited to folders.
 */
internal class NativeFolderPicker(private val owner: () -> Frame?) : FolderPicker {
    override fun choose(title: String, current: Path?): Path? = nativeChoose(owner, title, current, directories = true)
}

/** [NativeFolderPicker]'s panel for one file: the system's file panel on macOS, a Swing chooser elsewhere. */
internal class NativeFilePicker(private val owner: () -> Frame?) : FilePicker {
    override fun choose(title: String): Path? = nativeChoose(owner, title, current = null, directories = false)
}

private const val DIRECTORIES = "apple.awt.fileDialogForDirectories"

private fun nativeChoose(owner: () -> Frame?, title: String, current: Path?, directories: Boolean): Path? {
    check(!GraphicsEnvironment.isHeadless()) { "No screen to show the ${if (directories) "folder" else "file"} picker on" }
    var chosen: Path? = null
    val show = Runnable {
        val frame = owner()?.also { it.toFront() }
        chosen = if (System.getProperty("os.name").contains("Mac")) mac(frame, title, current, directories) else swing(frame, title, current, directories)
    }
    if (EventQueue.isDispatchThread()) show.run() else EventQueue.invokeAndWait(show)
    return chosen
}

private fun mac(frame: Frame?, title: String, current: Path?, directories: Boolean): Path? {
    val previous = System.getProperty(DIRECTORIES)
    System.setProperty(DIRECTORIES, directories.toString())
    try {
        val dialog = FileDialog(frame, title, FileDialog.LOAD)
        current?.let { dialog.directory = it.parent?.toString(); dialog.file = it.fileName?.toString() }
        // Modal: returns once the panel closes. A chosen folder or file comes back as its parent and its name.
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return Path.of(directory, file)
    } finally {
        if (previous == null) System.clearProperty(DIRECTORIES) else System.setProperty(DIRECTORIES, previous)
    }
}

private fun swing(frame: Frame?, title: String, current: Path?, directories: Boolean): Path? {
    val chooser = JFileChooser(current?.toFile()).apply {
        dialogTitle = title
        fileSelectionMode = if (directories) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
    }
    return if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
}
