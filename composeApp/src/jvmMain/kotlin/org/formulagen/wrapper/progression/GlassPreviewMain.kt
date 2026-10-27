package org.formulagen.wrapper.progression

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** Interactive material study with no game process or progression save. */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Hovel · Glass study · Preview only",
        state = rememberWindowState(width = 1180.dp, height = 900.dp)) {
        var selected by remember { mutableStateOf<String?>(null) }
        var alternate by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf("Preview only · Your companions are unchanged") }
        val offer = remember { previewOffer() }
        val moonlit = remember {
            PresentationScene("MOONLIT GARDEN") {
                drawRect(Brush.linearGradient(listOf(Color(0xFF47385D), Color(0xFF123F4B), Color(0xFF102426))))
                for (index in 0..6) drawCircle(Color(0xFF8BCECC).copy(alpha = .12f), size.width * (.12f + index * .11f),
                    androidx.compose.ui.geometry.Offset(size.width * .18f, size.height * .48f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
        MaterialTheme(colorScheme = darkColorScheme()) {
            Column(Modifier.fillMaxSize().background(CompanionGlass.ink)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(result, Modifier.padding(vertical = 18.dp), color = CompanionGlass.muted)
                    TextButton({ alternate = !alternate }) { Text("Change scene") }
                }
                Box(Modifier.weight(1f)) {
                    CompanionPresentation(PresentationState(offer, selectedId = selected), { selected = it }, { action ->
                        if (action == "choose") {
                            val choice = offer.offer?.options?.find { it.id == selected }
                            result = "Preview confirmed · ${choice?.name}: ${choice?.before} → ${choice?.after} · Nothing saved"
                        } else { selected = null; result = "Preview reset · Your companions are unchanged" }
                    }, {}, scene = if (alternate) moonlit else FoundryPresentationScene)
                }
            }
        }
    }
}

/** Illustrative choices for inspecting the material without earning a level. */
private fun previewOffer() = PresentationSnapshot(1, "growth", CompanionIdentity("ember", "Ember", 2, 100), 1, false,
    offer = UpgradeOffer("preview:0", 2, listOf(
        UpgradeOption("attack", "Attack", "Increase weapon damage by 3% of base damage.", "+3%", "ffbd79", "100%", "103%"),
        UpgradeOption("defense", "Defense", "Add 2 armor. Incoming damage is divided by 1 + armor / 100.", "+2", "78dbcc", "0", "2"),
        UpgradeOption("energy", "Energy", "Add 3 maximum Energy for any equipment ability.", "+3", "b9a0ff", "60", "63"),
    )))
