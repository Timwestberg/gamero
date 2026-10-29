package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.HearingAction
import org.formulagen.wrapper.bridge.HearingState
import org.formulagen.wrapper.bridge.ChatEntry
import org.formulagen.wrapper.bridge.ChatScope
import org.formulagen.wrapper.bridge.ChatSend
import org.formulagen.wrapper.bridge.ChatState
import org.formulagen.wrapper.bridge.ChatVoice
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.bridge.VoiceState
import org.formulagen.wrapper.voice.DuplexChoice
import org.formulagen.wrapper.voice.FILLER_LINES
import org.formulagen.wrapper.voice.HearingDuplex
import org.formulagen.wrapper.voice.cancelEcho
import org.formulagen.wrapper.voice.FillerPcm
import org.formulagen.wrapper.voice.FillerSettings
import org.formulagen.wrapper.voice.HearingEngine
import org.formulagen.wrapper.voice.HearingResult
import org.formulagen.wrapper.voice.readFillerSettings
import org.formulagen.wrapper.voice.PieceAudio
import org.formulagen.wrapper.voice.PieceEngine
import org.formulagen.wrapper.voice.SpeechCancelled
import org.formulagen.wrapper.voice.VoiceConfig
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

class ConverseSessionTest {
    private val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
    private val member = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)

    @Test fun twelveTurnsStayInOneSessionAndReturnToListening() = runBlocking {
        val speech = RowSpeech()
        val runtime = ScriptedRuntime(completeOnStart = true)
        val row = VoicePickerModel()
        chatRow(speech, runtime, row).use { desk ->
            desk.open(scope)
            desk.send(send("Hello"))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            desk.testingSession()
            assertEquals(TalkPhase.LISTENING, row.state.value.phase)
            assertFalse(row.state.value.fillersOn)
            repeat(12) { turn ->
                runtime.completeOnStart = false
                val turns = runtime.turns
                desk.testingUtterance(HearingResult("turn $turn", speechMs = 500, decodeMs = 1.0))
                assertEquals(TalkPhase.THINKING, row.state.value.phase, "turn $turn")
                await { runtime.turns > turns }
                runtime.finish("ok $turn")
                await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
                assertEquals(TalkPhase.SPEAKING, row.state.value.phase, "still speaking after turn $turn")
                speech.settle()
                assertEquals(TalkPhase.LISTENING, row.state.value.phase, "turn $turn did not resume")
            }
            assertEquals(13, runtime.turns)
        }
    }

    @Test fun silenceAndAnEmptyTranscriptSendNothing() = runBlocking {
        val speech = RowSpeech()
        val runtime = ScriptedRuntime(completeOnStart = true)
        val row = VoicePickerModel()
        chatRow(speech, runtime, row).use { desk ->
            desk.open(scope)
            desk.send(send("Hello"))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            desk.testingSession()
            val turns = runtime.turns
            repeat(10) { desk.testingUtterance(HearingResult("", speechMs = 0, decodeMs = 0.0)) }
            desk.testingUtterance(HearingResult("   ", speechMs = 800, decodeMs = 1.0))
            desk.testingUtterance(HearingResult("hi", speechMs = 80, decodeMs = 1.0))
            Thread.sleep(40)
            assertEquals(turns, runtime.turns)
            assertEquals(TalkPhase.LISTENING, row.state.value.phase)
            assertNull(runBlocking { desk.snapshot(scope) }.notice)
        }
    }

    @Test fun stopOrASecondClickEndsTheSessionMidReply() = runBlocking {
        val speech = RowSpeech()
        val runtime = ScriptedRuntime()
        val row = VoicePickerModel()
        chatRow(speech, runtime, row).use { desk ->
            desk.open(scope)
            runtime.completeOnStart = true
            desk.send(send("Hello"))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            desk.testingSession()
            runtime.completeOnStart = false
            desk.testingUtterance(HearingResult("keep going", speechMs = 500, decodeMs = 1.0))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.RESPONDING }
            desk.stop(scope)
            assertEquals(ChatState.CANCELLED, runBlocking { desk.snapshot(scope) }.state)
            assertEquals(TalkPhase.OFF, row.state.value.phase)
            desk.testingSession()
            val turns = runtime.turns
            desk.testingUtterance(HearingResult("again", speechMs = 500, decodeMs = 1.0))
            await { runtime.turns > turns }
            desk.converseClick()
            assertEquals(TalkPhase.LISTENING, row.state.value.phase)
            assertEquals(ChatState.READY, runBlocking { desk.snapshot(scope) }.state)
            assertNull(runBlocking { desk.snapshot(scope) }.notice)
            desk.converseClick()
            assertEquals(TalkPhase.OFF, row.state.value.phase)
        }
    }

    @Test fun aVoiceSwitchMidSessionStillHearsTheNextTurn() = runBlocking {
        val speech = RowSpeech()
        val runtime = ScriptedRuntime()
        val row = VoicePickerModel()
        chatRow(speech, runtime, row).use { desk ->
            desk.open(scope)
            runtime.completeOnStart = true
            desk.send(send("Hello"))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            desk.testingSession()
            runtime.completeOnStart = false
            desk.testingUtterance(HearingResult("switch", speechMs = 500, decodeMs = 1.0))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.RESPONDING }
            desk.chooseSpokenVoice("heart60-adam40")
            assertEquals(TalkPhase.SPEAKING, row.state.value.phase)
            speech.settle()
            assertEquals(TalkPhase.THINKING, row.state.value.phase)
            runtime.finish("ok")
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            assertEquals(TalkPhase.LISTENING, row.state.value.phase)
            assertEquals("heart60-adam40", row.state.value.selectedId)
            val turns = runtime.turns
            desk.testingUtterance(HearingResult("after the switch", speechMs = 500, decodeMs = 1.0))
            await { runtime.turns > turns }
            assertEquals("after the switch", runtime.lastText)
            runtime.finish("done")
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            assertEquals(TalkPhase.LISTENING, row.state.value.phase)
        }
    }

    @Test fun playbackStaysQuietForTheGuardThenListensAgain() = runBlocking {
        val speech = RowSpeech()
        val runtime = ScriptedRuntime()
        val row = VoicePickerModel()
        chatRow(speech, runtime, row, guardMs = 150).use { desk ->
            desk.open(scope)
            runtime.completeOnStart = true
            desk.send(send("Hello"))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            desk.testingSession()
            runtime.completeOnStart = false
            val turns = runtime.turns
            desk.testingUtterance(HearingResult("wait", speechMs = 400, decodeMs = 1.0))
            await { runtime.turns > turns }
            runtime.finish("ok")
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            assertEquals(TalkPhase.SPEAKING, row.state.value.phase)
            await { row.state.value.phase == TalkPhase.LISTENING }
        }
    }

    @Test fun aMicrophoneSessionHearsTenTurnsAndIgnoresSilence() = runBlocking {
        val speech = RowSpeech()
        val runtime = ScriptedRuntime(completeOnStart = true)
        val row = VoicePickerModel()
        val mic = QueueMic()
        val hearing = DesktopHearing(LevelEngine(), { mic })
        chatRow(speech, runtime, row, hearing = hearing, silenceMs = 200).use { desk ->
            desk.open(scope)
            desk.send(send("Hello"))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            runtime.completeOnStart = false
            desk.converseClick()
            await { row.state.value.phase == TalkPhase.LISTENING }
            repeat(10) { turn ->
                val turns = runtime.turns
                pushUtterance(mic)
                await { runtime.turns > turns }
                assertEquals(TalkPhase.THINKING, row.state.value.phase, "turn $turn")
                assertTrue(hearing.isPaused(), "mic open while Ember thinks, turn $turn")
                runtime.finish("ok $turn")
                await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
                speech.settle()
                await { row.state.value.phase == TalkPhase.LISTENING && !hearing.isPaused() }
            }
            val turns = runtime.turns
            repeat(12) { mic.push(FloatArray(1600)) }
            Thread.sleep(200)
            assertEquals(turns, runtime.turns)
            assertEquals(TalkPhase.LISTENING, row.state.value.phase)
            desk.converseClick()
            assertEquals(TalkPhase.OFF, row.state.value.phase)
        }
    }

    @Test fun aFailedVoiceReturnsToListening() = runBlocking {
        val speech = RowSpeech()
        val runtime = ScriptedRuntime(completeOnStart = true)
        val row = VoicePickerModel()
        chatRow(speech, runtime, row).use { desk ->
            desk.open(scope)
            desk.send(send("Hello"))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            desk.testingSession()
            repeat(10) {
                speech.become(VoiceState.SPEAKING)
                assertEquals(TalkPhase.SPEAKING, row.state.value.phase)
                speech.become(VoiceState.IDLE)
                assertEquals(TalkPhase.LISTENING, row.state.value.phase, "cancel $it")
                speech.become(VoiceState.PREPARING)
                assertEquals(TalkPhase.SPEAKING, row.state.value.phase)
                speech.become(VoiceState.ERROR)
                assertEquals(TalkPhase.LISTENING, row.state.value.phase, "worker failure $it")
                desk.testingUtterance(HearingResult("", 0, 0.0))
                assertEquals(TalkPhase.LISTENING, row.state.value.phase)
            }
        }
    }

    @Test fun theFillerToggleDefaultsOffAndIsRemembered() = runBlocking {
        val file = Files.createTempDirectory("fillers").resolve("voice-fillers")
        val speech = RowSpeech()
        val row = VoicePickerModel()
        chatRow(speech, ScriptedRuntime(completeOnStart = true), row, fillerFile = file).use { desk ->
            desk.open(scope)
            assertFalse(row.state.value.fillersOn)
            desk.toggleFillers()
            assertTrue(row.state.value.fillersOn)
            assertTrue(readSavedFillers(file))
            desk.toggleFillers()
            assertFalse(row.state.value.fillersOn)
            assertFalse(readSavedFillers(file))
            assertTrue(readFillerSettings(mapOf("HOVEL_VOICE_FILLERS" to "1"), savedEnabled = false).enabled)
            assertFalse(readFillerSettings(emptyMap(), savedEnabled = readSavedFillers(file)).enabled)
        }
    }

    @Test fun silenceAndGuardDefaultsAreConfigurable() {
        assertEquals(1000, hearingSilenceMs(emptyMap()))
        assertEquals(200, hearingSilenceMs(mapOf("HOVEL_HEARING_SILENCE_MS" to "50")))
        assertEquals(300, hearingGuardMs(emptyMap()))
        assertEquals(0, hearingGuardMs(mapOf("HOVEL_HEARING_GUARD_MS" to "0")))
    }

    private fun send(text: String) =
        ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), text, true)

    private fun chatRow(
        speech: ChatSpeech,
        runtime: ChatRuntime,
        row: VoicePickerModel,
        guardMs: Int = 0,
        silenceMs: Int = 1000,
        hearing: DesktopHearing? = null,
        fillerFile: java.nio.file.Path? = null,
    ) = DesktopChat(
        Files.createTempDirectory("talk-row"),
        listOf(runtime),
        speech,
        voices = row,
        fillers = FillerSettings(thresholdMs = 60_000),
        fillerFile = fillerFile,
        silenceMs = silenceMs,
        guardMs = guardMs,
        hearing = hearing,
    )
}

