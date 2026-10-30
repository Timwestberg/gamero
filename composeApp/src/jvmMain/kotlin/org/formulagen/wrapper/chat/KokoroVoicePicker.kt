package org.formulagen.wrapper.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.formulagen.wrapper.voice.HearingDuplex
import org.formulagen.wrapper.voice.duplexStatusLabel
import org.jetbrains.compose.resources.painterResource
import westbergwrapper.composeapp.generated.resources.Res
import westbergwrapper.composeapp.generated.resources.ember_chat

/** One Kokoro blend the voice can speak with. The name is what the row shows. */
internal data class VoiceChoice(val id: String, val name: String)

/**
 * Shown only while the voice serves a conversation and the engine is Kokoro.
 * The names come from the blend recipe, and [selectedId] is the one in use.
 */
internal data class VoicePickerState(
    val visible: Boolean = false,
    val voices: List<VoiceChoice> = emptyList(),
    val selectedId: String = "",
    val phase: TalkPhase = TalkPhase.OFF,
    val fillersOn: Boolean = false,
    val partial: String = "",
    val hearingNotice: String? = null,
    /** False once the page owns Talk and it is off, so this row cannot arm a second capture. */
    val showTalk: Boolean = false,
    val duplexLabel: String = "",
) {
    companion object {
        val Hidden = VoicePickerState()
    }
}

internal fun voicePickerState(
    chatOpen: Boolean,
    kokoro: Boolean,
    voices: List<VoiceChoice>,
    selectedId: String,
    phase: TalkPhase = TalkPhase.OFF,
    fillersOn: Boolean = false,
    partial: String = "",
    hearingNotice: String? = null,
    pageOwnsTalk: Boolean = false,
    duplexMode: HearingDuplex = HearingDuplex.HALF,
    duplexReason: String = "default",
): VoicePickerState {
    if (!chatOpen || !kokoro || voices.isEmpty()) return VoicePickerState.Hidden
    val selected = if (voices.any { it.id == selectedId }) selectedId else voices.first().id
    return VoicePickerState(
        visible = true,
        voices = voices,
        selectedId = selected,
        phase = phase,
        fillersOn = fillersOn,
        partial = partial,
        hearingNotice = hearingNotice,
        showTalk = !pageOwnsTalk,
        duplexLabel = duplexStatusLabel(duplexMode, duplexReason),
    )
}

/** The line spoken when a person picks a voice, so the next sentence is not the first time they hear it. */
internal fun voicePreviewLine(name: String) = "Hi, I'm $name."

internal class VoicePickerModel {
    private val holder = mutableStateOf(VoicePickerState.Hidden)
    val state: State<VoicePickerState> get() = holder

    fun update(next: VoicePickerState) {
        holder.value = next
    }
}

/**
 * A short row across the top of the window while the voice serves a conversation; its page has no voice choice or
 * filler toggle of its own.
 * The chat icon sits at the left. Click it to start a hands-free session, and click it again to stop.
 * The glyph is the Material Icons filled Chat bubble. The desktop pet's own PNG lives in
 * ember-desktop-pet, which is not part of this repository.
 */
@Composable
internal fun KokoroVoicePicker(
    state: VoicePickerState,
    onSelect: (String) -> Unit,
    onTalk: () -> Unit = {},
    onToggleFillers: () -> Unit = {},
    /** Escape while the row has focus: interrupts or ends its Talk. True when it took the key. */
    onEscape: () -> Boolean = { false },
) {
    if (!state.visible) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            .onPreviewKeyEvent { event -> event.key == Key.Escape && event.type == KeyEventType.KeyDown && onEscape() },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.showTalk) {
                IconButton(onClick = onTalk, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painterResource(Res.drawable.ember_chat),
                        contentDescription = if (state.phase == TalkPhase.OFF) "Talk with Ember" else "Stop talking",
                        tint = talkTint(state.phase),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Text(
                talkLabel(state.phase),
                style = MaterialTheme.typography.labelMedium,
                color = talkTint(state.phase),
                modifier = Modifier.padding(end = 8.dp),
            )
            TextButton(onClick = onToggleFillers, modifier = Modifier.height(32.dp)) {
                Text(
                    if (state.fillersOn) "Fillers on" else "Fillers",
                    fontWeight = if (state.fillersOn) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (state.fillersOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.duplexLabel.isNotEmpty()) {
                Text(
                    state.duplexLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text("Voice", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 4.dp))
            for (voice in state.voices) {
                val selected = voice.id == state.selectedId
                TextButton(onClick = { if (!selected) onSelect(voice.id) }, modifier = Modifier.height(32.dp)) {
                    Text(
                        voice.name,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val note = state.hearingNotice ?: state.partial.takeIf { it.isNotEmpty() }
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun talkTint(phase: TalkPhase): Color = when (phase) {
    TalkPhase.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
    TalkPhase.LISTENING -> MaterialTheme.colorScheme.primary
    TalkPhase.THINKING -> MaterialTheme.colorScheme.tertiary
    TalkPhase.SPEAKING -> MaterialTheme.colorScheme.secondary
}
