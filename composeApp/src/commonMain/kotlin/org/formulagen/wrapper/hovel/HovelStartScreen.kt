package org.formulagen.wrapper.hovel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import westbergwrapper.composeapp.generated.resources.Res
import westbergwrapper.composeapp.generated.resources.hovel_furniture_sheet
import westbergwrapper.composeapp.generated.resources.hovel_room_shell
import westbergwrapper.composeapp.generated.resources.hovel_traveler_atlas
import westbergwrapper.composeapp.generated.resources.pixelify_sans

/**
 * Hovel start screen, drawn with the game's own art (see composeResources/files/hovel/PROVENANCE.md).
 * The desktop host shows it for GAMERO_PRODUCT=hovel; it covers the ready, preparing and failed
 * states and can open with a short launch animation.
 */
sealed interface HovelStartState {
    data class Ready(val url: String) : HovelStartState
    data object Preparing : HovelStartState
    /** [canRetry] is false when the host must restart to try again, as with JCEF. */
    data class Failed(val reason: String, val canRetry: Boolean = true) : HovelStartState
}

/** Hovel palette: the environment art brief plus the web host's `--hv-*` tokens. */
private object HovelPalette {
    val oak = Color(0xFFBD8649)
    val plum = Color(0xFF443C55)
    val amber = Color(0xFFE7B16B)
    val margin = Color(0xFF3B3342)
    val ground = Color(0xFF17141F)
    val surface = Color(0xFF1E1A28)
    val line = Color(0xFF3A3350)
    val text = Color(0xFFF4EDE1)
    val muted = Color(0xFFC9BFD9)
    val ochre = Color(0xFFE8A95A)
    val ochreInk = Color(0xFF2A1708)
    val error = Color(0xFFFF9C8A)
}

// ---------------------------------------------------------------------------------------------
// Game art
// ---------------------------------------------------------------------------------------------

class HovelArt(val shell: ImageBitmap, val furniture: ImageBitmap, val characters: ImageBitmap, val display: FontFamily)

@Composable
fun rememberHovelArt(): HovelArt {
    val shell = imageResource(Res.drawable.hovel_room_shell)
    val furniture = imageResource(Res.drawable.hovel_furniture_sheet)
    val characters = imageResource(Res.drawable.hovel_traveler_atlas)
    val display = FontFamily(Font(Res.font.pixelify_sans))
    return remember(shell, furniture, characters, display) { HovelArt(shell, furniture, characters, display) }
}

/** A sprite placed like room.tscn places it: [anchor] (atlas pixels) lands on [position] (room pixels). */
private class Sprite(val src: IntOffset, val size: IntSize, val anchor: Offset, val position: Offset, val scale: Float)

// Room space is the Godot Environment node: the shell is 768 x 512 room pixels.
private const val RoomWidth = 768f
private const val RoomHeight = 512f
private val Rug = Sprite(IntOffset(52, 596), IntSize(552, 316), Offset(276f, 158f), Offset(577f, 356f), 0.2774565f)
private val Furniture = listOf(
    Sprite(IntOffset(628, 112), IntSize(436, 396), Offset(218f, 376f), Offset(580f, 207f), 0.2382135f), // bookshelf
    Sprite(IntOffset(1132, 112), IntSize(336, 388), Offset(168f, 370f), Offset(658f, 209f), 0.1584165f), // plant
    Sprite(IntOffset(80, 80), IntSize(496, 408), Offset(248f, 388f), Offset(166f, 224f), 0.2082435f), // desk
    Sprite(IntOffset(668, 600), IntSize(428, 308), Offset(214f, 292f), Offset(594f, 336f), 0.2436555f), // bench
    Sprite(IntOffset(1188, 596), IntSize(256, 316), Offset(128f, 296f), Offset(504f, 336f), 0.217194f), // side table
)
// The assembled traveler in the character atlas, scaled to the kit's 110 px room height.
private val Keeper = Sprite(IntOffset(1165, 520), IntSize(230, 485), Offset(115f, 482f), Offset(384f, 331f), 110f / 490f)
private val Doorway = Offset(384f, 478f)
private val Windows = listOf(Offset(256f, 84f), Offset(511f, 84f))

