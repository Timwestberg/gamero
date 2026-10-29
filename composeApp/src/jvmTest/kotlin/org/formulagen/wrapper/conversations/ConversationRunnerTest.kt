package org.formulagen.wrapper.conversations

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.workspace.FolderPicker

class ConversationRunnerTest {
    private val server = FakeServer()
    private val root = folder("runner")
    private val account = id()
    private val store = ConversationStore(root, account).open()
    @Volatile private var picked: java.nio.file.Path? = null
    private val folders = ProjectFolderStore(root, FolderPicker { _, _ -> picked })
    private val statuses = CopyOnWriteArrayList<String>()
    private val heartbeats = CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject>()
    private val beat = HostHeartbeat(emptyList(), ProviderDirectory(AT, emptyList()), CONVERSATION_HOST_FEATURES)

    /** An engine the test drives: [behave] runs each claimed message; every run is recorded with its Stop. */
    private inner class Engine : MessageEngine {
        val runs = CopyOnWriteArrayList<ClaimResult>()
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val cancels = ConcurrentHashMap<String, CancelSignal>()
        @Volatile var behave: (ClaimResult, ClaimOutput, CancelSignal) -> MessageState = { _, _, _ -> MessageState.ANSWERED }
        val closings = CopyOnWriteArrayList<() -> Boolean>()
        override fun run(claim: ClaimResult, output: ClaimOutput, cancel: CancelSignal, closing: () -> Boolean): MessageState {
            runs += claim
            cancels[claim.message.id] = cancel
            closings += closing
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try { return behave(claim, output, cancel) } finally { running.decrementAndGet() }
        }
    }

    private val engine = Engine()
    private val runner = ConversationRunner(server, store, engine, RemoteApprovals(NativeDialogs(Screen()), server, server.hostId), folders,
        ProjectFolderPublisher(folders, server, account) { emptyList() }, { beat }, { heartbeats += it }, { statuses += it },
        heartbeatEvery = 50.milliseconds, firstBackoff = 10.milliseconds, maxBackoff = 40.milliseconds)

    @AfterTest fun close() { runner.close(); store.close() }

    private fun queue(talk: TestConversation, text: String = "Hi", messageId: String = id()): String {
        server.queue(talk.id, Queued(talk.message(text, messageId = messageId)) { claimId, message -> talk.claim(claimId, message, brain()) })
        return messageId
    }

    private fun waitConnected() = eventually("the stream connected") { runner.connected }

