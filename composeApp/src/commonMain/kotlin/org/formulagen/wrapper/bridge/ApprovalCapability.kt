package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/**
 * Approvals on the phone. The page only asks native code to show an open approval's dialog; native
 * code fetches the approval and posts the answer itself with the phone's device token, so a page can
 * never forge an approval's content or answer one. Android and iOS only; desktop approvals are native
 * dialogs with no bridge method.
 */
interface ApprovalCapability {
    /** Shows open approval [approvalId] natively and answers how its dialog ended, never what was chosen. */
    suspend fun present(approvalId: String): ApprovalPresentResult
}

/**
 * Registering this phone as an approval device, the same explicit step as connecting a Mac: [prepare]
 * asks the person natively and keeps a new token, answering only its SHA-256; the page registers that
 * with the account; [activate] checks the registration with the server and keeps the token. A call
 * while another runs throws [BridgeBusyException]; the person saying no throws [BridgeDeclinedException].
 */
interface ApprovalDeviceCapability {
    suspend fun prepare(ownerId: String): ApprovalDeviceRegistration
    suspend fun activate(ownerId: String, deviceId: String): ApprovalDeviceActivated
    /** This phone's approval device, or null, from its own store; it learns of a revocation on its next call. */
    suspend fun status(): ApprovalDeviceRef?
}

/** An open approval to show, by its lower-case id. */
@Serializable
data class ApprovalPresentArgs(val approvalId: String)

/** `APPROVAL_PRESENT_OUTCOMES`: how showing an approval ended. */
@Serializable
enum class ApprovalPresentOutcome {
    /** This phone's answer won. */
    @SerialName("answered") ANSWERED,
    /** Another answer won first, or the approval ended while this answer was on its way; the phone said so. */
    @SerialName("lost") LOST,
    /** The approval was no longer open, or ended while its dialog showed, which then closed. */
    @SerialName("ended") ENDED,
    /** The person closed the dialog without answering. */
    @SerialName("dismissed") DISMISSED,
    /** It names something outside the project's folders; the phone said to answer it on the Mac. */
    @SerialName("mac-only") MAC_ONLY,
    /** This phone is not an approval device, or its registration was revoked. */
    @SerialName("unregistered") UNREGISTERED,
}

/** `approvalsPresentResult`. */
@Serializable
data class ApprovalPresentResult(val outcome: ApprovalPresentOutcome)

/** `approvalDevicePrepare`. */
@Serializable
data class ApprovalDevicePrepareArgs(val ownerId: String)

/** `approvalDeviceActivate`. */
@Serializable
data class ApprovalDeviceActivateArgs(val deviceId: String, val ownerId: String)

/** `approvalDeviceRegistration`: what the page registers with the account. Only the token's SHA-256. */
@Serializable
data class ApprovalDeviceRegistration(val id: String, val name: String, val platform: String, val tokenHash: String)

/** `approvalDeviceActivated`. */
@Serializable
data class ApprovalDeviceActivated(val deviceId: String, val registered: Boolean = true)

/** This phone's approval device: its id and the account it answers for. */
@Serializable
data class ApprovalDeviceRef(val id: String, val ownerId: String)

/** `approvalDeviceStatus`. The contract's `device` is `nullable`, not `optional`, so it is an [ExplicitNull]. */
@Serializable
internal data class ApprovalDeviceStatus(val device: ExplicitNull<ApprovalDeviceRef>) {
    constructor(device: ApprovalDeviceRef?) : this(ExplicitNull(device))
}

/** Mirrors `APPROVAL_METHODS` in event-contracts/host-bridge/approvals.ts. */
object ApprovalMethods {
    /** The dialog stays up at most [PRESENT_DIALOG_LIMIT]; reading and answering fit in the rest. */
    val PRESENT = BridgeMethod(
        "approvals.present", ApprovalPresentArgs.serializer(), ApprovalPresentResult.serializer(),
        timeout = PRESENT_DIALOG_LIMIT + 1.minutes,
    ) { SharedRules.isRecordId(it.approvalId) }

    fun pack(approvals: Capability<ApprovalCapability>): CapabilityPack = binder.pack(approvals)

    internal val binder = CapabilityBinder("approvals", listOf<CapabilityHandler<ApprovalCapability>>(
        PRESENT.handle { approvals, args -> approvals.present(args.approvalId) },
    ))
}

/** How long an approval's dialog stays up on the phone, at most; the Mac's own dialog waits as long. */
val PRESENT_DIALOG_LIMIT = 10.minutes

/** Mirrors `APPROVAL_DEVICE_METHODS` in event-contracts/host-bridge/approvals.ts. */
object ApprovalDeviceMethods {
    /** Waits for the person to agree natively, then for the Keychain or Keystore. */
    val PREPARE = BridgeMethod(
        "approvalDevice.prepare", ApprovalDevicePrepareArgs.serializer(), ApprovalDeviceRegistration.serializer(),
        timeout = 5.minutes,
    ) { BridgeProtocol.UUID_PATTERN.matches(it.ownerId) }

    /** Checks the registration with the server before keeping the token. */
    val ACTIVATE = BridgeMethod(
        "approvalDevice.activate", ApprovalDeviceActivateArgs.serializer(), ApprovalDeviceActivated.serializer(),
        timeout = 2.minutes,
    ) { BridgeProtocol.UUID_PATTERN.matches(it.ownerId) && SharedRules.isRecordId(it.deviceId) }

    internal val STATUS = BridgeMethod("approvalDevice.status", NoArgs.serializer(), ApprovalDeviceStatus.serializer())

    fun pack(devices: Capability<ApprovalDeviceCapability>): CapabilityPack = binder.pack(devices)

    internal val binder = CapabilityBinder("approvalDevice", listOf<CapabilityHandler<ApprovalDeviceCapability>>(
        PREPARE.handle { devices, args -> devices.prepare(args.ownerId) },
        ACTIVATE.handle { devices, args -> devices.activate(args.ownerId, args.deviceId) },
        STATUS.handle { devices, _ -> ApprovalDeviceStatus(devices.status()) },
    ))
}
