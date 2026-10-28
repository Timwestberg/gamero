package org.formulagen.wrapper.approvals

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
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
    internal class Shown(val dialog: NativeDialog, val answer: (Int) -> Unit)

    var shown by mutableStateOf<Shown?>(null)
        private set

    override fun present(dialog: NativeDialog, answer: (Int) -> Unit): () -> Unit {
        val entry = Shown(dialog, answer)
        EventQueue.invokeLater {
            shown = entry
            window()?.let { it.toFront(); it.requestFocus() }
            runCatching { if (Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) Desktop.getDesktop().requestForeground(true) }
        }
        return { EventQueue.invokeLater { if (shown === entry) shown = null } }
    }
}

/** Renders the dialog [presenter] shows, if any. Closing the window answers its dismiss button (Deny, Not now). */
@Composable
internal fun NativeDialogHost(presenter: ComposeDialogPresenter) {
    val shown = presenter.shown ?: return
    key(shown) {
        DialogWindow(
            onCloseRequest = { shown.answer(shown.dialog.dismissIndex) },
            state = rememberDialogState(size = DpSize(520.dp, 300.dp)),
            title = "Hovel",
            resizable = true,
            alwaysOnTop = true,
        ) {
            Surface {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(shown.dialog.title, style = MaterialTheme.typography.titleMedium)
                    shown.dialog.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.dialog.buttons.forEachIndexed { index, label ->
                            if (index == shown.dialog.dismissIndex) OutlinedButton(onClick = { shown.answer(index) }) { Text(label) }
                            else Button(onClick = { shown.answer(index) }) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}
