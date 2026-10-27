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
import kotlin.math.pow

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

    /** Bright game artwork must not wash out the fixed light foreground text. */
    @Test fun brightArtworkRetainsTextContrast() {
        val scene = ImageBitmap(160, 160)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(scene), Size(160f, 160f)) { drawRect(Color.White) }
        val renderer = LiquidGlassRenderer(scene)
        try {
            val background = render(renderer, Offset.Zero).toPixelMap()[80, 80]
            for (foreground in listOf(CompanionGlass.text, CompanionGlass.muted, CompanionGlass.amber, CompanionGlass.mint, CompanionGlass.plum)) {
                val contrast = (luminance(foreground) + .05) / (luminance(background) + .05)
                assertTrue(contrast >= 4.5, "Glass body text contrast was $contrast for $foreground")
            }
        } finally { renderer.close() }
    }

    /** Convert rendered sRGB samples to relative luminance for contrast measurement. */
    private fun luminance(color: Color): Double {
        fun linear(channel: Float) = if (channel <= .04045f) channel / 12.92 else ((channel + .055) / 1.055).pow(2.4)
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
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
