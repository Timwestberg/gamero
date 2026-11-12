package org.formulagen.wrapper.approvals

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NativeDialogContentTest {
    private val scratch = "/Users/someone/Hovel/Ember/conversations/2026-11-11 Ask TPM to do this- first write a three-line draft outline as draft.m/scratch"
    private val command = "ls -la \"/Users/someone/Library/Caches/shopping-list\" && mkdir -p \"$scratch\" && echo \"scratch ok\""

    /** A permission request as a working specialist's long command makes it: the case that hid Deny. */
    private val long = NativeDialog(
        title = "Ember wants to run a command",
        lines = listOf(command, "List project folder and create scratch folder\nNeeds access to $scratch", "Member: Claude · Haiku",
            "Project folder: ~/Library/Caches/shopping-list") + List(12) { "One more line of what the provider said, number $it." },
        buttons = listOf("Allow once" to ApprovalDecision.ALLOW_ONCE,
            "Allow `$command` and access to $scratch and all edits in shopping-list for this conversation" to ApprovalDecision.ALLOW_FOR_CONVERSATION,
            "Deny" to ApprovalDecision.DENY),
        dismiss = ApprovalDecision.DENY,
    )

    @Test fun everyButtonOfALongRequestIsInTheWindowAndCanBePressed() = runDesktopComposeUiTest(width = 560, height = 520) {
        val answers = mutableListOf<Int>()
        setContent { NativeDialogContent(long, armed = true, answer = answers::add) }
        val window = onRoot().getBoundsInRoot()
        for ((label, _) in long.buttons) {
            val button = onNodeWithText(label)
            button.assertIsDisplayed()
            val bounds = button.getBoundsInRoot()
            assertTrue(bounds.left >= 0.dp && bounds.right <= window.right && bounds.top >= 0.dp && bounds.bottom <= window.bottom,
                "\"${label.take(30)}\" is inside the window: $bounds in $window")
            assertTrue(bounds.right - bounds.left > 40.dp, "\"${label.take(30)}\" has room for its label")
        }
        onNodeWithText("Deny").performClick()
        onNodeWithText("Allow once").performClick()
        assertEquals(listOf(2, 0), answers)
        // What is asked is all there to read, by scrolling: the last line is reached, and the buttons have not moved.
        val before = onNodeWithText("Deny").getBoundsInRoot()
        onNodeWithTag(DIALOG_TEXT_TAG).performScrollToNode(hasText("One more line of what the provider said, number 11."))
        onNodeWithText("One more line of what the provider said, number 11.").assertIsDisplayed()
        assertEquals(before, onNodeWithText("Deny").getBoundsInRoot())
    }

    @Test fun aShortRequestKeepsItsButtonsOnOneRowAndNeedsNoScrolling() = runDesktopComposeUiTest(width = 560, height = 360) {
        val short = NativeDialog("Ember wants to read files in shopping-list with Claude · Opus.",
            listOf("Project folder: ~/shopping-list", "Hovel reads the files it asks for. It changes no file."),
            listOf("Read files" to true, "Not now" to false), dismiss = false)
        setContent { NativeDialogContent(short, armed = true, answer = {}) }
        val read = onNodeWithText("Read files").getBoundsInRoot()
        val not = onNodeWithText("Not now").getBoundsInRoot()
        assertEquals(read.top, not.top, "side by side")
        assertTrue(not.left > read.right)
        onNodeWithText("Hovel reads the files it asks for. It changes no file.").assertIsDisplayed()
    }

    @Test fun aDialogWithMuchToReadOpensTaller() {
        assertEquals(520.dp, dialogSize(long).height)
        assertEquals(360.dp, dialogSize(NativeDialog("Start?", listOf("One line."), listOf("Yes" to true, "No" to false), dismiss = false)).height)
        assertEquals(560.dp, dialogSize(long).width)
    }

    @Test fun buttonsAreOffUntilTheDialogIsArmed() = runDesktopComposeUiTest(width = 560, height = 360) {
        val answers = mutableListOf<Int>()
        setContent { NativeDialogContent(long, armed = false, answer = answers::add) }
        onNodeWithText("Allow once").performClick()
        onNodeWithText("Deny").performClick()
        assertEquals(emptyList(), answers)
    }
}
