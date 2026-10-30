package org.formulagen.wrapper.bridge

/**
 * The companion contract this build speaks, event-contracts `hovel/companions/contract.constants.ts`.
 * The host sends [CURRENT] in [HEADER] on every request to hovel-service, and trades it with the page
 * in `host.contract`, so neither side shows what the other cannot render.
 */
object HovelContract {
    /** `HOVEL_CONTRACT`. Contract 3: companions only ([COMPANIONS_ONLY]). */
    const val CURRENT = 3

    /** `HOVEL_FIRST_CONTRACT`: what a document naming nothing newer needs. */
    const val FIRST = 1

    /**
     * `HOVEL_CONTRACT_FEATURES`: the contract each answer change arrived in. Contract 2 added the claim's
     * `stop-requested` answer and a message page's threads.
     */
    val FEATURES: Map<String, Int> = mapOf(
        "companionsOnly" to 3,
        "messagePageThreads" to 2,
        "stopRequestedClaim" to 2,
    )

    /**
     * `HOVEL_CONTRACT_FEATURES.companionsOnly`, step E: tasks, the character and the hosted Brain are gone, and the
     * host bridge lost `chat.*`, `brain.*` and `workspace.*`. Nothing older has the answers this Mac gives, so a page
     * below it gets `update-required` from `host.contract` and from the methods that changed with it.
     */
    const val COMPANIONS_ONLY = 3

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
