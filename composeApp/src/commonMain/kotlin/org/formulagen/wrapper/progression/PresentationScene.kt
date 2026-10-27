package org.formulagen.wrapper.progression

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Each game supplies artwork independently of progression or glass rendering. */
class PresentationScene(val name: String, val paint: DrawScope.() -> Unit)

/** Scene pixels and their origin, shared by background and refracting surfaces. */
internal data class GlassBackdrop(val image: ImageBitmap, val origin: Offset)
internal val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** Warm architectural light for Ember Tower's dedicated progression screen. */
val FoundryPresentationScene = PresentationScene("THE FOUNDRY") {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF182C35), Color(0xFF101E29), Color(0xFF0B151E))))
    drawRect(Brush.radialGradient(listOf(Color(0xFF855442).copy(alpha = .55f), Color.Transparent),
        center = Offset(size.width * .12f, size.height * .50f), radius = size.maxDimension * .65f))
    drawRect(Brush.radialGradient(listOf(Color(0xFF2C7B79).copy(alpha = .55f), Color.Transparent),
        center = Offset(size.width * .91f, size.height * .64f), radius = size.maxDimension * .48f))
    val center = Offset(size.width * .55f, size.height * .94f)
    for (index in 0..5) {
        val radius = size.maxDimension * (.30f + index * .12f)
        drawCircle(Color(0xFFE7B16B).copy(alpha = .09f), radius, center, style = Stroke(1.2.dp.toPx()))
    }
    for (index in -3..7) {
        val x = size.width * index / 5f
        drawLine(Color(0xFF8BDCD5).copy(alpha = .08f), Offset(x, 0f), Offset(x + size.width * .45f, size.height), 1.dp.toPx())
    }
    drawLine(Brush.linearGradient(listOf(Color.Transparent, Color(0xFFDDB58A).copy(alpha = .38f), Color.Transparent)),
        Offset(0f, size.height * .68f), Offset(size.width, size.height * .33f), 3.dp.toPx())
}

/** Rasterize only local artwork, once per size, density or scene change. */
@Composable
internal fun PresentationSceneHost(scene: PresentationScene, content: @Composable BoxScope.() -> Unit) {
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val image = remember(scene, dimensions, density) {
        if (dimensions.width <= 0 || dimensions.height <= 0) null
        else ImageBitmap(dimensions.width, dimensions.height).also { bitmap ->
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap),
                Size(dimensions.width.toFloat(), dimensions.height.toFloat()), scene.paint)
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { dimensions = it }
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind { image?.let { drawImage(it) } }) {
        CompositionLocalProvider(LocalGlassBackdrop provides image?.let { GlassBackdrop(it, origin) }) { content() }
    }
}
