package org.formulagen.wrapper.progression

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG relative luminance of a rendered sRGB sample. */
internal fun luminance(color: Color): Double {
    fun linear(channel: Float) = if (channel <= .04045f) channel / 12.92 else ((channel + .055) / 1.055).pow(2.4)
    return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
}

/** WCAG contrast ratio between two opaque colors, in either order. */
internal fun contrast(a: Color, b: Color): Double {
    val (la, lb) = luminance(a) to luminance(b)
    return (max(la, lb) + .05) / (min(la, lb) + .05)
}
