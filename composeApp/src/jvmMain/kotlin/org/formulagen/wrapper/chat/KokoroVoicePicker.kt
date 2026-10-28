package org.formulagen.wrapper.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One Kokoro blend the chat can speak with. The name is what the row shows. */
internal data class VoiceChoice(val id: String, val name: String)

/**
 * Shown only while an Ember chat is open and the engine is Kokoro.
 * The names come from the blend recipe, and [selectedId] is the one in use.
 */
internal data class VoicePickerState(
    val visible: Boolean = false,
    val voices: List<VoiceChoice> = emptyList(),
    val selectedId: String = "",
    val holdEnabled: Boolean = false,
    val holding: Boolean = false,
    val partial: String = "",
    val hearingNotice: String? = null,
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
    holdEnabled: Boolean = false,
    holding: Boolean = false,
    partial: String = "",
    hearingNotice: String? = null,
): VoicePickerState {
    if (!chatOpen || !kokoro || voices.isEmpty()) return VoicePickerState.Hidden
    val selected = if (voices.any { it.id == selectedId }) selectedId else voices.first().id
    return VoicePickerState(
        visible = true,
        voices = voices,
        selectedId = selected,
        holdEnabled = holdEnabled,
        holding = holding,
        partial = partial,
        hearingNotice = hearingNotice,
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
 * A short row across the top of the catalog window while Ember's chat is open.
 * The selected name is the voice the next sentence uses.
 */
@Composable
internal fun KokoroVoicePicker(
    state: VoicePickerState,
    onSelect: (String) -> Unit,
    onHoldStart: () -> Unit = {},
    onHoldEnd: () -> Unit = {},
) {
    if (!state.visible) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.holding) "Release to send" else "Hold to talk",
                style = MaterialTheme.typography.labelMedium,
                color = if (state.holdEnabled || state.holding) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp).pointerInput(state.holdEnabled, state.holding) {
                    detectTapGestures(onPress = {
                        if (!state.holdEnabled && !state.holding) return@detectTapGestures
                        onHoldStart()
                        tryAwaitRelease()
                        onHoldEnd()
                    })
                },
            )
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
            Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.End))
        }
    }
}
