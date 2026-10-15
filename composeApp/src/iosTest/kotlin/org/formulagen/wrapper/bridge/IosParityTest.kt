package org.formulagen.wrapper.bridge

import kotlin.test.Test

class IosParityTest {
    /** Phase C of the host bridge plan ships these on iOS. */
    private val pending = setOf("haptic.play", "link.open", "lifecycle.pause", "lifecycle.resume")

    @Test
    fun hovelOnIosMatchesTheParityTable() {
        val bridge = HostBridge("https://hovel.example/hovel.html", iosHostCapabilities("test"), iosHovelBridgePacks())
        try {
            assertMatchesParity(HostPlatform.IOS, bridge.router, pending)
        } finally {
            bridge.close()
        }
    }
}
