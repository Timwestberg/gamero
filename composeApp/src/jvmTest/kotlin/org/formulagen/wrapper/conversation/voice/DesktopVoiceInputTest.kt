package org.formulagen.wrapper.conversation.voice

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import org.formulagen.wrapper.bridge.LocalSpeechArgs
import org.formulagen.wrapper.bridge.VoiceInputScope
import org.formulagen.wrapper.conversation.ChatVoice
import org.formulagen.wrapper.conversation.VoiceState
import org.formulagen.wrapper.voice.HearingResult

class DesktopVoiceInputTest {
    private class Speaker : ChatSpeech {
        var revision = 0
        var stops = 0
        var state = VoiceState.IDLE
        override fun status() = ChatVoice(state)
        override fun playbackRevision() = revision
        override fun speak(text: String) { revision++; state = VoiceState.SPEAKING }
        override fun stop() { revision++; stops++; state = VoiceState.IDLE }
        override fun close() {}
    }
    private class Ear : Hearing {
        override fun down(onPartial: (String) -> Unit, onFailed: (String) -> Unit) = false
        override fun up(onResult: (HearingResult) -> Unit) = false
        override fun startSession(silenceMs: Int, onPartial: (String) -> Unit, onUtterance: (HearingResult) -> Unit, onFailed: (String) -> Unit, onFrame: (Int, Int, Float) -> Unit) = false
        override fun setPaused(value: Boolean) {}
        override fun restartUtterance() {}
        override fun noteEmberSpeaking(text: String) {}
        override fun noteEmberSilent(text: String) {}
        override fun endSession() {}
        override fun awaitStopped(timeoutMs: Long) {}
        override fun close() {}
    }
    @Test fun stalePlaybackCannotStopAnotherSpeakersReply() = runBlocking {
        val speaker = Speaker()
        val input = DesktopVoiceInput(Ear(), speaker)
        try {
            assertEquals("speaking", input.speak(LocalSpeechArgs("first", "Hello")).state)
            speaker.speak("A newer reply")
            assertEquals("error", input.speechStatus(VoiceInputScope("first")).state)
            input.stopSpeech(VoiceInputScope("first"))
            input.invalidate()
            assertEquals(0, speaker.stops)
            assertEquals(VoiceState.SPEAKING, speaker.state)
        } finally { input.close() }
    }
    @Test fun ownedPlaybackStopsAndCannotRestartAfterCancellation() = runBlocking {
        val speaker = Speaker()
        val input = DesktopVoiceInput(Ear(), speaker)
        try {
            input.speak(LocalSpeechArgs("first", "Hello"))
            input.stopSpeech(VoiceInputScope("first"))
            assertEquals(1, speaker.stops)
            assertEquals("cancelled", input.speak(LocalSpeechArgs("first", "Hello")).state)
        } finally { input.close() }
    }
}
