package org.formulagen.wrapper.hovel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import org.formulagen.wrapper.ui.fgInk950

@OptIn(ExperimentalTestApi::class)
class HovelStartScreenPreviewTest {
    // Every start screen preview composes, and the settled desktop ones sit on the glass ground.
    @Test fun preparing() = capture("preparing", groundAt = true) { KeeperPreparingPreview() }
    @Test fun failed() = capture("failed", groundAt = true) { KeeperFailedPreview() }
    @Test fun launchMidway() = capture("launch-midway") { KeeperLaunchMidwayPreview() }
    @Test fun launchSettled() = capture("launch-settled", groundAt = true) { HovelLaunchPreview() }
    @Test fun phonePreparing() = capture("phone-preparing", width = 390, height = 844) { KeeperPhonePreparingPreview() }

    /** Export the preview's pixels to build/reports for visual review, as CompanionUiTest does. */
    private fun capture(name: String, width: Int = 1280, height: Int = 800, groundAt: Boolean = false, preview: @Composable () -> Unit) =
        runDesktopComposeUiTest(width, height) {
            setContent(preview)
            val image = onRoot().captureToImage()
            val folder = File("build/reports/hovel-start-screen").apply { mkdirs() }
            ImageIO.write(image.toAwtImage(), "png", File(folder, "$name.png"))
            // The top-left corner is outside both the room pane and the panel on a settled desktop screen.
            if (groundAt) assertEquals(fgInk950, image.toPixelMap()[8, 8], "$name ground")
        }
}
