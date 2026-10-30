package org.formulagen.wrapper.bridge

/**
 * The companion contract this build speaks, event-contracts `hovel/companions/contract.constants.ts`.
 * The host sends [CURRENT] in [HEADER] on every request to hovel-service, and trades it with the page
 * in `host.contract`, so neither side shows what the other cannot render.
 */
object HovelContract {
    /** `HOVEL_CONTRACT`. Contract 2: a claim may answer `stop-requested` with the message to settle. */
    const val CURRENT = 2

    /** `HOVEL_FIRST_CONTRACT`: what a document naming nothing newer needs. */
    const val FIRST = 1

    /** `HOVEL_CONTRACT_HEADER`. */
    const val HEADER = "X-Hovel-Contract"

    /**
     * `HOVEL_CONTRACT_RUNTIMES`: the contract each runtime arrived in. A page whose contract is older than a runtime's
     * is never shown it ([runtimesFor]).
     */
    val RUNTIMES: Map<RuntimeId, Int> = RuntimeId.entries.associateWith { FIRST }

    /** The runtimes a page on [contract] can render, in the contract's order, by [table] ([RUNTIMES]). */
    fun runtimesFor(contract: Int, table: Map<RuntimeId, Int> = RUNTIMES): List<RuntimeId> =
        RuntimeId.entries.filter { table.getValue(it) <= contract }
}
