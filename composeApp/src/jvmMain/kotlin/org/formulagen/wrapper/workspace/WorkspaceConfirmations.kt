package org.formulagen.wrapper.workspace

import java.awt.Dialog
import java.awt.Font
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.WindowConstants

/*
 * The questions asked before work ends (project-workspace.SPEC.md, "Opening a workspace"): closing a
 * busy tab, closing a window with busy tabs, and quitting with any. Each is decided from the tabs'
 * [Busy] states alone, so the decisions are plain functions; [Confirmer] asks.
 */

/** A question before work ends: [proceed] is the button that ends it. Cancel is the default. */
internal data class Confirmation(val title: String, val message: String, val proceed: String)

/** A busy tab as a confirmation lists it: [name] is its label and position, such as "Shell (tab 2)". */
internal data class BusyTab(val name: String, val busy: Busy)

/** The busy tabs of one window, for the quit confirmation. */
internal data class BusyWindow(val title: String, val tabs: List<BusyTab>)

internal object Confirmations {
    /** Null closes the tab at once: nothing in it is busy. */
    fun closeTab(busy: Busy?): Confirmation? = busy?.let {
        Confirmation("Close this tab?", "${it.reason}. Closing the tab ends it.", "Close Tab")
    }

    /** Null closes the window at once. */
    fun closeWindow(window: String, busy: List<BusyTab>): Confirmation? = if (busy.isEmpty()) null else Confirmation(
        "Close “$window”?",
        "Closing the window ends what is running in it:\n${lines(busy)}",
        "Close Window",
    )

    /** Null quits at once. One question for every window, naming each busy window and its tabs. */
    fun quit(appName: String, windows: List<BusyWindow>): Confirmation? {
        val busy = windows.filter { it.tabs.isNotEmpty() }
        if (busy.isEmpty()) return null
        return Confirmation(
            "Quit $appName?",
            "Quitting ends what is running in your workspaces:\n" + busy.joinToString("\n") { "\nIn “${it.title}”:\n${lines(it.tabs)}" },
            "Quit",
        )
    }

    /** Every line starts with Hovel's own text: Swing would draw a label that starts with `<html>` as HTML. */
    private fun lines(tabs: List<BusyTab>) = tabs.joinToString("\n") { "• ${it.name}: ${it.busy.reason}" }
}

/** Asks the player a [Confirmation]: true to go ahead. AWT thread; blocks until answered. */
internal fun interface Confirmer {
    fun confirm(owner: Window?, question: Confirmation): Boolean
}

/**
 * A sheet on the workspace window, as macOS shows a document's questions. Owned by that window, never
 * by `NativeDialogs`, which always raises the main window. Cancel is the default button, so a stray
 * Return keeps the work.
 *
 * macOS makes a sheet only of a document-modal dialog whose root pane says so before it is first
 * shown, so the dialog is built here rather than by [JOptionPane.createDialog], which makes an
 * application-modal one and lays it out at once. With no window to attach to, it is application-modal.
 */
internal object SheetConfirmer : Confirmer {
    private const val CANCEL = "Cancel"

    override fun confirm(owner: Window?, question: Confirmation): Boolean {
        val options = arrayOf<Any>(question.proceed, CANCEL)
        // A sheet has no title bar, so the title leads the message, in bold.
        val title = JLabel(question.title).apply {
            putClientProperty("html.disable", true)
            font = font.deriveFont(Font.BOLD)
        }
        val pane = JOptionPane(arrayOf<Any>(title, " ", question.message), JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, CANCEL)
        val modality = if (owner != null) Dialog.ModalityType.DOCUMENT_MODAL else Dialog.ModalityType.APPLICATION_MODAL
        val dialog = JDialog(owner, question.title, modality)
        dialog.rootPane.putClientProperty("apple.awt.documentModalSheet", owner != null)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dialog.contentPane = pane
        // A button (or Escape) sets the pane's value: that answers, and ends the modal wait.
        pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY) { if (pane.value != JOptionPane.UNINITIALIZED_VALUE) dialog.isVisible = false }
        try {
            dialog.pack()
            dialog.setLocationRelativeTo(owner)
            pane.selectInitialValue()
            dialog.isVisible = true
        } finally {
            dialog.dispose()
        }
        return pane.value == question.proceed
    }
}
