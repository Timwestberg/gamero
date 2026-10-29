package org.formulagen.wrapper.conversations

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.voice.DuplexChoice
import org.formulagen.wrapper.voice.FillerSettings
import org.formulagen.wrapper.voice.HearingDuplex
import org.formulagen.wrapper.voice.HearingResult
import org.formulagen.wrapper.workspace.FolderPicker

/** The Mac's microphone as the tests drive it: a session hears what [hear] says, and a hold answers [held] on release. */
internal class FakeEar : Hearing {
    @Volatile private var utterance: ((HearingResult) -> Unit)? = null
    @Volatile private var holding: ((String) -> Unit)? = null
    @Volatile var held = ""
    /** Whether the session's capture is paused, as Talk holds the microphone. */
    @Volatile var muted = false
    val sessions = AtomicInteger()

    override fun down(onPartial: (String) -> Unit, onFailed: (String) -> Unit): Boolean {
        if (utterance != null || holding != null) return false
        holding = onPartial
        return true
    }

    override fun up(onResult: (HearingResult) -> Unit): Boolean {
        holding ?: return false
        holding = null
        onResult(HearingResult(held, 900, 1.0))
        return true
    }

    override fun startSession(silenceMs: Int, onPartial: (String) -> Unit, onUtterance: (HearingResult) -> Unit, onFailed: (String) -> Unit,
        onFrame: (voicedMs: Int, chunkMs: Int, vadProb: Float) -> Unit): Boolean {
        if (utterance != null || holding != null) return false
        sessions.incrementAndGet()
        muted = false
        utterance = onUtterance
        return true
    }

    override fun setPaused(value: Boolean) { muted = value }
    override fun restartUtterance() {}
    override fun noteEmberSpeaking(text: String) {}
    override fun noteEmberSilent(text: String) {}
    override fun endSession() { utterance = null }
    override fun awaitStopped(timeoutMs: Long) {}
    override fun close() { utterance = null; holding = null }

    val listening: Boolean get() = utterance != null && !muted

    /** The person says [text]: one finished utterance, as the microphone thread delivers it. */
    fun hear(text: String, speechMs: Int = 900) = checkNotNull(utterance) { "no session" }(HearingResult(text, speechMs, 1.0))

    /** The person dictates [text] so far. */
    fun dictate(text: String) { held = text; checkNotNull(holding) { "no hold" }(text) }
}

/** The speaker as the tests see it: the pieces it was given, in order, and when the first one came. */
internal class FakeSpeaker : ChatSpeech {
    val pieces = CopyOnWriteArrayList<String>()
    val whole = CopyOnWriteArrayList<String>()
    val stops = AtomicInteger()
    val ends = AtomicInteger()
    @Volatile var firstPieceAt = 0L
    @Volatile private var voice = ChatVoice()
    @Volatile private var listener: (() -> Unit)? = null

    override fun status() = voice
    override fun onVoice(listener: () -> Unit) { this.listener = listener }
    override fun speak(text: String) { whole += text; voice = ChatVoice(VoiceState.SPEAKING) }
    override fun stop() { stops.incrementAndGet(); voice = ChatVoice() }
    override fun enqueue(piece: String): Boolean {
        if (pieces.isEmpty()) firstPieceAt = System.nanoTime()
        pieces += piece
        voice = ChatVoice(VoiceState.SPEAKING)
        return true
    }
    override fun endStream() { ends.incrementAndGet() }
    override fun kokoroActive() = true
    override fun voiceChoices() = listOf(VoiceChoice("adam70-michael30", "Sam"), VoiceChoice("heart60-adam40", "Harper"))
    override fun currentVoiceId() = "adam70-michael30"
    override fun close() {}

    /** Playback finished. */
    fun settle() { voice = ChatVoice(); listener?.invoke() }
}