    @Test fun workAvailableClaimsRunsAndSettlesEachMessageInTurn() {
        runner.start(); waitConnected()
        val talk = TestConversation()
        val first = queue(talk, "One"); val second = queue(talk, "Two")
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.settled(first) == MessageState.ANSWERED && server.settled(second) == MessageState.ANSWERED }
        assertEquals(listOf("One", "Two"), engine.runs.map { it.message.text })
        assertEquals(1, engine.peak.get(), "one message at a time per conversation")
        val progress = server.progress.filter { it.claimedMessageId == first }
        assertEquals(listOf(MessageState.RUNNING, MessageState.ANSWERED), progress.map { it.state })
        assertEquals(listOf(2L, 3L), progress.map { it.expectedRevision }, "each report against the revision the last one made")
        eventually("a settled claim leaves nothing journaled") { store.pendingClaims().isEmpty() }
        eventually("it looks once more and finds nothing") { server.claimRequests.size >= 3 }
    }

    @Test fun conversationsRunInParallelUpToTheCap() {
        val release = CountDownLatch(1)
        engine.behave = { _, _, _ -> release.await(5, TimeUnit.SECONDS); MessageState.ANSWERED }
        runner.start(); waitConnected()
        val talks = (1..ConversationRunner.MAX_RUNNING + 1).map { TestConversation().also { queue(it) } }
        talks.forEach { server.emit(HostStreamEvent.WorkAvailable(it.id)) }
        eventually { engine.running.get() == ConversationRunner.MAX_RUNNING }
        Thread.sleep(100)
        assertEquals(ConversationRunner.MAX_RUNNING, engine.running.get(), "the fifth waits for a slot")
        assertEquals(ConversationRunner.MAX_RUNNING, server.claimRequests.size, "and claims nothing meanwhile")
        release.countDown()
        eventually { engine.runs.size == talks.size }
        assertEquals(ConversationRunner.MAX_RUNNING, engine.peak.get())
    }

    @Test fun stopAndCancelEndOnlyTheirMessage() {
        engine.behave = { _, _, cancel ->
            eventually { cancel.isCancelled }
            MessageState.STOPPED
        }
        runner.start(); waitConnected()
        val a = TestConversation(); val b = TestConversation()
        val first = queue(a); val second = queue(b)
        server.emit(HostStreamEvent.WorkAvailable(a.id)); server.emit(HostStreamEvent.WorkAvailable(b.id))
        eventually { engine.cancels.size == 2 }
        server.emit(HostStreamEvent.StopRequested(a.id))
        eventually { server.settled(first) == MessageState.STOPPED }
        assertFalse(engine.cancels.getValue(second).isCancelled)
        server.emit(HostStreamEvent.MessageCancelled(id()))
        assertFalse(engine.cancels.getValue(second).isCancelled, "another message's cancel changes nothing")
        server.emit(HostStreamEvent.MessageCancelled(second))
        eventually { server.settled(second) == MessageState.STOPPED }
    }

    @Test fun aClaimThatNeedsANewerContractStaysQueuedAndSaysSo() {
        runner.start(); waitConnected()
        val talk = TestConversation()
        queue(talk)
        server.macUpdateRequired = setOf(talk.id)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { ConversationRunner.UPDATE_REQUIRED in statuses }
        assertEquals(emptyList(), engine.runs)
        assertEquals(emptyList(), store.pendingClaims())
    }

    @Test fun aRefusedClaimIsNeverRun() {
        server.refuseRunning = true
        runner.start(); waitConnected()
        val talk = TestConversation()
        val message = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.claimRequests.isNotEmpty() && store.pendingClaims().isEmpty() }
        assertEquals(emptyList(), engine.runs, "the server took it back before it launched")
        assertNull(server.settled(message))
    }

    @Test fun anEngineFailureSettlesAsUncertain() {
        engine.behave = { _, _, _ -> error("crashed mid-turn") }
        runner.start(); waitConnected()
        val talk = TestConversation()
        val message = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.settled(message) == MessageState.RECONCILIATION_REQUIRED }
        assertEquals(1, engine.runs.size)
    }

    @Test fun aClaimJournaledBeforeACrashIsClaimedAgainWithItsId() {
        val talk = TestConversation()
        val message = queue(talk)
        val journaled = store.journal(ClaimEntry(id(), talk.id, ClaimPhase.CLAIMING))
        // The crash came after the server committed the claim.
        server.claim(ClaimRequest(journaled.claimId, talk.id))
        runner.start()
        eventually { server.settled(message) == MessageState.ANSWERED }
        assertEquals(journaled.claimId, server.claimRequests[1].claimId, "no new claim id while one is journaled")
        assertEquals(1, engine.runs.size)
    }

    @Test fun aClaimThatMayHaveLaunchedSettlesAsUncertainAndNeverRunsAgain() {
        val talk = TestConversation()
        val message = queue(talk)
        val claimId = id()
        server.claim(ClaimRequest(claimId, talk.id))
        server.progress(message, MessageProgress(claimId, message, 2, MessageState.RUNNING))
        // The journal had the launch boundary, but not the revision `running` made.
        store.journal(ClaimEntry(claimId, talk.id, ClaimPhase.LAUNCHING, message, 2))
        runner.start()
        eventually { server.settled(message) == MessageState.RECONCILIATION_REQUIRED }
        eventually("then it looks for more and finds nothing") { store.pendingClaims().isEmpty() }
        assertEquals(emptyList(), engine.runs)
    }

    @Test fun aDecidedOutcomeIsDeliveredAfterARestart() {
        val talk = TestConversation()
        val message = queue(talk)
        val claimId = id()
        server.claim(ClaimRequest(claimId, talk.id))
        store.journal(ClaimEntry(claimId, talk.id, ClaimPhase.SETTLING, message, 2, MessageState.ANSWERED))
        runner.start()
        eventually { server.settled(message) == MessageState.ANSWERED }
        assertEquals(emptyList(), engine.runs)
    }

    @Test fun aJournaledClaimWhoseMessageExpiredRunsNothing() {
        val talk = TestConversation()
        queue(talk)
        server.expire(talk.id)
        store.journal(ClaimEntry(id(), talk.id, ClaimPhase.CLAIMING))
        runner.start()
        eventually { server.claimRequests.isNotEmpty() && store.pendingClaims().isEmpty() }
        assertEquals(emptyList(), engine.runs)
    }

    @Test fun anOfflineServerKeepsTheOutcomeUntilAHeartbeatGetsThrough() {
        engine.behave = { _, _, _ -> server.offline = true; MessageState.ANSWERED }
        runner.start(); waitConnected()
        val talk = TestConversation()
        val message = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { store.pendingClaims().any { it.phase == ClaimPhase.SETTLING } }
        eventually { ConversationRunner.OFFLINE in statuses }
        server.offline = false
        eventually { server.settled(message) == MessageState.ANSWERED }
        eventually { store.pendingClaims().isEmpty() }
    }

    @Test fun theHeartbeatReportsAndFeedsTheMemoryLane() {
        runner.start()
        eventually { server.heartbeats.size >= 2 && heartbeats.size >= 2 }
        assertEquals(beat, server.heartbeats.first())
        server.offline = true
        eventually { statuses.lastOrNull() == ConversationRunner.OFFLINE }
    }

    @Test fun theStreamReconnectsAfterAFailure() {
        server.streamFailures = 2
        runner.start()
        waitConnected()
        val talk = TestConversation()
        val message = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.settled(message) == MessageState.ANSWERED }
        assertEquals(0, server.streamFailures)
    }

    @Test fun aProjectsFoldersReachTheAccountOnceItsRevisionIsKnown() {
        val project = id()
        picked = folder("runner-project")
        folders.choose(account, project, org.formulagen.wrapper.bridge.FolderRole.WORKING)
        runner.start(); waitConnected()
        server.emit(HostStreamEvent.ProjectChanged(project))
        Thread.sleep(100)
        assertEquals(emptyList(), server.folderSaves, "no revision known yet")
        // A claim in that project tells the Mac its revision, and the folders follow.
        val talk = TestConversation(projectId = project)
        val message = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.settled(message) == MessageState.ANSWERED }
        eventually { server.folderSaves.isNotEmpty() }
        assertEquals(3, server.folderSaves.first().second.expectedRevision)
        assertEquals(listOf(folders.folders(account, project).single().id), server.folderSaves.first().second.folders.map { it.id })
    }

    @Test fun quittingEndsTheRunningMessageAsUncertainNotStopped() {
        engine.behave = { _, _, cancel -> eventually { cancel.isCancelled }; MessageState.RECONCILIATION_REQUIRED }
        runner.start(); waitConnected()
        val talk = TestConversation()
        val message = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { engine.cancels.isNotEmpty() }
        assertFalse(engine.closings.single()())
        runner.close()
        assertTrue(engine.cancels.values.single().isCancelled)
        assertTrue(engine.closings.single()(), "the engine can tell quitting from the person's Stop")
        assertEquals(MessageState.RECONCILIATION_REQUIRED, server.settled(message))
    }

    @Test fun aDeliveredOutcomeLetsTheNextMessageRun() {
        engine.behave = { claim, _, _ -> if (claim.message.text == "One") server.offline = true; MessageState.ANSWERED }
        runner.start(); waitConnected()
        val talk = TestConversation()
        val first = queue(talk, "One")
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { store.pendingClaims().any { it.phase == ClaimPhase.SETTLING } }
        // The server holds the next message until the first settles, then says nothing more.
        val second = queue(talk, "Two")
        server.offline = false
        eventually { server.settled(first) == MessageState.ANSWERED && server.settled(second) == MessageState.ANSWERED }
    }
}
