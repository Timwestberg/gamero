package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.JsonObject
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.bridge.brainWire
import org.formulagen.wrapper.conversations.ApprovalDevice
import org.formulagen.wrapper.conversations.ApprovalDeviceList
import org.formulagen.wrapper.conversations.DevicePairConfirm
import org.formulagen.wrapper.conversations.DevicePairLookup
import org.formulagen.wrapper.conversations.DevicePairRequest
import org.formulagen.wrapper.conversations.PairingRefusalCodes
import java.time.Duration
import java.time.Instant

/**
 * "Pair a phone…", the one way a phone comes to answer this Mac's approvals. The person opens it from the Hovel menu
 * (never on its own) and types the code the phone shows on its own native screen. This Mac first looks the code up
 * ([NativeRoute.DEVICE_LOOKUP]) and asks "Pair <name> (<platform>, registered <n> min ago)?"; only on that second
 * confirmation does it pair ([NativeRoute.DEVICE_PAIR]), naming the phone it asked about, so the code pairs that phone
 * or none. Both go on this Mac's own host route with its host token, so
 * neither a page nor the owner's web session can confirm a phone, and each Mac confirms a phone for itself: the phone
 * then answers this Mac's approvals only. The server counts wrong codes across all the account's Macs, then makes
 * them wait, and tells the owner of every pairing.
 *
 * [exchange] is the connected Mac's host transport, or null before this Mac is connected. Calls block on HTTP, so
 * callers run them off the UI thread.
 */
internal class PhonePairing(private val exchange: () -> NativeExchange?, val clock: () -> Instant = Instant::now) {
    /** How looking up, pairing, unpairing or listing ended. */
    sealed interface Outcome {
        /** The phone showing the code, not paired yet: the window asks before it pairs. */
        data class Found(val phone: Candidate) : Outcome

        /** The phones this Mac confirms, after the change, if any. */
        data class Done(val phones: List<PairedPhone>, val paired: PairedPhone? = null) : Outcome

        /** The typed text is not a code; nothing was sent. */
        data object Malformed : Outcome

        /** No phone shows that code (or it ran out). It counts toward the account's attempt limit. */
        data object WrongCode : Outcome

        /** The phone the window asked about had that code, but it ran out before the person confirmed. Not counted. */
        data object Expired : Outcome

        /** Too many wrong codes on the account's Macs for now. */
        data object TooManyAttempts : Outcome

        /** This Mac is not connected to an account, or its connection was revoked. */
        data object NotConnected : Outcome

        /** The server did not answer, or answered something else. */
        data object Failed : Outcome
    }

    /** A phone this Mac confirms, with its name cleaned to one line for the window. */
    data class PairedPhone(val id: String, val name: String)

    /**
     * The phone a code names, as the window asks about it: "Pair iPhone (iPhone, registered 3 min ago)?", until its code
     * runs out at [codeExpiresAt].
     */
    data class Candidate(val id: String, val name: String, val platform: String, val minutesAgo: Long, val codeExpiresAt: Instant) {
        val question: String get() = "Pair $name (${PLATFORMS[platform] ?: platform}, registered ${ago()})?"

        private fun ago() = when (minutesAgo) {
            0L -> "just now"
            1L -> "1 min ago"
            else -> "$minutesAgo min ago"
        }
    }

    /** The phones this Mac confirms. */
    fun phones(): Outcome = call { Outcome.Done(list(it)) }

    /** The phone showing [typed], once it is a code (spaces and dashes aside), without pairing it. */
    fun lookup(typed: String): Outcome {
        val code = codeOf(typed) ?: return Outcome.Malformed
        val body = brainWire(DevicePairRequest.serializer(), DevicePairRequest(code)) as JsonObject
        return call { native ->
            val found = brainJson.decodeFromJsonElement(DevicePairLookup.serializer(), native.exchange("POST", NativeRoute.DEVICE_LOOKUP.path(), body))
            val device = found.device
            Outcome.Found(Candidate(device.id, displayName(device), device.platform, minutesSince(device.registeredAt),
                Instant.parse(found.codeExpiresAt)))
        }
    }

    /** Pairs [phone], the one the window asked about for [typed]; the window calls it only once the person confirmed. */
    fun pair(typed: String, phone: Candidate): Outcome {
        val code = codeOf(typed) ?: return Outcome.Malformed
        val body = brainWire(DevicePairConfirm.serializer(), DevicePairConfirm(code, phone.id)) as JsonObject
        return call { native ->
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

    private fun displayName(device: ApprovalDevice) = SharedRules.dialogName(device.name, "Phone")

    private fun phoneOf(device: ApprovalDevice) = PairedPhone(device.id, displayName(device))

    private fun minutesSince(time: String): Long =
        runCatching { Duration.between(Instant.parse(time), clock()).toMinutes().coerceAtLeast(0) }.getOrDefault(0)

    private fun call(block: (NativeExchange) -> Outcome): Outcome {
        val native = exchange() ?: return Outcome.NotConnected
        return try {
            block(native)
        } catch (refused: HovelRejectedException) {
            if (refused.code == PairingRefusalCodes.EXPIRED) Outcome.Expired else Outcome.Failed
        } catch (refused: HovelStatusException) {
            when {
                refused.status == 404 -> Outcome.WrongCode
                refused.status == 429 && refused.code == PairingRefusalCodes.TOO_MANY_ATTEMPTS -> Outcome.TooManyAttempts
                refused.status == 401 -> Outcome.NotConnected
                else -> Outcome.Failed
            }
        } catch (_: Exception) {
            Outcome.Failed
        }
    }

    companion object {
        private val SEPARATORS = Regex("[\\s-]")
        private val PLATFORMS = mapOf("ios" to "iPhone", "android" to "Android")

        /** [typed] as a pairing code: its digits once spaces and dashes are taken out, or null. */
        fun codeOf(typed: String): String? = typed.replace(SEPARATORS, "").takeIf { DevicePairRequest.PAIRING_CODE.matches(it) }
    }
}
