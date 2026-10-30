package org.formulagen.wrapper.hovel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.conversations.DevicePairRequest
import java.time.Duration
import java.time.Instant

/** What the "Pair a phone…" window says for each [PhonePairing.Outcome] that is not a success. */
internal fun pairingMessage(outcome: PhonePairing.Outcome): String? = when (outcome) {
    is PhonePairing.Outcome.Found -> outcome.phone.question
    is PhonePairing.Outcome.Done -> outcome.paired?.let { "Paired with ${it.name}. It can now answer this Mac's approvals." }
    PhonePairing.Outcome.Malformed -> "Type the six digits the phone shows."
    PhonePairing.Outcome.WrongCode -> "No phone shows that code. Check it, or show a new code on the phone."
    PhonePairing.Outcome.Expired -> CODE_RAN_OUT
    PhonePairing.Outcome.TooManyAttempts -> "Too many wrong codes on your Macs. Wait a few minutes, then show a new code on the phone."
    PhonePairing.Outcome.NotConnected -> "Connect this Mac to your account from Hovel first."
    PhonePairing.Outcome.Failed -> "Hovel could not reach the server. Try again."
}

/** What the window says when the code of the phone it asked about runs out. */
private const val CODE_RAN_OUT = "That code ran out. Show a new code on the phone, then type it again."

/** What the code field takes: the code's digits, and a space or dash a person may type between them. */
private const val CODE_FIELD_LIMIT = DevicePairRequest.PAIRING_CODE_DIGITS + 2

/**
 * The "Pair a phone…" window, opened only from the Hovel menu: a field for the code the phone shows, then the phone
 * that code names with Pair and Cancel, and the phones this Mac confirms, each with Unpair. A phone is paired only on
 * that second click, and the question goes when the code runs out.
 */
@Composable
internal fun PhonePairingWindow(pairing: PhonePairing, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var phones by remember { mutableStateOf<List<PhonePairing.PairedPhone>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** The phone the typed code names, waiting for the person's Pair or Cancel. */
    var found by remember { mutableStateOf<PhonePairing.Candidate?>(null) }

    fun run(call: () -> PhonePairing.Outcome) {
        if (busy) return
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { call() }
            found = (outcome as? PhonePairing.Outcome.Found)?.phone
            if (outcome is PhonePairing.Outcome.Done) {
                phones = outcome.phones
                if (outcome.paired != null) code = ""
            }
            message = pairingMessage(outcome)
            busy = false
        }
    }
    LaunchedEffect(Unit) { run(pairing::phones) }
    // the question lasts as long as its code
    LaunchedEffect(found) {
        val asked = found ?: return@LaunchedEffect
        delay(Duration.between(Instant.now(), asked.codeExpiresAt).toMillis().coerceAtLeast(0))
        if (found == asked) {
            found = null
            message = CODE_RAN_OUT
        }
    }

    DialogWindow(onCloseRequest = onClose, state = rememberDialogState(size = DpSize(480.dp, 440.dp)), title = "Pair a phone", resizable = true) {
        Surface {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pair a phone with this Mac", style = MaterialTheme.typography.titleMedium)
                Text(
                    "On the phone, open Hovel and choose Pair with a Mac. Type the code the phone shows in its own alert, " +
                        "never a code a web page or message shows you. The phone can then answer this Mac's approvals, and " +
                        "only this Mac's; anything outside a project's folders stays on the Mac.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { typed ->
                            code = typed.filter { it.isDigit() || it == ' ' || it == '-' }.take(CODE_FIELD_LIMIT)
                            // a new code asks again
                            found = null
                        },
                        label = { Text("Code") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    Button(onClick = { run { pairing.lookup(code) } }, enabled = !busy && found == null && PhonePairing.codeOf(code) != null) {
                        Text("Find phone")
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                found?.let { asked ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run { pairing.pair(code, asked) } }, enabled = !busy) { Text("Pair") }
                        OutlinedButton(onClick = { found = null; message = null }, enabled = !busy) { Text("Cancel") }
                    }
                    Text("Pair only a phone you are holding. If you don't recognize it, Cancel.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Phones paired with this Mac", style = MaterialTheme.typography.titleSmall)
                if (phones.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodyMedium)
                phones.forEach { phone ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(phone.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { run { pairing.unpair(phone.id) } }, enabled = !busy) { Text("Unpair") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
}