class VoiceConversationTest {
    private val server = FakeServer()
    private val talk = TestConversation()
    private val root = folder("voice")
    private val account = id()
    private val store = ConversationStore(root, account).open()
    private val folders = ProjectFolderStore(root, FolderPicker { _, _ -> null })
    @Volatile private var reply: (TurnRequest) -> String? = { "It is late. Time for tea." }
    @Volatile private var document: BrainDocument = brain()
    @Volatile private var speak = false
    private val coordinator = ScriptedRuntime(RuntimeId.CODEX) { reply(it) }
    private val approvals = RemoteApprovals(NativeDialogs(Screen()), server, server.hostId)
    private val engine = CoordinatorEngine(store, folders, ProviderTurns(listOf(coordinator), emptyList(), ConnectedProviders(), approvals),
        approvals, StartOfWork(NativeDialogs(Screen())), server)
    private val ear = FakeEar()
    private val microphone = SharedHearing(ear)
    private val speaker = FakeSpeaker()
    private val row = VoicePickerModel()
    private val settings = ConversationVoiceSettings(DuplexChoice(HearingDuplex.HALF, "test"), FillerSettings(), null,
        silenceMs = 1_000, guardMs = 0, bargeMs = 250)
    private val voice = DesktopConversationVoice(speaker, microphone.user(), null, settings, row, HOVEL_PRODUCT)
    private val beat = HostHeartbeat(emptyList(), ProviderDirectory(AT, emptyList()), listOf("memory-context"), "ready")
    private val runner = ConversationRunner(server, store, engine, approvals, folders,
        ProjectFolderPublisher(folders, server, account, server.hostId) { emptyList() }, { beat },
        heartbeatEvery = 50.milliseconds, firstBackoff = 10.milliseconds, maxBackoff = 40.milliseconds, replies = voice)

    init {
        // A heard turn is queued as the server queues the owner's message, and claimed with this test's brain.
        server.onSend = { conversationId, message ->
            Queued(talk.message(message.text, messageId = message.id)) { claimId, claimed ->
                talk.claim(claimId, claimed, document, speak = speak).also { check(it.conversation.id == conversationId) }
            }
        }
        runner.start()
        voice.connected(heardTurns(server, runner))
    }

    @AfterTest fun close() { voice.close(); runner.close(); store.close() }

    private fun hearing(action: HearingAction, conversationId: String = talk.id) =
        runBlocking { voice.hearing(ConversationHearingArgs(conversationId, action)) }

    private fun dictation(action: DictationAction, conversationId: String = talk.id) =
        runBlocking { voice.dictation(ConversationDictationArgs(conversationId, action)) }

    private fun status() = runBlocking { voice.voiceStatus() }

    private fun phase() = checkNotNull(voice.served).talk.phase

    @Test fun aHeardTurnIsSentAsTheOwnersMessageClaimedAtOnceAndSpokenSentenceBySentence() {
        val started = hearing(HearingAction.HANDS_FREE)
        assertEquals(talk.id, started.conversationId)
        assertEquals(HearingState.LISTENING, started.hearing.state)
        assertTrue(ear.listening)
        ear.hear("  what   time is it ")
        val (conversationId, sent) = server.sent.single()
        assertEquals(talk.id, conversationId)
        assertEquals(MessageSend(sent.id, emptyList(), "what time is it", null), sent, "the owner's message, in the main conversation")
        // No `work.available` ever reaches the stream: the runner claims at once.
        eventually("answered") { server.settled(sent.id) == MessageState.ANSWERED }
        eventually("spoken") { speaker.ends.get() == 1 }
        assertEquals(listOf("It is late.", "Time for tea."), speaker.pieces)
        assertTrue("what time is it" in coordinator.requests.single().text, "the coordinator answers what was heard")
        assertEquals(TalkPhase.SPEAKING, phase())
        assertTrue(ear.muted, "half duplex: the microphone is closed while she speaks")
        speaker.settle()
        eventually("listening again") { phase() == TalkPhase.LISTENING && ear.listening }
        val latency = checkNotNull(checkNotNull(voice.served).heardLatency)
        assertTrue(latency.endpointToClaimedMs < 1_000, "claimed at once: $latency")
    }

