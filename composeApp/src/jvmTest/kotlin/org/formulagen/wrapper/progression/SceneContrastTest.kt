package org.formulagen.wrapper.progression

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.formulagen.wrapper.ui.fgRed400

/**
 * Games supply the artwork behind the companion screens, so it can be any color. Danger text must not
 * sit straight on it: the surface just above each red line has to give `fg-red-400` 4.5:1.
 */
@OptIn(ExperimentalTestApi::class)
class SceneContrastTest {
    @Test fun dangerTextOverTheFoundry() = assertDangerContrast(FoundryPresentationScene)

    /** Pure white is the worst case for light text, standing in for any bright game art. */
    @Test fun dangerTextOverWhiteArt() = assertDangerContrast(PresentationScene("WHITE") { drawRect(Color.White) })

    private fun assertDangerContrast(scene: PresentationScene) = runDesktopComposeUiTest(width = 1180, height = 1600) {
        val review = ExpeditionReview("run-1", "failed", "active", "Ember fell in the Foundry.", 100, listOf("ARC RIFLE"),
            emptyList(), listOf("FOUNDRY LANCE"), broughtLost = listOf("EMBER SHIELD"), temporaryExpired = true)
        setContent {
            CompanionPresentation(PresentationState(growthFixture().copy(view = "review", review = review), error = ERROR), {}, {}, {}, scene = scene)
        }
        val pixels = onRoot().captureToImage().toPixelMap()
        for (text in listOf("Brought gear lost under this mode", ERROR)) {
            val bounds = onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            val row = (bounds.top - 2).toInt()
            val backdrop = (bounds.left.toInt() until bounds.right.toInt()).map { pixels[it, row] }.maxBy(::luminance)
            val ratio = contrast(fgRed400, backdrop)
            assertTrue(ratio >= 4.5, "\"$text\" over ${scene.name} measured $ratio:1")
        }
    }

    private companion object {
        const val ERROR = "Couldn't save the review. Check your connection and try again."
    }
}
