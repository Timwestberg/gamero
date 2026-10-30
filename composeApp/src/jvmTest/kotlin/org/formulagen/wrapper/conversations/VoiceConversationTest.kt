package org.formulagen.wrapper.conversations

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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

/** The Mac's microphone as the tests drive it: a session hears what [hear] says, and a hold answers [held] on release. */
internal class FakeEar : Hearing {
    @Volatile private var utterance: ((HearingResult) -> Unit)? = null
    @Volatile private var failure: ((String) -> Unit)? = null
    @Volatile private var holding: ((String) -> Unit)? = null
    @Volatile var held = ""
    /** Whether the session's capture is paused, as Talk holds the microphone. */
    @Volatile var muted = false
    /** Captures that will not start, as a microphone still letting go. */
    @Volatile var refuseSessions = false
    /** Runs as a page's capture is about to start, while the last one lets go. */
    @Volatile var beforeSession: (() -> Unit)? = null
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
        if (refuseSessions || utterance != null || holding != null) return false
        sessions.incrementAndGet()
        muted = false
        utterance = onUtterance
        failure = onFailed
        return true
    }

    override fun setPaused(value: Boolean) { muted = value }
    override fun restartUtterance() {}
    override fun noteEmberSpeaking(text: String) {}
    override fun noteEmberSilent(text: String) {}
    override fun endSession() { utterance = null }
    override fun awaitStopped(timeoutMs: Long) { beforeSession?.invoke() }
    override fun close() { utterance = null; holding = null }

    val listening: Boolean get() = utterance != null && !muted

    /** The person says [text]: one finished utterance, as the microphone thread delivers it. */
    fun hear(text: String, speechMs: Int = 900) = checkNotNull(utterance) { "no session" }(HearingResult(text, speechMs, 1.0))

    /** The person dictates [text] so far. */
    fun dictate(text: String) { held = text; checkNotNull(holding) { "no hold" }(text) }

    /** The microphone fails mid-session. */
    fun fail(message: String) { utterance = null; checkNotNull(failure)(message) }
}

/** The speaker as the tests see it: every stop, piece and stream end in order, and when the first piece came. */
internal class FakeSpeaker : ChatSpeech {
    val pieces = CopyOnWriteArrayList<String>()
    val whole = CopyOnWriteArrayList<String>()
    val events = CopyOnWriteArrayList<String>()
    val stops = AtomicInteger()
    val ends = AtomicInteger()
    val warms = AtomicInteger()
    @Volatile var firstPieceAt = 0L
    @Volatile private var voice = ChatVoice()
    @Volatile private var listener: (() -> Unit)? = null

    override fun status() = voice
    override fun onVoice(listener: () -> Unit) { this.listener = listener }
    override fun speak(text: String) { whole += text; events += "speak $text"; voice = ChatVoice(VoiceState.SPEAKING) }
    override fun stop() { stops.incrementAndGet(); events += "stop"; voice = ChatVoice() }
    override fun enqueue(piece: String): Boolean {
        if (pieces.isEmpty()) firstPieceAt = System.nanoTime()
        pieces += piece
        events += "piece $piece"
        voice = ChatVoice(VoiceState.SPEAKING)
        return true
    }
    override fun endStream() { ends.incrementAndGet(); events += "end" }
    override fun warm() { warms.incrementAndGet() }
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
    private val root: Path = folder("voice")
    private val account = id()
    @Volatile private var reply: (TurnRequest) -> String? = { "It is late. Time for tea." }
    @Volatile private var document: BrainDocument = brain()
    @Volatile private var speak = false
    private val coordinator = ScriptedRuntime(RuntimeId.CODEX) { reply(it) }
    private val ear = FakeEar()
    private val microphone = SharedHearing(ear)
    private val speaker = FakeSpeaker()
    private val row = VoicePickerModel()
    private val settings = TalkSettings(FillerSettings(), duplex = DuplexChoice(HearingDuplex.HALF, "test"), guardMs = 0)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var voice = voice()
    private var mac = Mac(root)

    /** This Mac's conversation voice; a restart makes a new one. */
    private fun voice() = DesktopConversationVoice(speaker, microphone, settings, row, HOVEL_PRODUCT, claimWaitMs = CLAIM_WAIT_MS,
        sleep = {}, timer = timer)

    /** Wait until every timer due within [ms] has run: a task queued after them runs last. */
    private fun timersDue(ms: Long) {
        val done = CountDownLatch(1)
        timer.schedule({ done.countDown() }, ms + 10, TimeUnit.MILLISECONDS)
        assertTrue(done.await(ms + 5_000, TimeUnit.MILLISECONDS))
    }

