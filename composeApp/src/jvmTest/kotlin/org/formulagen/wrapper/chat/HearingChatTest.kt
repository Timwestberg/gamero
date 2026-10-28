package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.ChatScope
import org.formulagen.wrapper.bridge.ChatSend
import org.formulagen.wrapper.bridge.ChatState
import org.formulagen.wrapper.bridge.ChatVoice
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.bridge.VoiceState
import org.formulagen.wrapper.voice.FillerPcm
import org.formulagen.wrapper.voice.FillerSettings
import org.formulagen.wrapper.voice.HearingEngine
import org.formulagen.wrapper.voice.HearingResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HearingCommitTest {
    private val member = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)

    @Test fun releaseRules() {
        val said = HearingResult("  what time is it  ", speechMs = 800, decodeMs = 4.0)
        assertEquals(HearingCommit.Send("what time is it"), decideHearing(said, member, ChatState.READY))
        assertEquals(HearingCommit.Ignore, decideHearing(HearingResult("", 800, 1.0), member, ChatState.READY))
        assertEquals(HearingCommit.Ignore, decideHearing(HearingResult("hi", speechMs = 100, decodeMs = 1.0), member, ChatState.READY))
        assertEquals(HearingCommit.Notice(HEARING_NEEDS_MEMBER), decideHearing(said, null, ChatState.READY))
        assertEquals(HearingCommit.Notice(ANSWER_FIRST), decideHearing(said, member, ChatState.AWAITING_YOU))
        assertEquals(HearingCommit.Ignore, decideHearing(said, member, ChatState.RESPONDING))
        assertFalse(hearingHoldAllowed(ChatState.RESPONDING, VoiceState.IDLE, fillerPlaying = false))
        assertTrue(hearingHoldAllowed(ChatState.RESPONDING, VoiceState.SPEAKING, fillerPlaying = true))
        assertFalse(hearingHoldAllowed(ChatState.READY, VoiceState.SPEAKING, fillerPlaying = false))
        assertTrue(hearingHoldAllowed(ChatState.READY, VoiceState.IDLE, fillerPlaying = false))
    }
}

class HearingChatTest {
    private val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
    private val member = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)

    @Test fun aHoldWithNoMemberDoesNotSend() = runBlocking {
        val speech = RememberingSpeech()
        val runtime = ScriptedRuntime()
        chat(speech, runtime, FillerSettings(thresholdMs = 20)).use { desk ->
            desk.open(scope)
            desk.commitHeard(HearingResult("hello", 500, 1.0))
            assertEquals(HEARING_NEEDS_MEMBER, desk.snapshot(scope).notice)
            assertTrue(desk.snapshot(scope).messages.isEmpty())
            assertTrue(speech.clips.isEmpty())
        }
    }

    @Test fun aTranscriptBecomesOneTalkTurnAndOneFiller() = runBlocking {
        val speech = RememberingSpeech()
        val runtime = ScriptedRuntime()
        chat(speech, runtime, FillerSettings(enabled = true, thresholdMs = 30)).use { desk ->
            desk.open(scope)
            runtime.completeOnStart = true
            desk.send(ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), "Hello", true))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            runtime.completeOnStart = false
            desk.commitHeard(HearingResult("what time is it", 900, 8.0))
            await { runtime.started.await(2, TimeUnit.SECONDS) && runtime.lastText == "what time is it" }
            await { speech.clips.size == 1 }
            assertEquals(1, speech.clips.size)
            runtime.finish("Four.")
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            assertEquals("Four.", desk.snapshot(scope).messages.last().let { (it as org.formulagen.wrapper.bridge.ChatEntry.Assistant).text })
            Thread.sleep(80)
            assertEquals(1, speech.clips.size)
            assertEquals(listOf("Ready.", "Four."), speech.said)
            assertEquals("Hmm,", speech.clips.single().decodeToString())
        }
    }

    @Test fun anEmptyOrSilentHoldDoesNotSend() = runBlocking {
        val speech = RememberingSpeech()
        val runtime = ScriptedRuntime(completeOnStart = true)
        chat(speech, runtime, FillerSettings(thresholdMs = 20)).use { desk ->
            desk.open(scope)
            desk.send(ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), "Hello", true))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            val turns = runtime.turns
            desk.commitHeard(HearingResult("   ", 800, 1.0))
            desk.commitHeard(HearingResult("hi", speechMs = 80, decodeMs = 1.0))
            Thread.sleep(50)
            assertEquals(turns, runtime.turns)
            assertTrue(speech.clips.isEmpty())
            assertNull(desk.snapshot(scope).notice)
        }
    }

    @Test fun aReplyThatIsAlreadySpeakingSkipsTheFiller() = runBlocking {
        val speech = RememberingSpeech()
        val runtime = ScriptedRuntime(completeOnStart = true)
        chat(speech, runtime, FillerSettings(enabled = true, thresholdMs = 400)).use { desk ->
            desk.open(scope)
            desk.send(ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), "Hello", false))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            desk.commitHeard(HearingResult("go on", 700, 2.0))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            Thread.sleep(450)
            assertTrue(speech.clips.isEmpty())
            assertEquals(listOf("Ready."), speech.said)
        }
    }
}