    @Test fun whileTheReplyIsWrittenTalkThinksAndTheMicrophoneWaits() {
        val held = CountDownLatch(1)
        reply = { held.countDown(); null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("tell me a story")
        assertTrue(held.await(5, TimeUnit.SECONDS))
        assertEquals(TalkPhase.THINKING, phase())
        assertEquals(HearingState.THINKING, status().hearing.state)
        assertTrue(ear.muted)
        ear.hear("are you there")
        assertEquals(1, server.sent.size, "nothing more is sent while a reply is in progress")
        coordinator.held.single().partial("Once upon a time. There")
        eventually("the first sentence is spoken as it arrives") { speaker.pieces == listOf("Once upon a time.") }
        coordinator.held.single().completed("Once upon a time. There was a cat.", id())
        eventually { speaker.pieces == listOf("Once upon a time.", "There was a cat.") && speaker.ends.get() == 1 }
    }

    @Test fun bargeInStopsTheSpeakerAndTheMessageAndKeepsListening() {
        reply = { null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("read me the news")
        eventually { coordinator.held.isNotEmpty() }
        coordinator.held.single().partial("First, the weather. Then")
        eventually { speaker.pieces.isNotEmpty() }
        val stops = speaker.stops.get()
        assertTrue(voice.escape(), "Escape belongs to the voice conversation")
        assertTrue(speaker.stops.get() > stops, "the speaker flushes first")
        val messageId = server.sent.single().second.id
        eventually("the message stops as the person's Stop does") { server.settled(messageId) == MessageState.STOPPED }
        eventually("Talk listens again") { phase() == TalkPhase.LISTENING && ear.listening }
        assertTrue(checkNotNull(voice.served).talk.on)
        reply = { "Sure." }
        ear.hear("never mind, what time is it")
        eventually { server.sent.size == 2 && server.settled(server.sent.last().second.id) == MessageState.ANSWERED }
    }

    @Test fun endingTalkMidReplyStopsTheMessage() {
        reply = { null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("plan my week")
        eventually { coordinator.held.isNotEmpty() }
        val ended = hearing(HearingAction.OFF)
        assertEquals(HearingState.OFF, ended.hearing.state)
        assertFalse(ear.listening)
        eventually { server.settled(server.sent.single().second.id) == MessageState.STOPPED }
        assertFalse(voice.escape(), "with Talk off, the page keeps Escape")
    }

    @Test fun aHeardTurnStoppedBeforeItsClaimLandsNeverRuns() {
        val landing = CountDownLatch(1)
        val asked = CountDownLatch(1)
        server.onClaim = { asked.countDown(); landing.await(5, TimeUnit.SECONDS) }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("start the build")
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        assertTrue(voice.escape())
        landing.countDown()
        val messageId = server.sent.single().second.id
        eventually { server.settled(messageId) == MessageState.STOPPED }
        assertTrue(coordinator.requests.isEmpty(), "no provider turn ran")
    }

    @Test fun aTypedMessageIsReadAloudOnlyWhenTheConversationSpeaks() {
        val quiet = TestConversation()
        val loud = TestConversation()
        fun queue(conversation: TestConversation, speaks: Boolean): String {
            val message = conversation.message("Hello")
            server.queue(conversation.id, Queued(message) { claimId, claimed -> conversation.claim(claimId, claimed, document, speak = speaks) })
            server.emit(HostStreamEvent.WorkAvailable(conversation.id))
            return message.id
        }
        eventually("the stream connected") { runner.connected }
        val first = queue(quiet, speaks = false)
        eventually { server.settled(first) == MessageState.ANSWERED }
        assertTrue(speaker.pieces.isEmpty())
        assertNull(status().conversationId, "the voice serves nobody")
        val second = queue(loud, speaks = true)
        eventually { server.settled(second) == MessageState.ANSWERED && speaker.ends.get() == 1 }
        assertEquals(listOf("It is late.", "Time for tea."), speaker.pieces)
        assertEquals(loud.id, status().conversationId, "the voice moved to the conversation it reads")
        assertFalse(checkNotNull(voice.served).talk.on, "reading aloud never opens the microphone")
        assertFalse(ear.listening)
    }

    @Test fun aSpecialistsThreadIsNeverSpokenButTheCoordinatorsAnswerIs() {
        val roles = brain("Reviewer" to MemberMode.TALK)
        document = roles
        val reviewer = roles.configuration.specialists.single()
        reply = { turn ->
            if ("Header shapes:" !in turn.persona.instructions) "Thread work. Not for the speaker."
            else {
                val request = brainJson.parseToJsonElement(turn.text.removeSuffix(REPLY_REPAIR)).jsonObject
                val attempts = request.getValue("attempts").jsonArray
                val allowed = request.getValue("allowed").jsonArray.map { it.jsonPrimitive.content }
                when {
                    "step-result" in allowed -> """{"kind":"step-result","attemptId":"${attempts.last().jsonObject.getValue("id").jsonPrimitive.content}","outcome":"ready","explanation":"Fine."}"""
                    attempts.isEmpty() -> """{"kind":"delegate","specialistId":"${reviewer.id}","task":"review it","inputIds":[]}"""
                    else -> "{\"kind\":\"answer\"}\nThe review is done."
                }
            }
        }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("review my draft")
        val messageId = server.sent.single().second.id
        eventually("answered") { server.settled(messageId) == MessageState.ANSWERED }
        eventually { speaker.pieces.contains("The review is done.") }
        assertFalse(speaker.pieces.any { "Thread work" in it } || speaker.whole.any { "Thread work" in it }, "threads are text only: ${speaker.pieces}")
        assertTrue(server.deltas.any { it.threadId != null }, "the specialist's reply streamed into its thread")
    }

    @Test fun aRefusedOrUnreachableHeardTurnSaysWhyAndListensAgain() {
        server.sendStatus = 404
        hearing(HearingAction.HANDS_FREE)
        ear.hear("what time is it")
        eventually { status().hearing.notice == TALK_NOT_THIS_MAC }
        assertEquals(HearingState.LISTENING, status().hearing.state)
        assertTrue(server.claimRequests.isEmpty())
        server.sendStatus = null
        server.sendFailures = 2
        ear.hear("what time is it now")
        eventually("sent on its third try") { server.sent.size == 1 }
        eventually { server.settled(server.sent.single().second.id) == MessageState.ANSWERED }
    }

    @Test fun talkNeedsThisMacConnectedToTheAccount() {
        voice.connected(null)
        val refused = hearing(HearingAction.HANDS_FREE)
        assertEquals(HearingState.OFF, refused.hearing.state)
        assertEquals(TALK_NOT_CONNECTED, refused.hearing.notice)
        assertFalse(ear.listening)
    }

    @Test fun dictationFillsTheDraftWithoutSendingOrSpeaking() {
        val listening = dictation(DictationAction.START)
        assertEquals(DictationState.LISTENING, listening.dictation?.state)
        ear.dictate("add a reminder")
        assertEquals(ConversationDictation(DictationState.HEARING, partial = "add a reminder"), status().dictation)
        ear.held = "add a reminder for Friday"
        val done = dictation(DictationAction.STOP)
        assertEquals(ConversationDictation(DictationState.DONE, text = "add a reminder for Friday"), done.dictation)
        assertNull(status().dictation)
        assertTrue(server.sent.isEmpty())
        assertTrue(speaker.pieces.isEmpty() && speaker.whole.isEmpty())
        assertEquals(TalkPhase.OFF, phase())
    }

    @Test fun aReplyThatArrivesWhileThePageDictatesIsNotReadAloud() {
        dictation(DictationAction.START)
        val message = talk.message("Hello")
        server.queue(talk.id, Queued(message) { claimId, claimed -> talk.claim(claimId, claimed, document, speak = true) })
        runner.wake(talk.id)
        eventually { server.settled(message.id) == MessageState.ANSWERED }
        assertTrue(speaker.pieces.isEmpty() && speaker.whole.isEmpty(), "the microphone would hear it: ${speaker.pieces}")
        assertEquals(DictationState.LISTENING, status().dictation?.state)
    }

    @Test fun oneMicrophoneServesTeamChatOrAConversationAtATime() {
        val chat = microphone.user()
        assertTrue(chat.startSession(1_000, {}, {}, {}))
        val refused = hearing(HearingAction.HANDS_FREE)
        assertEquals(MICROPHONE_IN_USE, refused.hearing.notice)
        assertEquals(HearingState.OFF, refused.hearing.state)
        dictation(DictationAction.START)
        assertEquals(ConversationDictation(DictationState.UNAVAILABLE, notice = MICROPHONE_IN_USE), status().dictation)
        chat.endSession()
        assertEquals(HearingState.LISTENING, hearing(HearingAction.HANDS_FREE).hearing.state)
        assertFalse(chat.startSession(1_000, {}, {}, {}), "now team chat waits")
    }

    @Test fun theVoiceServesOneConversationAtATime() {
        val other = TestConversation()
        hearing(HearingAction.HANDS_FREE)
        val elsewhere = hearing(HearingAction.HANDS_FREE, other.id)
        assertEquals(talk.id, elsewhere.conversationId, "Talk stays where it is on")
        assertEquals(talk.id, dictation(DictationAction.START, other.id).conversationId)
        assertEquals(talk.id, hearing(HearingAction.OFF, other.id).conversationId, "ending another conversation's Talk ends nothing here")
        assertTrue(checkNotNull(voice.served).talk.on)
        hearing(HearingAction.OFF)
        assertEquals(other.id, hearing(HearingAction.HANDS_FREE, other.id).conversationId)
    }

    @Test fun theVoiceRowShowsTheServedConversationInHovelAndThePageEndingLetsGo() {
        assertFalse(row.state.value.visible)
        hearing(HearingAction.HANDS_FREE)
        val shown = row.state.value
        assertTrue(shown.visible, "a conversation's row shows in Hovel")
        assertFalse(shown.showTalk, "the page owns Talk")
        assertEquals(TalkPhase.LISTENING, shown.phase)
        voice.toggleFillers()
        assertTrue(row.state.value.fillersOn)
        reply = { null }
        ear.hear("keep working")
        eventually { coordinator.held.isNotEmpty() }
        voice.pageEnded()
        assertFalse(row.state.value.visible)
        assertFalse(ear.listening, "the microphone closes with the page")
        assertNull(status().conversationId)
        Thread.sleep(50)
        assertNull(server.settled(server.sent.single().second.id), "the conversation's message keeps running")
        coordinator.held.single().completed("Done.", id())
        eventually { server.settled(server.sent.single().second.id) == MessageState.ANSWERED }
        assertTrue(speaker.pieces.isEmpty(), "nobody is served, so nothing is read aloud")
    }
}

class ConversationVoiceLatencyTest {
    /**
     * Hermetic: endpoint to the first sentence reaching the speaker, with a provider that answers at once and a
     * server with no latency. Team chat sends in memory; a conversation posts, claims and journals the claim
     * durably four times before its provider turn (claiming, claimed, launching, running), then runs the engine.
     * The difference is what this Mac adds; each real round trip adds its network time (three on the heard path:
     * send, claim, running).
     */
    @Test fun aHeardTurnReachesTheSpeakerAsFastAsTeamChat() {
        val chat = (1..TURNS).map { teamChatTurn() }.sorted()
        val conversation = conversationTurns().sorted()
        val chatMedian = chat[TURNS / 2]
        val conversationMedian = conversation[TURNS / 2]
        println("voice latency endpoint->first piece (ms): team chat median=$chatMedian all=$chat; conversation median=$conversationMedian all=$conversation")
        assertTrue(conversationMedian - chatMedian < 150, "the conversation path adds ${conversationMedian - chatMedian} ms")
    }

    private fun teamChatTurn(): Long {
        val speaker = FakeSpeaker()
        val runtime = object : ChatRuntime {
            override val id = RuntimeId.CODEX
            override fun readiness(profile: String) = Readiness.Ready
            override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
                val conversation = id()
                events.opened(conversation)
                events.accepted()
                events.partial("It is late.")
                events.completed("It is late. Time for tea.", conversation)
                return TurnHandle {}
            }
            override fun summarize(request: SummaryRequest) = "summary"
            override fun release() {}
            override fun close() {}
        }
        val scope = ChatScope("local", id(), "Ember", id())
        DesktopChat(java.nio.file.Files.createTempDirectory("latency-chat"), listOf(runtime), speaker, guardMs = 0).use { desk ->
            runBlocking {
                desk.open(scope)
                desk.send(ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId,
                    TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL), id(), "Hello", false))
            }
            eventually { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            desk.testingSession()
            val endpoint = System.nanoTime()
            desk.testingUtterance(HearingResult("what time is it", 900, 1.0))
            eventually { speaker.firstPieceAt != 0L }
            return (speaker.firstPieceAt - endpoint) / 1_000_000
        }
    }

    private fun conversationTurns(): List<Long> {
        val server = FakeServer()
        val talk = TestConversation()
        val root = folder("latency-voice")
        val account = id()
        val store = ConversationStore(root, account).open()
        val folders = ProjectFolderStore(root, FolderPicker { _, _ -> null })
        val coordinator = ScriptedRuntime(RuntimeId.CODEX, chunk = 12) { "It is late. Time for tea." }
        val approvals = RemoteApprovals(NativeDialogs(Screen()), server, server.hostId)
        val engine = CoordinatorEngine(store, folders, ProviderTurns(listOf(coordinator), emptyList(), ConnectedProviders(), approvals),
            approvals, StartOfWork(NativeDialogs(Screen())), server)
        val ear = FakeEar()
        val speaker = FakeSpeaker()
        val voice = DesktopConversationVoice(speaker, ear, null,
            ConversationVoiceSettings(DuplexChoice(HearingDuplex.HALF, "test"), FillerSettings(), null, 1_000, 0, 250), null, HOVEL_PRODUCT)
        val beat = HostHeartbeat(emptyList(), ProviderDirectory(AT, emptyList()), listOf("memory-context"), "ready")
        val runner = ConversationRunner(server, store, engine, approvals, folders,
            ProjectFolderPublisher(folders, server, account, server.hostId) { emptyList() }, { beat }, replies = voice)
        // One brain for the conversation, so its coordinator continues one provider conversation as it does for a person.
        val document = brain()
        server.onSend = { _, message ->
            Queued(talk.message(message.text, messageId = message.id)) { claimId, claimed -> talk.claim(claimId, claimed, document) }
        }
        try {
            runner.start()
            voice.connected(heardTurns(server, runner))
            runBlocking { voice.hearing(ConversationHearingArgs(talk.id, HearingAction.HANDS_FREE)) }
            return (1..TURNS).map { turn ->
                speaker.pieces.clear()
                speaker.firstPieceAt = 0L
                val endpoint = System.nanoTime()
                ear.hear("what time is it, turn $turn")
                eventually { speaker.firstPieceAt != 0L }
                val took = (speaker.firstPieceAt - endpoint) / 1_000_000
                eventually { server.sent.size == turn && server.settled(server.sent.last().second.id) == MessageState.ANSWERED }
                speaker.settle()
                eventually { voice.served?.talk?.phase == TalkPhase.LISTENING }
                took
            }
        } finally {
            voice.close(); runner.close(); store.close()
        }
    }

    private companion object {
        const val TURNS = 9
    }
}
