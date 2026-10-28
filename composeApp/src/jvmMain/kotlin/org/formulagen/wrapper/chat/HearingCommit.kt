package org.formulagen.wrapper.chat

import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.ChatState
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.bridge.VoiceState
import org.formulagen.wrapper.voice.HEARING_MIN_SPEECH_MS
import org.formulagen.wrapper.voice.HearingResult

internal sealed interface HearingCommit {
    data class Send(val text: String) : HearingCommit
    data class Notice(val text: String) : HearingCommit
    data object Ignore : HearingCommit
}

internal const val HEARING_NEEDS_MEMBER = "Send one message so Ember knows who should answer."

/** What a push-to-talk release should do. A short or silent hold sends nothing. */
internal fun decideHearing(
    result: HearingResult,
    member: TeamMember?,
    state: ChatState,
    minSpeechMs: Int = HEARING_MIN_SPEECH_MS,
): HearingCommit {
    if (state == ChatState.AWAITING_YOU) return HearingCommit.Notice(ANSWER_FIRST)
    if (state == ChatState.RESPONDING || state == ChatState.HANDING_OVER) return HearingCommit.Ignore
    if (member == null) return HearingCommit.Notice(HEARING_NEEDS_MEMBER)
    val text = result.text.trim().replace(Regex("\\s+"), " ")
    if (text.isEmpty() || result.speechMs < minSpeechMs) return HearingCommit.Ignore
    return HearingCommit.Send(text.take(ChatLimits.MESSAGE))
}

/** The hold button stays idle while a reply is in flight, except to cut a filler that is playing. */
internal fun hearingHoldAllowed(state: ChatState, voice: VoiceState, fillerPlaying: Boolean): Boolean {
    if (fillerPlaying) return true
    if (state == ChatState.RESPONDING || state == ChatState.HANDING_OVER || state == ChatState.AWAITING_YOU) return false
    if (voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING) return false
    return true
}
