package org.formulagen.wrapper.bridge

import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import kotlin.test.Test

class IosParityTest {
    /** Phase C of the host bridge plan ships these on iOS. */
    private val pending = setOf("haptic.play", "lifecycle.pause", "lifecycle.resume")

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
