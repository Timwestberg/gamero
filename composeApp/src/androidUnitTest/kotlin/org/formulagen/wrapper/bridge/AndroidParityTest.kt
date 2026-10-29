package org.formulagen.wrapper.bridge

import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.hovel.hovelBridgePacks
import kotlin.test.Test

class AndroidParityTest {
    /** Phase C of the host bridge plan ships the platform methods on Android, and phone approvals ship `approvals.present`. */
    private val pending = setOf("haptic.play", "link.open", "lifecycle.pause", "lifecycle.resume", "approvals.present")

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
