package org.formulagen.wrapper.progression

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.ui.fgBlue300
import org.formulagen.wrapper.ui.fgInk900
import org.formulagen.wrapper.ui.glassEdge
import org.formulagen.wrapper.ui.radiusCard

/** Refract scene artwork behind the panel; content is drawn separately and stays sharp. */
@Composable
internal fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    tint: Color = fgBlue300,
    emphasis: Float = 0f,
    reducedEffects: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val material = remember(backdrop?.image, reducedEffects) {
        if (reducedEffects) null else backdrop?.let {
            runCatching { liquidGlassRenderer(it.image) }.getOrElse { error ->
                log(LogLevel.Warn, "glass", "Liquid glass is unavailable; drawing plain panels", error)
                null
            }
        }
    }
    DisposableEffect(material) { onDispose { material?.close() } }
    val shape = RoundedCornerShape(radiusCard)
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }.clip(shape)
        .background(fgInk900)
        .drawWithCache {
            val brush = material?.brush(GlassUniforms(size, origin - (backdrop?.origin ?: Offset.Zero),
                radiusCard.toPx(), density, emphasis, tint))
            onDrawBehind { if (brush != null) drawRect(brush) }
        }
        .border(if (emphasis >= .6f) 2.dp else 1.dp,
            if (emphasis >= .6f) tint.copy(alpha = .85f) else glassEdge, shape), content = content)
}