    /** This Mac's conversation side on [root]: its store, engine and runner, linked to the voice. A restart is a new one. */
    private inner class Mac(root: Path) : AutoCloseable {
        val store = ConversationStore(root, account).open()
        private val folders = ProjectFolderStore(root, FolderPicker { _, _ -> null })
        private val approvals = RemoteApprovals(NativeDialogs(Screen()), server, server.hostId)
        private val turns = ProviderTurns(listOf(coordinator), emptyList(), ConnectedProviders(), approvals)
        private val engine = CoordinatorEngine(store, folders, turns, approvals, StartOfWork(NativeDialogs(Screen())), server)
        private val beat = HostHeartbeat(emptyList(), ProviderDirectory(AT, emptyList()), listOf("memory-context"), "ready")
        val runner = ConversationRunner(server, store, engine, approvals, folders,
            ProjectFolderPublisher(folders, server, account, server.hostId) { emptyList() }, { beat },
            heartbeatEvery = 50.milliseconds, firstBackoff = 10.milliseconds, maxBackoff = 40.milliseconds, replies = voice)

        init {
            runner.start()
            voice.connected(heardTurns(server, runner))
        }

        override fun close() {
            voice.connected(null)
            runner.close()
            store.close()
        }
    }

    init {
        // A heard turn is queued as the server queues the owner's message, and claimed with this test's brain.
        server.onSend = { conversationId, message ->
            Queued(talk.message(message.text, messageId = message.id)) { claimId, claimed ->
                talk.claim(claimId, claimed, document, speak = speak).also { check(it.conversation.id == conversationId) }
            }
        }
    }

    @AfterTest fun close() { voice.close(); mac.close(); timer.shutdownNow() }

    private fun hearing(action: HearingAction, conversationId: String = talk.id) =
        runBlocking { voice.hearing(ConversationHearingArgs(conversationId, action)) }

    private fun dictation(action: DictationAction, conversationId: String = talk.id) =
        runBlocking { voice.dictation(ConversationDictationArgs(conversationId, action)) }

    private fun status() = runBlocking { voice.voiceStatus() }

    private fun phase() = checkNotNull(voice.served).talk.phase

    private fun heardId() = server.sent.last().second.id

    /** The run delegates to a held talk specialist, then answers once it is released; [release] lets it finish. */
    private fun delegatingRun(): () -> Unit {
        val roles = brain("Reviewer" to MemberMode.TALK)
        document = roles
        val reviewer = roles.configuration.specialists.single()
        val specialist = CountDownLatch(1)
        reply = { turn ->
            if ("Header shapes:" !in turn.persona.instructions) { specialist.countDown(); null }
            else {
                val request = brainJson.parseToJsonElement(turn.text.removeSuffix(REPLY_REPAIR)).jsonObject
                val attempts = request.getValue("attempts").jsonArray
                val allowed = request.getValue("allowed").jsonArray.map { it.jsonPrimitive.content }
                when {
                    "step-result" in allowed -> {
                        val attempt = attempts.last().jsonObject.getValue("id").jsonPrimitive.content
                        """{"kind":"step-result","attemptId":"$attempt","outcome":"ready","explanation":"Fine."}"""
                    }
                    attempts.isEmpty() -> """{"kind":"delegate","specialistId":"${reviewer.id}","task":"review it","inputIds":[]}"""
                    else -> "{\"kind\":\"answer\"}\nThe review is done."
                }
            }
        }
        return {
            assertTrue(specialist.await(5, TimeUnit.SECONDS), "the specialist started")
            coordinator.held.last().completed("Reviewed.", id())
        }
    }

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

