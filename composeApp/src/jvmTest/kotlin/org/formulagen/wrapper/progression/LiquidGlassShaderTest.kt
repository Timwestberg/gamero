package org.formulagen.wrapper.progression

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertTrue
import org.formulagen.wrapper.ui.fgBlue300
import org.formulagen.wrapper.ui.fgGreen400
import org.formulagen.wrapper.ui.fgInk300
import org.formulagen.wrapper.ui.fgWhite

class LiquidGlassShaderTest {
    /** Compile and execute the real material, checking sampling after a card moves. */
    @Test fun samplesSceneAtCardOrigin() {
        val scene = ImageBitmap(320, 160)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(scene), Size(320f, 160f)) {
            drawRect(Color.Red, size = Size(160f, 160f))
            drawRect(Color.Blue, topLeft = Offset(160f, 0f), size = Size(160f, 160f))
        }
        val renderer = LiquidGlassRenderer(scene)
        try {
            val left = render(renderer, Offset.Zero).toPixelMap()[80, 80]
            val right = render(renderer, Offset(160f, 0f)).toPixelMap()[80, 80]
            assertTrue(left.red > left.blue + .2f, "Initial card must sample the red scene region")
            assertTrue(right.blue > right.red + .2f, "Moved card must sample its new scene region")
        } finally { renderer.close() }
    }

    /**
     * Bright game artwork must not wash out the fixed light foreground text. fg-red-400 is left out on
     * purpose: it measures about 4:1 here, so error text stays off glass.
     */
    @Test fun brightArtworkRetainsTextContrast() {
        val scene = ImageBitmap(160, 160)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(scene), Size(160f, 160f)) { drawRect(Color.White) }
        val renderer = LiquidGlassRenderer(scene)
        try {
            val background = render(renderer, Offset.Zero).toPixelMap()[80, 80]
            for (foreground in listOf(fgWhite, fgInk300, fgGreen400, fgBlue300)) {
                val ratio = contrast(foreground, background)
                assertTrue(ratio >= 4.5, "Glass body text contrast was $ratio for $foreground")
            }
        } finally { renderer.close() }
    }

    /** Render pixels instead of only checking shader source or generated uniforms. */
    private fun render(renderer: LiquidGlassRenderer, origin: Offset): ImageBitmap {
        val image = ImageBitmap(160, 160)
        val size = Size(160f, 160f)
        val brush = renderer.brush(GlassUniforms(size, origin, 24f, 1f, .7f, Color.White))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), size) { drawRect(brush) }
        return image
    }
}
