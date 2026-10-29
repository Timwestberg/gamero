package org.formulagen.wrapper.bridge

/**
 * The companion contract this build speaks, event-contracts `hovel/companions/contract.constants.ts`.
 * The host sends [CURRENT] in [HEADER] on every request to hovel-service, and trades it with the page
 * in `host.contract`, so neither side shows what the other cannot render.
 */
object HovelContract {
    /** `HOVEL_CONTRACT`. */
    const val CURRENT = 1

    /** `HOVEL_FIRST_CONTRACT`: what a document naming nothing newer needs. */
    const val FIRST = 1

    /** `HOVEL_CONTRACT_HEADER`. */
    const val HEADER = "X-Hovel-Contract"

    /** `HOVEL_CONTRACT_RUNTIMES`: the contract each runtime arrived in. */
    val RUNTIMES: Map<RuntimeId, Int> = mapOf(RuntimeId.CLAUDE_CODE to FIRST, RuntimeId.CODEX to FIRST, RuntimeId.GROK_BUILD to FIRST)

    /** The runtimes a client on [contract] can render: those that arrived in it or before. */
    fun runtimesFor(contract: Int): Set<RuntimeId> = RuntimeId.entries.filter { RUNTIMES.getValue(it) <= contract }.toSet()
}
