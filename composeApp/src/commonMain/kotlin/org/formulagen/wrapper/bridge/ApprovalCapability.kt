package org.formulagen.wrapper.bridge

import kotlinx.serialization.Serializable

/**
 * Approvals on the phone. The page only asks native code to show an open approval's dialog; native
 * code fetches the approval and posts the answer itself with the phone's device token, so a page can
 * never forge an approval's content or answer one. Android and iOS only; phone approvals bring the
 * implementation. Desktop approvals are native dialogs with no bridge method.
 */
interface ApprovalCapability {
    suspend fun present(approvalId: String)
}

/** An open approval to show, by its lower-case id. */
@Serializable
data class ApprovalPresentArgs(val approvalId: String)

/** Mirrors event-contracts/host-bridge/approvals.ts. */
object ApprovalMethods {
    val PRESENT = BridgeMethod("approvals.present", ApprovalPresentArgs.serializer(), NoResult.serializer()) {
        SharedRules.isRecordId(it.approvalId)
    }

    fun pack(approvals: Capability<ApprovalCapability>): CapabilityPack = binder.pack(approvals)

    internal val binder = CapabilityBinder("approvals", listOf<CapabilityHandler<ApprovalCapability>>(
        PRESENT.handle { approvals, args -> approvals.present(args.approvalId); NoResult },
    ))
}
