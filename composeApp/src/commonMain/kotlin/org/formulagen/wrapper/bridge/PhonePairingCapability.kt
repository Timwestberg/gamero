package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.conversations.DevicePairRequest
import kotlin.time.Duration.Companion.minutes

/**
 * Pairing a phone from the Mac app, started by a page. The page hands over a pairing code it got from the phone it
 * just added with a sign-in code; nobody typed it. The Mac looks the code up, asks "Pair <name>?" in its own native
 * dialog, and pairs with its host token on a yes. The page starts the question and learns only how it ended: it
 * never answers it. Desktop only; a phone has no such method to support.
 */
interface PhonePairingCapability {
    /** Asks the person natively about the phone that owns [code]. A second call while one asks throws [BridgeBusyException]. */
    suspend fun confirm(code: String): PhonePairingConfirmed
}

/** `phonePairingConfirm`. */
@Serializable
data class PhonePairingConfirmArgs(val code: String)

/** `PHONE_PAIRING_CONFIRM_OUTCOMES`: how the Mac's own question ended. */
@Serializable
enum class PhonePairingOutcome {
    /** The person said yes, and the phone now answers this Mac's approvals. */
    @SerialName("paired") PAIRED,
    /** The person said no, or closed the dialog. */
    @SerialName("declined") DECLINED,
    /** The code ran out, or named no phone. */
    @SerialName("expired") EXPIRED,
    /** The Mac could not ask or could not pair: it is not connected to the account, or the service refused. */
    @SerialName("failed") FAILED,
}

/** `phonePairingConfirmed`. */
@Serializable
data class PhonePairingConfirmed(val outcome: PhonePairingOutcome)

/** Mirrors `PHONE_PAIRING_METHODS` in event-contracts/host-bridge/phone-pairing.ts. */
object PhonePairingMethods {
    /** The dialog stays up while the code lasts (`COMPANION_LIMITS.pairingMs`); the look-up and the pairing fit in the rest. */
    val CONFIRM = BridgeMethod(
        "phonePairing.confirm", PhonePairingConfirmArgs.serializer(), PhonePairingConfirmed.serializer(),
        timeout = PAIRING_LIMIT + 1.minutes,
    ) { DevicePairRequest.PAIRING_CODE.matches(it.code) }

    fun pack(pairing: Capability<PhonePairingCapability>): CapabilityPack = binder.pack(pairing)

    internal val binder = CapabilityBinder("phonePairing", listOf<CapabilityHandler<PhonePairingCapability>>(
        CONFIRM.handle { pairing, args -> pairing.confirm(args.code) },
    ))
}
