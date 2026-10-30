package org.formulagen.wrapper.conversations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The approval device payloads of event-contracts `hovel/companions` (`device.schemas.ts`) that both a phone and a Mac
 * read or send: a phone as its owner, the phone itself and the Macs that confirm it see it, and the code a Mac's
 * "Pair a phone…" dialog sends. The phone's own routes and payloads stay in `phone/`.
 */

/** `APPROVAL_DEVICE_STATES`: `pending` until a Mac confirms the phone, then `paired`. */
@Serializable
internal enum class ApprovalDeviceState {
    @SerialName("pending") PENDING,
    @SerialName("paired") PAIRED,
}

/** `approvalDevicePairing`: one Mac that confirmed the phone. */
@Serializable
internal data class ApprovalDevicePairing(val computerId: String, val name: String, val pairedAt: String)

/** `approvalDevice`: a phone as the owner, the phone itself and its Macs see it. */
@Serializable
internal data class ApprovalDevice(
    val id: String,
    val name: String,
    val pairedWith: List<ApprovalDevicePairing>,
    val pendingUntil: String?,
    val platform: String,
    val registeredAt: String,
    val revokedAt: String?,
    val state: ApprovalDeviceState,
)

/** `approvalDeviceList`. */
@Serializable
internal data class ApprovalDeviceList(val devices: List<ApprovalDevice>)

/** `PAIRING_REFUSAL_CODES`: why a Mac's lookup or pairing was refused, as the refusal body's `code` says. */
internal object PairingRefusalCodes {
    /** A confirmation whose code matched the named phone but ran out meanwhile (409). Not a wrong code. */
    const val EXPIRED = "pairing-code-expired"

    /** The account's Macs used up their wrong codes for the window (429). */
    const val TOO_MANY_ATTEMPTS = "too-many-attempts"
}

/** `devicePairLookup`: the phone a typed code names, and when that code runs out. */
@Serializable
internal data class DevicePairLookup(val codeExpiresAt: String, val device: ApprovalDevice)

/** `devicePairConfirm`: the person's confirmation, naming the phone the lookup found, so only that phone pairs. */
@Serializable
internal data class DevicePairConfirm(val code: String, val deviceId: String) {
    init { require(DevicePairRequest.PAIRING_CODE.matches(code)) { "A pairing code is ${DevicePairRequest.PAIRING_CODE_DIGITS} digits" } }
}

/** `devicePairRequest`: what the person typed into a Mac's "Pair a phone…" dialog, to look the phone up. */
@Serializable
internal data class DevicePairRequest(val code: String) {
    init { require(PAIRING_CODE.matches(code)) { "A pairing code is $PAIRING_CODE_DIGITS digits" } }

    companion object {
        /** `PAIRING_CODE_DIGITS`. */
        const val PAIRING_CODE_DIGITS = 6

        /** `PAIRING_CODE_PATTERN`, without its anchors: [Regex.matches] takes the whole text. */
        val PAIRING_CODE = Regex("[0-9]{$PAIRING_CODE_DIGITS}")
    }
}

