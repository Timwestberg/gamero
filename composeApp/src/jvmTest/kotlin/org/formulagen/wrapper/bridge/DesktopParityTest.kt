package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.formulagen.wrapper.companion.HostCredentials
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.desktopProductPacks
import org.formulagen.wrapper.hovel.HovelCompanionCapability
import java.nio.file.Files
import kotlin.test.Test

class DesktopParityTest {
    /** Phase C of the host bridge plan ships these on desktop. */
    private val pending = setOf("link.open", "lifecycle.pause", "lifecycle.resume")

    @Test
    fun hovelOnDesktopMatchesTheParityTable() {
        val directory = Files.createTempDirectory("parity")
        val credentials = object : HostCredentials {
            override fun save(hostId: String, token: String) = Unit
            override fun read(hostId: String) = ""
        }
        val companion = HovelCompanion("https://example.test/hovel.html", directory, directory.resolve("codex"), directory, credentials)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val router = BridgeRouter(
            product = HOVEL_PRODUCT,
            capabilities = desktopHostCapabilities(),
            products = desktopProductPacks(HOVEL_PRODUCT, HovelCompanionCapability(companion), FakeChatCapability(),
                FakeProviderCapability(), FakePetCapability(), FakeGameCapability(), FakeWorkspaceCapability()),
            scope = scope,
            transport = {},
        )
        try {
            assertMatchesParity(HostPlatform.DESKTOP, router, pending)
        } finally {
            router.close()
            scope.cancel()
            companion.close()
        }
    }
}
