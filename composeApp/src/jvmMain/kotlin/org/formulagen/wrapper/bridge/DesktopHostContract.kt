package org.formulagen.wrapper.bridge

/**
 * `host.contract` on the desktop: the page sends its companion contract and gets this build's
 * ([HovelContract.CURRENT]). The page's contract is kept for its context ([pageContract]); every runtime
 * arrived in the first contract, so nothing is filtered by it yet. [invalidate] forgets it when the context ends.
 */
internal class DesktopHostContract : HostContractCapability {
    @Volatile var pageContract: Int? = null
        private set

    override suspend fun contract(pageContract: Int): Int {
        this.pageContract = pageContract
        return HovelContract.CURRENT
    }

    fun invalidate() { pageContract = null }
}
