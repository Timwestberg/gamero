package org.formulagen.wrapper.conversations

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * The Mac's folder picker, behind an interface so [ProjectFolderStore] is testable without a screen.
 * Blocks until the person chooses or cancels, so callers run it off the bridge thread.
 */
internal fun interface FolderPicker {
    /** The folder the person chose, or null when they cancelled. [title] says what it is for; [current] is where it opens. */
    fun choose(title: String, current: Path?): Path?
}

/**
 * The native picker on the AWT event thread, in front of [owner] (Hovel's window, brought forward
 * first). On macOS it is the system's folder panel (`FileDialog` with
 * `apple.awt.fileDialogForDirectories`); elsewhere a Swing chooser limited to folders.
 */
internal class NativeFolderPicker(private val owner: () -> Frame?) : FolderPicker {
    override fun choose(title: String, current: Path?): Path? {
        check(!GraphicsEnvironment.isHeadless()) { "No screen to show the folder picker on" }
        var chosen: Path? = null
        val show = Runnable {
            val frame = owner()?.also { it.toFront() }
            chosen = if (System.getProperty("os.name").contains("Mac")) mac(frame, title, current) else swing(frame, title, current)
        }
        if (EventQueue.isDispatchThread()) show.run() else EventQueue.invokeAndWait(show)
        return chosen
    }
}

private const val DIRECTORIES = "apple.awt.fileDialogForDirectories"

private fun mac(frame: Frame?, title: String, current: Path?): Path? {
    val previous = System.getProperty(DIRECTORIES)
    System.setProperty(DIRECTORIES, "true")
    try {
        val dialog = FileDialog(frame, title, FileDialog.LOAD)
        current?.let { dialog.directory = it.parent?.toString(); dialog.file = it.fileName?.toString() }
        // Modal: returns once the panel closes. A chosen folder comes back as its parent and its name.
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return Path.of(directory, file)
    } finally {
        if (previous == null) System.clearProperty(DIRECTORIES) else System.setProperty(DIRECTORIES, previous)
    }
}

private fun swing(frame: Frame?, title: String, current: Path?): Path? {
    val chooser = JFileChooser(current?.toFile()).apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
}
