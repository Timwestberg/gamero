package org.formulagen.wrapper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.dp

// Hovel's Liquid Glass tokens, named after the web host's custom properties in
// game-platform/apps/game-bridge-host/src/components/liquid-glass/glass.css.
// Native interface chrome uses only these. Warm colors belong to world art and
// to the colors a game chooses for its companions, never to chrome.

internal val fgInk950 = Color(0xFF071013)
internal val fgInk900 = Color(0xFF0D1A1F)
internal val fgInk700 = Color(0xFF233942)
internal val fgInk300 = Color(0xFFB6C7CC)
internal val fgWhite = Color(0xFFFFFFFF)

internal val fgGreen500 = Color(0xFF00F5A3)
internal val fgGreen400 = Color(0xFF1FF8B0)
internal val fgBlue500 = Color(0xFF00A6FF)
internal val fgBlue300 = Color(0xFF54C6FF)
internal val fgRed400 = Color(0xFFFF6B7A)

/** `glass-edge`: the 1dp border on every glass surface. */
internal val glassEdge = Color.White.copy(alpha = .18f)

internal val radiusPanel = 22.dp
internal val radiusCard = 24.dp

/**
 * `brand-gradient`: CSS `linear-gradient(135deg, fg-green-500, fg-blue-500)`.
 * Like CSS, it runs from the top-left corner to the bottom-right one on any box shape.
 */
internal val brandGradient: Brush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val reach = (size.width + size.height) / 4
        val center = Offset(size.width / 2, size.height / 2)
        return LinearGradientShader(center - Offset(reach, reach), center + Offset(reach, reach), listOf(fgGreen500, fgBlue500))
    }
}
