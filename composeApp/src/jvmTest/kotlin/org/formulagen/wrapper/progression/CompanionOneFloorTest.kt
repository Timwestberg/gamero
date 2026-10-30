package org.formulagen.wrapper.progression

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.*
import org.formulagen.wrapper.bridge.GameId
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalTestApi::class)
class CompanionOneFloorTest {
    /** Real arena rewards pass through rendered cards, confirmation and the success review. */
    @Test fun completeFloorThroughCompose() = runDesktopComposeUiTest(width = 1180, height = 820) {
        val game = desktopGame(System.getenv(), GameId.HOVEL_COMPANIONS)
        assumeTrue("Set HOVEL_GAMES_ROOT (it replaced HOVEL_GAME_PROJECT) to run the actual one-floor playtest", game != null)
        val save = Files.createTempDirectory("hovel-one-floor-ui-").resolve("save.json")
        val captures = File("build/reports/companion-presentation/one-floor").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = PresentationController { "floor-${System.nanoTime()}" }
        val host = DesktopGameHost(requireNotNull(game), scope, controller, System.getenv() + mapOf(
            "HOVEL_COMPANION_SAVE" to save.toString(),
            "HOVEL_PRESENTATION_HEADLESS" to if (System.getenv("HOVEL_ONE_FLOOR_RENDERED") == "1") "0" else "1",
            "HOVEL_CAPTURE_DIR" to captures.absolutePath,
            "HOVEL_PRESENTATION_SCENARIO" to "res://companions/tests/one_floor_scenario.gd"))
        try {
            setContent {
                val state by controller.state.collectAsState()
                CompanionPresentation(state, controller::select,
                    { action -> controller.begin(action)?.let(host::send) },
                    { controller.retry()?.let(host::send) }, controllerInput = host.controllerInput)
            }
            runOnIdle { host.start() }
            waitUntil(timeoutMillis = 90000) { controller.state.value.snapshot?.view == "growth" || host.exitCode.value != null }
            assertNull(host.exitCode.value, "Arena must reach its earned level before exiting")
            assertEquals(100L, controller.state.value.snapshot?.companion?.xp)
            onNodeWithTag("confirm-upgrade").assertIsNotEnabled()
            onNodeWithTag("upgrade-attack").performClick()
            onNodeWithText("Attack: 100% → 103%").assertExists()
            onNodeWithTag("upgrade-energy").performClick()
            onNodeWithText("Energy: 60 → 63").assertExists()
            assertNull(controller.state.value.pending, "Preview does not send an allocation")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(captures, "earned-growth.png"))
            onNodeWithTag("confirm-upgrade").performClick()
            waitUntil(timeoutMillis = 10000) { controller.state.value.snapshot?.view == "saved" && controller.state.value.pending == null }
            assertEquals("63", controller.state.value.snapshot?.appliedOption?.after)
            onNodeWithText("Continue").performClick()
            waitUntil(timeoutMillis = 60000) { controller.state.value.snapshot?.view == "review" || host.exitCode.value != null }
            assertNull(host.exitCode.value, "Arena must reach the physical exit before exiting")
            val review = assertNotNull(controller.state.value.snapshot?.review)
            assertEquals("success", review.outcome)
            assertEquals(130L, review.xp)
            assertEquals(listOf("ARC RIFLE"), review.retained)
            assertEquals(listOf("FOUNDRY LANCE"), review.banked)
            assertTrue(review.lost.isEmpty())
            assertTrue(review.temporaryExpired)
            onNodeWithText("+130 XP").assertExists()
            onNodeWithText("Foundry lance").assertExists()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(captures, "success-review.png"))
            onNodeWithText("Return to Hovel").performClick()
            waitUntil(timeoutMillis = 10000) { host.exitCode.value != null }
            assertEquals(0, host.exitCode.value)
        } finally {
            host.close()
            scope.cancel()
        }
    }
}
