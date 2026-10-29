package org.formulagen.wrapper.bridge

import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import kotlin.test.Test

class IosParityTest {
    /** Phase C of the host bridge plan ships the platform methods on iOS, and phone approvals ship `approvals.present`. */
    private val pending = setOf("haptic.play", "lifecycle.pause", "lifecycle.resume", "approvals.present")

    @Test
    fun hovelOnIosMatchesTheParityTable() {
        val bridge = HostBridge(HOVEL_PRODUCT, "https://hovel.example/hovel.html", iosHostCapabilities("test"), iosHovelBridgePacks())
        try {
            assertMatchesParity(HostPlatform.IOS, bridge.router, pending)
        } finally {
            bridge.close()
        }
    }
}
