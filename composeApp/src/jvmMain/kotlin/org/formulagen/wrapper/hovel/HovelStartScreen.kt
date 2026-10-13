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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.skia.Image

/**
 * Design exploration for the Hovel desktop start screen, drawn with the game's own art
 * (see resources/hovel/start/PROVENANCE.md). Not wired into main.kt yet: each variant
 * renders the same three states the current start screen has.
 */
sealed interface HovelStartState {
    data class Ready(val url: String) : HovelStartState
    data object Preparing : HovelStartState
    data class Failed(val reason: String) : HovelStartState
}

private const val RuntimeNote = "Hovel runs in a verified JetBrains Runtime with JCEF. It never downloads or modifies browser binaries."

/** Hovel palette: the environment art brief plus the web host's `--hv-*` tokens. */
private object HovelPalette {
    val plaster = Color(0xFFE9DCC1)
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
    // Godot room HUD: label shadow and the "Bubble"/"BubbleFocus" style boxes in room.tscn.
    val labelShadow = Color(0.12f, 0.10f, 0.09f)
    val bubble = Color(0.12f, 0.20f, 0.22f, 0.97f)
    val bubbleBorder = Color(0.55f, 0.69f, 0.63f)
    val bubbleFocus = Color(0.18f, 0.29f, 0.30f)
    val bubbleFocusBorder = Color(0.90f, 0.76f, 0.46f)
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

private fun hudText(art: HovelArt, size: Float, color: Color = HovelPalette.text) = TextStyle(
    color = color, fontFamily = art.display, fontSize = size.sp,
    shadow = Shadow(HovelPalette.labelShadow, Offset(1f, 2f), 0f),
)

// ---------------------------------------------------------------------------------------------
// Variant A: In the room. The start screen is the game's own room, with the HUD bubble as the button.
// ---------------------------------------------------------------------------------------------

@Composable
fun RoomStartScreen(state: HovelStartState, onContinue: () -> Unit, art: HovelArt = rememberHovelArt(), modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(HovelPalette.margin)) {
        val density = LocalDensity.current.density
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        // Leave a plum band above the room for the title, like the art preview's header margin.
        val scale = minOf(widthPx / RoomWidth, heightPx * 0.8f / RoomHeight)
        val frame = RoomFrame(scale, Offset((widthPx - RoomWidth * scale) / 2, heightPx - RoomHeight * scale - heightPx * 0.04f))
        fun roomDp(x: Float, y: Float): Pair<Dp, Dp> = frame.toScreen(Offset(x, y)).let { (it.x / density).dp to (it.y / density).dp }

        Canvas(Modifier.fillMaxSize()) {
            drawStarterRoom(art, frame)
            // Dim the room so the HUD reads, keeping the window glow at the center.
            drawRect(Brush.radialGradient(
                listOf(Color.Transparent, HovelPalette.ground.copy(alpha = 0.55f)),
                center = frame.toScreen(Offset(384f, 250f)), radius = RoomWidth * frame.scale * 0.7f,
            ))
        }

        Column(Modifier.fillMaxWidth().height((frame.origin.y / density).dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("HOVEL", style = hudText(art, 44f, HovelPalette.amber), letterSpacing = 6.sp)
        }

        // The game's response bubble sits at (264, 116)-(524, 194) in room space; the button lives there.
        val (bubbleX, bubbleY) = roomDp(264f, 112f)
        val bubbleWidth = (260f * frame.scale / density).dp
        Box(Modifier.offset(bubbleX, bubbleY).width(bubbleWidth)) {
            when (state) {
                is HovelStartState.Ready -> HudBubble(focused = true, onClick = onContinue) {
                    Text("Step inside", style = hudText(art, 22f), textAlign = TextAlign.Center)
                }
                HovelStartState.Preparing -> HudBubble(focused = false) {
                    Text("Lighting the lamps…", style = hudText(art, 18f))
                    Spacer(Modifier.height(8.dp))
                    PixelProgress()
                }
                is HovelStartState.Failed -> HudBubble(focused = true, onClick = onContinue) {
                    Text("The door is stuck", style = hudText(art, 18f, HovelPalette.error))
                    Text(state.reason, style = hudText(art, 12f, HovelPalette.muted), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("Try again", style = hudText(art, 16f, HovelPalette.amber))
                }
            }
        }

        // CharacterName and TaskState labels, placed under the keeper as in room.tscn.
        val (labelX, labelY) = roomDp(104f, 341f)
        Column(Modifier.offset(labelX, labelY).width((560f * frame.scale / density).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your keeper", style = hudText(art, 18f))
            Text(
                when (state) {
                    is HovelStartState.Ready -> "Waiting at ${state.url}"
                    HovelStartState.Preparing -> "Getting ready"
                    is HovelStartState.Failed -> "Locked out"
                },
                style = hudText(art, 13f, HovelPalette.plaster),
            )
        }

        Text(
            RuntimeNote, style = hudText(art, 11f, HovelPalette.muted), textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun HudBubble(focused: Boolean, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) HovelPalette.bubbleFocus else HovelPalette.bubble)
            .border(if (focused) 2.dp else 1.dp, if (focused) HovelPalette.bubbleFocusBorder else HovelPalette.bubbleBorder, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
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
// Variant B: Keeper card. The web host's dark plum panel beside a close-up of the room.
// ---------------------------------------------------------------------------------------------

@Composable
fun KeeperStartScreen(state: HovelStartState, onContinue: () -> Unit, art: HovelArt = rememberHovelArt(), modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize().background(HovelPalette.ground)) {
        Column(
            Modifier.weight(0.9f).fillMaxHeight().padding(40.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(HovelPalette.surface)
                    .border(1.dp, HovelPalette.line, RoundedCornerShape(16.dp))
                    .padding(32.dp),
            ) {
                Text("YOUR HOVEL", color = HovelPalette.ochre, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                Text("Welcome home", color = HovelPalette.text, fontFamily = art.display, fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
                Text("Your room is just as you left it. Rearrange the furniture, change your keeper's look, and pick up your tasks.", color = HovelPalette.muted, fontSize = 15.sp, lineHeight = 22.sp)
                Spacer(Modifier.height(28.dp))
                when (state) {
                    is HovelStartState.Ready -> {
                        PanelButton(art, "Open the door", onContinue)
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
                        PanelButton(art, "Try again", onContinue)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(RuntimeNote, color = HovelPalette.muted.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        BoxWithConstraints(Modifier.weight(1.1f).fillMaxHeight().clipToBounds()) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            // Zoom on the keeper and the furniture behind them.
            val scale = maxOf(widthPx / (RoomWidth * 0.62f), heightPx / (RoomHeight * 0.95f))
            val focus = Offset(450f, 250f)
            val frame = RoomFrame(scale, Offset(widthPx / 2, heightPx / 2) - focus * scale)
            Canvas(Modifier.fillMaxSize()) {
                drawStarterRoom(art, frame, keeperScale = 1.15f)
                drawRect(Brush.horizontalGradient(listOf(HovelPalette.ground, Color.Transparent), endX = size.width * 0.25f))
            }
        }
    }
}

@Composable
private fun PanelButton(art: HovelArt, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HovelPalette.ochre)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) { Text(label, color = HovelPalette.ochreInk, fontFamily = art.display, fontSize = 18.sp) }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

private val previewReady = HovelStartState.Ready("http://localhost:5173/hovel.html")
private val previewFailed = HovelStartState.Failed("A verified JetBrains Runtime with JCEF is required")

@Preview @Composable fun RoomReadyPreview() = RoomStartScreen(previewReady, {})
@Preview @Composable fun RoomPreparingPreview() = RoomStartScreen(HovelStartState.Preparing, {})
@Preview @Composable fun RoomFailedPreview() = RoomStartScreen(previewFailed, {})
@Preview @Composable fun KeeperReadyPreview() = KeeperStartScreen(previewReady, {})
@Preview @Composable fun KeeperPreparingPreview() = KeeperStartScreen(HovelStartState.Preparing, {})
@Preview @Composable fun KeeperFailedPreview() = KeeperStartScreen(previewFailed, {})
