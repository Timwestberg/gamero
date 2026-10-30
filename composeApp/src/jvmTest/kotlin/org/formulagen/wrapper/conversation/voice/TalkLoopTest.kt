package org.formulagen.wrapper.conversation.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.formulagen.wrapper.conversation.DictationAction
import org.formulagen.wrapper.conversation.DictationState
import org.formulagen.wrapper.conversation.HearingState
import org.formulagen.wrapper.conversation.VoiceState
import org.formulagen.wrapper.conversations.FakeEar
import org.formulagen.wrapper.conversations.FakeSpeaker
import org.formulagen.wrapper.voice.DuplexChoice
import org.formulagen.wrapper.voice.FillerPcm
import org.formulagen.wrapper.voice.FillerSettings
import org.formulagen.wrapper.voice.HearingDuplex
import org.formulagen.wrapper.voice.HearingResult

/** The talk loop on its own, with a conversation the test plays ([Host]). */
class TalkLoopTest {
    private val lock = Any()
    private val ear = FakeEar()
    private val speaker = FillerSpeaker()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val clock = AtomicLong(1_000_000_000L)
    private val host = Host()

    /** A conversation that sends each heard turn as a reply in progress, and records what the loop asks of it. */
    private inner class Host : TalkHost {
        lateinit var loop: TalkLoop
        var state = TalkReply.IDLE
        val sent = mutableListOf<String>()
        var interrupts = 0
        var ends = 0
        var changes = 0
        override val open get() = true
        override val reply get() = state
        override fun sendHeardLocked(result: HearingResult) =
            decideHeard(result, state, LIMIT).also { if (it is HearingCommit.Send) { sent += it.text; state = TalkReply.RESPONDING } }
        override fun interruptLocked() { interrupts++; state = TalkReply.IDLE }
        override fun endReplyLocked(): Boolean {
            ends++
            val busy = state != TalkReply.IDLE
            state = TalkReply.IDLE
            if (busy) changedLocked()
            return busy
        }
        override fun lastReplyLocked() = ""
        override fun changedLocked() { loop.observeLocked(); changes++ }
    }

    /** The fake speaker, with cached filler clips. */
    private class FillerSpeaker(private val speaker: FakeSpeaker = FakeSpeaker()) : ChatSpeech by speaker {
        val clips = java.util.concurrent.CopyOnWriteArrayList<String>()
        val pieces get() = speaker.pieces
        val warms get() = speaker.warms.get()
        fun settle() = speaker.settle()
        override fun fillerPcm(voiceId: String, line: String) = FillerPcm(line.encodeToByteArray(), 24_000)
        override fun playClip(pcm: ByteArray, sampleRate: Int, onDone: () -> Unit) { clips += pcm.decodeToString() }
    }

    private fun loop(
        settings: TalkSettings = TalkSettings(duplex = DuplexChoice(HearingDuplex.HALF, "test"), guardMs = 0),
    ) = TalkLoop(lock, host, speaker, SharedHearing(ear).user(), settings, timer, LIMIT, clock::get)
        .also { host.loop = it }

    private fun TalkLoop.arm() {
        assertTrue(synchronized(lock) { pressLocked() })
        startCapture()
    }

    private fun TalkLoop.hearing() = synchronized(lock) { hearingLocked() }

    /** The reply arrives as one sentence, and the turn ends. */
    private fun TalkLoop.answer(text: String) = synchronized(lock) {
        spokenReplyLocked().finishLocked(text)
        host.state = TalkReply.IDLE
        host.changedLocked()
    }

    @AfterTest fun close() { timer.shutdownNow() }

    @Test fun aHeardTurnThinksSpeaksAndListensAgain() {
        val talk = loop()
        talk.arm()
        assertEquals(TalkPhase.LISTENING, talk.phase)
        ear.hear("  what  time is it ")
        assertEquals(listOf("what time is it"), host.sent)
        assertEquals(TalkPhase.THINKING, talk.phase)
        assertTrue(ear.muted, "half duplex holds the microphone while the reply is written")
        talk.answer("It is late.")
        assertEquals(TalkPhase.SPEAKING, talk.phase)
        speaker.settle()
        synchronized(lock) { host.changedLocked() }
        assertEquals(TalkPhase.LISTENING, talk.phase)
        assertTrue(ear.listening)
    }

