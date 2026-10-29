package org.formulagen.wrapper.bridge

/**
 * `host.contract` on the desktop: the page sends its companion contract and gets this build's
 * ([HovelContract.CURRENT]). The page's contract is kept for its context, so the host leaves out
 * anything newer than it can render ([pageRuntimes]); [invalidate] forgets it when the context ends.
 */
internal class DesktopHostContract : HostContractCapability {
    @Volatile var pageContract: Int? = null
        private set

    override suspend fun contract(pageContract: Int): Int {
        this.pageContract = pageContract
        return HovelContract.CURRENT
    }

    /** The runtimes the current page can render: those of its contract, or of the first one before a handshake. */
    fun pageRuntimes(): Set<RuntimeId> = HovelContract.runtimesFor(minOf(pageContract ?: HovelContract.FIRST, HovelContract.CURRENT))

    fun invalidate() { pageContract = null }
}
