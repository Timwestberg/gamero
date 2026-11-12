package org.formulagen.wrapper.conversation.voice

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import org.formulagen.wrapper.bridge.LocalSpeechArgs
import org.formulagen.wrapper.bridge.PlaybackVoice
import org.formulagen.wrapper.bridge.PlaybackVoiceChoice
import org.formulagen.wrapper.bridge.PlaybackVoices
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
        val said = mutableListOf<String>()
        override fun speak(text: String) { revision++; state = VoiceState.SPEAKING }
        override fun stop() { revision++; stops++; said.clear(); state = VoiceState.IDLE }
        override fun append(text: String): Boolean { said += text; state = VoiceState.SPEAKING; return true }
        /** Everything queued has been said. */
        fun finish() { said.clear(); state = VoiceState.IDLE }
        var voices = listOf(VoiceChoice("adam70-michael30", "Sam"), VoiceChoice("heart60-adam40", "Harper"))
        var voice = "adam70-michael30"
        val previews = mutableListOf<String>()
        override fun voiceChoices() = voices
        override fun currentVoiceId() = voice
        override fun previewVoice(id: String) { voice = id; previews += id }
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
    @Test fun listsTheMacsVoicesAndSpeaksWithTheOneChosen() = runBlocking {
        val speaker = Speaker()
        val input = DesktopVoiceInput(Ear(), speaker)
        try {
            val sam = PlaybackVoice("adam70-michael30", "Sam")
            val harper = PlaybackVoice("heart60-adam40", "Harper")
            assertEquals(PlaybackVoices("adam70-michael30", listOf(sam, harper)), input.voices())
            assertEquals(PlaybackVoices("heart60-adam40", listOf(sam, harper)), input.chooseVoice(PlaybackVoiceChoice("heart60-adam40")))
            assertEquals(listOf("heart60-adam40"), speaker.previews, "the Mac says hello in the new voice")
            // A voice the Mac does not list changes nothing and says nothing.
            assertEquals(PlaybackVoices("heart60-adam40", listOf(sam, harper)), input.chooseVoice(PlaybackVoiceChoice("af_nobody")))
            assertEquals(1, speaker.previews.size)
            // A Mac on a voice with no choice lists none and selects none.
            speaker.voices = emptyList()
            assertEquals(PlaybackVoices(null, emptyList()), input.voices())
        } finally { input.close() }
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
    @Test fun appendQueuesBehindTheSessionsReplyAndSpeakReplacesIt() = runBlocking {
        val speaker = Speaker()
        val input = DesktopVoiceInput(Ear(), speaker)
        try {
            assertEquals("speaking", input.speak(LocalSpeechArgs("first", "One.")).state)
            assertEquals("speaking", input.append(LocalSpeechArgs("first", "Two.")).state)
            assertEquals(listOf("One.", "Two."), speaker.said)
            assertEquals(0, speaker.stops, "a queued sentence does not flush the one being said")
            assertEquals("speaking", input.speak(LocalSpeechArgs("first", "Start over.")).state)
            assertEquals(listOf("Start over."), speaker.said)
            assertEquals(1, speaker.stops)
            speaker.finish()
            assertEquals("done", input.speechStatus(VoiceInputScope("first")).state)
            // The next sentence of a streamed reply arrives after the last one finished: the session speaks again.
            assertEquals("speaking", input.append(LocalSpeechArgs("first", "Three.")).state)
            assertEquals(listOf("Three."), speaker.said)
            assertEquals(1, speaker.stops)
        } finally { input.close() }
    }
    @Test fun appendWithNothingPlayingIsSpeak() = runBlocking {
        val speaker = Speaker()
        val input = DesktopVoiceInput(Ear(), speaker)
        try {
            assertEquals("speaking", input.append(LocalSpeechArgs("first", "Hello.")).state)
            assertEquals(listOf("Hello."), speaker.said)
            assertEquals("speaking", input.speechStatus(VoiceInputScope("first")).state)
        } finally { input.close() }
    }
    @Test fun anotherSessionCannotInterruptTheSpeakerWithSpeakOrAppend() = runBlocking {
        val speaker = Speaker()
        val input = DesktopVoiceInput(Ear(), speaker)
        try {
            input.speak(LocalSpeechArgs("first", "One."))
            assertEquals("error", input.speak(LocalSpeechArgs("second", "Me first.")).state)
            assertEquals("error", input.append(LocalSpeechArgs("second", "Me too.")).state)
            assertEquals(listOf("One."), speaker.said)
            assertEquals("speaking", input.speechStatus(VoiceInputScope("first")).state)
            speaker.finish()
            assertEquals("speaking", input.append(LocalSpeechArgs("second", "Now me.")).state)
            assertEquals("error", input.speechStatus(VoiceInputScope("first")).state)
        } finally { input.close() }
    }
    @Test fun appendNeverJoinsAReplyThatTalkStartedOnTheSameSpeaker() = runBlocking {
        val speaker = Speaker()
        val input = DesktopVoiceInput(Ear(), speaker)
        try {
            input.speak(LocalSpeechArgs("first", "One."))
            speaker.speak("Talk's reply")
            assertEquals("error", input.append(LocalSpeechArgs("first", "Two.")).state)
            assertEquals(emptyList(), speaker.said.filter { it == "Two." })
            assertEquals(0, speaker.stops)
        } finally { input.close() }
    }
    @Test fun aStoppedSessionCannotAppend() = runBlocking {
        val speaker = Speaker()
        val input = DesktopVoiceInput(Ear(), speaker)
        try {
            input.speak(LocalSpeechArgs("first", "One."))
            assertEquals("cancelled", input.stopSpeech(VoiceInputScope("first")).state)
            assertEquals("cancelled", input.append(LocalSpeechArgs("first", "Two.")).state)
            assertEquals(emptyList(), speaker.said)
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