class StreamBargeTest {
    private val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
    private val member = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)

    @Test fun oddSplitsSpeakClosedSentencesBeforeTheReplyFinishes() = runBlocking {
        val speech = StreamingSpeech()
        val runtime = ScriptedRuntime()
        val row = VoicePickerModel()
        session(speech, runtime, row).use { desk ->
            arm(desk, runtime, speech)
            val turns = runtime.turns
            desk.testingUtterance(HearingResult("go on", speechMs = 500, decodeMs = 1.0))
            await { runtime.turns > turns }
            runtime.partial("Hel")
            runtime.partial("Hello. Wor")
            assertEquals(listOf("Hello."), speech.pieces)
            runtime.partial("Hello. World is here. And th")
            assertEquals(listOf("Hello.", "World is here."), speech.pieces)
            runtime.finish("Hello. World is here. And then some.")
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            assertEquals(listOf("Hello.", "World is here.", "And then some."), speech.pieces)
            assertTrue(speech.ended)
            val latency = checkNotNull(desk.stageLatency)
            assertTrue(latency.endpointToSendMs in 0..80, "endpoint to send ${latency.endpointToSendMs}")
            assertTrue(latency.sendToFirstTokenMs >= 0)
            assertTrue(latency.firstTokenToFirstAudioMs >= 0)
            println("STAGE endpoint_to_send_ms=${latency.endpointToSendMs} send_to_first_token_ms=${latency.sendToFirstTokenMs} first_token_to_first_audio_ms=${latency.firstTokenToFirstAudioMs}")
        }
    }

    @Test fun anInterruptMidSentenceThenTheNextTurn() = runBlocking {
        val speech = StreamingSpeech()
        val runtime = ScriptedRuntime()
        val row = VoicePickerModel()
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        speech.onStop = { order += "flush" }
        runtime.onStop = { order += "cancel" }
        session(speech, runtime, row).use { desk ->
            arm(desk, runtime, speech)
            val turns = runtime.turns
            desk.testingUtterance(HearingResult("interrupt me", speechMs = 500, decodeMs = 1.0))
            await { runtime.turns > turns }
            runtime.partial("Hello there friend. And more is still")
            assertEquals(listOf("Hello there friend."), speech.pieces)
            desk.converseClick()
            await { order.size >= 2 && order.last() == "cancel" }
            assertEquals("flush", order[order.lastIndex - 1])
            assertEquals(TalkPhase.LISTENING, row.state.value.phase)
            assertNull(runBlocking { desk.snapshot(scope) }.notice)
            val reply = runBlocking { desk.snapshot(scope) }.messages.last() as ChatEntry.Assistant
            assertEquals("Hello there friend. And more is still", reply.text)
            val next = runtime.turns
            desk.testingUtterance(HearingResult("next turn please", speechMs = 500, decodeMs = 1.0))
            await { runtime.turns > next }
            assertEquals("next turn please", runtime.lastText)
        }
    }

    @Test fun escapeInterruptsAFillerAndListensAgain() = runBlocking {
        val speech = StreamingSpeech()
        val runtime = ScriptedRuntime()
        val row = VoicePickerModel()
        session(speech, runtime, row, fillers = FillerSettings(enabled = true, thresholdMs = 0)).use { desk ->
            arm(desk, runtime, speech)
            val turns = runtime.turns
            desk.testingUtterance(HearingResult("think", speechMs = 500, decodeMs = 1.0))
            await { runtime.turns > turns }
            await { speech.fillerPlaying }
            desk.endConversation()
            assertFalse(speech.fillerPlaying)
            assertTrue(speech.stopped)
            assertEquals(TalkPhase.LISTENING, row.state.value.phase)
            val next = runtime.turns
            desk.testingUtterance(HearingResult("after the filler", speechMs = 400, decodeMs = 1.0))
            await { runtime.turns > next }
            assertEquals("after the filler", runtime.lastText)
        }
    }

    @Test fun tenStreamingTurnsStayInOneSession() = runBlocking {
        val speech = StreamingSpeech()
        val runtime = ScriptedRuntime()
        val row = VoicePickerModel()
        session(speech, runtime, row).use { desk ->
            arm(desk, runtime, speech)
            repeat(10) { turn ->
                val turns = runtime.turns
                desk.testingUtterance(HearingResult("turn $turn", speechMs = 400, decodeMs = 1.0))
                await { runtime.turns > turns }
                runtime.partial("Yes $turn.")
                runtime.finish("Yes $turn. Done.")
                await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
                speech.settle()
                await { row.state.value.phase == TalkPhase.LISTENING }
            }
            assertEquals(11, runtime.turns)
            assertEquals(TalkPhase.LISTENING, row.state.value.phase)
        }
    }

    @Test fun aProviderErrorMidStreamReturnsToListening() = runBlocking {
        val speech = StreamingSpeech()
        val runtime = ScriptedRuntime()
        val row = VoicePickerModel()
        session(speech, runtime, row).use { desk ->
            arm(desk, runtime, speech)
            val turns = runtime.turns
            desk.testingUtterance(HearingResult("fail this", speechMs = 500, decodeMs = 1.0))
            await { runtime.turns > turns }
            runtime.partial("Hello. Still going")
            runtime.fail(ChatFailure.Temporary)
            await { row.state.value.phase == TalkPhase.LISTENING }
            val reply = runBlocking { desk.snapshot(scope) }.messages.last() as ChatEntry.Assistant
            assertTrue(reply.text.contains("Hello."))
            val next = runtime.turns
            desk.testingUtterance(HearingResult("recovered", speechMs = 500, decodeMs = 1.0))
            await { runtime.turns > next }
            assertEquals("recovered", runtime.lastText)
            assertEquals(TalkPhase.THINKING, row.state.value.phase)
        }
    }

    private fun arm(desk: DesktopChat, runtime: ScriptedRuntime, speech: StreamingSpeech) = runBlocking {
        runtime.completeOnStart = true
        desk.open(scope)
        desk.send(send("Hello"))
        await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
        speech.settle()
        speech.pieces.clear()
        speech.ended = false
        desk.testingSession()
        runtime.completeOnStart = false
    }

    private fun send(text: String) =
        ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), text, true)

    private fun session(
        speech: ChatSpeech,
        runtime: ChatRuntime,
        row: VoicePickerModel,
        fillers: FillerSettings = FillerSettings(thresholdMs = 60_000),
    ) = DesktopChat(
        Files.createTempDirectory("stream-row"),
        listOf(runtime),
        speech,
        voices = row,
        fillers = fillers,
        guardMs = 0,
    )
}

