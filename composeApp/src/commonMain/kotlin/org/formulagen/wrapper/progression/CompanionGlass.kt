package org.formulagen.wrapper.progression

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.formulagen.wrapper.ui.fgBlue300
import org.formulagen.wrapper.ui.fgInk300
import org.formulagen.wrapper.ui.fgInk900

/** A game-supplied `rrggbb` (or `#rrggbb`) accent, or [fallback] when it is missing or malformed. */
internal fun glassColor(hex: String?, fallback: Color = fgBlue300): Color =
    hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) } ?: fallback

/** Small illustrated robot portrait in the companion's [tint], brightened after saving, with [eyes] on its dark visor. */
@Composable
fun CompanionPortrait(name: String, tint: Color, acknowledged: Boolean, modifier: Modifier = Modifier, eyes: Color = fgInk300) {
    Canvas(modifier.size(58.dp).semantics { contentDescription = "$name portrait" }) {
        val w = size.width
        drawCircle(tint.copy(alpha = .12f))
        drawCircle(tint.copy(alpha = if (acknowledged) .8f else .25f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        drawLine(tint, Offset(w * .5f, w * .16f), Offset(w * .5f, w * .30f), 2.dp.toPx())
        drawCircle(tint, w * .035f, Offset(w * .5f, w * .15f))
        drawRoundRect(tint, Offset(w * .20f, w * .28f), Size(w * .60f, w * .48f), CornerRadius(w * .13f))
        drawRoundRect(fgInk900, Offset(w * .28f, w * .39f), Size(w * .44f, w * .23f), CornerRadius(w * .07f))
        for (x in listOf(.40f, .60f)) drawCircle(eyes, w * .035f, Offset(w * x, w * .50f))
        drawLine(fgInk900, Offset(w * .44f, w * .68f), Offset(w * .56f, w * .68f), 1.5.dp.toPx())
    }
}
