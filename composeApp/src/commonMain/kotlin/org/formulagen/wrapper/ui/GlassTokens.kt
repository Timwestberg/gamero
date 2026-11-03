package org.formulagen.wrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.jetbrains.compose.resources.Font
import westbergwrapper.composeapp.generated.resources.Res
import westbergwrapper.composeapp.generated.resources.hovel_sans_black
import westbergwrapper.composeapp.generated.resources.hovel_sans_bold
import westbergwrapper.composeapp.generated.resources.hovel_sans_bold_italic
import westbergwrapper.composeapp.generated.resources.hovel_sans_heavy
import westbergwrapper.composeapp.generated.resources.hovel_sans_italic
import westbergwrapper.composeapp.generated.resources.hovel_sans_medium
import westbergwrapper.composeapp.generated.resources.hovel_sans_regular
import westbergwrapper.composeapp.generated.resources.hovel_sans_semibold

// Hovel's Liquid Glass tokens. The colors, glassEdge, brandGradient and hovelSans
// match the web host's custom properties in
// game-platform/apps/game-bridge-host/src/components/liquid-glass/glass.css
// (`--fg-*`, `--glass-edge`, `--brand-gradient`, `--font-sans`). radiusPanel (22) and
// radiusCard (24) are the design system's radius-panel and radius-card tokens, which
// glass.css does not declare as properties.
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
 * The panel role: opaque `fg-ink-900` behind a `glass-edge` rim. Text on it keeps its contrast whatever
 * the art behind it, so danger text (`fg-red-400`, 6.45:1 here) always sits on one.
 */
internal fun Modifier.inkPanel(radius: Dp = radiusPanel): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(fgInk900).border(1.dp, glassEdge, shape)
}

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

/**
 * `font-sans`: Hovel Sans, a self-hosted Latin subset of Lato 2.015, so interface text
 * renders the same on every OS. The web serves the same subset; see files/hovel/PROVENANCE.md.
 */
internal val hovelSans: FontFamily
    @Composable get() = FontFamily(
        Font(Res.font.hovel_sans_regular, FontWeight.Normal),
        Font(Res.font.hovel_sans_italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.hovel_sans_medium, FontWeight.Medium),
        Font(Res.font.hovel_sans_semibold, FontWeight.SemiBold),
        Font(Res.font.hovel_sans_bold, FontWeight.Bold),
        Font(Res.font.hovel_sans_bold_italic, FontWeight.Bold, FontStyle.Italic),
        Font(Res.font.hovel_sans_heavy, FontWeight.ExtraBold),
        Font(Res.font.hovel_sans_black, FontWeight.Black),
    )

/** Material's type scale with every style set in Hovel Sans. */
@Composable
internal fun hovelTypography(): Typography {
    val family = hovelSans
    return remember(family) {
        fun TextStyle.inHovelSans() = copy(fontFamily = family)
        with(Typography()) {
            copy(
                displayLarge = displayLarge.inHovelSans(), displayMedium = displayMedium.inHovelSans(), displaySmall = displaySmall.inHovelSans(),
                headlineLarge = headlineLarge.inHovelSans(), headlineMedium = headlineMedium.inHovelSans(), headlineSmall = headlineSmall.inHovelSans(),
                titleLarge = titleLarge.inHovelSans(), titleMedium = titleMedium.inHovelSans(), titleSmall = titleSmall.inHovelSans(),
                bodyLarge = bodyLarge.inHovelSans(), bodyMedium = bodyMedium.inHovelSans(), bodySmall = bodySmall.inHovelSans(),
                labelLarge = labelLarge.inHovelSans(), labelMedium = labelMedium.inHovelSans(), labelSmall = labelSmall.inHovelSans(),
            )
        }
    }
}

/** Tracking for titles, as on the web's `.auth-card__title`: −0.03em at the 800 weight. */
internal val titleTracking = (-0.03).em