class FullDuplexSessionTest {
    private val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
    private val member = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)

    @Test fun tenTurnsIgnoreCancelledEchoUntilTheUserSpeaks() = runBlocking {
        val speech = RowSpeech()
        val runtime = ScriptedRuntime(completeOnStart = true)
        val row = VoicePickerModel()
        val mic = QueueMic()
        val hearing = DesktopHearing(LevelEngine(), { mic })
        desktop(speech, runtime, row, hearing).use { desk ->
            desk.open(scope)
            desk.send(send("Hello"))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            runtime.completeOnStart = false
            desk.converseClick()
            await { row.state.value.phase == TalkPhase.LISTENING }
            val echo = FloatArray(1600) { 0.45f }
            repeat(10) { turn ->
                val turns = runtime.turns
                pushUtterance(mic)
                await { runtime.turns > turns }
                runtime.finish("ok $turn")
                await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
                speech.become(VoiceState.SPEAKING)
                repeat(8) { mic.push(cancelEcho(echo, echo, gain = 1f)) }
                Thread.sleep(80)
                assertEquals(turns + 1, runtime.turns, "echo became a turn on $turn")
                assertEquals(TalkPhase.SPEAKING, row.state.value.phase, "echo barged in on $turn")
                speech.settle()
                await { row.state.value.phase == TalkPhase.LISTENING && !hearing.isPaused() }
            }
            desk.converseClick()
            assertEquals(TalkPhase.OFF, row.state.value.phase)
        }
    }

    @Test fun sustainedSpeechBargesInAndACoughDoesNot() = runBlocking {
        val speech = StreamingSpeech()
        val runtime = ScriptedRuntime()
        val row = VoicePickerModel()
        val mic = QueueMic()
        val hearing = DesktopHearing(LevelEngine(), { mic })
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        speech.onStop = { order += "flush" }
        runtime.onStop = { order += "cancel" }
        desktop(speech, runtime, row, hearing).use { desk ->
            runtime.completeOnStart = true
            desk.open(scope)
            desk.send(send("Hello"))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            speech.settle()
            speech.pieces.clear()
            runtime.completeOnStart = false
            desk.converseClick()
            await { row.state.value.phase == TalkPhase.LISTENING }
            val turns = runtime.turns
            pushUtterance(mic)
            await { runtime.turns > turns }
            runtime.partial("Hello there friend. Still talking")
            speech.become(VoiceState.SPEAKING)
            repeat(2) { mic.push(FloatArray(1600) { 0.3f }) }
            Thread.sleep(80)
            assertEquals(TalkPhase.SPEAKING, row.state.value.phase)
            repeat(2) { mic.push(FloatArray(1600) { 0.3f }) }
            await { row.state.value.phase == TalkPhase.LISTENING }
            await { order.contains("cancel") }
            assertEquals("flush", order[order.lastIndex - 1])
            val reply = runBlocking { desk.snapshot(scope) }.messages.last() as ChatEntry.Assistant
            assertTrue(reply.text.contains("Hello there friend."))
            assertNull(runBlocking { desk.snapshot(scope) }.notice)
        }
    }

    private fun send(text: String) =
        ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), text, true)

    private fun desktop(speech: ChatSpeech, runtime: ChatRuntime, row: VoicePickerModel, hearing: DesktopHearing) =
        DesktopChat(
            Files.createTempDirectory("full-duplex"),
            listOf(runtime),
            speech,
            voices = row,
            fillers = FillerSettings(thresholdMs = 60_000),
            silenceMs = 200,
            guardMs = 0,
            hearing = hearing,
            duplexChoice = DuplexChoice(HearingDuplex.FULL, "test"),
            bargeMs = 250,
        )
}