/** Maps room pixels to screen pixels. */
private class RoomFrame(val scale: Float, val origin: Offset) {
    fun toScreen(room: Offset) = origin + room * scale
}

private fun DrawScope.drawSprite(image: ImageBitmap, sprite: Sprite, frame: RoomFrame, position: Offset = sprite.position, extraScale: Float = 1f) {
    val s = sprite.scale * extraScale * frame.scale
    val topLeft = frame.toScreen(position) - sprite.anchor * s
    drawImage(
        image, sprite.src, sprite.size,
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize((sprite.size.width * s).toInt(), (sprite.size.height * s).toInt()),
        filterQuality = FilterQuality.Medium,
    )
}

/** Draws the starter room as room.tscn lays it out, with the keeper at [keeperAt] (null hides them). */
private fun DrawScope.drawStarterRoom(art: HovelArt, frame: RoomFrame, keeperAt: Offset?, keeperScale: Float = 1f) {
    drawRect(HovelPalette.margin)
    drawImage(
        art.shell, IntOffset.Zero, IntSize(art.shell.width, art.shell.height),
        dstOffset = IntOffset(frame.origin.x.toInt(), frame.origin.y.toInt()),
        dstSize = IntSize((RoomWidth * frame.scale).toInt(), (RoomHeight * frame.scale).toInt()),
        filterQuality = FilterQuality.Medium,
    )
    drawSprite(art.furniture, Rug, frame)
    val actors = Furniture.map { it to it.position } + listOfNotNull(keeperAt?.let { Keeper to it })
    actors.sortedBy { it.second.y }.forEach { (sprite, at) ->
        if (sprite === Keeper) drawSprite(art.characters, sprite, frame, at, keeperScale) else drawSprite(art.furniture, sprite, frame)
    }
}

// ---------------------------------------------------------------------------------------------
// Launch animation
// ---------------------------------------------------------------------------------------------

/** Progress of [t] through the window [start, end], clamped to 0..1. */
private fun phase(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)

private fun easeInOut(x: Float) = x * x * (3 - 2 * x)

/** Quantizes [x] to [steps] levels, for pixel-style reveals. */
private fun stepped(x: Float, steps: Int) = (x * steps).toInt().coerceAtMost(steps) / steps.toFloat()

/**
 * The launch beat, as a pure function of intro progress t in 0..1:
 * the dark room's lamps light up, the keeper walks in through the door,
 * the camera settles on them and the panel arrives.
 */
private class LaunchBeat(t: Float) {
    val darkness = 0.88f * (1 - easeInOut(phase(t, 0.08f, 0.42f)))
    val lamps = phase(t, 0.08f, 0.24f).let { if (it < 1f) stepped(it, 4) else 1f }
    private val walk = phase(t, 0.28f, 0.66f)
    val keeperAt: Offset? = if (t < 0.28f) null else {
        val y = Doorway.y + (Keeper.position.y - Doorway.y) * easeInOut(walk)
        // A small hop on each of four steps while walking.
        val hop = if (walk in 0f..0.999f) abs(sin(walk * 4 * PI)).toFloat() * 4f else 0f
        Offset(Keeper.position.x, y - hop)
    }
    val zoom = easeInOut(phase(t, 0.5f, 0.88f))
    val panel = easeInOut(phase(t, 0.78f, 1f))
    val title = stepped(phase(t, 0.78f, 0.94f), 4)
}

/** Plays the launch animation once, then settles on [KeeperStartScreen]. */
@Composable
fun HovelLaunchScreen(
    state: HovelStartState,
    onContinue: () -> Unit,
    footnote: String? = null,
    durationMillis: Int = 2600,
    art: HovelArt = rememberHovelArt(),
    modifier: Modifier = Modifier,
) {
    // Saved so the launch plays once, not again when an Android activity is recreated.
    var played by rememberSaveable { mutableStateOf(false) }
    val intro = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        intro.animateTo(1f, tween(durationMillis, easing = LinearEasing))
        played = true
    }
    KeeperStartScreen(state, onContinue, footnote, art, modifier, intro = intro.value)
}

