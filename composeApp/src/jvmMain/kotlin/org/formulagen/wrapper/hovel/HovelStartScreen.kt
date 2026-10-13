package org.formulagen.wrapper.hovel

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Design exploration for the Hovel desktop start screen. Not wired into main.kt yet:
 * each variant renders the same three states the current start screen has.
 */
sealed interface HovelStartState {
    data class Ready(val url: String) : HovelStartState
    data object Preparing : HovelStartState
    data class Failed(val reason: String) : HovelStartState
}

private const val RuntimeNote = "Hovel runs in a verified JetBrains Runtime with JCEF. It never downloads or modifies browser binaries."

// ---------------------------------------------------------------------------------------------
// Variant A: Hearth. Warm, dark, centered. Least information, strongest mood.
// ---------------------------------------------------------------------------------------------

private object Hearth {
    val night = Color(0xFF1B1411)
    val ember = Color(0xFFE8833A)
    val glow = Color(0xFFFFC27A)
    val card = Color(0xFF2A201B)
    val text = Color(0xFFF6EBDD)
    val muted = Color(0xFFB9A694)
    val error = Color(0xFFFF8A7A)
}

@Composable
fun HearthStartScreen(state: HovelStartState, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF3A2519), Hearth.night), radius = 900f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Hearth.card)
                .border(1.dp, Hearth.ember.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                .padding(horizontal = 36.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.size(72.dp)) { drawHouse(roof = Hearth.ember, body = Hearth.glow.copy(alpha = 0.9f), window = Hearth.night) }
            Spacer(Modifier.height(16.dp))
            Text("Hovel", color = Hearth.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
            Text("Build your rooms. Make your keeper.", color = Hearth.muted, fontSize = 15.sp)
            Spacer(Modifier.height(28.dp))
            when (state) {
                is HovelStartState.Ready -> {
                    Button(
                        onClick = onContinue,
                        colors = ButtonDefaults.buttonColors(containerColor = Hearth.ember, contentColor = Hearth.night),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Light the hearth", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    Spacer(Modifier.height(14.dp))
                    Text(state.url, color = Hearth.muted, fontSize = 12.sp)
                }
                HovelStartState.Preparing -> {
                    CircularProgressIndicator(color = Hearth.ember, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Warming up…", color = Hearth.text, fontSize = 15.sp)
                }
                is HovelStartState.Failed -> {
                    Text("The fire wouldn't catch", color = Hearth.error, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(state.reason, color = Hearth.muted, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onContinue, shape = RoundedCornerShape(12.dp)) { Text("Try again", color = Hearth.text) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(RuntimeNote, color = Hearth.muted.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Variant B: Blueprint. Room-building motif, shows startup as a visible checklist.
// ---------------------------------------------------------------------------------------------

private object Blueprint {
    val paper = Color(0xFF0F2A47)
    val grid = Color(0xFF1E4470)
    val line = Color(0xFF9CC8F2)
    val text = Color(0xFFEAF4FF)
    val muted = Color(0xFF8FB0D0)
    val accent = Color(0xFFFFD166)
    val error = Color(0xFFFF8C8C)
}

private enum class StepState { Done, Active, Waiting, Failed }

@Composable
fun BlueprintStartScreen(state: HovelStartState, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Blueprint.paper)) {
        Canvas(Modifier.fillMaxSize()) { drawBlueprintGrid() }
        Row(Modifier.fillMaxSize().padding(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PLAN 01 / START", color = Blueprint.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text("Hovel", color = Blueprint.text, fontSize = 56.sp, fontWeight = FontWeight.Black)
                Text("Every room starts as a sketch.", color = Blueprint.muted, fontSize = 18.sp)
                Spacer(Modifier.height(36.dp))
                val steps = when (state) {
                    is HovelStartState.Ready -> listOf(StepState.Done, StepState.Waiting, StepState.Waiting)
                    HovelStartState.Preparing -> listOf(StepState.Done, StepState.Active, StepState.Waiting)
                    is HovelStartState.Failed -> listOf(StepState.Done, StepState.Failed, StepState.Waiting)
                }
                BlueprintStep("Runtime verified", steps[0])
                BlueprintStep("Starting browser engine", steps[1], detail = (state as? HovelStartState.Failed)?.reason)
                BlueprintStep("Opening your hovel", steps[2])
                Spacer(Modifier.height(32.dp))
                when (state) {
                    is HovelStartState.Ready -> BlueprintButton("Start building", onContinue)
                    HovelStartState.Preparing -> Text("Drafting…", color = Blueprint.muted, fontFamily = FontFamily.Monospace)
                    is HovelStartState.Failed -> BlueprintButton("Retry", onContinue)
                }
                Spacer(Modifier.height(20.dp))
                Text(RuntimeNote, color = Blueprint.muted, fontSize = 11.sp, modifier = Modifier.widthIn(max = 420.dp))
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) { drawFloorPlan() }
        }
    }
}

@Composable
private fun BlueprintStep(label: String, state: StepState, detail: String? = null) {
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
    )
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val (mark, color) = when (state) {
            StepState.Done -> "■" to Blueprint.line
            StepState.Active -> "▣" to Blueprint.accent.copy(alpha = pulse)
            StepState.Waiting -> "□" to Blueprint.muted
            StepState.Failed -> "✕" to Blueprint.error
        }
        Text(mark, color = color, fontSize = 16.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp))
        Column {
            Text(label, color = if (state == StepState.Waiting) Blueprint.muted else Blueprint.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            if (detail != null) Text(detail, color = Blueprint.error, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun BlueprintButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Blueprint.accent, contentColor = Blueprint.paper),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.height(46.dp),
    ) { Text(label.uppercase(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
}

private fun DrawScope.drawBlueprintGrid() {
    val step = 24.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(Blueprint.grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = if ((x / step).toInt() % 5 == 0) 1.5f else 0.6f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(Blueprint.grid, Offset(0f, y), Offset(size.width, y), strokeWidth = if ((y / step).toInt() % 5 == 0) 1.5f else 0.6f)
        y += step
    }
}

private fun DrawScope.drawFloorPlan() {
    val s = minOf(size.width, size.height) * 0.8f
    val left = (size.width - s) / 2
    val top = (size.height - s) / 2
    val wall = Stroke(width = 5.dp.toPx())
    val thin = Stroke(width = 1.5.dp.toPx())
    drawRect(Blueprint.line, Offset(left, top), Size(s, s * 0.75f), style = wall)
    // Interior walls: kitchen, bedroom, hall.
    drawLine(Blueprint.line, Offset(left + s * 0.55f, top), Offset(left + s * 0.55f, top + s * 0.45f), strokeWidth = 4.dp.toPx())
    drawLine(Blueprint.line, Offset(left + s * 0.55f, top + s * 0.45f), Offset(left + s, top + s * 0.45f), strokeWidth = 4.dp.toPx())
    // Door swing.
    drawArc(Blueprint.accent, 180f, 90f, false, Offset(left + s * 0.4f, top + s * 0.62f), Size(s * 0.26f, s * 0.26f), style = thin)
    // Furniture blocks.
    drawRect(Blueprint.line.copy(alpha = 0.6f), Offset(left + s * 0.66f, top + s * 0.08f), Size(s * 0.24f, s * 0.28f), style = thin)
    drawCircle(Blueprint.line.copy(alpha = 0.6f), s * 0.07f, Offset(left + s * 0.25f, top + s * 0.25f), style = thin)
    drawRect(Blueprint.line.copy(alpha = 0.6f), Offset(left + s * 0.7f, top + s * 0.55f), Size(s * 0.2f, s * 0.12f), style = thin)
    // Dimension line.
    drawLine(Blueprint.muted, Offset(left, top + s * 0.85f), Offset(left + s, top + s * 0.85f), strokeWidth = 1.dp.toPx())
    drawLine(Blueprint.muted, Offset(left, top + s * 0.81f), Offset(left, top + s * 0.89f), strokeWidth = 1.dp.toPx())
    drawLine(Blueprint.muted, Offset(left + s, top + s * 0.81f), Offset(left + s, top + s * 0.89f), strokeWidth = 1.dp.toPx())
}

// ---------------------------------------------------------------------------------------------
// Variant C: Cottage. Light, friendly split layout with an illustrated scene.
// ---------------------------------------------------------------------------------------------

private object Cottage {
    val cream = Color(0xFFFBF6EE)
    val ink = Color(0xFF2F2A26)
    val muted = Color(0xFF7A6F66)
    val moss = Color(0xFF5E8C61)
    val sky = Color(0xFFBFE0F0)
    val hill = Color(0xFF9CC79A)
    val roof = Color(0xFFC2563A)
    val wall = Color(0xFFF3D9A4)
    val error = Color(0xFFB3261E)
}

@Composable
fun CottageStartScreen(state: HovelStartState, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize().background(Cottage.cream)) {
        Box(Modifier.weight(1.1f).fillMaxHeight().clipToBounds()) {
            Canvas(Modifier.fillMaxSize()) { drawCottageScene() }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 48.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Welcome home to", color = Cottage.muted, fontSize = 16.sp)
            Text("Hovel", color = Cottage.ink, fontSize = 52.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(8.dp))
            Text("Design rooms, dress your keeper, and make the place your own.", color = Cottage.muted, fontSize = 16.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(32.dp))
            when (state) {
                is HovelStartState.Ready -> {
                    Button(
                        onClick = onContinue,
                        colors = ButtonDefaults.buttonColors(containerColor = Cottage.moss),
                        shape = CircleShape,
                        modifier = Modifier.height(52.dp).widthIn(min = 200.dp),
                    ) { Text("Come in", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(12.dp))
                    Text("Opening ${state.url}", color = Cottage.muted, fontSize = 12.sp)
                }
                HovelStartState.Preparing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Cottage.moss, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Unlocking the front door…", color = Cottage.ink, fontSize = 16.sp)
                }
                is HovelStartState.Failed -> Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Cottage.error.copy(alpha = 0.08f)).padding(16.dp),
                ) {
                    Text("We couldn't open the door", color = Cottage.error, fontWeight = FontWeight.SemiBold)
                    Text(state.reason, color = Cottage.ink, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onContinue, shape = CircleShape) { Text("Try again", color = Cottage.ink) }
                }
            }
            Spacer(Modifier.height(40.dp))
            Text(RuntimeNote, color = Cottage.muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

private fun DrawScope.drawCottageScene() {
    drawRect(Brush.verticalGradient(listOf(Cottage.sky, Color(0xFFEAF5F0))))
    drawCircle(Color(0xFFFFE08A), size.minDimension * 0.08f, Offset(size.width * 0.78f, size.height * 0.2f))
    val crest = size.height * 0.72f
    drawCircle(Color(0xFF7FB27E), size.width * 0.8f, Offset(size.width * 1.05f, crest + size.width * 0.8f - size.height * 0.06f))
    drawCircle(Cottage.hill, size.width * 0.95f, Offset(size.width * 0.4f, crest + size.width * 0.95f))
    val houseWidth = size.width * 0.42f
    drawCottage(Offset(size.width * 0.4f - houseWidth / 2, crest + size.height * 0.02f - houseWidth * 0.7f), houseWidth)
}

private fun DrawScope.drawCottage(origin: Offset, w: Float) {
    val h = w * 0.7f
    drawRect(Color(0xFF9E4430), origin + Offset(w * 0.7f, -h * 0.05f), Size(w * 0.1f, h * 0.25f))
    drawRect(Cottage.wall, origin + Offset(0f, h * 0.35f), Size(w, h * 0.65f))
    drawPath(Path().apply {
        moveTo(origin.x - w * 0.08f, origin.y + h * 0.38f)
        lineTo(origin.x + w * 0.5f, origin.y - h * 0.15f)
        lineTo(origin.x + w * 1.08f, origin.y + h * 0.38f)
        close()
    }, Cottage.roof)
    drawRoundRect(Color(0xFF7A4A2E), origin + Offset(w * 0.42f, h * 0.62f), Size(w * 0.16f, h * 0.38f), CornerRadius(w * 0.08f))
    drawRect(Color(0xFFFFD27A), origin + Offset(w * 0.12f, h * 0.55f), Size(w * 0.18f, h * 0.18f))
    drawRect(Color(0xFFFFD27A), origin + Offset(w * 0.7f, h * 0.55f), Size(w * 0.18f, h * 0.18f))
}

// ---------------------------------------------------------------------------------------------
// Shared drawing
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawHouse(roof: Color, body: Color, window: Color) {
    val w = size.width
    val h = size.height
    drawRect(body, Offset(w * 0.2f, h * 0.45f), Size(w * 0.6f, h * 0.45f))
    drawPath(Path().apply {
        moveTo(w * 0.08f, h * 0.5f)
        lineTo(w * 0.5f, h * 0.1f)
        lineTo(w * 0.92f, h * 0.5f)
        close()
    }, roof)
    drawRect(window, Offset(w * 0.42f, h * 0.62f), Size(w * 0.16f, h * 0.28f))
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

private val previewReady = HovelStartState.Ready("http://localhost:3000")
private val previewFailed = HovelStartState.Failed("A verified JetBrains Runtime with JCEF is required")

@Preview @Composable fun HearthReadyPreview() = HearthStartScreen(previewReady, {})
@Preview @Composable fun HearthPreparingPreview() = HearthStartScreen(HovelStartState.Preparing, {})
@Preview @Composable fun HearthFailedPreview() = HearthStartScreen(previewFailed, {})
@Preview @Composable fun BlueprintReadyPreview() = BlueprintStartScreen(previewReady, {})
@Preview @Composable fun BlueprintPreparingPreview() = BlueprintStartScreen(HovelStartState.Preparing, {})
@Preview @Composable fun BlueprintFailedPreview() = BlueprintStartScreen(previewFailed, {})
@Preview @Composable fun CottageReadyPreview() = CottageStartScreen(previewReady, {})
@Preview @Composable fun CottagePreparingPreview() = CottageStartScreen(HovelStartState.Preparing, {})
@Preview @Composable fun CottageFailedPreview() = CottageStartScreen(previewFailed, {})