    @Test fun silenceAnEmptyTranscriptOrANoiseBurstSendNothing() {
        val talk = loop()
        talk.arm()
        ear.hear("")
        ear.hear("   ", speechMs = 800)
        ear.hear("hi", speechMs = 80)
        assertTrue(host.sent.isEmpty())
        assertEquals(TalkPhase.LISTENING, talk.phase)
    }

    @Test fun aPressDuringTheReplyBargesInAndListensAgain() {
        val talk = loop()
        talk.arm()
        ear.hear("read me the news")
        assertFalse(synchronized(lock) { talk.pressLocked() }, "no new capture")
        assertEquals(1, host.interrupts)
        assertEquals(TalkPhase.LISTENING, talk.phase)
        assertTrue(ear.listening, "listening again once the guard passed")
    }

    @Test fun escapeInterruptsAReplyEndsAListeningSessionAndOtherwiseGoesOn() {
        val talk = loop()
        assertFalse(talk.escape(), "no session: the key goes on")
        talk.arm()
        ear.hear("go on")
        assertTrue(talk.escape())
        assertEquals(1, host.interrupts)
        assertTrue(talk.on)
        assertTrue(talk.escape())
        assertFalse(talk.on, "only listening: Escape ends it")
        assertEquals(1, host.ends)
    }

    @Test fun inFullDuplexSpeechDuringPlaybackBargesInAndSendsTheNewTurn() {
        val talk = loop(TalkSettings(duplex = DuplexChoice(HearingDuplex.FULL, "test"), guardMs = 0))
        talk.arm()
        ear.hear("tell me a story")
        synchronized(lock) {
            talk.spokenReplyLocked().growLocked("Once upon a time. There")
            host.changedLocked()
        }
        assertEquals(TalkPhase.SPEAKING, talk.phase)
        ear.hear("stop, tell me the weather")
        assertEquals(1, host.interrupts)
        assertEquals(listOf("tell me a story", "stop, tell me the weather"), host.sent)
    }

    @Test fun aFillerPlaysWhileTheReplyIsLateAndTheReplyCutsIt() {
        val fillers = FillerSettings(enabled = true, thresholdMs = 20)
        val talk = loop(TalkSettings(fillers, duplex = DuplexChoice(HearingDuplex.HALF, "test"), guardMs = 0))
        talk.arm()
        ear.hear("what time is it")
        // The loop's clock says the reply is late; the timer only says when to look.
        clock.addAndGet(50_000_000)
        val due = CountDownLatch(1)
        timer.schedule({ due.countDown() }, 60, TimeUnit.MILLISECONDS)
        assertTrue(due.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("Hmm,"), speaker.clips)
        talk.answer("Four.")
        assertEquals(listOf("Four."), speaker.pieces)
    }

    @Test fun aFillerChoiceMadeInAnotherLoopHoldsAtTheNextTurn() {
        val shared = TalkSettings(FillerSettings(thresholdMs = 20), duplex = DuplexChoice(HearingDuplex.HALF, "test"), guardMs = 0)
        val talk = loop(shared)
        val other = TalkLoop(Any(), host, speaker, null, shared, timer, LIMIT)
        other.toggleFillers()
        assertTrue(shared.fillers.enabled)
        host.loop = talk
        talk.arm()
        ear.hear("what time is it")
        // The loop's clock says the reply is late; the timer only says when to look.
        clock.addAndGet(50_000_000)
        val due = CountDownLatch(1)
        timer.schedule({ due.countDown() }, 60, TimeUnit.MILLISECONDS)
        assertTrue(due.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("Hmm,"), speaker.clips, "the choice made elsewhere is this turn's")
        assertTrue(synchronized(lock) { talk.pickerLocked(open = true).fillersOn })
    }

