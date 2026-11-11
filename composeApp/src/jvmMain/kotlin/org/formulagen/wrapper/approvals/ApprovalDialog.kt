package org.formulagen.wrapper.approvals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.rememberDialogState
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Window

/**
 * The native dialogs as Compose windows, modeled on the "Connect this Mac" approval: state the page
 * cannot reach, shown by [NativeDialogHost] in Hovel's own window tree. Presenting brings [window]
 * and the app forward, because the request usually arrives while the player is elsewhere.
 */
internal class ComposeDialogPresenter(private val window: () -> Window?) : DialogPresenter {
    /** A dialog on screen and where its answer goes. */
    internal class Shown(val dialog: NativeDialog<*>, val answer: (Int) -> Unit)

    var shown by mutableStateOf<Shown?>(null)
        private set

    override fun present(dialog: NativeDialog<*>, answer: (Int) -> Unit): () -> Unit {
        val entry = Shown(dialog, answer)
        EventQueue.invokeLater {
            shown = entry
            window()?.let { it.toFront(); it.requestFocus() }
            runCatching { if (Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) Desktop.getDesktop().requestForeground(true) }
        }
        return { EventQueue.invokeLater { if (shown === entry) shown = null } }
    }
}

/**
 * Renders the dialog [presenter] shows, if any. Its buttons stay disabled for
 * [DIALOG_ARMING_MILLIS] after it appears, so a click or key press meant for something else cannot
 * answer it. The dismiss button (Deny, Not now) is the primary one, and closing the window, Escape
 * and Enter all choose it. Modeless dialogs keep the chat's Stop and Close controls reachable;
 * approval decisions remain native and cancellation dismisses the outstanding question.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NativeDialogHost(presenter: ComposeDialogPresenter) {
    val shown = presenter.shown ?: return
    key(shown) {
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(DIALOG_ARMING_MILLIS); armed = true }
        val dismiss = shown.dialog.dismissIndex
        DialogWindow(
            onCloseRequest = { shown.answer(dismiss) },
            state = rememberDialogState(size = dialogSize(shown.dialog)),
            title = "Hovel",
            resizable = true,
            alwaysOnTop = true,
            modalityType = DialogModalityType.Modeless,
            onPreviewKeyEvent = { event ->
                val decline = event.type == KeyEventType.KeyDown && (event.key == Key.Escape || event.key == Key.Enter || event.key == Key.NumPadEnter)
                if (decline) shown.answer(dismiss)
                decline
            },
        ) {
            NativeDialogContent(shown.dialog, armed, shown.answer)
        }
    }
}

/**
 * A dialog's content: what is asked, then its buttons. The buttons are never scrolled away and never pushed off
 * their row: the text above them scrolls when it is long, and a button that does not fit beside the others takes a
 * line of its own, with its label wrapped. So the dismiss button (Deny, Not now) can always be pressed, however long
 * a command or a "for this conversation" label is. A label is shown in full, because it says what the answer allows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeDialogContent(dialog: NativeDialog<*>, armed: Boolean, answer: (Int) -> Unit) {
    val dismiss = dialog.dismissIndex
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag(DIALOG_TEXT_TAG),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(dialog.title, style = MaterialTheme.typography.titleMedium)
                dialog.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dialog.buttons.forEachIndexed { index, (label, _) ->
                    if (index == dismiss) Button(onClick = { answer(index) }, enabled = armed) { Text(label) }
                    else OutlinedButton(onClick = { answer(index) }, enabled = armed) { Text(label) }
                }
            }
        }
    }
}

/** The scrolling text of a dialog, for a test. */
internal const val DIALOG_TEXT_TAG = "native-dialog-text"

/** A dialog's window: wide enough to read a path, and taller when there is much to read before the buttons. */
internal fun dialogSize(dialog: NativeDialog<*>): DpSize {
    val characters = dialog.title.length + dialog.lines.sumOf { it.length } + dialog.buttons.sumOf { it.first.length }
    return DpSize(560.dp, if (characters > DIALOG_LONG_CHARACTERS) 520.dp else 360.dp)
}

/** More text than this, over the title, the lines and the buttons, and the dialog opens taller. */
internal const val DIALOG_LONG_CHARACTERS = 500