class PlaybackBargeTest {
    private val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
    private val member = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)

    @Test fun fullDuplexSpeechDuringPlaybackFlushesAndCancels() = runBlocking {
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val line = HoldingLine { order += "flush" }
        val runtime = ScriptedRuntime()
        runtime.onStop = { order += "cancel" }
        val windows = WindowEngine()
        val hearing = DesktopHearing(windows, { QueueMic().also { mic = it } })
        val speech = DesktopSpeech(
            VoiceConfig(backend = VoiceConfig.KOKORO, fillersEnabled = false, sentenceGapMs = 0),
            { line },
            { ImmediateEngine() },
        )
        DesktopChat(
            Files.createTempDirectory("playback-barge"),
            listOf(runtime),
            speech,
            fillers = FillerSettings(thresholdMs = 60_000),
            silenceMs = 1000,
            guardMs = 0,
            hearing = hearing,
            duplexChoice = DuplexChoice(HearingDuplex.FULL, "self-check-passed"),
            bargeMs = 250,
        ).use { desk ->
            runtime.completeOnStart = true
            desk.open(scope)
            desk.send(send("Hello"))
            await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            await { speech.status().state == VoiceState.IDLE }
            runtime.completeOnStart = false
            desk.converseClick()
            await { mic != null }
            val turns = runtime.turns
            desk.testingUtterance(HearingResult("interrupt her", speechMs = 500, decodeMs = 1.0))
            await { runtime.turns > turns }
            runtime.partial("Hello there friend. Still talking now.")
            await { line.playing.await(2, TimeUnit.SECONDS) }
            await { speech.status().state == VoiceState.SPEAKING || speech.status().state == VoiceState.PREPARING }
            val mic = checkNotNull(this@PlaybackBargeTest.mic)
            mic.push(FloatArray(1600) { 0.4f })
            mic.push(FloatArray(1600) { 0.4f })
            mic.push(FloatArray(1600))
            mic.push(FloatArray(1600) { 0.4f })
            assertTrue(line.flushed.await(2, TimeUnit.SECONDS), "playback was not flushed")
            val flushMs = (line.flushNs - windows.bargeNs) / 1_000_000
            assertTrue(windows.bargeNs != 0L && flushMs in 0..50, "flush took ${flushMs}ms")
            await { order.contains("cancel") }
            assertEquals("flush", order.first())
            val reply = runBlocking { desk.snapshot(scope) }.messages.last { it is ChatEntry.Assistant } as ChatEntry.Assistant
            assertTrue(reply.text.contains("Hello there friend."))
        }
    }

    @Test fun clickAndEscapeBargeWhilePlaybackIsPreparing() = runBlocking {
        val release = CountDownLatch(1)
        val runtime = ScriptedRuntime()
        var cancelled = false
        runtime.onStop = { cancelled = true }
        val speech = DesktopSpeech(
            VoiceConfig(backend = VoiceConfig.KOKORO, fillersEnabled = false, sentenceGapMs = 0),
            { HoldingLine {} },
            {
                object : PieceEngine {
                    override val sampleRate = 24000
                    override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
                        release.await(2, TimeUnit.SECONDS)
                        return PieceAudio(FloatArray(4800), 24000, 1.0, 1.0)
                    }
                    override fun release() {}
                }
            },
        )
        DesktopChat(
            Files.createTempDirectory("prepare-barge"),
            listOf(runtime),
            speech,
            fillers = FillerSettings(thresholdMs = 60_000),
            hearing = DesktopHearing(LevelEngine(), { QueueMic() }),
            duplexChoice = DuplexChoice(HearingDuplex.FULL, "self-check-passed"),
        ).use { desk ->
            try {
                runtime.completeOnStart = true
                desk.open(scope)
                desk.send(send("Hello"))
                await { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
                runtime.completeOnStart = false
                desk.testingSession()
                val turns = runtime.turns
                desk.testingUtterance(HearingResult("keep talking", speechMs = 500, decodeMs = 1.0))
                await { runtime.turns > turns }
                runtime.partial("Hello there friend. This sentence is still open")
                await { speech.status().state == VoiceState.PREPARING }
                desk.converseClick()
                assertTrue(cancelled, "click did not cancel the provider")
                assertEquals(VoiceState.IDLE, speech.status().state)
            } finally {
                release.countDown()
            }
        }
    }

    private var mic: QueueMic? = null

    private fun send(text: String) =
        ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), text, true)
}