// ---------------------------------------------------------------------------------------------
// Keeper card: the web host's dark plum panel beside a close-up of the room.
// ---------------------------------------------------------------------------------------------

/**
 * @param footnote host-specific fine print, such as the desktop runtime note.
 * @param intro launch animation progress; 1 is the settled start screen.
 */
@Composable
fun KeeperStartScreen(
    state: HovelStartState,
    onContinue: () -> Unit,
    footnote: String? = null,
    art: HovelArt = rememberHovelArt(),
    modifier: Modifier = Modifier,
    intro: Float = 1f,
) {
    val beat = LaunchBeat(intro)
    val panelMotion = Modifier.alpha(beat.panel).graphicsLayer { translationY = (1 - beat.panel) * 24.dp.toPx() }
    var panelHeight by remember { mutableStateOf(0) }
    BoxWithConstraints(modifier.fillMaxSize().background(HovelPalette.ground)) {
        val phone = maxWidth < 600.dp
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // The room fills the window during the launch, then settles into its pane.
        val settled = if (phone) Rect(0f, 0f, w, (h - panelHeight).coerceAtLeast(h * 0.5f)) else Rect(w * 0.9f / 2f, 0f, w, h)
        Canvas(Modifier.fillMaxSize()) { drawKeeperRoom(art, beat, settled, fadeFromBottom = phone) }
        if (phone) {
            KeeperPanel(
                state, onContinue, art, footnote, compact = true, beat.title,
                panelMotion.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { panelHeight = it.height }
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp).padding(bottom = 32.dp),
            )
        } else {
            Column(Modifier.fillMaxWidth(0.45f).fillMaxHeight().padding(40.dp), verticalArrangement = Arrangement.Center) {
                KeeperPanel(
                    state, onContinue, art, footnote, compact = false, beat.title,
                    modifier = panelMotion
                        .widthIn(max = 440.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(HovelPalette.surface)
                        .border(1.dp, HovelPalette.line, RoundedCornerShape(16.dp))
                        .padding(32.dp),
                )
            }
        }
    }
}

