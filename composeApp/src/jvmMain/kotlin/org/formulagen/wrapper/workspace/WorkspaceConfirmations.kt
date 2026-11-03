package org.formulagen.wrapper.workspace

import java.awt.Dialog
import java.awt.Font
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.WindowConstants
import org.formulagen.wrapper.approvals.NativeDialog

/*
 * The questions asked before work ends (project-workspace.SPEC.md, "Opening a workspace"): closing a
 * busy tab, closing a window with busy tabs, and quitting with any. Each is decided from the tabs'
 * [Busy] states alone, so the decisions are plain functions; [Confirmer] asks.
 */

/** A busy tab as a confirmation lists it: [name] is its label and position, such as "Shell (tab 2)". */
internal data class BusyTab(val name: String, val busy: Busy)

/** The busy tabs of one window, for the quit confirmation. */
internal data class BusyWindow(val title: String, val tabs: List<BusyTab>)

/** Every confirmation's wording, from [Busy] data alone. Each answers true to end the work; Cancel is the default. */
internal object Confirmations {
    private const val CANCEL = "Cancel"

    /** Null closes the tab at once: nothing in it is busy. */
    fun closeTab(busy: Busy?): NativeDialog<Boolean>? = busy?.let {
        question("Close this tab?", listOf("${reason(it)}. Closing the tab ends it."), "Close Tab")
    }

    /** Null closes the window at once. */
    fun closeWindow(window: String, busy: List<BusyTab>): NativeDialog<Boolean>? =
        if (busy.isEmpty()) null else question("Close “$window”?", listOf("Closing the window ends what is running in it:") + lines(busy), "Close Window")

    /** Null quits at once. One question for every window, naming each busy window and its tabs. */
    fun quit(appName: String, windows: List<BusyWindow>): NativeDialog<Boolean>? {
        val busy = windows.filter { it.tabs.isNotEmpty() }
        if (busy.isEmpty()) return null
        val lines = listOf("Quitting ends what is running in your workspaces:") + busy.flatMap { listOf("", "In “${it.title}”:") + lines(it.tabs) }
        return question("Quit $appName?", lines, "Quit")
    }

    /** One sentence naming what closing would end. */
    fun reason(busy: Busy): String = when (busy) {
        is Busy.Running -> "“${busy.command}” is running"
        is Busy.Stopped -> "“${busy.command}” is suspended"
        is Busy.Unsaved ->
            if (busy.files.isEmpty()) "${busy.editor} has unsaved changes" else "${busy.editor} has unsaved changes in ${busy.files.joinToString(", ")}"
        is Busy.EditorTerminal -> "${busy.editor} has a terminal running"
        is Busy.NoAnswer -> "${busy.what} did not answer, so it may have unsaved work"
    }

    /** One bullet line per busy tab. */
    private fun lines(tabs: List<BusyTab>): List<String> = tabs.map { "• ${it.name}: ${reason(it.busy)}" }

    /** A question whose [proceed] button ends the work. */
    private fun question(title: String, lines: List<String>, proceed: String) =
        NativeDialog(title, lines, listOf(proceed to true, CANCEL to false), dismiss = false)
}

/** Asks the player a confirmation. AWT thread; blocks until answered. */
internal fun interface Confirmer {
    /** The chosen button's value for [question], asked on [owner] (no window when null). */
    fun confirm(owner: Window?, question: NativeDialog<Boolean>): Boolean
}

/** A sheet on the workspace window, drawing every line as plain text (docs/desktop.md, "Confirmation sheets"). */
internal object SheetConfirmer : Confirmer {
    override fun confirm(owner: Window?, question: NativeDialog<Boolean>): Boolean {
        val pane = pane(question)
        val modality = if (owner != null) Dialog.ModalityType.DOCUMENT_MODAL else Dialog.ModalityType.APPLICATION_MODAL
        val dialog = JDialog(owner, question.title, modality)
        dialog.rootPane.putClientProperty("apple.awt.documentModalSheet", owner != null)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dialog.contentPane = pane
        // A chosen button ends the modal wait.
        pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY) { if (pane.value != JOptionPane.UNINITIALIZED_VALUE) dialog.isVisible = false }
        try {
            dialog.pack()
            dialog.setLocationRelativeTo(owner)
            pane.selectInitialValue()
            dialog.isVisible = true
        } finally {
            dialog.dispose()
        }
        return question.buttons.firstOrNull { it.first == pane.value }?.second ?: question.dismiss
    }

    /** [question] as an option pane of plain-text labels, its title in bold, its dismiss button the default. */
    internal fun pane(question: NativeDialog<Boolean>): JOptionPane {
        val title = plainLabel(question.title).apply { font = font.deriveFont(Font.BOLD) }
        val message = listOf(title, plainLabel(" ")) + question.lines.map { plainLabel(it.ifEmpty { " " }) }
        val options = question.buttons.map { it.first }.toTypedArray<Any>()
        return JOptionPane(message.toTypedArray<Any>(), JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, options[question.dismissIndex])
    }

    /** A label that never draws [text] as HTML: the property is set before the text. */
    internal fun plainLabel(text: String): JLabel = JLabel().apply {
        putClientProperty("html.disable", true)
        this.text = text
    }
}
