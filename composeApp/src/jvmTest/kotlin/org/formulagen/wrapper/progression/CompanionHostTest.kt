package org.formulagen.wrapper.progression

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import org.junit.Assume.assumeTrue

class CompanionHostTest {
    /** Earn XP in the real scene, confirm in Kotlin and verify the saved receipt. */
    @Test fun realGodotRoundTrip() = roundTrip(false)

    /** A failed save followed by socket loss can retry the identical request safely. */
    @Test fun saveFailureAndReconnect() = roundTrip(true)

    /** Share the real launch flow while varying the injected storage failure. */
    @Test fun reviewAllocationReturnsToSameReceipt() = roundTrip(false, true)

    /** Exercise the desktop adapter with real saved offers and receipts. */
    private fun roundTrip(recover: Boolean, reviewFirst: Boolean = false) = runBlocking {
        val project = System.getenv("HOVEL_GAME_PROJECT")?.let(Path::of)
        assumeTrue("Set HOVEL_GAME_PROJECT to run the actual Godot integration", project != null)
        val gameProject = requireNotNull(project)
        val save = Files.createTempDirectory("hovel-roundtrip-").resolve("save.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = PresentationController { "roundtrip-${System.nanoTime()}" }
        val host = DesktopCompanionHost(gameProject, scope, controller, System.getenv() + mapOf(
            "HOVEL_PRESENTATION_RECOVERY" to if (recover) "1" else "0",
            "HOVEL_PRESENTATION_REVIEW_FIRST" to if (reviewFirst) "1" else "0",
            "HOVEL_COMPANION_SAVE" to save.toString(), "HOVEL_PRESENTATION_HEADLESS" to "1",
            "HOVEL_PRESENTATION_SCENARIO" to "res://companions/tests/presentation_scenario.gd"))
        try {
            withContext(Dispatchers.Main) { host.start() }
            if (reviewFirst) {
                val initial = withTimeout(25000) { controller.state.first { it.snapshot?.view == "review" } }
                assertEquals("run-1", initial.snapshot?.review?.id)
                assertEquals(1, initial.snapshot?.pending)
                withContext(Dispatchers.Main) { host.send(assertNotNull(controller.begin("upgrades"))) }
            }
            val growth = withTimeout(25000) { controller.state.first { it.snapshot?.view == "growth" && it.pending == null } }
            assertEquals("60", growth.snapshot?.offer?.options?.find { it.id == "energy" }?.before)
            withContext(Dispatchers.Main) {
                controller.select("energy")
                val command = assertNotNull(controller.begin("choose"))
                host.send(command)
                if (!recover) host.send(command)
            }
            if (recover) {
                withTimeout(10000) { controller.state.first { it.error != null } }
                withTimeout(10000) { host.connected.first { !it } }
                withTimeout(10000) { host.connected.first { it } }
                withContext(Dispatchers.Main) { host.send(assertNotNull(controller.retry())) }
            }
            val saved = withTimeout(10000) { controller.state.first { it.snapshot?.view == "saved" && it.pending == null } }
            assertEquals("63", saved.snapshot?.appliedOption?.after)
            withContext(Dispatchers.Main) { host.send(assertNotNull(controller.begin("continue"))) }
            val review = withTimeout(15000) { controller.state.first { it.snapshot?.view == "review" && it.pending == null } }
            assertEquals(100L, review.snapshot?.review?.xp)
            assertEquals("run-1", review.snapshot?.review?.id)
            assertEquals(0, review.snapshot?.pending)
            assertEquals(listOf("FOUNDRY LANCE"), review.snapshot?.review?.lost)
            assertEquals(listOf("ARC RIFLE"), review.snapshot?.review?.retained)
            withContext(Dispatchers.Main) { host.send(assertNotNull(controller.begin("close"))) }
            assertEquals(0, withTimeout(10000) { host.exitCode.first { it != null } })
            val persisted = Files.readString(save)
            assertTrue(persisted.contains("energy"))
            assertTrue(persisted.contains("FOUNDRY LANCE"))
        } finally { host.close(); scope.cancel() }
    }
}