    @Test fun dictationFillsTheDraftWithinTheLimitAndTalkWaitsForIt() {
        val talk = loop()
        assertEquals(DictationStep.Start, synchronized(lock) { talk.dictateLocked(DictationAction.START) })
        talk.startDictation()
        ear.dictate("add")
        assertEquals(TalkDictation(DictationState.HEARING, "add", null), synchronized(lock) { talk.dictationLocked() })
        ear.held = "x".repeat(LIMIT + 10)
        val heard = talk.awaitDictation()
        assertEquals(LIMIT, synchronized(lock) { talk.dictationEndedLocked(heard, keep = true) }?.length)
        assertNull(synchronized(lock) { talk.dictationLocked() })
        talk.arm()
        assertEquals(DictationStep.Ready, synchronized(lock) { talk.dictateLocked(DictationAction.START) })
        assertEquals(DictationState.UNAVAILABLE, synchronized(lock) { talk.dictationLocked() }?.state)
    }

    @Test fun muteHoldsTheMicrophoneAndUnmuteListensAgain() {
        val talk = loop()
        talk.arm()
        synchronized(lock) { talk.muteLocked() }
        assertEquals(HearingState.MUTED, talk.hearing().state)
        assertTrue(ear.muted)
        synchronized(lock) { talk.unmuteLocked() }
        assertEquals(HearingState.LISTENING, talk.hearing().state)
    }

    @Test fun theRowIconEndsAPageSessionButNeverStartsOne() {
        val conversation = loop()
        conversation.arm()
        synchronized(lock) { conversation.pageOwnsLocked() }
        assertTrue(synchronized(lock) { conversation.pickerLocked(open = true) }.showTalk)
        conversation.click()
        assertFalse(conversation.on, "a conversation's icon ends the page's session")
        conversation.click()
        assertFalse(conversation.on, "and never starts one the page owns")
        assertFalse(synchronized(lock) { conversation.pickerLocked(open = true) }.showTalk)
    }

    @Test fun aSessionWarmsTheVoiceHoweverItStartsAndAnInterruptNeverStartsOne() {
        val talk = loop()
        assertFalse(synchronized(lock) { talk.interruptLocked() })
        assertFalse(talk.on, "an interrupt never arms")
        assertEquals(0, speaker.warms)
        talk.click()
        assertTrue(talk.on)
        assertEquals(1, speaker.warms, "the row's icon warms the voice, fillers or not")
    }

    @Test fun endingWithNoSessionLeavesADictationHoldingTheMicrophone() {
        val talk = loop()
        synchronized(lock) { talk.dictateLocked(DictationAction.START) }
        talk.startDictation()
        ear.dictate("add")
        synchronized(lock) { talk.endLocked() }
        ear.held = "add a test"
        assertEquals("add a test", talk.awaitDictation(), "the hold was never ended")
    }

    @Test fun aCaptureThatStartsAfterTheRowEndedTalkLetsTheMicrophoneGo() {
        val talk = loop()
        assertTrue(synchronized(lock) { talk.pressLocked() })
        synchronized(lock) { talk.pageOwnsLocked() }
        // The row's icon ends Talk while the page's capture is still starting.
        ear.beforeSession = { talk.click() }
        talk.startCapture()
        assertFalse(talk.on)
        assertFalse(ear.listening, "the microphone was let go")
        ear.beforeSession = null
        talk.arm()
        assertTrue(talk.on && ear.listening, "so the next session can have it")
    }

    @Test fun aCaptureThatCannotStartSaysTheMicrophoneIsBusy() {
        val talk = loop()
        ear.refuseSessions = true
        talk.arm()
        assertFalse(talk.on)
        assertEquals(TalkHearing(HearingState.OFF, null, MICROPHONE_IN_USE), talk.hearing())
    }

    @Test fun theStageLatenciesReadTheLoopsClock() {
        val talk = loop()
        talk.arm()
        ear.hear("what time is it")
        clock.addAndGet(40_000_000)
        synchronized(lock) { talk.spokenReplyLocked().growLocked("It is late.") }
        clock.addAndGet(5_000_000)
        synchronized(lock) { talk.observeLocked() }
        val latency = assertNotNull(talk.stageLatency)
        assertEquals(StageLatency(0, 40, 5), latency)
        assertEquals(VoiceState.SPEAKING, speaker.status().state)
    }

    private companion object {
        const val LIMIT = 100
    }
}

