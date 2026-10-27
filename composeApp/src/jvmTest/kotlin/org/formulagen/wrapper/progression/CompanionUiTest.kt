package org.formulagen.wrapper.progression

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.flow.MutableSharedFlow

@OptIn(ExperimentalTestApi::class)
class CompanionUiTest {
    /** Controller focus must follow the current screen after growth becomes a review. */
    @Test fun controllerNavigationSurvivesScreenChanges() = runDesktopComposeUiTest(width = 1180, height = 820) {
        val input = MutableSharedFlow<String>(extraBufferCapacity = 16)
        var snapshot by mutableStateOf(growthFixture())
        val actions = mutableListOf<String>()
        setContent { CompanionPresentation(PresentationState(snapshot), {}, actions::add, {}, controllerInput = input) }
        onNodeWithTag("upgrade-energy").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        runOnIdle {
            snapshot = snapshot.copy(view = "review", offer = null, pending = 0,
                review = ExpeditionReview("run-1", "success", "active", "Together.", 100,
                    listOf("ARC RIFLE"), emptyList(), emptyList(), temporaryExpired = true))
        }
        onNodeWithText("Return to Hovel").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        runOnIdle { input.tryEmit("right") }
        waitForIdle()
        onNodeWithText("Shared equipment").assertIsFocused()
        runOnIdle { input.tryEmit("accept") }
        waitForIdle()
        assertEquals(listOf("inventory"), actions)
        runOnIdle { input.tryEmit("back") }
        waitForIdle()
        assertEquals(listOf("inventory", "close"), actions)
    }

    /** Controller activation previews a card and confirms only from the separate button. */
    @Test fun controllerPreviewRequiresSeparateConfirmation() = runDesktopComposeUiTest(width = 1180, height = 820) {
        val input = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val controller = PresentationController { "controller-choice" }
        controller.receive(GameMessage("state", "s", snapshot = growthFixture()))
        var submitted: PresentationCommand? = null
        setContent {
            val state by controller.state.collectAsState()
            CompanionPresentation(state, controller::select, { submitted = controller.begin(it) }, {}, controllerInput = input)
        }
        onNodeWithTag("upgrade-attack").assertIsFocused()
        onNodeWithTag("upgrade-energy").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        runOnIdle { input.tryEmit("accept") }
        waitForIdle()
        assertNull(submitted)
        onNodeWithTag("upgrade-energy").assertIsSelected()
        runOnIdle { input.tryEmit("down") }
        waitForIdle()
        onNodeWithTag("confirm-upgrade").assertIsFocused()
        runOnIdle { input.tryEmit("accept") }
        waitForIdle()
        assertEquals("energy", submitted?.optionId)
        runOnIdle { input.tryEmit("accept") }
        waitForIdle()
        assertEquals("controller-choice", submitted?.requestId)
    }

    /** Preview is inspectable before any confirmation callback fires. */
    @Test fun previewConfirmationAndCapture() = runDesktopComposeUiTest(width = 1180, height = 820) {
        val controller = PresentationController { "ui-request" }
        controller.receive(GameMessage("state", "s", snapshot = growthFixture()))
        var submitted: PresentationCommand? = null
        setContent {
            val state by controller.state.collectAsState()
            CompanionPresentation(state, controller::select, { submitted = controller.begin(it) }, {})
        }
        onNodeWithTag("confirm-upgrade").assertIsNotEnabled()
        onNodeWithTag("upgrade-energy").performClick()
        assertNull(submitted)
        onNodeWithTag("confirm-upgrade").assertIsEnabled()
        onNodeWithText("Energy: 60 → 63").assertExists()
        saveCapture("growth-desktop")
        onNodeWithTag("confirm-upgrade").performClick()
        assertEquals("energy", submitted?.optionId)
        onNodeWithText("Saving…").assertExists()
    }

    /** Keyboard activation selects first, then confirms through the separate button. */
    @Test fun keyboardPreviewAndConfirmation() = runDesktopComposeUiTest(width = 1180, height = 820) {
        val controller = PresentationController { "keyboard" }
        controller.receive(GameMessage("state", "s", snapshot = growthFixture()))
        var command: PresentationCommand? = null
        setContent {
            val state by controller.state.collectAsState()
            CompanionPresentation(state, controller::select, { command = controller.begin(it) }, {})
        }
        onNodeWithTag("upgrade-energy").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithTag("upgrade-energy").performKeyInput { pressKey(Key.Enter) }
        assertNull(command)
        onNodeWithTag("confirm-upgrade").assertIsEnabled()
        onNodeWithTag("upgrade-energy").performKeyInput { pressKey(Key.Tab); pressKey(Key.Enter) }
        assertEquals("energy", command?.optionId)
    }

    /** Failure receipts preserve named brought gear, XP and lost discoveries. */
    @Test fun reviewCapture() = runDesktopComposeUiTest(width = 1180, height = 820) {
        val review = ExpeditionReview("run-1", "failed", "active", "Ember defeated 5 foes in the Foundry.", 100,
            listOf("ARC RIFLE"), emptyList(), listOf("FOUNDRY LANCE"), temporaryExpired = true)
        setContent { CompanionPresentation(PresentationState(growthFixture().copy(view = "review", review = review)), {}, {}, {}) }
        onNodeWithText("+100 XP").assertExists()
        onNodeWithText("Arc rifle").assertExists()
        onNodeWithText("Foundry lance").assertExists()
        saveCapture("review-failure-desktop")
    }

    /** A phone-sized viewport and large text retain every choice and confirmation. */
    @Test fun narrowLargeTextReducedEffects() = runDesktopComposeUiTest(width = 390, height = 844) {
        val controller = PresentationController { "mobile" }
        controller.receive(GameMessage("state", "s", snapshot = growthFixture()))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.35f)) {
                val state by controller.state.collectAsState()
                CompanionPresentation(state, controller::select, { controller.begin(it) }, {}, reducedEffectsInitially = true)
            }
        }
        onNodeWithTag("upgrade-energy").performScrollTo().performClick()
        onNodeWithTag("confirm-upgrade").performScrollTo().assertIsDisplayed().assertIsEnabled()
        saveCapture("growth-narrow-large-text")
    }

    /** Scrolling and replacing game artwork preserve selection and the material lifetime. */
    @Test fun narrowGlassAndSceneReplacement() = runDesktopComposeUiTest(width = 390, height = 844) {
        val controller = PresentationController { "scene-change" }
        controller.receive(GameMessage("state", "s", snapshot = growthFixture()))
        var scene by mutableStateOf(FoundryPresentationScene)
        setContent {
            val state by controller.state.collectAsState()
            CompanionPresentation(state, controller::select, { controller.begin(it) }, {}, scene = scene)
        }
        onNodeWithTag("upgrade-energy").performScrollTo().performClick()
        onNodeWithTag("upgrade-energy").assertIsSelected()
        saveCapture("growth-narrow-glass")
        runOnIdle { scene = PresentationScene("ANOTHER GAME") { drawRect(Color(0xFF252743)) } }
        onNodeWithTag("upgrade-energy").assertIsSelected()
        onNodeWithTag("confirm-upgrade").performScrollTo().assertIsEnabled()
        saveCapture("growth-alternate-scene")
    }

    /** Export actual Compose pixels for visual review. */
    private fun ComposeUiTest.saveCapture(name: String) {
        val folder = File("build/reports/companion-presentation").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, "$name.png"))
    }
}
