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
    private val beat = HostHeartbeat(emptyList(), ProviderDirectory(AT, emptyList()), listOf("memory-context"), "ready")

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
    private val publisher = ProjectFolderPublisher(folders, server, account, server.hostId) { emptyList() }

    /** A runner on this test's server, store and engine; [heartbeat] builds each beat. */
    private fun runner(now: () -> Long = System::nanoTime, heartbeat: () -> HostHeartbeat = { beat }) = ConversationRunner(server, store, engine,
        RemoteApprovals(NativeDialogs(Screen()), server, server.hostId), folders, publisher, heartbeat, { heartbeats += it }, { statuses += it },
        heartbeatEvery = 50.milliseconds, firstBackoff = 10.milliseconds, maxBackoff = 40.milliseconds, now = now)

    private val runner = runner()

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
        eventually("the fifth waits for a slot") { runner.waitingForSlot == 1 }
        assertEquals(ConversationRunner.MAX_RUNNING, engine.running.get())
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

    @Test fun aClaimRefusedBeforeItRunsIsSettledNotRun() {
        server.refuseRunning = true
        runner.start(); waitConnected()
        val talk = TestConversation()
        val message = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.claimRequests.isNotEmpty() && store.pendingClaims().isEmpty() }
        assertEquals(emptyList(), engine.runs, "the server refused it before it launched")
        assertEquals(MessageState.RECONCILIATION_REQUIRED, server.settled(message), "settled, never left claimed")
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
        eventually("the server does not know the project yet") { project !in folders.unpublished(account) }
        assertEquals(emptyList(), server.folderSaves)
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

    @Test fun aStopWhileTheClaimIsOnItsWayStopsTheMessageOnceItLands() {
        val asked = CountDownLatch(1)
        val landing = CountDownLatch(1)
        server.onClaim = { asked.countDown(); landing.await(5, TimeUnit.SECONDS) }
        runner.start(); waitConnected()
        val talk = TestConversation()
        val message = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        runner.dispatch(HostStreamEvent.StopRequested(talk.id))
        server.onClaim = {}
        landing.countDown()
        eventually { server.settled(message) == MessageState.STOPPED }
        assertEquals(emptyList(), engine.runs, "no provider turn starts")
        assertEquals(listOf(MessageState.STOPPED), server.progress.filter { it.claimedMessageId == message }.map { it.state })
        // The Stop was for that message only: the next one runs.
        val next = queue(talk, "Next")
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.settled(next) == MessageState.ANSWERED }
    }

    @Test fun aClaimThatLandsAfterQuittingWaitsForTheNextLaunch() {
        val asked = CountDownLatch(1)
        val landing = CountDownLatch(1)
        server.onClaim = { asked.countDown(); landing.await(5, TimeUnit.SECONDS) }
        runner.start(); waitConnected()
        val talk = TestConversation()
        val message = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        val quitting = java.util.concurrent.CompletableFuture.runAsync { runner.close() }
        eventually("quitting began") { !runner.connected }
        server.onClaim = {}
        landing.countDown()
        quitting.get(10, TimeUnit.SECONDS)
        assertEquals(emptyList(), engine.runs, "no provider turn after quitting")
        val journaled = store.pendingClaims().single()
        assertEquals(ClaimPhase.CLAIMED, journaled.phase)
        assertNull(server.settled(message))
        runner().use { next ->
            next.start()
            eventually { server.settled(message) == MessageState.ANSWERED }
        }
        assertEquals(2, server.claimRequests.count { it.claimId == journaled.claimId }, "the next launch claims it again with its id")
        assertEquals(1, engine.runs.size)
    }

    @Test fun aClaimOrProgressRefusedForGoodIsDropped() {
        server.claimStatus = 404
        runner.start(); waitConnected()
        val talk = TestConversation()
        queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.claimRequests.isNotEmpty() && store.pendingClaims().isEmpty() }
        server.claimStatus = null
        server.progressStatus = 403
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.claimRequests.size >= 2 && store.pendingClaims().isEmpty() }
        assertEquals(emptyList(), engine.runs, "a message refused before it ran never runs")
        assertEquals(emptyList(), server.progress)
    }

    @Test fun aClaimRefusedAsTooOldSaysToUpdate() {
        server.claimStatus = 409
        server.claimCode = ConversationRunner.UPDATE_REQUIRED_CODE
        runner.start(); waitConnected()
        val talk = TestConversation()
        queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { ConversationRunner.UPDATE_REQUIRED in statuses }
        assertEquals(emptyList(), store.pendingClaims())
        assertEquals(emptyList(), engine.runs)
    }

    @Test fun aRefusalMidRunSettlesTheMessageRatherThanLeavingItClaimed() {
        engine.behave = { _, _, _ -> throw org.formulagen.wrapper.hovel.HovelRejectedException() }
        runner.start(); waitConnected()
        val talk = TestConversation()
        val message = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.settled(message) == MessageState.RECONCILIATION_REQUIRED }
        eventually { store.pendingClaims().isEmpty() }
    }

    @Test fun aSilentStreamIsDroppedAndReconnects() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        runner(now = clock::get).use { quiet ->
            quiet.start()
            eventually { quiet.connected && server.streamsOpened.get() == 1 }
            clock.addAndGet(TimeUnit.SECONDS.toNanos(60))
            quiet.watch()
            assertEquals(1, server.streamsOpened.get(), "a minute of quiet is fine")
            clock.addAndGet(TimeUnit.SECONDS.toNanos(16))
            quiet.watch()
            eventually("it reconnects after about 75 seconds of nothing") { server.streamsOpened.get() == 2 && quiet.connected }
        }
    }

    @Test fun anIdleConversationsLaneIsLetGo() {
        runner.start(); waitConnected()
        val talk = TestConversation()
        val first = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.settled(first) == MessageState.ANSWERED }
        eventually("nothing queued and nothing journaled") { talk.id !in runner.lanes }
        val second = queue(talk)
        server.emit(HostStreamEvent.WorkAvailable(talk.id))
        eventually { server.settled(second) == MessageState.ANSWERED }
    }

    @Test fun aFailedHeartbeatStillRetriesWhatWaitsForTheServer() {
        engine.behave = { _, _, _ -> server.offline = true; MessageState.ANSWERED }
        runner(heartbeat = { error("the directory could not be read") }).use { failing ->
            failing.start()
            eventually { failing.connected }
            val talk = TestConversation()
            val message = queue(talk)
            server.emit(HostStreamEvent.WorkAvailable(talk.id))
            eventually { store.pendingClaims().any { it.phase == ClaimPhase.SETTLING } }
            server.offline = false
            eventually("the outcome is delivered though no heartbeat gets through") { server.settled(message) == MessageState.ANSWERED }
            assertEquals(emptyList(), server.heartbeats)
            assertEquals(ConversationRunner.OFFLINE, statuses.last())
        }
    }
}
