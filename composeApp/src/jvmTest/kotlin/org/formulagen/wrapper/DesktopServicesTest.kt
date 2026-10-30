package org.formulagen.wrapper

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import kotlin.test.*

class DesktopServicesTest {
    @Test fun closesInReverseOrderAndReportsEveryFailure() {
        val closed = mutableListOf<String>()
        val failure = assertFailsWith<IllegalStateException> {
            closeInReverse(listOf(
                AutoCloseable { closed += "pet" },
                AutoCloseable { closed += "providers"; error("providers failed") },
                AutoCloseable { closed += "games"; error("games failed") },
            ))
        }
        assertEquals(listOf("games", "providers", "pet"), closed)
        assertEquals("games failed", failure.message)
        assertEquals(listOf("providers failed"), failure.suppressed.map { it.message })
    }

    @Test fun packsEveryDesktopCapabilityForEachProduct() = runBlocking<Unit> {
        val support = Files.createTempDirectory("desktop-services")
        val services = DesktopServices.create("http://127.0.0.1:8133/", support, mapOf("GAMERO_CODEX_PATH" to "/missing/codex"),
            GAMERO_PRODUCT_NAME, window = null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val product = listOf("providers.status", "providers.list", "pet.configure", "pet.show", "games.launch")
            val gamero = BridgeRouter(GAMERO_PRODUCT_NAME, desktopHostCapabilities(), services.packs(GAMERO_PRODUCT_NAME), scope, {})
            assertTrue(gamero.knownMethods.containsAll(product))
            assertTrue("companion.capabilities" !in gamero.knownMethods, "the companion is Hovel's product")
            // No game project and no window: games.launch answers unsupported rather than unknown-method.
            assertEquals(product - "games.launch", product.filter { it in gamero.supportedMethods })
            val hovel = BridgeRouter(HOVEL_PRODUCT, desktopHostCapabilities(), services.packs(HOVEL_PRODUCT), scope, {})
            assertTrue("companion.capabilities" in hovel.knownMethods)
            assertNull(services.companion, "the Gamero product never starts Hovel's companion")
            assertNull(services.games)
            assertEquals(ProviderState.UNAVAILABLE, services.providers.perform(ProviderAction.STATUS, ProviderScope(RuntimeId.CODEX)).state)
            assertTrue(listOf("chat.open", "brain.read", "workspace.status", "providers.listForClient").none { it in hovel.knownMethods })
            gamero.close(); hovel.close()
        } finally { services.close(); scope.cancel(); support.toFile().deleteRecursively() }
        assertFails { services.pet.status() }
    }

    @Test fun claudeIsOfferedOnlyInDevelopmentBuildsWithTheGateOn() = runBlocking<Unit> {
        val support = Files.createTempDirectory("desktop-services-claude")
        val gateOn = mapOf("GAMERO_CODEX_PATH" to "/missing/codex", "GAMERO_CLAUDE_PATH" to "/missing/claude", "HOVEL_CLAUDE_PROVIDER" to "1")
        suspend fun runtimes(environment: Map<String, String>) = DesktopServices.create("http://127.0.0.1:8133/", support, environment,
            GAMERO_PRODUCT_NAME, window = null).use { it.providers.list().runtimes }
        try {
            assertEquals(listOf(RuntimeId.CODEX), runtimes(gateOn), "An unmarked build fails closed")
            System.setProperty("gamero.dev", "true")
            assertEquals(listOf(RuntimeId.CODEX), runtimes(gateOn - "HOVEL_CLAUDE_PROVIDER"))
            assertEquals(listOf(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE), runtimes(gateOn))
            System.setProperty("gamero.release", "true")
            assertEquals(listOf(RuntimeId.CODEX), runtimes(gateOn), "A release build ignores the setting")
        } finally { System.clearProperty("gamero.release"); System.clearProperty("gamero.dev"); support.toFile().deleteRecursively() }
    }
}