private class WindowEngine : HearingEngine {
    private var loud = false
    private var loudCount = 0
    @Volatile var bargeNs = 0L
    override fun startUtterance() { loud = false }
    override fun accept(samples: FloatArray) {
        loud = samples.any { kotlin.math.abs(it) > 0.2f }
        if (loud && ++loudCount == 3) bargeNs = System.nanoTime()
    }
    override fun voicedMillisOfLastAccept() = if (loud) 32 else 0
    override fun lastVadProbability() = if (loud) 0.91f else 0.02f
    override fun partial() = if (loud) "THAT'S NOW THANKS" else ""
    override fun finish() = HearingResult(if (loud) "THAT'S NOW THANKS" else "", 400, 1.0)
    override fun close() {}
}

private class ImmediateEngine : PieceEngine {
    override val sampleRate = 24000
    override fun synthesize(text: String, maxFrames: Int, seed: Int) = PieceAudio(FloatArray(4800) { 0.2f }, 24000, 1.0, 1.0)
    override fun release() {}
}

private class HoldingLine(private val onFlush: () -> Unit) : PcmLine {
    val playing = CountDownLatch(1)
    val flushed = CountDownLatch(1)
    @Volatile var flushNs = 0L
    private val release = CountDownLatch(1)
    override fun start() {}
    override fun write(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        playing.countDown()
        release.await(3, TimeUnit.SECONDS)
    }
    override fun drain() {}
    override fun flush() {
        flushNs = System.nanoTime()
        onFlush()
        flushed.countDown()
        release.countDown()
    }
    override fun stop() { release.countDown() }
    override fun close() { release.countDown() }
}