    @Test fun aHeardTurnIsBoundedByTheMessageLimit() {
        hearing(HearingAction.HANDS_FREE)
        ear.hear("word ".repeat(4_000))
        eventually("posted") { server.sent.isNotEmpty() }
        assertEquals(CompanionLimits.MESSAGE, server.sent.single().second.text.length)
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

    @Test fun bargeInBeforeTheRunDelegatesStopsTheReplyAndKeepsListening() {
        reply = { null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("read me the news")
        eventually { coordinator.held.isNotEmpty() }
        coordinator.held.single().partial("First, the weather. Then")
        eventually { speaker.pieces.isNotEmpty() }
        val stops = speaker.stops.get()
        assertTrue(voice.escape(), "the window's Escape belongs to the voice conversation")
        assertTrue(speaker.stops.get() > stops, "the speaker flushes first")
        eventually("Talk's own turn, not delegated, stops as Stop stops it") { server.settled(heardId()) == MessageState.STOPPED }
        eventually("Talk listens again") { phase() == TalkPhase.LISTENING && ear.listening }
        assertTrue(checkNotNull(voice.served).talk.on)
        reply = { "Sure." }
        ear.hear("never mind, what time is it")
        eventually { server.sent.size == 2 && server.settled(heardId()) == MessageState.ANSWERED }
    }

    @Test fun bargeInOnceTheRunDelegatedOnlySilencesItAndTheRunContinues() {
        val release = delegatingRun()
        hearing(HearingAction.HANDS_FREE)
        ear.hear("review my draft")
        eventually("the task is delegated") { server.threads().any { it.state == BrainAttemptState.RUNNING } }
        eventually("Talk says the agents are working") { status().hearing.notice == TALK_AGENTS_WORKING }
        val stops = speaker.stops.get()
        assertTrue(voice.escape())
        assertTrue(speaker.stops.get() > stops, "the speaker flushes")
        assertEquals(TALK_AGENTS_WORKING, status().hearing.notice, "and says why nothing stopped")
        release()
        eventually("the agents' work goes on to its answer") { server.settled(heardId()) == MessageState.ANSWERED }
        eventually("a later reply in the run is spoken") { speaker.pieces.contains("The review is done.") }
        eventually("the note goes with the run") { status().hearing.notice != TALK_AGENTS_WORKING }
        assertTrue(checkNotNull(voice.served).talk.on)
    }

    @Test fun endingTalkDuringADelegatedRunLetsItContinueSilently() {
        val release = delegatingRun()
        hearing(HearingAction.HANDS_FREE)
        ear.hear("review my draft")
        eventually("the task is delegated") { server.threads().any { it.state == BrainAttemptState.RUNNING } }
        assertEquals(HearingState.OFF, hearing(HearingAction.OFF).hearing.state)
        assertFalse(ear.listening)
        release()
        eventually("the run continues to its answer") { server.settled(heardId()) == MessageState.ANSWERED }
        assertFalse(speaker.pieces.contains("The review is done."), "nothing more is said once Talk ended: ${speaker.pieces}")
    }

    @Test fun endingTalkOrAFailedMicrophoneNeverStopsAClaimedReply() {
        reply = { null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("plan my week")
        eventually { coordinator.held.isNotEmpty() }
        coordinator.held.single().partial("Monday is busy. Tuesday")
        eventually { speaker.pieces.isNotEmpty() }
        ear.fail("The microphone did not open.")
        assertEquals(HearingState.UNAVAILABLE, status().hearing.state)
        assertEquals("The microphone did not open.", status().hearing.notice)
        coordinator.held.single().completed("Monday is busy. Tuesday is free.", id())
        eventually("the reply finishes") { server.settled(heardId()) == MessageState.ANSWERED }
        assertEquals(listOf("Monday is busy."), speaker.pieces, "the rest is not said")
        assertFalse(voice.escape(), "with Talk off, Escape goes on")
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
        eventually { server.settled(heardId()) == MessageState.STOPPED }
        assertTrue(coordinator.requests.isEmpty(), "no provider turn ran")
    }

    @Test fun anAbandonedHeardTurnStaysStoppedWhenTheVoiceMovesOrThePageGoes() {
        val landing = CountDownLatch(1)
        val asked = CountDownLatch(1)
        server.onClaim = { asked.countDown(); landing.await(5, TimeUnit.SECONDS) }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("start the build")
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        val first = heardId()
        hearing(HearingAction.OFF)
        val other = TestConversation().id
        assertEquals(other, hearing(HearingAction.HANDS_FREE, other).conversationId, "the voice moved to another conversation")
        voice.pageEnded()
        landing.countDown()
        eventually { server.settled(first) == MessageState.STOPPED }
        assertTrue(coordinator.requests.isEmpty(), "ending Talk gave the turn up, and it never ran")
    }

    @Test fun anAbandonedHeardTurnStaysStoppedAcrossARestart() {
        server.onSend = { _, _ -> null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("start the build")
        eventually("posted") { server.sent.isNotEmpty() }
        assertTrue(voice.escape(), "given up on before it was claimed")
        val messageId = heardId()
        mac.close()
        voice.close()
        voice = voice()
        // Its claim lands only after Hovel restarted, which remembers nothing of the voice but the journal.
        val queued = Queued(talk.message("start the build", messageId = messageId)) { claimId, claimed -> talk.claim(claimId, claimed, document) }
        server.queue(talk.id, queued)
        mac = Mac(root)
        mac.runner.wake(talk.id)
        eventually { server.settled(messageId) == MessageState.STOPPED }
        assertTrue(coordinator.requests.isEmpty())
        eventually("the mark has done its work") { !mac.store.stopsOnClaim(talk.id, messageId) }
    }

    @Test fun aTypedMessageIsReadAloudOnlyWhenTheConversationSpeaks() {
        val quiet = TestConversation()
        val loud = TestConversation()
        fun queue(conversation: TestConversation, speaks: Boolean): String {
            val message = conversation.message("Hello")
            server.queue(conversation.id, Queued(message) { claimId, claimed ->
                conversation.claim(claimId, claimed, document, speak = speaks)
            })
            mac.runner.wake(conversation.id)
            return message.id
        }
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

    @Test fun turningSpokenRepliesOffStopsTheSpeakerAtOnce() {
        reply = { null }
        val message = talk.message("Hello")
        server.queue(talk.id, Queued(message) { claimId, claimed -> talk.claim(claimId, claimed, document, speak = true) })
        mac.runner.wake(talk.id)
        eventually { coordinator.held.isNotEmpty() }
        coordinator.held.single().partial("One. Two")
        eventually { speaker.pieces == listOf("One.") }
        val stops = speaker.stops.get()
        // The page turns Speak replies off for the conversation the voice serves: `release`.
        hearing(HearingAction.RELEASE)
        assertTrue(speaker.stops.get() > stops, "the speaker stops now")
        coordinator.held.single().completed("One. Two. Three.", id())
        eventually { server.settled(message.id) == MessageState.ANSWERED }
        assertEquals(listOf("One."), speaker.pieces, "nothing more of the run is said")
    }

    @Test fun turningSpokenRepliesOffDuringTalkOnlyStopsTheSpeaker() {
        reply = { null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("read me a poem")
        eventually { coordinator.held.isNotEmpty() }
        coordinator.held.single().partial("Roses are red. Violets")
        eventually { speaker.pieces == listOf("Roses are red.") }
        val stops = speaker.stops.get()
        val quiet = hearing(HearingAction.RELEASE)
        assertTrue(speaker.stops.get() > stops, "the speaker stops")
        assertEquals(HearingState.THINKING, quiet.hearing.state, "Talk stays on")
        coordinator.held.single().completed("Roses are red. Violets are blue.", id())
        eventually("the reply is not stopped") { server.settled(heardId()) == MessageState.ANSWERED }
        assertEquals(listOf("Roses are red."), speaker.pieces)
        eventually { phase() == TalkPhase.LISTENING && ear.listening }
        assertTrue(checkNotNull(voice.served).talk.on)
    }

    @Test fun turningSpokenRepliesOffNeverEndsADictation() {
        dictation(DictationAction.START)
        ear.dictate("add a")
        hearing(HearingAction.RELEASE)
        hearing(HearingAction.OFF)
        assertEquals(DictationState.HEARING, status().dictation?.state, "the dictation goes on")
        ear.held = "add a reminder"
        assertEquals(ConversationDictation(DictationState.DONE, text = "add a reminder"), dictation(DictationAction.STOP).dictation)
    }

    @Test fun anInterruptNeverStartsTalk() {
        val pressed = hearing(HearingAction.PRESS)
        assertEquals(HearingState.OFF, pressed.hearing.state)
        assertEquals(0, ear.sessions.get(), "Escape's interrupt never opens the microphone")
    }

    @Test fun aHeardTurnPostedButNotClaimedWhenHovelQuitsNeverRunsAfterTheRelaunch() {
        server.onSend = { _, _ -> null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("start the build")
        eventually("posted") { server.sent.isNotEmpty() }
        val messageId = heardId()
        // Quitting closes the account link first, then the voice: the stop is journaled while the link is up.
        mac.close()
        voice.close()
        voice = voice()
        val queued = Queued(talk.message("start the build", messageId = messageId)) { claimId, claimed -> talk.claim(claimId, claimed, document) }
        server.queue(talk.id, queued)
        mac = Mac(root)
        mac.runner.wake(talk.id)
        eventually { server.settled(messageId) == MessageState.STOPPED }
        assertTrue(coordinator.requests.isEmpty())
    }

    @Test fun aTurnClaimedAfterTheWaitIsStillTalksOwnAndANewerOneWaitingIsLeftAlone() {
        server.onSend = { _, _ -> null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("start the build")
        eventually("the first turn waited too long") { status().hearing.notice == TALK_QUEUED }
        val first = heardId()
        ear.hear("and run the tests")
        eventually("a second turn is posted") { server.sent.size == 2 }
        val second = heardId()
        reply = { null }
        server.queue(talk.id, Queued(talk.message("start the build", messageId = first)) { claimId, claimed -> talk.claim(claimId, claimed, document) })
        mac.runner.wake(talk.id)
        eventually("the first turn runs") { coordinator.held.isNotEmpty() }
        assertTrue(voice.escape(), "the window's Escape on Talk's own turn")
        eventually("Talk's own turn, though it waited, stops") { server.settled(first) == MessageState.STOPPED }
        reply = { "Fine." }
        server.queue(talk.id, Queued(talk.message("and run the tests", messageId = second)) { claimId, claimed -> talk.claim(claimId, claimed, document) })
        mac.runner.wake(talk.id)
        eventually("the newer turn was not given up") { server.settled(second) == MessageState.ANSWERED }
    }

    @Test fun aTurnTalkSaidIsWaitingStillRunsWhenTalkEndsAndIsNotReadAloud() {
        server.onSend = { _, _ -> null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("start the build")
        eventually("the page is told it waits") { status().hearing.notice == TALK_QUEUED }
        val waiting = heardId()
        hearing(HearingAction.OFF)
        server.queue(talk.id, Queued(talk.message("start the build", messageId = waiting)) { claimId, claimed ->
            talk.claim(claimId, claimed, document)
        })
        mac.runner.wake(talk.id)
        eventually("it runs as the person's message") { server.settled(waiting) == MessageState.ANSWERED }
        assertFalse(mac.store.stopsOnClaim(talk.id, waiting))
        assertTrue(speaker.pieces.isEmpty(), "Talk ended, so its reply is not said: ${speaker.pieces}")
    }

    @Test fun aTurnTalkSaidIsWaitingStillRunsAfterTheVoiceMovesAndHovelQuits() {
        server.onSend = { _, _ -> null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("start the build")
        eventually("the page is told it waits") { status().hearing.notice == TALK_QUEUED }
        val waiting = heardId()
        hearing(HearingAction.OFF)
        val other = TestConversation().id
        assertEquals(other, hearing(HearingAction.HANDS_FREE, other).conversationId, "the voice moved to another conversation")
        voice.pageEnded()
        mac.close()
        voice.close()
        voice = voice()
        server.queue(talk.id, Queued(talk.message("start the build", messageId = waiting)) { claimId, claimed ->
            talk.claim(claimId, claimed, document)
        })
        mac = Mac(root)
        mac.runner.wake(talk.id)
        eventually("it runs after the relaunch") { server.settled(waiting) == MessageState.ANSWERED }
    }

    @Test fun aThreadReplyNeverMovesTheVoice() {
        val other = TestConversation()
        val claim = other.claim(id(), other.message("In the thread", threadId = id()), document, speak = true)
        assertNull(voice.forClaim(claim) {})
        assertNull(status().conversationId, "the voice still serves nobody")
    }

    @Test fun oneReplyCutsOffThePreviousOneAndAThreadReplyIsNotAReply() {
        hearing(HearingAction.HANDS_FREE)
        val served = checkNotNull(voice.served)
        val main = talk.claim(id(), talk.message("Hi"), document, speak = true)
        val heard = checkNotNull(served.forClaim(main) {})
        heard.delta("first", "One. ")
        heard.appended("first", "One.")
        speaker.events.clear()
        heard.delta("second", "Two. ")
        assertEquals(listOf("stop", "piece Two."), speaker.events, "the second reply starts fresh")
        heard.ended()
        val inThread = talk.claim(id(), talk.message("Reply in the thread", threadId = id()), document, speak = true)
        assertNull(served.forClaim(inThread) {}, "a thread reply's claim is never a reply in progress here")
        assertEquals(TalkReply.IDLE, served.reply)
    }

    @Test fun aSpecialistsThreadIsNeverSpokenButTheCoordinatorsAnswerIs() {
        val release = delegatingRun()
        hearing(HearingAction.HANDS_FREE)
        ear.hear("review my draft")
        release()
        eventually("answered") { server.settled(heardId()) == MessageState.ANSWERED }
        eventually { speaker.pieces.contains("The review is done.") }
        assertFalse(speaker.pieces.any { "Reviewed" in it }, "threads are text only: ${speaker.pieces}")
        assertTrue(server.appended().any { it.threadId != null && it.text == "Reviewed." }, "the specialist's reply is in its thread")
    }

    @Test fun aRefusedOrUnreachableHeardTurnSaysWhyAndListensAgain() {
        server.sendStatus = 404
        hearing(HearingAction.HANDS_FREE)
        ear.hear("what time is it")
        eventually { status().hearing.notice == TALK_NOT_THIS_MAC }
        assertEquals(HearingState.LISTENING, status().hearing.state)
        assertTrue(server.claimRequests.isEmpty())
        timersDue(CLAIM_WAIT_MS)
        assertEquals(TALK_NOT_THIS_MAC, status().hearing.notice, "a turn never stored is never shown as waiting")
        server.sendStatus = null
        server.sendFailures = 2
        ear.hear("what time is it now")
        eventually("sent on its third try") { server.sent.size == 1 }
        eventually { server.settled(heardId()) == MessageState.ANSWERED }
    }

    @Test fun aResendAfterALostAnswerKeepsOneMessage() {
        server.lostSendAnswers = 1
        hearing(HearingAction.HANDS_FREE)
        ear.hear("what time is it")
        eventually { server.settled(heardId()) == MessageState.ANSWERED }
        assertEquals(1, server.sent.size, "the retry named the same id, and the server kept one message")
        assertEquals(1, coordinator.requests.size, "it ran once")
    }

    @Test fun aStoredTurnNotClaimedInTimeLetsTalkListenAgain() {
        server.onSend = { _, _ -> null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("what time is it")
        eventually { status().hearing.notice == TALK_QUEUED }
        eventually { phase() == TalkPhase.LISTENING && ear.listening }
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
        mac.runner.wake(talk.id)
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

    @Test fun aCaptureThatCannotStartSaysBusyInsteadOfListening() {
        ear.refuseSessions = true
        val refused = hearing(HearingAction.HANDS_FREE)
        assertEquals(HearingState.OFF, refused.hearing.state)
        assertEquals(MICROPHONE_IN_USE, refused.hearing.notice)
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

    @Test fun theVoiceRowShowsTheServedConversationAndItsIconEndsTalkThePageStarted() {
        assertFalse(row.state.value.visible)
        hearing(HearingAction.HANDS_FREE)
        val shown = row.state.value
        assertTrue(shown.visible, "a conversation's row shows in Hovel")
        assertTrue(shown.showTalk, "its icon can end a session the page started")
        assertEquals(TalkPhase.LISTENING, shown.phase)
        voice.converseClick()
        assertEquals(HearingState.OFF, status().hearing.state)
        assertFalse(row.state.value.showTalk, "with Talk off, the page owns starting it")
        voice.converseClick()
        assertEquals(HearingState.OFF, status().hearing.state, "the icon never starts a session the page owns")
    }

    @Test fun theFillerChoiceHoldsAcrossConversationsAndThePageGoingAway() {
        hearing(HearingAction.HANDS_FREE)
        voice.toggleFillers()
        assertTrue(row.state.value.fillersOn)
        hearing(HearingAction.OFF)
        hearing(HearingAction.HANDS_FREE, TestConversation().id)
        assertTrue(row.state.value.fillersOn, "the next conversation keeps it")
        voice.pageEnded()
        hearing(HearingAction.HANDS_FREE)
        assertTrue(row.state.value.fillersOn, "and so does the next page")
        assertTrue(settings.fillers.enabled)
    }

    @Test fun thePageGoingAwayLetsGoOfTheMicrophoneAndTheMessageRunsOn() {
        val held = CountDownLatch(1)
        reply = { held.countDown(); null }
        hearing(HearingAction.HANDS_FREE)
        ear.hear("keep working")
        assertTrue(held.await(5, TimeUnit.SECONDS))
        voice.pageEnded()
        assertFalse(row.state.value.visible)
        assertFalse(ear.listening, "the microphone closes with the page")
        assertNull(status().conversationId)
        assertNull(server.settled(heardId()), "the conversation's message keeps running")
        coordinator.held.single().completed("Done.", id())
        eventually { server.settled(heardId()) == MessageState.ANSWERED }
        assertTrue(speaker.pieces.isEmpty(), "nobody is served, so nothing is read aloud")
    }

    private companion object {
        const val CLAIM_WAIT_MS = 1_000L
    }
}
