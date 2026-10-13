package org.formulagen.wrapper.hovel

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test
import org.formulagen.wrapper.companion.*

class SimulatedSmokeTest {
    @Test fun simulatedJournalSurvivesCloseAndReopenWithoutAnotherLaunch() = runBlocking {
        val directory = Files.createTempDirectory("simulated-smoke-")
        try {
            SimulatedHovelCompanion(directory).use { first ->
                assertEquals(1, first.launchesThisStart)
                val capabilities = first.dispatch("capabilities", buildJsonObject {})
                assertEquals("simulated", capabilities.getValue("providerMode").jsonPrimitive.content)
                assertFalse(capabilities.getValue("connectComputer").jsonPrimitive.boolean)
                for (method in listOf("prepareConnection", "activateConnection", "execute", "brain/authorize")) {
                    assertFails { first.dispatch(method, buildJsonObject {}) }
                }
            }
            SimulatedHovelCompanion(directory).use { second -> assertEquals(0, second.launchesThisStart) }
            assertFalse(Files.exists(directory.resolve("registration.json")))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun ambiguousModesFailAndPackagedSimulationNeverFallsBackToCodex() {
        assertEquals(ProviderLaunchMode.CODEX, ProviderLaunchMode.parse(null, null))
        assertEquals(ProviderLaunchMode.SIMULATED, ProviderLaunchMode.parse("simulated", null))
        assertEquals(ProviderLaunchMode.SIMULATED, ProviderLaunchMode.parse(null, "simulated"))
        assertFails { ProviderLaunchMode.parse("simulated", "codex") }
        assertFails { ProviderLaunchMode.parse(null, "simulation") }
        assertFails { ProviderLaunchMode.parse("codex", null, "simulated") }
        assertEquals(ProviderLaunchMode.SIMULATED, ProviderLaunchMode.parse(null, null, "simulated"))
    }

    @Test fun simulatedModeBlocksLiveCompanionAndEveryCodexProcessBoundary() {
        val directory = Files.createTempDirectory("simulated-cli-denial-")
        val before = System.getProperty("gamero.provider.mode")
        try {
            val executable = directory.resolve("forbidden-cli")
            Files.writeString(executable, "#!/bin/sh\ntouch invoked\nexit 1\n")
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
            System.setProperty("gamero.provider.mode", "simulated")
            assertFails { HovelCompanion("http://127.0.0.1/native-bridge-smoke.html", directory, executable, directory) }
            val provider = CodexCliProvider(executable, directory, directory.resolve("output"))
            assertEquals(CodexCliProvider.Readiness.UNAVAILABLE, provider.readiness())
            assertFails { provider.builder(listOf("login", "status")) }
            assertFails { provider.execute(ExecutionRequest(java.util.UUID.randomUUID().toString(),
                java.util.UUID.randomUUID().toString(), "must not launch")) }
            assertFalse(Files.exists(directory.resolve("invoked")))
        } finally {
            if (before == null) System.clearProperty("gamero.provider.mode") else System.setProperty("gamero.provider.mode", before)
            directory.toFile().deleteRecursively()
        }
    }
}
