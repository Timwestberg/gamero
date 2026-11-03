package org.formulagen.wrapper.workspace

import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import javax.swing.JLabel
import javax.swing.plaf.basic.BasicHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The close and quit questions: when they are asked, their wording, and a sheet that never draws HTML. */
class WorkspaceConfirmationsTest {
    /** Markup a program's arguments or a file name could carry. Drawn as HTML, it would fetch the image. */
    private val markup = "<html><img src=http://127.0.0.1:9/beacon></html>"

    /** Every label inside [component], depth first. */
    private fun labels(component: Component): List<JLabel> =
        listOfNotNull(component as? JLabel) + ((component as? Container)?.components.orEmpty().flatMap(::labels))

    /** The labels of the sheet built for [busy], built on the AWT thread as the sheet is. */
    private fun sheetLabels(vararg busy: Busy): List<JLabel> {
        val question = Confirmations.quit("Hovel", listOf(BusyWindow("demo · workspace", busy.mapIndexed { index, it -> BusyTab("Shell (tab ${index + 1})", it) })))!!
        var found = emptyList<JLabel>()
        EventQueue.invokeAndWait { found = labels(SheetConfirmer.pane(question)) }
        return found
    }

    @Test fun markupInABusyReasonOrFileNameIsDrawnAsPlainText() {
        val found = sheetLabels(Busy.Running("rg $markup"), Busy.Unsaved("Neovim", listOf(markup)), Busy.NoAnswer(markup))
        assertTrue(found.size >= 6, "a label per line: ${found.map { it.text }}")
        for (label in found) assertNull(label.getClientProperty(BasicHTML.propertyKey), "no HTML view for \"${label.text}\"")
        val texts = found.map { it.text }
        assertTrue("• Shell (tab 1): “rg $markup” is running" in texts, "$texts")
        assertTrue("• Shell (tab 2): Neovim has unsaved changes in $markup" in texts, "$texts")
        assertTrue("• Shell (tab 3): $markup did not answer, so it may have unsaved work" in texts, "$texts")
    }

    @Test fun theControlAStringMessageWithMarkupAnywhereIsDrawnAsHtml() {
        // Why the sheet never passes Swing a String: this is what it would draw.
        var found = emptyList<JLabel>()
        EventQueue.invokeAndWait {
            found = labels(javax.swing.JOptionPane(arrayOf<Any>("Quitting ends:", "• Shell: “rg <html><b>x</b></html>” is running")))
        }
        assertTrue(found.any { it.getClientProperty(BasicHTML.propertyKey) != null }, "Swing found the markup mid-line")
    }

    @Test fun aLineThatIsAllMarkupIsStillPlainText() {
        var label: JLabel? = null
        EventQueue.invokeAndWait { label = SheetConfirmer.plainLabel(markup) }
        assertNull(label!!.getClientProperty(BasicHTML.propertyKey))
        assertEquals(markup, label!!.text)
    }

    @Test fun theDismissButtonIsCancelAndTheDefault() {
        val question = Confirmations.closeTab(Busy.Running("make"))!!
        assertEquals(listOf("Close Tab" to true, "Cancel" to false), question.buttons)
        assertFalse(question.dismiss)
        var initial: Any? = null
        EventQueue.invokeAndWait { initial = SheetConfirmer.pane(question).initialValue }
        assertEquals("Cancel", initial)
    }

    @Test fun confirmationsAreAskedOnlyWhenSomethingIsBusy() {
        assertNull(Confirmations.closeTab(null))
        assertNull(Confirmations.closeWindow("w", emptyList()))
        assertNull(Confirmations.quit("Hovel", listOf(BusyWindow("w", emptyList()))))
        val busy = BusyTab("Shell (tab 1)", Busy.Running("make"))
        assertEquals("Close Window" to true, Confirmations.closeWindow("w", listOf(busy))!!.buttons.first())
        assertEquals("Quit Hovel?", Confirmations.quit("Hovel", listOf(BusyWindow("w", emptyList()), BusyWindow("v", listOf(busy))))!!.title)
        val quit = Confirmations.quit("Hovel", listOf(BusyWindow("alpha", emptyList()), BusyWindow("beta", listOf(busy))))!!
        assertFalse(quit.lines.any { "alpha" in it }, "only busy windows are listed")
        assertTrue(quit.lines.any { "beta" in it })
    }

    @Test fun busyReasonsNameWhatRuns() {
        assertEquals("“npm run dev” is running", Confirmations.reason(Busy.Running("npm run dev")))
        assertEquals("Neovim has unsaved changes", Confirmations.reason(Busy.Unsaved("Neovim", emptyList())))
        assertEquals("Neovim has unsaved changes in f1.kt, f2.kt, f3.kt, 2 more", Confirmations.reason(Busy.Unsaved("Neovim", listOf("f1.kt", "f2.kt", "f3.kt", "2 more"))))
        assertEquals("Neovim has a terminal running", Confirmations.reason(Busy.EditorTerminal("Neovim")))
        assertEquals("Neovim did not answer, so it may have unsaved work", Confirmations.reason(Busy.NoAnswer("Neovim")))
    }
}