class FillerWarmTest {
    @Test fun aCancelledWarmKeepsGoingForTheSelectedVoice() {
        var cancelled = false
        val engine = object : PieceEngine {
            override val sampleRate = 24000
            override fun synthesize(text: String, maxFrames: Int, seed: Int) = PieceAudio(floatArrayOf(0.2f), 24000, 1.0, 1.0)
            override fun synthesizeLine(text: String, voiceId: String): PieceAudio {
                if (!cancelled && voiceId == "heart60-adam40") {
                    cancelled = true
                    throw SpeechCancelled()
                }
                return PieceAudio(floatArrayOf(0.2f), 24000, 1.0, 1.0)
            }
            override fun release() {}
        }
        val config = VoiceConfig(backend = VoiceConfig.KOKORO, kokoroVoiceId = "heart60-adam40", fillersEnabled = true)
        DesktopSpeech(config, { IdleLine }, { engine }).use { speech ->
            speech.warmFillers(FILLER_LINES)
            await { cancelled }
            speech.warmFillers(FILLER_LINES)
            await { speech.fillerPcm("heart60-adam40", "Hmm,") != null }
            assertTrue(speech.fillerPcm("heart60-adam40", "Let me think,") != null)
            assertTrue(speech.fillerPcm("heart60-adam40", "Okay, so") != null)
        }
    }

