package org.formulagen.wrapper.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The companion contract handshake (`host.contract`). The host keeps the page's contract for the
 * page's context and leaves out anything newer, such as a runtime past it. Desktop only; the
 * conversation runner brings the implementation.
 */
interface HostContractCapability {
    /** Keeps [pageContract] for this context and answers the host's contract. */
    suspend fun contract(pageContract: Int): Int
}

/** The page's contract. Kept raw so a contract sent as text is refused, as the contract's `z.int()` does. */
@Serializable
data class HostContractArgs(val contract: JsonPrimitive) {
    /** The contract when it is a whole number of at least 1, else null. */
    fun value(): Int? = contract.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 1 }
}

/** The host's contract. */
@Serializable
data class HostContract(val contract: Int)

/** Mirrors `host.contract` in event-contracts/host-bridge/capabilities.ts. */
object HostContractMethods {
    val CONTRACT = BridgeMethod("host.contract", HostContractArgs.serializer(), HostContract.serializer()) { it.value() != null }

    fun pack(contract: Capability<HostContractCapability>): CapabilityPack = binder.pack(contract)

    internal val binder = CapabilityBinder("host", listOf<CapabilityHandler<HostContractCapability>>(
        CONTRACT.handle { contract, args -> HostContract(contract.contract(checkNotNull(args.value()))) },
    ))
}