@Composable
private fun KeeperPanel(
    state: HovelStartState,
    onContinue: () -> Unit,
    art: HovelArt,
    footnote: String?,
    compact: Boolean,
    titleReveal: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("Hovel", color = HovelPalette.ochre, fontFamily = art.display, fontSize = 56.sp, modifier = Modifier.alpha(titleReveal))
        Spacer(Modifier.height(4.dp))
        Text("Make yourself at home.", color = HovelPalette.muted, fontSize = 17.sp)
        Spacer(Modifier.height(if (compact) 24.dp else 28.dp))
        val buttonModifier = if (compact) Modifier.fillMaxWidth() else Modifier
        when (state) {
            is HovelStartState.Ready -> {
                PanelButton(art, "Open the door", onContinue, buttonModifier)
                Spacer(Modifier.height(10.dp))
                Text(state.url, color = HovelPalette.muted.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            HovelStartState.Preparing -> {
                Text("Lighting the lamps…", color = HovelPalette.text, fontFamily = art.display, fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                PixelProgress()
            }
            is HovelStartState.Failed -> {
                Text("The door is stuck", color = HovelPalette.error, fontFamily = art.display, fontSize = 18.sp)
                Text(state.reason, color = HovelPalette.muted, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                if (state.canRetry) PanelButton(art, "Try again", onContinue, buttonModifier)
                else Text("Close Hovel and open it again to retry.", color = HovelPalette.text, fontSize = 14.sp)
            }
        }
        if (footnote != null) {
            Spacer(Modifier.height(24.dp))
            Text(footnote, color = HovelPalette.muted.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The starter room inside [pane]: the whole window and the whole room while the launch plays,
 * then [settled] with a close-up centered on the keeper.
 */
private fun DrawScope.drawKeeperRoom(art: HovelArt, beat: LaunchBeat, settled: Rect, fadeFromBottom: Boolean) {
    val z = beat.zoom
    val pane = Rect(lerp(0f, settled.left, z), lerp(0f, settled.top, z), lerp(size.width, settled.right, z), lerp(size.height, settled.bottom, z))
    val closeScale = if (fadeFromBottom) settled.height / (RoomHeight * 0.95f)
    else maxOf(settled.width / (RoomWidth * 0.62f), settled.height / (RoomHeight * 0.95f))
    val closeFocus = if (fadeFromBottom) Offset(470f, 262f) else Offset(450f, 250f)
    // Desktop opens on the whole room; a portrait phone opens on the doorway and the keeper's path,
    // since the whole room would be a thin band there.
    val wideScale = if (fadeFromBottom) size.height * 0.68f / RoomHeight else minOf(size.width / RoomWidth, size.height / RoomHeight) * 0.92f
    val wideFocus = if (fadeFromBottom) Offset(Keeper.position.x, 290f) else Offset(RoomWidth / 2, RoomHeight / 2)
    val scale = lerp(wideScale, closeScale, z)
    val focus = wideFocus + (closeFocus - wideFocus) * z
    val frame = RoomFrame(scale, pane.center - focus * scale)
    clipRect(pane.left, pane.top, pane.right, pane.bottom) {
        drawStarterRoom(art, frame, beat.keeperAt, keeperScale = 1f + 0.15f * z)
        if (beat.darkness > 0f) {
            drawRect(HovelPalette.ground.copy(alpha = beat.darkness))
            // Window light blooms ahead of the room, as the lamps catch.
            Windows.forEach { window ->
                val center = frame.toScreen(window)
                val radius = 70f * frame.scale * beat.lamps
                if (radius > 0f) drawCircle(Brush.radialGradient(listOf(HovelPalette.amber.copy(alpha = 0.55f * beat.darkness), Color.Transparent), center, radius), radius, center)
            }
        }
        val fade = HovelPalette.ground.copy(alpha = beat.panel)
        if (fadeFromBottom) {
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, fade), startY = pane.top + pane.height * 0.7f, endY = pane.bottom))
        } else {
            drawRect(Brush.horizontalGradient(listOf(fade, Color.Transparent), startX = pane.left, endX = pane.left + pane.width * 0.25f))
        }
    }
}

@Composable
private fun PanelButton(art: HovelArt, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HovelPalette.ochre)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = HovelPalette.ochreInk, fontFamily = art.display, fontSize = 18.sp) }
}

/** Twelve chunky honey-oak cells that fill and loop, matching the art's chunky pixel clusters. */
@Composable
private fun PixelProgress(cells: Int = 12) {
    val filled by rememberInfiniteTransition().animateFloat(
        initialValue = 0f, targetValue = cells + 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(cells) { i ->
            Box(Modifier.size(10.dp).background(if (i < filled.toInt()) HovelPalette.oak else HovelPalette.plum))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

private val previewReady = HovelStartState.Ready("http://localhost:5173/hovel.html")
private val previewFailed = HovelStartState.Failed("A verified JetBrains Runtime with JCEF is required")
private const val previewFootnote = "Hovel runs in a verified JetBrains Runtime with JCEF. It never downloads or modifies browser binaries."

@Preview @Composable fun KeeperReadyPreview() = KeeperStartScreen(previewReady, {}, previewFootnote)
@Preview @Composable fun KeeperPreparingPreview() = KeeperStartScreen(HovelStartState.Preparing, {}, previewFootnote)
@Preview @Composable fun KeeperFailedPreview() = KeeperStartScreen(previewFailed, {}, previewFootnote)
@Preview @Composable fun KeeperPhoneReadyPreview() = Box(Modifier.size(390.dp, 844.dp)) { KeeperStartScreen(previewReady, {}) }
@Preview @Composable fun KeeperLaunchMidwayPreview() = KeeperStartScreen(previewReady, {}, previewFootnote, intro = 0.45f)
@Preview @Composable fun HovelLaunchPreview() = HovelLaunchScreen(previewReady, {}, previewFootnote)