    @Test fun tenSpeaksThatAreCancelledReturnTheVoiceToIdle() {
        val engine = object : PieceEngine {
            override val sampleRate = 24000
            override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
                if (text.startsWith("fail")) throw SpeechCancelled()
                return PieceAudio(FloatArray(400), 24000, 1.0, 1.0)
            }
            override fun release() {}
        }
        val updates = CopyOnWriteArrayList<VoiceState>()
        DesktopSpeech(VoiceConfig(backend = VoiceConfig.KOKORO, fillersEnabled = false), { IdleLine }, { engine }).use { speech ->
            speech.onVoice { updates += speech.status().state }
            repeat(10) {
                speech.speak("Hello there.")
                await { speech.status().state == VoiceState.IDLE }
                speech.speak("fail this take")
                await { speech.status().state == VoiceState.IDLE }
                speech.stop()
                assertEquals(VoiceState.IDLE, speech.status().state)
            }
        }
        assertTrue(updates.isNotEmpty())
    }
}

private object IdleLine : PcmLine {
    override fun start() {}
    override fun write(pcm: ByteArray) {}
    override fun drain() {}
    override fun flush() {}
    override fun stop() {}
    override fun close() {}
}

private class StreamingSpeech : ChatSpeech {
    val pieces = java.util.concurrent.CopyOnWriteArrayList<String>()
    var ended = false
    var stopped = false
    var fillerPlaying = false
    var onStop: () -> Unit = {}
    private var voice = ChatVoice()
    private var listener: (() -> Unit)? = null
    override fun status() = voice
    override fun onVoice(listener: () -> Unit) { this.listener = listener }
    override fun speak(text: String) { voice = ChatVoice(VoiceState.SPEAKING) }
    override fun stop() {
        stopped = true
        fillerPlaying = false
        voice = ChatVoice()
        onStop()
    }
    override fun close() {}
    override fun kokoroActive() = true
    override fun voiceChoices() = listOf(VoiceChoice("adam70-michael30", "Sam"))
    override fun currentVoiceId() = "adam70-michael30"
    override fun enqueue(piece: String): Boolean {
        pieces += piece
        voice = ChatVoice(VoiceState.SPEAKING)
        return true
    }
    override fun endStream() { ended = true }
    override fun fillerPcm(voiceId: String, line: String) = FillerPcm(line.encodeToByteArray(), 24000)
    override fun playClip(pcm: ByteArray, sampleRate: Int, onDone: () -> Unit) {
        fillerPlaying = true
        voice = ChatVoice(VoiceState.SPEAKING)
    }
    fun settle() {
        voice = ChatVoice()
        listener?.invoke()
    }
    fun become(state: VoiceState) {
        voice = ChatVoice(state)
        listener?.invoke()
    }
}

private class RowSpeech : ChatSpeech {
    private var voice = ChatVoice()
    private var listener: (() -> Unit)? = null
    var selected = "adam70-michael30"
    override fun status() = voice
    override fun onVoice(listener: () -> Unit) { this.listener = listener }
    override fun speak(text: String) { voice = ChatVoice(VoiceState.SPEAKING) }
    override fun stop() { voice = ChatVoice() }
    override fun close() {}
    override fun kokoroActive() = true
    override fun voiceChoices() = listOf(
        VoiceChoice("adam70-michael30", "Sam"),
        VoiceChoice("adam60-george40", "Jamie"),
        VoiceChoice("adam55-bella45", "Riley"),
        VoiceChoice("heart60-adam40", "Harper"),
    )
    override fun currentVoiceId() = selected
    override fun previewVoice(id: String) { selected = id; voice = ChatVoice(VoiceState.SPEAKING) }
    fun become(next: VoiceState) {
        voice = ChatVoice(next)
        listener?.invoke()
    }
    fun settle() = become(VoiceState.IDLE)
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

/** A runtime conversation handle as runtimes report them: histories keep only UUIDs. */
private const val CONVERSATION = "5f0c2f4e-1d2b-4c3a-9e8f-7a6b5c4d3e2f"

private class ScriptedRuntime(var completeOnStart: Boolean = false) : ChatRuntime {
    override val id = RuntimeId.CODEX
    val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    var lastText = ""
    var turns = 0
    private var events: TurnEvents? = null

    override fun readiness(profile: String) = Readiness.Ready

    var onStop: () -> Unit = {}

