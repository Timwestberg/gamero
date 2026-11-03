package org.formulagen.wrapper.bridge

/**
 * `host.contract` on the desktop: the page sends its companion contract and gets this build's
 * ([HovelContract.CURRENT]). The page's contract is kept for its context ([pageContract]), so `providers.list`
 * leaves out any runtime newer than it. [invalidate] forgets it when the context ends.
 *
 * A page below [HovelContract.COMPANIONS_ONLY] is from before step E and would misread this Mac: the handshake
 * answers it `update-required` (`failed` with [BridgeUpdateRequiredException.MESSAGE]), and so does every method
 * [requireCurrentPage] guards for that context.
 */
internal class DesktopHostContract : HostContractCapability {
    @Volatile var pageContract: Int? = null
        private set

    override suspend fun contract(pageContract: Int): Int {
        this.pageContract = pageContract
        requireCurrentPage()
        return HovelContract.CURRENT
    }

    /**
     * Refuses a context whose page sent a contract below [HovelContract.COMPANIONS_ONLY]. A context that has not
     * traded contracts is not refused here; what it calls answers as the page's own handshake expects.
     */
    fun requireCurrentPage() {
        val page = pageContract ?: return
        if (page < HovelContract.COMPANIONS_ONLY) throw BridgeUpdateRequiredException()
    }

    fun invalidate() { pageContract = null }
}
