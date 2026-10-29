package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.JsonObject
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.bridge.brainWire
import org.formulagen.wrapper.phone.ApprovalDevice
import org.formulagen.wrapper.phone.ApprovalDeviceList
import org.formulagen.wrapper.phone.DevicePairRequest

/**
 * "Pair a phone…", the one way a phone comes to answer this Mac's approvals. The person opens it from the Hovel menu
 * (never on its own), and types the six-digit code the phone shows on its own native screen. This Mac sends the code
 * on its own host route ([NativeRoute.DEVICE_PAIR]) with its host token, so neither a page nor the owner's web session
 * can confirm a phone, and each Mac confirms a phone for itself: the phone then answers this Mac's approvals only.
 * The server allows a few wrong codes per Mac, then makes it wait.
 *
 * [exchange] is the connected Mac's host transport, or null before this Mac is connected. Calls block on HTTP, so
 * callers run them off the UI thread.
 */
internal class PhonePairing(private val exchange: () -> NativeExchange?) {
    /** How pairing, unpairing or listing ended. */
    sealed interface Outcome {
        /** The phones this Mac confirmed, after the change, if any. */
        data class Done(val phones: List<PairedPhone>, val paired: PairedPhone? = null) : Outcome

        /** The typed text is not six digits; nothing was sent. */
        data object Malformed : Outcome

        /** No phone shows that code (or it ran out). It counts toward the attempt limit. */
        data object WrongCode : Outcome

        /** Too many wrong codes on this Mac for now. */
        data object TooManyAttempts : Outcome

        /** This Mac is not connected to an account, or its connection was revoked. */
        data object NotConnected : Outcome

        /** The server did not answer, or answered something else. */
        data object Failed : Outcome
    }

    /** A phone this Mac confirmed, with its name cleaned to one line for the window. */
    data class PairedPhone(val id: String, val name: String)

    /** The phones this Mac confirmed. */
    fun phones(): Outcome = call { Outcome.Done(list(it)) }

    /** Sends [typed] as a pairing code, once it is six digits (spaces and dashes aside). */
    fun pair(typed: String): Outcome {
        val code = codeOf(typed) ?: return Outcome.Malformed
        return call { native ->
            val body = brainWire(DevicePairRequest.serializer(), DevicePairRequest(code)) as JsonObject
            val paired = device(native.exchange("POST", NativeRoute.DEVICE_PAIR.path(), body))
            val phones = list(native)
            Outcome.Done(phones, phones.firstOrNull { it.id == paired.id } ?: phoneOf(paired))
        }
    }

    /** Lets phone [deviceId] go: it answers none of this Mac's approvals again. A phone already gone is let go alike. */
    fun unpair(deviceId: String): Outcome = call { native ->
        try {
            native.exchange("POST", NativeRoute.DEVICE_UNPAIR.path(deviceId), JsonObject(emptyMap()))
        } catch (gone: HovelStatusException) {
            if (gone.status != 404) throw gone
        }
        Outcome.Done(list(native))
    }

    private fun list(native: NativeExchange): List<PairedPhone> {
        val devices = brainJson.decodeFromJsonElement(ApprovalDeviceList.serializer(), native.exchange("GET", NativeRoute.DEVICES.path(), null))
        return devices.devices.map(::phoneOf)
    }

    private fun device(data: JsonObject): ApprovalDevice = brainJson.decodeFromJsonElement(ApprovalDevice.serializer(), data)

    private fun phoneOf(device: ApprovalDevice) = PairedPhone(device.id, SharedRules.displayable(device.name, "Phone"))

    private fun call(block: (NativeExchange) -> Outcome): Outcome {
        val native = exchange() ?: return Outcome.NotConnected
        return try {
            block(native)
        } catch (refused: HovelStatusException) {
            when {
                refused.status == 404 -> Outcome.WrongCode
                refused.status == 429 -> Outcome.TooManyAttempts
                refused.status == 401 -> Outcome.NotConnected
                else -> Outcome.Failed
            }
        } catch (_: Exception) {
            Outcome.Failed
        }
    }

    companion object {
        private val SEPARATORS = Regex("[\\s-]")

        /** [typed] as a pairing code: six ASCII digits once spaces and dashes are taken out, or null. */
        fun codeOf(typed: String): String? = typed.replace(SEPARATORS, "").takeIf { DevicePairRequest.PAIRING_CODE.matches(it) }
    }
}