    override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
        this.events = events
        lastText = turn.text
        turns += 1
        events.opened(CONVERSATION)
        events.accepted()
        if (completeOnStart) events.completed("Ready.", CONVERSATION)
        else started.countDown()
        return TurnHandle {
            onStop()
            release.countDown()
        }
    }

    fun partial(text: String) { events?.partial(text) }

    fun fail(failure: ChatFailure = ChatFailure.Temporary) { events?.failed(failure) }

    fun finish(text: String) {
        release.countDown()
        events?.completed(text, CONVERSATION)
    }

    override fun summarize(request: SummaryRequest) = "summary"
    override fun release() {}
    override fun close() {}
}

class CatalogControlTest {
    private val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
    private val member = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)

    @Test fun theCatalogPageArmsTheHostMicrophoneAndTheNativeIconStepsAside() = runBlocking {
        var opens = 0
        val hearing = DesktopHearing(LevelEngine(), {
            opens++
            ScriptedMic(FloatArray(0))
        })
        val row = VoicePickerModel()
        DesktopChat(
            Files.createTempDirectory("catalog-row"),
            listOf(ScriptedRuntime(completeOnStart = true)),
            RowSpeech(),
            voices = row,
            fillers = FillerSettings(thresholdMs = 60_000),
            hearing = hearing,
        ).use { desk ->
            desk.open(scope)
            assertTrue(row.state.value.visible)
            assertTrue(row.state.value.showTalk)
            assertEquals("Half duplex · default", row.state.value.duplexLabel)
            assertNull(desk.snapshot(scope).hearing, "A v2 page has not asked for hearing")
            assertEquals(org.formulagen.wrapper.voice.VoiceConversation.PERMISSION_COPY, row.state.value.hearingNotice)
            desk.hearing(request(HearingAction.PRESS))
            await { opens == 1 }
            assertFalse(row.state.value.showTalk, "The page press hides the native chat icon")
            assertEquals(HearingState.LISTENING, desk.snapshot(scope).hearing?.state)
            desk.converseClick()
            desk.hearing(request(HearingAction.PRESS))
            Thread.sleep(80)
            assertEquals(1, opens, "The native row and a second press do not open another capture")
            desk.hearing(request(HearingAction.RELEASE))
            assertFalse(row.state.value.showTalk)
            assertEquals(HearingState.OFF, desk.snapshot(scope).hearing?.state)
        }
    }

    @Test fun theEmberTowerLauncherDoesNotShowConversationControls() = runBlocking {
        val row = VoicePickerModel()
        DesktopChat(
            Files.createTempDirectory("launcher-row"),
            listOf(ScriptedRuntime(completeOnStart = true)),
            RowSpeech(),
            voices = row,
            product = org.formulagen.wrapper.hovel.HOVEL_PRODUCT,
        ).use { desk ->
            desk.open(scope)
            assertFalse(row.state.value.visible)
            assertNull(desk.snapshot(scope).hearing)
        }
    }

    private fun request(action: HearingAction) = org.formulagen.wrapper.bridge.ChatHearingRequest(
        scope.profile, scope.companionId, scope.name, scope.sessionId, action, member,
    )
}

private class LevelEngine : HearingEngine {
    private var voiced = 0
    private var speech = 0
    private var text = ""
    override fun startUtterance() { speech = 0; text = "" }
    override fun accept(samples: FloatArray) {
        val ms = samples.size * 1000 / 16_000
        voiced = if (samples.any { kotlin.math.abs(it) > 0.05f }) ms else 0
        if (voiced > 0) {
            speech += voiced
            text = "turn please"
        }
    }
    override fun voicedMillisOfLastAccept() = voiced
    override fun lastVadProbability() = if (voiced > 0) 1f else 0f
    override fun partial() = text
    override fun finish(): HearingResult {
        val result = HearingResult(text, speech, 1.0)
        text = ""
        speech = 0
        return result
    }
    override fun close() {}
}

private class QueueMic : MicCapture {
    private val chunks = java.util.ArrayDeque<FloatArray>()
    @Volatile private var closed = false
    fun push(samples: FloatArray) { synchronized(chunks) { chunks.addLast(samples) } }
    override fun read(into: FloatArray): Int {
        val next = synchronized(chunks) { if (chunks.isEmpty()) null else chunks.removeFirst() }
        if (next == null) {
            if (closed) return -1
            Thread.sleep(5)
            return 0
        }
        val n = minOf(into.size, next.size)
        next.copyInto(into, destinationOffset = 0, startIndex = 0, endIndex = n)
        return n
    }
    override fun close() { closed = true }
}

private fun pushUtterance(mic: QueueMic) {
    repeat(3) { mic.push(FloatArray(1600) { 0.2f }) }
    repeat(3) { mic.push(FloatArray(1600)) }
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
