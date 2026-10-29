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
import androidx.compose.foundation.layout.Row
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
            state = rememberDialogState(size = DpSize(560.dp, 360.dp)),
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
            Surface {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(shown.dialog.title, style = MaterialTheme.typography.titleMedium)
                    shown.dialog.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.dialog.buttons.forEachIndexed { index, (label, _) ->
                            if (index == dismiss) Button(onClick = { shown.answer(index) }, enabled = armed) { Text(label) }
                            else OutlinedButton(onClick = { shown.answer(index) }, enabled = armed) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}
