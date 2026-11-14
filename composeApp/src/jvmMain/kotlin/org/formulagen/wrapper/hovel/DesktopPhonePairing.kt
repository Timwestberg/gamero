package org.formulagen.wrapper.hovel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.bridge.BridgeBusyException
import org.formulagen.wrapper.bridge.PhonePairingCapability
import org.formulagen.wrapper.bridge.PhonePairingConfirmed
import org.formulagen.wrapper.bridge.PhonePairingOutcome
import java.time.Duration

/**
 * `phonePairing.confirm` on the Mac: "Add a phone" in Settings ends here. The page hands over the pairing code of the
 * phone it just added with a sign-in code. This looks the code up on the Mac's own host route, asks
 * "Pair <name> (<platform>, registered <n> min ago)?" in a native dialog, and pairs only on a yes there, naming the
 * phone it asked about, so the code pairs that phone or none.
 *
 * It is the same two steps as "Pair a phone…" in the Hovel menu ([PhonePairing]), with the code handed over and not
 * typed. The page starts the question and never answers it: Not now is the default button, and what closing the
 * dialog, a code that ran out, or a call the page gave up all mean. One question at a time.
 */
internal class DesktopPhonePairing(
    private val pairing: PhonePairing,
    private val dialogs: NativeDialogs,
    private val blocking: CoroutineDispatcher = Dispatchers.IO,
) : PhonePairingCapability {
    private val asking = Mutex()

    override suspend fun confirm(code: String): PhonePairingConfirmed {
        if (!asking.tryLock()) throw BridgeBusyException()
        try {
            return PhonePairingConfirmed(ask(code))
        } finally {
            asking.unlock()
        }
    }

    private suspend fun ask(code: String): PhonePairingOutcome {
        val phone = when (val found = runInterruptible(blocking) { pairing.lookup(code) }) {
            is PhonePairing.Outcome.Found -> found.phone
            // no phone shows the code, or it ran out before the Mac looked
            PhonePairing.Outcome.WrongCode, PhonePairing.Outcome.Expired, PhonePairing.Outcome.Malformed -> return PhonePairingOutcome.EXPIRED
            else -> return PhonePairingOutcome.FAILED
        }
        val left = Duration.between(pairing.clock(), phone.codeExpiresAt).toMillis()
        if (left <= 0) return PhonePairingOutcome.EXPIRED
        val cancel = CancelSignal()
        var ranOut = false
        val yes = coroutineScope {
            // the question lasts as long as its code
            val expiry = launch {
                delay(left)
                ranOut = true
                cancel.cancel()
            }
            try {
                runInterruptible(blocking) { dialogs.ask(pairDialog(phone), cancel) }
            } finally {
                // the page's call ended first: the dialog goes with it
                cancel.cancel()
                expiry.cancel()
            }
        }
        if (yes != true) return if (ranOut) PhonePairingOutcome.EXPIRED else PhonePairingOutcome.DECLINED
        return when (val paired = runInterruptible(blocking) { pairing.pair(code, phone) }) {
            is PhonePairing.Outcome.Done -> if (paired.paired != null) PhonePairingOutcome.PAIRED else PhonePairingOutcome.FAILED
            PhonePairing.Outcome.Expired, PhonePairing.Outcome.WrongCode -> PhonePairingOutcome.EXPIRED
            else -> PhonePairingOutcome.FAILED
        }
    }

    companion object {
        /** The Mac's question about [phone]. Not now is the default, and what closing the dialog answers. */
        fun pairDialog(phone: PhonePairing.Candidate): NativeDialog<Boolean> = NativeDialog(
            title = "Pair this phone with this Mac?",
            lines = listOf(
                phone.question,
                "It was just added to your account with a sign-in code. Paired, it can answer this Mac's approvals, and only this Mac's.",
                "Pair only a phone you are holding. If you don't recognize it, choose Not now.",
            ),
            buttons = listOf("Pair" to true, "Not now" to false),
            dismiss = false,
        )
    }
}
