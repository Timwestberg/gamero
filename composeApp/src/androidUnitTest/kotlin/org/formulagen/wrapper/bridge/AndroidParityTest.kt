package org.formulagen.wrapper.bridge

import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.hovel.hovelBridgePacks
import kotlin.test.Test

class AndroidParityTest {
    /** Phase C of the host bridge plan ships these on Android. */
    private val pending = setOf("haptic.play", "link.open", "lifecycle.pause", "lifecycle.resume")

    @Test
    fun hovelOnAndroidMatchesTheParityTable() {
        val bridge = HostBridge(HOVEL_PRODUCT, "https://hovel.example/hovel.html", androidHostCapabilities("test"), hovelBridgePacks())
        try {
            assertMatchesParity(HostPlatform.ANDROID, bridge.router, pending)
        } finally {
            bridge.close()
        }
    }
}
