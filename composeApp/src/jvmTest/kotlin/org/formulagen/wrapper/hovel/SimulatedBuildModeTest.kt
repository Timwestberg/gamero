package org.formulagen.wrapper.hovel

import org.junit.Test
import kotlin.test.*
import org.formulagen.wrapper.companion.ProviderLaunchMode
import org.formulagen.wrapper.companion.PackagedProviderMode

/** Run explicitly with -Pgamero.simulatedSmoke=true; no process, credentials or browser. */
class SimulatedBuildModeTest {
    @Test fun embeddedSimulationRejectsInheritedLiveRequest() {
        if (!PackagedProviderMode.simulated) return
        val before = System.getProperty("gamero.provider.mode")
        try {
            System.clearProperty("gamero.provider.mode")
            assertEquals(ProviderLaunchMode.SIMULATED, ProviderLaunchMode.current())
            System.setProperty("gamero.provider.mode", "codex")
            assertFails { ProviderLaunchMode.current() }
        } finally {
            if (before == null) System.clearProperty("gamero.provider.mode") else System.setProperty("gamero.provider.mode", before)
        }
    }
}
