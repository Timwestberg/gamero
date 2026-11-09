package org.formulagen.wrapper.bridge

import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import kotlin.test.Test

class IosParityTest {
    /** Phase C of the host bridge plan ships the platform methods on iOS. */
    private val pending = setOf("haptic.play", "link.open", "lifecycle.pause", "lifecycle.resume")

    @Test
    fun hovelOnIosMatchesTheParityTable() {
        val lists = object : ListsLocalCapability {
            override suspend fun authorize(proof: String) = ListsLocalOwner("00000000-0000-4000-8000-000000000001")
            override suspend fun account() = ListsLocalAccount(ExplicitNull<String>(null))
            override suspend fun lock() = Unit
            override suspend fun read(expectedOwnerId: String, offset: Int) = ListsLocalPage(0, 0, "")
            override suspend fun begin(expectedOwnerId: String, expectedRevision: Long, size: Int) = ListsLocalTransfer("00000000-0000-4000-8000-000000000002")
            override suspend fun append(args: ListsLocalAppend) = Unit
            override suspend fun commit(transferId: String) = Unit
        }
        val bridge = HostBridge(HOVEL_PRODUCT, "https://hovel.example/hovel.html", iosHostCapabilities("test"), iosHovelBridgePacks(FakeApprovalCapability(), FakeApprovalDeviceCapability(), lists))
        try {
            assertMatchesParity(HostPlatform.IOS, bridge.router, pending)
        } finally {
            bridge.close()
        }
    }
}
