package org.formulagen.wrapper.bridge

import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import kotlin.test.Test

class IosParityTest {
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
        val bridge = HostBridge(HOVEL_PRODUCT, "https://hovel.example/hovel.html", iosHostCapabilities("test"), iosHovelBridgePacks(FakeApprovalCapability(), FakeApprovalDeviceCapability(), lists, FakeVoiceInputCapability(), FakeVoicePlaybackCapability(), FakeNotificationsCapability()))
        try {
            assertMatchesParity(HostPlatform.IOS, bridge.router)
        } finally {
            bridge.close()
        }
    }
}
