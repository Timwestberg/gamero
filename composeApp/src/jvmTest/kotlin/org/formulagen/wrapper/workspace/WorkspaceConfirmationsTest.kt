package org.formulagen.wrapper.workspace

import java.awt.Component
import java.awt.Container
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
        return onAwtNow { labels(SheetConfirmer.pane(question)) }
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

    @Test fun aFoldersPathAndNameAreDrawnAsPlainText() {
        // A name holds no "/", so this markup spans folders.
        val path = java.nio.file.Path.of("/Users/player/<html><b>x</b></html>")
        val named = java.nio.file.Path.of("/Users/player/<html><b>x")
        val found = onAwtNow { labels(SheetConfirmer.pane(Confirmations.openFolder(path))) + labels(SheetConfirmer.pane(Confirmations.openFolder(named))) }
        for (label in found) assertNull(label.getClientProperty(BasicHTML.propertyKey), "no HTML view for \"${label.text}\"")
        val texts = found.map { it.text }
        assertTrue("/Users/player/<html><b>x</b></html>" in texts, "$texts")
        assertTrue("Open a workspace in “<html><b>x”?" in texts, "$texts")
        assertEquals("Cancel", onAwtNow { SheetConfirmer.pane(Confirmations.openFolder(path)).initialValue })
    }

    @Test fun thePasteQuestionIsPlainTextWithCancelTheDefault() {
        val question = Confirmations.paste(3, "<html><b>x")
        assertEquals("Paste 3 lines into <html><b>x?", question.title)
        assertEquals("Paste 1 line into Shell?", Confirmations.paste(1, "Shell").title)
        val found = onAwtNow { labels(SheetConfirmer.pane(question)) }
        for (label in found) assertNull(label.getClientProperty(BasicHTML.propertyKey), "no HTML view for \"${label.text}\"")
        assertTrue("Paste 3 lines into <html><b>x?" in found.map { it.text })
        assertEquals(listOf("Paste" to true, "Cancel" to false), question.buttons)
        assertEquals("Cancel", onAwtNow { SheetConfirmer.pane(question).initialValue })
    }

    @Test fun theControlAStringMessageWithMarkupAnywhereIsDrawnAsHtml() {
        // Why the sheet never passes Swing a String: this is what it would draw.
        val found = onAwtNow { labels(javax.swing.JOptionPane(arrayOf<Any>("Quitting ends:", "• Shell: “rg <html><b>x</b></html>” is running"))) }
        assertTrue(found.any { it.getClientProperty(BasicHTML.propertyKey) != null }, "Swing found the markup mid-line")
    }

    @Test fun aLineThatIsAllMarkupIsStillPlainText() {
        val label = onAwtNow { SheetConfirmer.plainLabel(markup) }
        assertNull(label.getClientProperty(BasicHTML.propertyKey))
        assertEquals(markup, label.text)
    }

    @Test fun theDismissButtonIsCancelAndTheDefault() {
        val question = Confirmations.closeTab(Busy.Running("make"))!!
        assertEquals(listOf("Close Tab" to true, "Cancel" to false), question.buttons)
        assertFalse(question.dismiss)
        assertEquals("Cancel", onAwtNow { SheetConfirmer.pane(question).initialValue })
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
