package org.formulagen.wrapper.hovel

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.skia.Image

/**
 * Design exploration for the Hovel desktop start screen, drawn with the game's own art
 * (see resources/hovel/start/PROVENANCE.md). Not wired into main.kt yet: it renders the
 * same three states the current start screen has.
 */
sealed interface HovelStartState {
    data class Ready(val url: String) : HovelStartState
    data object Preparing : HovelStartState
    data class Failed(val reason: String) : HovelStartState
}

private const val RuntimeNote = "Hovel runs in a verified JetBrains Runtime with JCEF. It never downloads or modifies browser binaries."

/** Hovel palette: the environment art brief plus the web host's `--hv-*` tokens. */
private object HovelPalette {
    val oak = Color(0xFFBD8649)
    val plum = Color(0xFF443C55)
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

class HovelArt(val shell: ImageBitmap, val furniture: ImageBitmap, val characters: ImageBitmap, val display: FontFamily) {
    companion object {
        private fun bytes(name: String) =
            checkNotNull(HovelArt::class.java.getResourceAsStream("/hovel/start/$name")) { "Missing Hovel asset $name" }.use { it.readBytes() }

        private fun image(name: String) = Image.makeFromEncoded(bytes(name)).toComposeImageBitmap()

        fun load() = HovelArt(
            shell = image("hovel_room_shell.png"),
            furniture = image("hovel_furniture_sheet.png"),
            characters = image("source-atlas.png"),
            display = FontFamily(Font("PixelifySans", bytes("PixelifySans.ttf"))),
        )
    }
}

@Composable
fun rememberHovelArt(): HovelArt = remember { HovelArt.load() }

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

/** Maps room pixels to screen pixels. */
private class RoomFrame(val scale: Float, val origin: Offset) {
    fun toScreen(room: Offset) = origin + room * scale
}

private fun DrawScope.drawSprite(image: ImageBitmap, sprite: Sprite, frame: RoomFrame, extraScale: Float = 1f) {
    val s = sprite.scale * extraScale * frame.scale
    val topLeft = frame.toScreen(sprite.position) - sprite.anchor * s
    drawImage(
        image, sprite.src, sprite.size,
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize((sprite.size.width * s).toInt(), (sprite.size.height * s).toInt()),
        filterQuality = FilterQuality.Medium,
    )
}

/** Draws the starter room exactly as room.tscn lays it out, keeper included. */
private fun DrawScope.drawStarterRoom(art: HovelArt, frame: RoomFrame, keeperScale: Float = 1f) {
    drawRect(HovelPalette.margin)
    drawImage(
        art.shell, IntOffset.Zero, IntSize(art.shell.width, art.shell.height),
        dstOffset = IntOffset(frame.origin.x.toInt(), frame.origin.y.toInt()),
        dstSize = IntSize((RoomWidth * frame.scale).toInt(), (RoomHeight * frame.scale).toInt()),
        filterQuality = FilterQuality.Medium,
    )
    drawSprite(art.furniture, Rug, frame)
    val actors = Furniture.map { it to false } + (Keeper to true)
    actors.sortedBy { it.first.position.y }.forEach { (sprite, isKeeper) ->
        if (isKeeper) drawSprite(art.characters, sprite, frame, keeperScale) else drawSprite(art.furniture, sprite, frame)
    }
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
// Keeper card: the web host's dark plum panel beside a close-up of the room.
// ---------------------------------------------------------------------------------------------

@Composable
fun KeeperStartScreen(state: HovelStartState, onContinue: () -> Unit, art: HovelArt = rememberHovelArt(), modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(HovelPalette.ground)) {
        if (maxWidth < 600.dp) {
            // Phone portrait: the room on top, fading into the panel below.
            Column(Modifier.fillMaxSize()) {
                KeeperRoom(art, Modifier.weight(1f).fillMaxWidth(), fadeFromBottom = true)
                KeeperPanel(state, onContinue, art, compact = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.9f).fillMaxHeight().padding(40.dp), verticalArrangement = Arrangement.Center) {
                    KeeperPanel(
                        state, onContinue, art, compact = false,
                        modifier = Modifier
                            .widthIn(max = 440.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(HovelPalette.surface)
                            .border(1.dp, HovelPalette.line, RoundedCornerShape(16.dp))
                            .padding(32.dp),
                    )
                }
                KeeperRoom(art, Modifier.weight(1.1f).fillMaxHeight(), fadeFromBottom = false)
            }
        }
    }
}

@Composable
private fun KeeperPanel(state: HovelStartState, onContinue: () -> Unit, art: HovelArt, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("Hovel", color = HovelPalette.ochre, fontFamily = art.display, fontSize = 56.sp)
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
                PanelButton(art, "Try again", onContinue, buttonModifier)
            }
        }
        // The JCEF note only applies to the desktop host.
        if (!compact) {
            Spacer(Modifier.height(24.dp))
            Text(RuntimeNote, color = HovelPalette.muted.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

/** A close-up of the starter room centered on the keeper, fading into the panel side. */
@Composable
private fun KeeperRoom(art: HovelArt, modifier: Modifier, fadeFromBottom: Boolean) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val scale = if (fadeFromBottom) heightPx / (RoomHeight * 0.95f)
        else maxOf(widthPx / (RoomWidth * 0.62f), heightPx / (RoomHeight * 0.95f))
        val focus = if (fadeFromBottom) Offset(470f, 262f) else Offset(450f, 250f)
        val frame = RoomFrame(scale, Offset(widthPx / 2, heightPx / 2) - focus * scale)
        Canvas(Modifier.fillMaxSize()) {
            drawStarterRoom(art, frame, keeperScale = 1.15f)
            if (fadeFromBottom) {
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, HovelPalette.ground), startY = size.height * 0.7f, endY = size.height))
            } else {
                drawRect(Brush.horizontalGradient(listOf(HovelPalette.ground, Color.Transparent), endX = size.width * 0.25f))
            }
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

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

private val previewReady = HovelStartState.Ready("http://localhost:5173/hovel.html")
private val previewFailed = HovelStartState.Failed("A verified JetBrains Runtime with JCEF is required")

@Preview @Composable fun KeeperReadyPreview() = KeeperStartScreen(previewReady, {})
@Preview @Composable fun KeeperPreparingPreview() = KeeperStartScreen(HovelStartState.Preparing, {})
@Preview @Composable fun KeeperFailedPreview() = KeeperStartScreen(previewFailed, {})
@Preview @Composable fun KeeperPhoneReadyPreview() = Box(Modifier.size(390.dp, 844.dp)) { KeeperStartScreen(previewReady, {}) }