class DesktopHearingTest {
    @Test fun aHoldFeedsTheEngineAndReleaseReturnsItsTranscript() {
        val engine = object : HearingEngine {
            var samples = 0
            override fun startUtterance() {}
            override fun accept(samples: FloatArray) { this.samples += samples.size }
            override fun partial() = "hel"
            override fun finish() = HearingResult("hello", 500, 11.0)
            override fun close() {}
        }
        val mic = ScriptedMic(FloatArray(4800) { 0.2f })
        val partials = CopyOnWriteArrayList<String>()
        DesktopHearing(engine, { mic }).use { hearing ->
            hearing.down(onPartial = { partials += it }, onFailed = { error("mic failed: $it") })
            val result = awaitResult(hearing)
            assertEquals("hello", result.text)
            assertEquals(11.0, result.decodeMs)
            assertTrue(engine.samples > 0)
            assertTrue(partials.isNotEmpty())
        }
    }
}

class FillerPlaybackTest {
    @Test fun stopFlushesACachedClip() {
        val started = CountDownLatch(1)
        val flushed = CountDownLatch(1)
        val line = object : PcmLine {
            override fun start() { started.countDown() }
            override fun write(pcm: ByteArray) { Thread.sleep(1_000) }
            override fun drain() {}
            override fun flush() { flushed.countDown() }
            override fun stop() {}
            override fun close() {}
        }
        DesktopSpeech(org.formulagen.wrapper.voice.VoiceConfig(fillersEnabled = false), { line }).use { speech ->
            speech.playClip(ByteArray(4800), 24000) {}
            assertTrue(started.await(2, TimeUnit.SECONDS), "clip reached the speaker")
            speech.stop()
            assertTrue(flushed.await(2, TimeUnit.SECONDS), "stop flushed the filler")
        }
    }
}

private class RememberingSpeech : ChatSpeech {
    val said = CopyOnWriteArrayList<String>()
    val clips = CopyOnWriteArrayList<ByteArray>()
    override fun status() = ChatVoice()
    override fun speak(text: String) { said += text }
    override fun stop() {}
    override fun close() {}
    override fun fillerPcm(voiceId: String, line: String) = FillerPcm(line.encodeToByteArray(), 24000)
    override fun playClip(pcm: ByteArray, sampleRate: Int, onDone: () -> Unit) {
        clips += pcm
        onDone()
    }
}

private class ScriptedRuntime(var completeOnStart: Boolean = false) : ChatRuntime {
    override val id = RuntimeId.CODEX
    val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    var lastText = ""
    var turns = 0
    private var events: TurnEvents? = null

    override fun readiness(profile: String) = Readiness.Ready

    override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
        this.events = events
        lastText = turn.text
        turns += 1
        events.opened("conv")
        events.accepted()
        if (completeOnStart) events.completed("Ready.", "conv")
        else started.countDown()
        return TurnHandle { release.countDown() }
    }

    fun finish(text: String) {
        release.countDown()
        events?.completed(text, "conv")
    }

    override fun summarize(request: SummaryRequest) = "summary"
    override fun release() {}
    override fun close() {}
}

private class ScriptedMic(private val data: FloatArray) : MicCapture {
    private var at = 0
    @Volatile private var closed = false
    override fun read(into: FloatArray): Int {
        if (closed) return -1
        if (at >= data.size) {
            Thread.sleep(15)
            return if (closed) -1 else 0
        }
        val n = minOf(into.size, data.size - at)
        data.copyInto(into, destinationOffset = 0, startIndex = at, endIndex = at + n)
        at += n
        return n
    }
    override fun close() { closed = true }
}

private fun chat(speech: ChatSpeech, runtime: ChatRuntime, fillers: FillerSettings) =
    DesktopChat(Files.createTempDirectory("hearing-chat"), listOf(runtime), speech, fillers = fillers)

private fun await(check: () -> Boolean) {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!check()) {
        if (System.nanoTime() > until) kotlin.test.fail("Condition timed out")
        Thread.sleep(10)
    }
}

private fun awaitResult(hearing: DesktopHearing): HearingResult {
    val box = arrayOfNulls<HearingResult>(1)
    val done = CountDownLatch(1)
    hearing.up {
        box[0] = it
        done.countDown()
    }
    assertTrue(done.await(3, TimeUnit.SECONDS))
    return box[0]!!
}
