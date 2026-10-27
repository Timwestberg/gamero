package org.formulagen.wrapper.progression

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Dedicated progression screen: hosts provide state, commands and game-specific artwork. */
@Composable
fun CompanionPresentation(
    state: PresentationState,
    onSelect: (String) -> Unit,
    onAction: (String) -> Unit,
    onRetry: () -> Unit,
    scene: PresentationScene = FoundryPresentationScene,
    controllerInput: Flow<String> = emptyFlow(),
    reducedEffectsInitially: Boolean = false,
) {
    var reduced by rememberSaveable { mutableStateOf(reducedEffectsInitially) }
    val snapshot = state.snapshot ?: return
    if (snapshot.view == "closed") return
    var acknowledge by remember { mutableStateOf(false) }
    LaunchedEffect(state.saved) {
        if (state.saved != null) { acknowledge = true; delay(750); acknowledge = false }
    }
    val busy = state.pending != null
    val selected = snapshot.offer?.options?.find { it.id == state.selectedId }
    val actions = buildList<Pair<String, () -> Unit>> {
        if (snapshot.view == "saved") {
            add("continue" to { if (!busy) onAction("continue") })
        } else if (snapshot.view == "growth") {
            snapshot.offer?.options?.forEach { add(it.id to { if (!busy) onSelect(it.id) }) }
            if (selected != null) add("confirm" to { if (!busy) onAction("choose") })
            add("later" to { if (!busy) onAction("later") })
            if (snapshot.canRespec) add("respec" to { if (!busy) onAction("respec") })
        } else {
            if (snapshot.pending > 0) add("upgrades" to { if (!busy) onAction("upgrades") })
            add("close" to { if (!busy) onAction("close") })
            add("inventory" to { if (!busy) onAction("inventory") })
        }
        if (state.error != null && busy) add("retry" to onRetry)
    }
    val focus = remember(actions.map { it.first }) { actions.associate { it.first to FocusRequester() } }
    var focused by remember(snapshot.view, snapshot.offer?.id) { mutableStateOf<String?>(null) }
    val currentActions by rememberUpdatedState(actions)
    val currentFocus by rememberUpdatedState(focus)
    val currentFocused by rememberUpdatedState(focused)
    val currentView by rememberUpdatedState(snapshot.view)
    val currentAction by rememberUpdatedState(onAction)
    LaunchedEffect(snapshot.view, snapshot.offer?.id) {
        withFrameNanos { }
        currentActions.firstOrNull()?.let { currentFocus[it.first]?.requestFocus() }
    }
    LaunchedEffect(snapshot.view, snapshot.revision) {
        if (snapshot.view == "saved") {
            delay(if (reduced) 0 else 500)
            currentAction("continue")
        }
    }
    LaunchedEffect(controllerInput) {
        controllerInput.collect { input ->
            val targets = currentActions
            val index = targets.indexOfFirst { it.first == currentFocused }
            when (input) {
                "left", "up", "right", "down" -> {
                    val direction = if (input == "left" || input == "up") -1 else 1
                    val next = if (index < 0) 0 else (index + direction + targets.size) % targets.size
                    currentFocus[targets[next].first]?.requestFocus()
                }
                "accept" -> if (index >= 0) targets[index].second() else targets.firstOrNull()?.let { currentFocus[it.first]?.requestFocus() }
                "back" -> targets.find { it.first == if (currentView == "growth") "later" else "close" }?.second?.invoke()
            }
        }
    }
    /** Bind native and forwarded controller focus to the same semantic control. */
    fun control(id: String): Modifier = Modifier.focusRequester(focus.getValue(id)).onFocusChanged { if (it.isFocused) focused = id }
    MaterialTheme(colorScheme = darkColorScheme(primary = CompanionGlass.amber, onPrimary = CompanionGlass.ink,
    surface = CompanionGlass.ink, onSurface = CompanionGlass.text, onSurfaceVariant = CompanionGlass.muted)) {
        CompositionLocalProvider(LocalContentColor provides CompanionGlass.text) {
            PresentationSceneHost(scene) {
                BoxWithConstraints(Modifier.fillMaxSize().testTag("companion-presentation")) {
                    val narrow = maxWidth < 720.dp
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(if (narrow) 16.dp else 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth().padding(24.dp)) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${scene.name}  /  COMPANIONS", fontSize = 10.sp, letterSpacing = 2.sp, color = CompanionGlass.muted)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(reduced, { reduced = it }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Reduce motion and transparency" })
                                    Text("Reduced effects", fontSize = 11.sp, color = CompanionGlass.muted)
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CompanionPortrait(snapshot.companion.name, glassColor(snapshot.companion.color), acknowledge || snapshot.view == "saved")
                                Column {
                                    Text(snapshot.companion.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                    Text(if (snapshot.view == "growth" && snapshot.offer != null) "LEVEL ${snapshot.offer.level - 1} → ${snapshot.offer.level}" else "LEVEL ${snapshot.companion.level}",
                                    fontSize = 11.sp, letterSpacing = 1.5.sp, color = CompanionGlass.amber)
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            if (snapshot.view == "saved") {
                                Text("A little stronger.", Modifier.align(Alignment.CenterHorizontally), fontFamily = FontFamily.Serif, fontSize = 34.sp)
                                snapshot.appliedOption?.let { option ->
                                    Text("${option.name}: ${option.before} → ${option.after}", Modifier.align(Alignment.CenterHorizontally).padding(24.dp), color = CompanionGlass.amber, fontSize = 28.sp)
                                }
                                Text("Upgrade saved", Modifier.align(Alignment.CenterHorizontally).semantics { liveRegion = LiveRegionMode.Polite }, color = CompanionGlass.mint)
                                TextButton({ onAction("continue") }, control("continue").align(Alignment.CenterHorizontally), enabled = !busy) { Text("Continue") }
                            } else if (snapshot.view == "growth") {
                                Text(if (snapshot.offer != null) "A little stronger, together." else "Room to grow.",
                                Modifier.align(Alignment.CenterHorizontally), fontFamily = FontFamily.Serif, fontSize = if (narrow) 28.sp else 34.sp)
                                Text(if (snapshot.offer != null) "Preview a boost. Choose how ${snapshot.companion.name} grows." else "All earned upgrades are allocated. You can rebuild freely.",
                                Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), color = CompanionGlass.muted, fontSize = 14.sp)
                                Spacer(Modifier.height(28.dp))
                                snapshot.offer?.let { offer ->
                                    if (narrow) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        offer.options.forEach { option -> UpgradeCard(option, state.selectedId == option.id, !busy, reduced,
                                            control(option.id).fillMaxWidth(), { onSelect(option.id) }) }
                                    } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        offer.options.forEach { option -> UpgradeCard(option, state.selectedId == option.id, !busy, reduced,
                                            control(option.id).weight(1f).fillMaxHeight(), { onSelect(option.id) }) }
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                Text("Companion growth · kept after this adventure", Modifier.align(Alignment.CenterHorizontally), color = CompanionGlass.muted, fontSize = 12.sp)
                                if (snapshot.pending > 1) Text("${snapshot.pending} upgrades available · one choice at a time", Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp), color = CompanionGlass.muted, fontSize = 12.sp)
                                Spacer(Modifier.height(20.dp))
                                if (selected != null) {
                                    Text("${selected.name}: ${selected.before} → ${selected.after}", Modifier.align(Alignment.CenterHorizontally).semantics { liveRegion = LiveRegionMode.Polite }, color = CompanionGlass.amber)
                                    Spacer(Modifier.height(12.dp))
                                    Button({ onAction("choose") }, control("confirm").align(Alignment.CenterHorizontally).heightIn(min = 48.dp).testTag("confirm-upgrade"), enabled = !busy) {
                                        Text(if (busy && state.pending.action == "choose") "Saving…" else "Choose ${selected.name}", fontWeight = FontWeight.SemiBold)
                                    }
                                } else if (snapshot.offer != null) {
                                    Button({}, Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).testTag("confirm-upgrade"), enabled = false) { Text("Select an upgrade") }
                                }
                                FlowRow(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton({ onAction("later") }, control("later"), enabled = !busy) { Text(if (snapshot.pending > 0) "Choose later" else "Back") }
                                    if (snapshot.canRespec) TextButton({ onAction("respec") }, control("respec"), enabled = !busy) { Text("Free respec") }
                                }
                            } else snapshot.review?.let { review ->
                                Text(if (review.outcome == "success") "Expedition complete" else "Expedition ended", Modifier.align(Alignment.CenterHorizontally), fontFamily = FontFamily.Serif, fontSize = if (narrow) 28.sp else 34.sp)
                                Text(review.highlight, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 24.dp), color = CompanionGlass.muted, fontSize = 15.sp)
                                ReviewProgress(snapshot, review)
                                Spacer(Modifier.height(20.dp))
                                if (narrow) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    EquipmentGroup("Brought gear retained", review.retained, CompanionGlass.mint, Modifier.fillMaxWidth())
                                    EquipmentGroup("Discoveries secured", review.banked, CompanionGlass.amber, Modifier.fillMaxWidth())
                                    if (review.lost.isNotEmpty()) EquipmentGroup("Discoveries left behind", review.lost, CompanionGlass.muted, Modifier.fillMaxWidth())
                                } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    EquipmentGroup("Brought gear retained", review.retained, CompanionGlass.mint, Modifier.weight(1f).fillMaxHeight())
                                    EquipmentGroup("Discoveries secured", review.banked, CompanionGlass.amber, Modifier.weight(1f).fillMaxHeight())
                                    if (review.lost.isNotEmpty()) EquipmentGroup("Discoveries left behind", review.lost, CompanionGlass.muted, Modifier.weight(1f).fillMaxHeight())
                                }
                                if (review.broughtLost.isNotEmpty()) EquipmentGroup("Brought gear lost under this mode", review.broughtLost, CompanionGlass.danger, Modifier.fillMaxWidth().padding(top = 12.dp))
                                if (review.temporaryExpired) Text("Temporary adventure boosts have expired.", Modifier.padding(top = 16.dp), color = CompanionGlass.muted, fontSize = 12.sp)
                                Spacer(Modifier.height(24.dp))
                                FlowRow(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (snapshot.pending > 0) Button({ onAction("upgrades") }, control("upgrades"), enabled = !busy) { Text("Choose upgrades (${snapshot.pending})") }
                                    Button({ onAction("close") }, control("close"), enabled = !busy) { Text("Return to Hovel") }
                                    TextButton({ onAction("inventory") }, control("inventory"), enabled = !busy) { Text("Shared equipment") }
                                }
                            }
                            if (state.error != null) {
                                Text(state.error, Modifier.fillMaxWidth().padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Assertive }, color = CompanionGlass.danger)
                                if (state.pending != null) TextButton(onRetry, control("retry").align(Alignment.CenterHorizontally)) { Text("Retry") }
                            }
                            if (acknowledge && snapshot.view != "saved") Text("Upgrade saved", Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = CompanionGlass.mint, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Selectable preview card, with readable focus and selection beyond color alone. */
@Composable
internal fun UpgradeCard(option: UpgradeOption, selected: Boolean, enabled: Boolean, reduced: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val color = glassColor(option.color)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val emphasis by animateFloatAsState(if (pressed) 1f else if (selected || focused) .7f else if (hovered) .3f else 0f,
        tween(if (reduced) 0 else 180))
    LiquidGlassSurface(modifier
        .onFocusChanged { focused = it.isFocused }
        .hoverable(interaction, enabled)
        .selectable(selected, interactionSource = interaction, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .testTag("upgrade-${option.id}")
        .semantics { contentDescription = "${option.name}, ${option.increase}. ${option.before} to ${option.after}. ${option.description}" },
        tint = color, emphasis = emphasis, reducedEffects = reduced) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(option.name.uppercase(), fontSize = 12.sp, letterSpacing = 1.5.sp, color = color)
                Text(if (selected) "✓ Selected" else "Preview", fontSize = 11.sp, color = if (selected) color else CompanionGlass.muted)
            }
            Text(option.increase, fontSize = 42.sp, fontWeight = FontWeight.Light, color = CompanionGlass.text)
            Text("${option.before} → ${option.after}", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = color)
            Text(option.description, fontSize = 14.sp, lineHeight = 21.sp, color = CompanionGlass.muted)
        }
    }
}

/** Run XP is separate from the companion's current total and concurrent gains. */
@Composable
private fun ReviewProgress(snapshot: PresentationSnapshot, review: ExpeditionReview) {
    FlowRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .04f)).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((value, label) in listOf("+${review.xp} XP" to "Kept from this adventure", "Level ${snapshot.companion.level}" to "${snapshot.companion.xp} total companion XP", snapshot.pending.toString() to "Upgrades available")) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, fontSize = 26.sp, color = CompanionGlass.amber)
                Text(label, fontSize = 12.sp, color = CompanionGlass.muted)
            }
        }
    }
}

/** Named receipt groups remain readable on both success and failure. */
@Composable
private fun EquipmentGroup(title: String, items: List<String>, accent: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, CompanionGlass.rim, RoundedCornerShape(16.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
        if (items.isEmpty()) Text("None this adventure", fontSize = 14.sp, color = CompanionGlass.muted)
        items.forEach { Text(it.lowercase().replaceFirstChar { char -> char.uppercase() }, fontSize = 16.sp) }
    }
}
