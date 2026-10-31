package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.runtimes.*
import org.formulagen.wrapper.conversation.voice.*
import org.formulagen.wrapper.util.*
import org.formulagen.wrapper.conversation.Activity
import org.formulagen.wrapper.conversation.ActivityKind
import org.formulagen.wrapper.conversation.ActivityStatus
import org.formulagen.wrapper.conversation.ActivityStep
import org.formulagen.wrapper.conversation.MemberMode

class CoordinatorEngineTest {
    /** One engine on a fake server, with a coordinator and specialists the test scripts. */
    private class Fixture(val document: BrainDocument, workingFolder: Boolean = true, context: Boolean = false) {
        val server = FakeServer()
        val talk = TestConversation()
        val root = folder("engine")
        val account = id()
        val store = ConversationStore(root, account)
        @Volatile var picked: Path? = null
        val folders = ProjectFolderStore(root, FolderPicker { _, _ -> picked })
        val working: Path = Files.createDirectories(root.resolve("hovel-notes")).toRealPath()
        val docs: Path = Files.createDirectories(root.resolve("design-docs")).toRealPath()
        /** The coordinator and every talk specialist; [decide] answers a coordinator turn. */
        val chat = ScriptedRuntime(RuntimeId.CODEX) { turn -> if (isCoordinator(turn)) decide(turn) else "Reviewed: looks good." }
        val worker = ScriptedRuntime(RuntimeId.CODEX) { "Implemented sign-in." }
        @Volatile var decide: (TurnRequest) -> String? = { "{\"kind\":\"answer\"}\nHello." }
        val screen = Screen { 0 }
        val dialogs = NativeDialogs(screen)
        val approvals = RemoteApprovals(dialogs, server, server.hostId)
        val turns = ProviderTurns(listOf(chat), listOf(worker), ConnectedProviders(), approvals)
        val engine = CoordinatorEngine(store, folders, turns, approvals, StartOfWork(), server)

        init {
            if (workingFolder) { picked = working; folders.choose(account, talk.projectId, FolderRole.WORKING) }
            if (context) { picked = docs; folders.choose(account, talk.projectId, FolderRole.CONTEXT) }
        }

        val implementer get() = document.configuration.specialists.first()

        /** Queue [text] (in [threadId]), claim it and run it here. */
        fun send(text: String, threadId: String? = null, messageId: String = id(), cancel: CancelSignal = CancelSignal(),
            history: List<ConversationMessage> = emptyList(), memory: String? = null, closing: () -> Boolean = { false }): MessageState {
            return run(claim(text, threadId, messageId, history, memory), cancel, closing)
        }

        /** Run [claim] here, as the runner does once it is claimed. */
        fun run(claim: ClaimResult, cancel: CancelSignal = CancelSignal(), closing: () -> Boolean = { false }): MessageState =
            ClaimOutput(server, claim.claimId, claim.message.id, attempts = 1, onStopRequested = cancel::cancel)
                .use { engine.run(claim, it, cancel, closing) }

        fun claim(text: String, threadId: String? = null, messageId: String = id(), history: List<ConversationMessage> = emptyList(),
            memory: String? = null, inputs: List<ArtifactRef> = emptyList(),
            thread: ConversationThread? = threadId?.let { id -> server.threads().lastOrNull { it.id == id } }): ClaimResult {
            server.queue(talk.id, Queued(talk.message(text, threadId, messageId).copy(inputs = inputs)) { claimId, message ->
                talk.claim(claimId, message, document, history, thread, memory) })
            return (server.claim(ClaimRequest(id(), talk.id)) as ClaimResponse.Claimed).claim
        }

        fun record() = store.read(talk.id)
        fun coordinatorTurns() = chat.requests.filter(::isCoordinator)
        fun request(turn: TurnRequest): JsonObject = brainJson.parseToJsonElement(turn.text.removeSuffix(REPLY_REPAIR)).jsonObject
        fun allowed(turn: TurnRequest) = request(turn).getValue("allowed").jsonArray.map { it.jsonPrimitive.content }

        companion object {
            fun isCoordinator(turn: TurnRequest) = "Header shapes:" in turn.persona.instructions
        }
    }

    private fun delegate(specialist: SpecialistRole, task: String = "add sign-in") =
        """{"kind":"delegate","specialistId":"${specialist.id}","task":"$task","inputIds":[]}"""

    /** Delegate to [specialist] first, grade its attempt ready, then answer. */
    private fun Fixture.delegateThenAnswer(specialist: SpecialistRole = implementer) {
        decide = { turn ->
            val attempts = request(turn).getValue("attempts").jsonArray
            when {
                "step-result" in allowed(turn) ->
                    """{"kind":"step-result","attemptId":"${attempts.last().jsonObject.getValue("id").jsonPrimitive.content}","outcome":"ready","explanation":"Works."}"""
                attempts.isEmpty() -> delegate(specialist)
                else -> "{\"kind\":\"answer\"}\nAll done: sign-in works."
            }
        }
    }

    @Test fun aBrainWithoutSpecialistsStreamsAPlainAnswer() {
        val fixture = Fixture(brain())
        fixture.decide = { "unused" }
        fixture.chat.reply = { "Hello there, friend. How can I help?" }
        val history = listOf(ConversationMessage(MessageAuthor.COORDINATOR, null, fixture.talk.id, AT, null, null, id(), emptyList(), null, 1, null,
            "Earlier reply", null))
        assertEquals(MessageState.ANSWERED, fixture.send("Hi!", history = history, memory = "Likes tea"))
        val reply = fixture.server.appended().single()
        assertEquals(MessageAppend(MessageAuthor.COORDINATOR, reply.id, null, "Hello there, friend. How can I help?", null), reply)
        assertEquals(reply.text, fixture.server.deltas.joinToString("") { it.text })
        assertTrue(fixture.server.deltas.all { it.messageId == reply.id && it.threadId == null })
        assertEquals((0 until fixture.server.deltas.size).toList(), fixture.server.deltas.map { it.seq })
        val first = fixture.chat.requests.single()
        assertFalse("Header shapes:" in first.persona.instructions, "no decision protocol")
        assertTrue("Earlier reply" in first.text && "Likes tea" in first.text && first.text.endsWith("Hi!"))
        assertEquals(fixture.talk.companionId, first.companionId)
        // The provider conversation continues: no history is sent again.
        assertEquals(MessageState.ANSWERED, fixture.send("And then?", history = history))
        val second = fixture.chat.requests.last()
        assertNotNull(second.conversation)
        assertEquals("And then?", second.text)
    }

    @Test fun aDelegatedTaskOpensAThreadWhoseReplyStreamsIntoIt() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.worker.activity = Activity(listOf(ActivityStep(ActivityKind.EDIT, "src/app.ts", ActivityStatus.DONE)), false)
        assertEquals(MessageState.ANSWERED, fixture.send("Add sign-in"))
        val appended = fixture.server.appended()
        val announcement = appended.first()
        assertEquals(MessageAppend(MessageAuthor.COORDINATOR, announcement.id, null, "Asked Implementer: add sign-in", null), announcement)
        val threads = fixture.server.threads()
        val threadId = threads.first().id
        assertTrue(threads.all { it.id == threadId && it.parentMessageId == announcement.id && it.title == "Implementer · add sign-in" })
        assertEquals(listOf(BrainAttemptState.QUEUED, BrainAttemptState.RUNNING, BrainAttemptState.COMPLETED), threads.map { it.state }.distinct())
        assertEquals(threads.map { it.revision }.sorted(), threads.map { it.revision }, "each upsert is a newer revision")
        val finished = threads.last()
        assertEquals(listOf(ActivityStep(ActivityKind.EDIT, "src/app.ts", ActivityStatus.DONE)), finished.activity)
        val stored = fixture.server.checkpoints.filterIsInstance<MessageCheckpoint.ArtifactStored>().single()
        assertEquals("Implemented sign-in.", stored.text)
        assertEquals(listOf(stored.artifact), finished.outputs)
        val specialist = appended.single { it.author == MessageAuthor.SPECIALIST }
        assertEquals(MessageAppend(MessageAuthor.SPECIALIST, specialist.id, null, "Implemented sign-in.", threadId), specialist)
        val streamed = fixture.server.deltas.filter { it.messageId == specialist.id }
        assertEquals("Implemented sign-in.", streamed.joinToString("") { it.text })
        assertTrue(streamed.all { it.threadId == threadId })
        val answer = appended.last()
        assertEquals(MessageAppend(MessageAuthor.COORDINATOR, answer.id, null, "All done: sign-in works.", null), answer)
        assertEquals("All done: sign-in works.", fixture.server.deltas.filter { it.messageId == answer.id }.joinToString("") { it.text },
            "the answer's body streams, never its header")
        assertEquals(BrainRunState.COMPLETED, fixture.server.checkpoints.filterIsInstance<MessageCheckpoint.RunChanged>().last().run.state)
        // The specialist worked in the project's folder on its thread's lease, which ended with the turn.
        val work = fixture.worker.requests.single().work!!
        assertEquals(ProjectFolders(fixture.working), work.folders)
        assertEquals(threadId, work.lease)
        assertEquals(listOf(threadId), fixture.worker.released)
        assertNotNull(fixture.record().threadContexts[threadId], "the thread keeps its specialist's conversation")
        // The start of work was raced with the phone and answered on the Mac.
        val opened = fixture.server.checkpoints.filterIsInstance<MessageCheckpoint.ApprovalOpened>().single().approval
        assertEquals(ApprovalKind.START_OF_WORK, opened.kind)
        assertEquals(threadId, opened.threadId)
        assertEquals(listOf(opened.id to ApprovalOption.CONTINUE), fixture.server.answers, "answered through approval.closed")
    }

    @Test fun stageConsentIsPersistedWithTheActualDialogAndSurvivesStoreReload() {
        val fixture = Fixture(brain("Implementer" to MemberMode.TALK, workflow = true, askBeforeStarting = true))
        val step = fixture.document.configuration.workflow!!.steps.first()
        fixture.decide = { turn ->
            when {
                "enter-workflow" in fixture.allowed(turn) -> """{"kind":"enter-workflow","entryStepId":"${step.id}","inputIds":[],"skipped":[],"reason":"Start"}"""
                "start-step" in fixture.allowed(turn) -> """{"kind":"start-step","stepId":"${step.id}","inputIds":[]}"""
                "step-result" in fixture.allowed(turn) -> {
                    val attempt = fixture.request(turn).getValue("attempts").jsonArray.last().jsonObject.getValue("id").jsonPrimitive.content
                    """{"kind":"step-result","attemptId":"$attempt","outcome":"ready","explanation":"Done"}"""
                }
                else -> "{\"kind\":\"answer\"}\nDone."
            }
        }
        assertEquals(MessageState.ANSWERED, fixture.run(fixture.claim("Run workflow").copy(workflowEvidence = true)))
        val opened = fixture.server.checkpoints.filterIsInstance<MessageCheckpoint.ApprovalOpened>().single { it.approval.kind == ApprovalKind.STAGE }.approval
        val restored = ConversationStore(fixture.root, fixture.account).read(fixture.talk.id).brain.run!!
        val consent = restored.stageConsents.values.single()
        val evidence = fixture.server.checkpoints.filterIsInstance<MessageCheckpoint.WorkflowEvidenceStored>()
        assertEquals(listOf("PENDING", "ANSWERED"), evidence.map { it.evidence.state })
        assertTrue(evidence.all { it.evidence.approvalId == opened.id })
        assertEquals(ApprovalOption.CONTINUE, evidence.last().evidence.option)
        assertEquals(opened.id, consent.approvalId)
        assertEquals(ApprovalOption.CONTINUE, consent.option)
        assertEquals(restored.attempts.single().id, consent.attemptId)
        assertEquals(BrainRunState.COMPLETED, restored.summary.state)
    }

    @Test fun anInvalidReplyIsRepairedOnceThenWaitsForThePerson() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.decide = { "Sure! I will get right on it." }
        assertEquals(MessageState.ANSWERED, fixture.send("Add sign-in"))
        val turns = fixture.coordinatorTurns()
        assertEquals(2, turns.size)
        assertTrue(turns.last().text.endsWith(REPLY_REPAIR))
        assertEquals(emptyList(), fixture.worker.requests)
        assertEquals(emptyList(), fixture.server.deltas, "an invalid header never streams")
        assertEquals(BrainRunState.AWAITING_INPUT, fixture.record().brain.run?.summary?.state)
        assertEquals("The coordinator returned an invalid decision, so no specialist was started. Reply to try again.",
            fixture.server.appended().last { it.author == MessageAuthor.SYSTEM }.text)
    }

    @Test fun aWorkflowReturnRunsAgainInTheStepsThread() {
        val fixture = Fixture(brain("Writer" to MemberMode.TALK, "Reviewer" to MemberMode.TALK, workflow = true))
        val steps = fixture.document.configuration.workflow!!.steps
        var reviews = 0
        fixture.decide = { turn ->
            val request = fixture.request(turn)
            val allowed = fixture.allowed(turn)
            val last = request.getValue("attempts").jsonArray.lastOrNull()?.jsonObject
            when {
                "enter-workflow" in allowed -> """{"kind":"enter-workflow","entryStepId":"${steps[0].id}","inputIds":[],"skipped":[],"reason":"Start."}"""
                "step-result" in allowed -> {
                    val stepId = last!!.getValue("stepId").jsonPrimitive.content
                    val outcome = if (stepId == steps[1].id && reviews++ == 0) "changes-needed" else "ready"
                    """{"kind":"step-result","attemptId":"${last.getValue("id").jsonPrimitive.content}","outcome":"$outcome","explanation":"Checked."}"""
                }
                "start-step" in allowed -> """{"kind":"start-step","stepId":${request.getValue("nextStepId")},"inputIds":[]}"""
                else -> "{\"kind\":\"answer\"}\nShipped."
            }
        }
        assertEquals(MessageState.ANSWERED, fixture.send("Write the release notes"))
        val record = fixture.record()
        val attempts = record.brain.run!!.attempts
        assertEquals(listOf(steps[0].id, steps[1].id, steps[0].id, steps[1].id), attempts.map { it.stepId })
        assertEquals(2, record.threads.size, "returns stay in their step's thread")
        assertEquals(attempts[0].id, record.attemptThreads[attempts[2].id])
        assertEquals(attempts[1].id, record.attemptThreads[attempts[3].id])
        val specialistTurns = fixture.chat.requests.filterNot(Fixture::isCoordinator)
        assertEquals(4, specialistTurns.size)
        assertNull(specialistTurns[0].conversation)
        assertNotNull(specialistTurns[2].conversation, "a return resumes the thread's own conversation")
        assertEquals(setOf(attempts[0].id, attempts[1].id), record.threadContexts.keys)
        assertEquals("Shipped.", fixture.server.appended().last().text)
    }

    @Test fun aThreadReplyForwardedReopensTheTaskInItsThread() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.send("Add sign-in")
        val threadId = fixture.server.threads().last().id
        val before = fixture.server.threads().last().revision
        fixture.decide = { turn ->
            assertEquals(listOf("answer", "delegate", "forward"), fixture.allowed(turn).sorted())
            assertEquals("Use the blue button", fixture.request(turn).getValue("reply").jsonPrimitive.content)
            """{"kind":"forward","threadId":"$threadId","note":"Match the design docs"}"""
        }
        fixture.worker.reply = { "Changed the button to blue." }
        assertEquals(MessageState.ANSWERED, fixture.send("Use the blue button", threadId))
        val notes = fixture.server.appended().filter { it.note != null }
        assertEquals(MessageAppend(MessageAuthor.COORDINATOR, notes.single().id, CoordinatorNote(NoteKind.FORWARDED, threadId),
            "Forwarded to Implementer: Match the design docs", threadId), notes.single())
        val turn = fixture.worker.requests.last()
        assertNotNull(turn.conversation, "the thread's specialist continues its own conversation")
        assertTrue("Use the blue button" in turn.text && "Match the design docs" in turn.text)
        assertEquals(threadId, turn.work!!.lease)
        assertEquals("Changed the button to blue.", fixture.server.appended().last().text)
        assertEquals(threadId, fixture.server.appended().last().threadId)
        val thread = fixture.server.threads().last()
        assertEquals(BrainAttemptState.COMPLETED, thread.state)
        assertTrue(thread.revision > before)
        assertEquals(2, thread.outputs.size)
    }

    @Test fun aThreadReplyAnsweredStaysInTheThread() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.send("Add sign-in")
        val threadId = fixture.server.threads().last().id
        fixture.decide = { "{\"kind\":\"answer\"}\nIt already uses the blue button." }
        val workerTurns = fixture.worker.requests.size
        assertEquals(MessageState.ANSWERED, fixture.send("Is the button blue?", threadId))
        val answer = fixture.server.appended().last()
        assertEquals(MessageAppend(MessageAuthor.COORDINATOR, answer.id, CoordinatorNote(NoteKind.ANSWERED, threadId),
            "It already uses the blue button.", threadId), answer)
        assertEquals("It already uses the blue button.", fixture.server.deltas.filter { it.messageId == answer.id }.joinToString("") { it.text })
        assertTrue(fixture.server.deltas.filter { it.messageId == answer.id }.all { it.threadId == threadId })
        assertEquals(workerTurns, fixture.worker.requests.size)
    }

    @Test fun aThreadReplyDelegatedStartsANewThread() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK, "Reviewer" to MemberMode.TALK))
        fixture.delegateThenAnswer()
        fixture.send("Add sign-in")
        val threadId = fixture.server.threads().last().id
        val reviewer = fixture.document.configuration.specialists[1]
        fixture.decide = { delegate(reviewer, "review the sign-in page") }
        assertEquals(MessageState.ANSWERED, fixture.send("Can someone review it?", threadId))
        val added = fixture.server.threads().filter { it.id != threadId }
        val newThread = added.first().id
        assertEquals("Reviewer · review the sign-in page", added.first().title)
        val announcement = fixture.server.appended().single { it.id == added.first().parentMessageId }
        assertEquals(null, announcement.threadId, "a new thread hangs under the main conversation")
        val note = fixture.server.appended().single { it.note?.kind == NoteKind.STARTED }
        assertEquals(CoordinatorNote(NoteKind.STARTED, newThread), note.note)
        assertEquals(threadId, note.threadId)
        assertEquals(BrainAttemptState.COMPLETED, added.last().state)
        val review = fixture.chat.requests.last()
        assertNull(review.conversation, "a separate task never shares context")
        assertTrue("review the sign-in page" in review.text)
        assertEquals(MessageAppend(MessageAuthor.SPECIALIST, fixture.server.appended().last().id, null, "Reviewed: looks good.", newThread),
            fixture.server.appended().last())
    }

    @Test fun aForwardToAnotherThreadIsRefused() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.send("Add sign-in")
        val threadId = fixture.server.threads().last().id
        fixture.decide = { """{"kind":"forward","threadId":"${id()}"}""" }
        val workerTurns = fixture.worker.requests.size
        assertEquals(MessageState.FAILED, fixture.send("Hmm", threadId))
        assertEquals(workerTurns, fixture.worker.requests.size)
        assertEquals(MessageAuthor.SYSTEM, fixture.server.appended().last().author)
        assertEquals(threadId, fixture.server.appended().last().threadId)
    }

    @Test fun stopEndsTheRunningSpecialistAndCancelsTheRun() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.worker.reply = { null }
        val cancel = CancelSignal()
        val state = CompletableFuture.supplyAsync { fixture.send("Add sign-in", cancel = cancel) }
        assertTrue(fixture.worker.started.await(5, TimeUnit.SECONDS))
        cancel.cancel()
        assertEquals(MessageState.STOPPED, state.get(5, TimeUnit.SECONDS))
        assertEquals(1, fixture.worker.stops.size)
        val threadId = fixture.server.threads().last().id
        assertEquals(listOf(threadId), fixture.worker.released)
        assertEquals(BrainRunState.CANCELLED, fixture.record().brain.run?.summary?.state)
        assertEquals(BrainAttemptState.INTERRUPTED, fixture.server.threads().last().state, "work that may have changed files is interrupted")
    }

    @Test fun quittingPausesTheRunForReviewInsteadOfStoppingIt() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.worker.reply = { null }
        val cancel = CancelSignal()
        val messageId = id()
        val state = CompletableFuture.supplyAsync { fixture.send("Add sign-in", messageId = messageId, cancel = cancel, closing = { cancel.isCancelled }) }
        assertTrue(fixture.worker.started.await(5, TimeUnit.SECONDS))
        cancel.cancel()
        assertEquals(MessageState.RECONCILIATION_REQUIRED, state.get(5, TimeUnit.SECONDS))
        assertEquals(BrainRunState.PAUSED, fixture.record().brain.run?.summary?.state, "the person's retry resumes it")
        assertEquals(BrainRunState.PAUSED, fixture.server.checkpoints.filterIsInstance<MessageCheckpoint.RunChanged>().last().run.state,
            "the paused run is published before Hovel quits")
        assertEquals(BrainAttemptState.INTERRUPTED, fixture.server.threads().last().state)
        fixture.worker.reply = { "Implemented sign-in." }
        assertEquals(MessageState.ANSWERED, fixture.send("Add sign-in", messageId = messageId))
    }

    @Test fun aLostCoordinatorConversationIsForgottenSoTheNextMessageStartsFresh() {
        val fixture = Fixture(brain())
        fixture.chat.reply = { "Hello." }
        fixture.send("Hi")
        assertEquals(1, fixture.record().brain.coordinatorContexts.size)
        fixture.chat.failure = ChatFailure.ConversationLost
        assertEquals(MessageState.FAILED, fixture.send("Still there?"))
        assertEquals(emptyMap(), fixture.record().brain.coordinatorContexts)
        assertEquals(MessageState.ANSWERED, fixture.send("Hello again"))
        assertNull(fixture.chat.requests.last().conversation)
    }

    @Test fun aRepairedReplyStreamsAsAReplyOfItsOwn() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        var turns = 0
        fixture.decide = { if (turns++ == 0) "{\"kind\":\"answer\"}\n" + "x".repeat(CompanionLimits.MESSAGE + 1) else "{\"kind\":\"answer\"}\nShort." }
        assertEquals(MessageState.ANSWERED, fixture.send("Hi"))
        val answer = fixture.server.appended().last()
        assertEquals("Short.", answer.text)
        assertEquals("Short.", fixture.server.deltas.filter { it.messageId == answer.id }.joinToString("") { it.text })
        assertEquals(2, fixture.server.deltas.map { it.messageId }.distinct().size)
    }

    @Test fun aRetriedMessageReviewsTheRunAndRerunsItsThread() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.worker.failure = ChatFailure.Temporary
        val messageId = id()
        assertEquals(MessageState.FAILED, fixture.send("Add sign-in", messageId = messageId))
        assertEquals(BrainRunState.PAUSED, fixture.record().brain.run?.summary?.state)
        val threadId = fixture.server.threads().last().id
        assertEquals(BrainAttemptState.INTERRUPTED, fixture.server.threads().last().state)
        assertTrue(fixture.server.appended().last().text.startsWith("Work paused for Implementer."))
        // The person retries the same message: the run is reviewed natively, and its task runs again in its thread.
        assertEquals(MessageState.ANSWERED, fixture.send("Add sign-in", messageId = messageId))
        val review = fixture.server.checkpoints.filterIsInstance<MessageCheckpoint.ApprovalOpened>().single { it.approval.kind == ApprovalKind.RESUME_REVIEW }
        assertEquals(listOf(ApprovalOption.CONTINUE, ApprovalOption.NOT_NOW), review.approval.options)
        val record = fixture.record()
        assertEquals(2, record.brain.run!!.attempts.size)
        assertEquals(setOf(threadId), record.attemptThreads.values.toSet())
        assertEquals(1, record.threads.size)
        assertEquals(BrainAttemptState.COMPLETED, fixture.server.threads().last().state)
        assertEquals(1, record.brain.messages.count { it.author == BrainAuthor.USER }, "a retry is not a second message")
    }

    @Test fun aDeclinedReviewKeepsTheRunPaused() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.worker.failure = ChatFailure.Temporary
        val messageId = id()
        fixture.send("Add sign-in", messageId = messageId)
        fixture.screen.answer = { dialog -> if (dialog.title == "Resume companion work") 1 else 0 }
        val turns = fixture.worker.requests.size
        assertEquals(MessageState.FAILED, fixture.send("Add sign-in", messageId = messageId))
        assertEquals(turns, fixture.worker.requests.size)
        assertEquals(BrainRunState.PAUSED, fixture.record().brain.run?.summary?.state)
    }

    @Test fun aDeclinedStartOfWorkWaitsForThePerson() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.screen.answer = { 1 }
        assertEquals(MessageState.ANSWERED, fixture.send("Add sign-in"))
        assertEquals(emptyList(), fixture.worker.requests)
        assertEquals(BrainRunState.AWAITING_INPUT, fixture.record().brain.run?.summary?.state)
        assertEquals(BrainAttemptState.BLOCKED, fixture.server.threads().last().state)
        assertEquals("Project access was not approved, so Implementer has not started.", fixture.server.appended().last().text)
    }

    @Test fun aWorkingSpecialistNeedsAWorkingFolder() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK), workingFolder = false)
        fixture.delegateThenAnswer()
        assertEquals(MessageState.FAILED, fixture.send("Add sign-in"))
        assertEquals(emptyList(), fixture.worker.requests)
        assertTrue("missing-project" in fixture.server.appended().last().text)
    }

    @Test fun contextFoldersReachOnlyARuntimeThatKeepsThemReadOnly() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK), context = true)
        fixture.delegateThenAnswer()
        assertEquals(emptyList(), fixture.engine.readableBy())
        fixture.send("Add sign-in")
        assertEquals(ProjectFolders(fixture.working), fixture.worker.requests.last().work!!.folders)
        fixture.worker.support = ContextFolderSupport.VERIFIED
        assertEquals(listOf(RuntimeId.CODEX), fixture.engine.readableBy())
        fixture.send("Add sign-out")
        assertEquals(ProjectFolders(fixture.working, listOf(fixture.docs)), fixture.worker.requests.last().work!!.folders)
    }

    @Test fun theCoordinatorReadsRecalledMemoryAndTheConversationSoFar() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.decide = { "{\"kind\":\"answer\"}\nNoted." }
        val history = listOf(
            ConversationMessage(MessageAuthor.USER, null, fixture.talk.id, AT, null, null, id(), emptyList(), null, 3, MessageState.ANSWERED, "Earlier question", null),
            ConversationMessage(MessageAuthor.COORDINATOR, null, fixture.talk.id, AT, null, null, id(), emptyList(), null, 1, null, "Earlier answer", null))
        fixture.send("Remember the colors", history = history, memory = "Prefers blue")
        val request = fixture.request(fixture.coordinatorTurns().single())
        assertEquals("Prefers blue", request.getValue("recalledMemory").jsonPrimitive.content)
        assertEquals(listOf("Earlier question", "Earlier answer", "Remember the colors"),
            request.getValue("conversation").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content })
    }

    @Test fun aCheckpointTheServerCouldNotTakeIsSentFirstByTheNextRun() {
        val fixture = Fixture(brain())
        fixture.chat.reply = { "First answer." }
        val first = fixture.claim("Hi")
        fixture.server.offline = true
        assertEquals(MessageState.ANSWERED, fixture.run(first))
        assertEquals(emptyList(), fixture.server.appended())
        assertEquals(1, fixture.record().outbox.size, "the reply waits in the outbox")
        fixture.server.offline = false
        fixture.chat.reply = { "Second answer." }
        assertEquals(MessageState.ANSWERED, fixture.send("And then?"))
        assertEquals(listOf("First answer.", "Second answer."), fixture.server.appended().map { it.text })
        assertEquals(emptyList(), fixture.record().outbox)
    }

    @Test fun aRefusedCheckpointEndsTheClaimAndIsDropped() {
        val fixture = Fixture(brain())
        fixture.chat.reply = { "Hello." }
        val claim = fixture.claim("Hi")
        fixture.server.refuseCheckpoints = true
        assertFailsWith<org.formulagen.wrapper.hovel.HovelRejectedException> { fixture.run(claim) }
        assertEquals(emptyList(), fixture.record().outbox, "a refused checkpoint is never sent again")
    }

    @Test fun aTextInputIsReadWholeAndAnUnreadableOneSaysItIsAPreview() {
        val fixture = Fixture(brain("Implementer" to MemberMode.TALK))
        fixture.decide = { "{\"kind\":\"answer\"}\nRead it." }
        val whole = "Spec line. ".repeat(250)
        val input = ArtifactRef(id(), "text/markdown", whole.take(100), "spec.md")
        fixture.server.texts[input.id] = whole
        assertEquals(MessageState.ANSWERED, fixture.run(fixture.claim("Read the spec", inputs = listOf(input))))
        val stored = fixture.record().brain.run!!.artifacts.single { it.id == input.id }
        assertEquals(whole, fixture.store.bytes(fixture.talk.id, stored).decodeToString(), "every page of the text, not the preview")
        val other = ArtifactRef(id(), "text/plain", "Only the start", "notes.txt")
        fixture.server.textsReadable = false
        assertEquals(MessageState.ANSWERED, fixture.run(fixture.claim("And these notes", inputs = listOf(other))))
        val preview = fixture.record().brain.run!!.artifacts.single { it.id == other.id }
        assertEquals(previewOnly("Only the start"), fixture.store.bytes(fixture.talk.id, preview).decodeToString())
        assertTrue("could not read this input's whole text" in fixture.coordinatorTurns().last().text, "the turn says it has only the preview")
    }

    @Test fun aThreadReplyBuildsOnTheNewerOfTheLocalAndTheServersThread() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.send("Add sign-in")
        val local = fixture.server.threads().last()
        val newer = local.copy(revision = local.revision + 10, title = "Implementer · renamed elsewhere")
        fixture.decide = { """{"kind":"forward","threadId":"${local.id}"}""" }
        val before = fixture.server.threads().size
        assertEquals(MessageState.ANSWERED, fixture.run(fixture.claim("Go on", local.id, thread = newer)))
        val upserts = fixture.server.threads().drop(before).filter { it.id == local.id }
        assertTrue(upserts.isNotEmpty() && upserts.all { it.revision > newer.revision }, "every upsert is newer than the server's")
        assertEquals("Implementer · renamed elsewhere", upserts.last().title)
    }

    @Test fun aThreadLeftRunningByACrashIsInterruptedByTheNextMessage() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        fixture.send("Add sign-in")
        val threadId = fixture.server.threads().last().id
        // A crash mid-turn: the record still says the thread runs, though nothing does.
        val crashed = fixture.record().let { record ->
            record.copy(threads = record.threads.mapValues { it.value.copy(state = BrainAttemptState.RUNNING) })
        }
        fixture.store.write(fixture.talk.id, crashed)
        fixture.decide = { "{\"kind\":\"answer\"}\nNothing is running now." }
        assertEquals(MessageState.ANSWERED, fixture.send("Status?"))
        val recovered = fixture.server.threads().last { it.id == threadId }
        assertEquals(BrainAttemptState.INTERRUPTED, recovered.state)
        assertEquals(crashed.threads.getValue(threadId).revision + 1, recovered.revision)
        assertEquals(BrainAttemptState.INTERRUPTED, fixture.record().threads.getValue(threadId).state)
    }

    @Test fun aStopTheServerReportsMidRunStopsTheRunAsThePersonsStopDoes() {
        val fixture = Fixture(brain("Implementer" to MemberMode.WORK))
        fixture.delegateThenAnswer()
        val messageId = id()
        // The stop.requested event was lost: the server refuses the next step with `stop-requested` instead.
        fixture.worker.reply = { fixture.server.stopRequested += messageId; "Implemented sign-in." }
        assertEquals(MessageState.STOPPED, fixture.send("Add sign-in", messageId = messageId))
        assertEquals(BrainRunState.CANCELLED, fixture.record().brain.run?.summary?.state, "cancelled, not left for review")
        assertEquals(emptyList(), fixture.server.checkpoints.filterIsInstance<MessageCheckpoint.ArtifactStored>(), "nothing new is stored")
    }

    @Test fun aCoordinatorWithNoProviderSaysToAssignOneInsteadOfToRetry() {
        // A new companion's brain: the coordinator has no binding yet, with or without specialists.
        for (document in listOf(brain(), brain("Implementer" to MemberMode.WORK))) {
            val configuration = document.configuration
            val fixture = Fixture(document.copy(configuration = configuration.copy(coordinator = configuration.coordinator.copy(binding = null))))
            assertEquals(MessageState.FAILED, fixture.send("Hi"))
            assertEquals("Assign a provider to the coordinator in Configure brain.",
                fixture.server.appended().last { it.author == MessageAuthor.SYSTEM }.text)
            assertEquals(emptyList(), fixture.chat.requests, "no provider turn starts")
        }
    }

    @Test fun aCoordinatorOnARuntimeThisMacDoesNotRunSaysToChooseAnother() {
        // This Mac runs Codex only; the coordinator is bound to Claude Code.
        for (document in listOf(brain(), brain("Implementer" to MemberMode.WORK))) {
            val configuration = document.configuration
            val claude = configuration.coordinator.copy(binding = BrainBinding(RuntimeId.CLAUDE_CODE, SharedRules.RUNTIME_DEFAULT_MODEL))
            val fixture = Fixture(document.copy(configuration = configuration.copy(coordinator = claude)))
            assertEquals(MessageState.FAILED, fixture.send("Hi"))
            assertEquals("Claude doesn't run on this Mac. Choose another provider for the coordinator in Configure brain.",
                fixture.server.appended().last { it.author == MessageAuthor.SYSTEM }.text)
            assertEquals(emptyList(), fixture.chat.requests, "no provider turn starts")
        }
    }

    @Test fun aThreadReplyDelegatedToASpecialistWithNoProviderSaysToAssignOne() {
        val document = brain("Implementer" to MemberMode.WORK, "Reviewer" to MemberMode.TALK)
        val configuration = document.configuration
        val fixture = Fixture(document.copy(configuration = configuration.copy(
            specialists = configuration.specialists.map { if (it.name == "Reviewer") it.copy(binding = null) else it })))
        fixture.delegateThenAnswer()
        fixture.send("Add sign-in")
        val threadId = fixture.server.threads().last().id
        val reviewer = fixture.document.configuration.specialists[1]
        fixture.decide = { delegate(reviewer, "review the sign-in page") }
        assertEquals(MessageState.FAILED, fixture.send("Can someone review it?", threadId))
        assertEquals("Assign a provider to Reviewer in Configure brain.", fixture.server.appended().last { it.author == MessageAuthor.SYSTEM }.text)
        assertEquals(listOf(threadId), fixture.server.threads().map { it.id }.distinct(), "no thread opens for it")
    }

    @Test fun specialistAdviceIsVisibleButTheCoordinatorCanIgnoreIt() {
        val base = brain("Reviewer" to MemberMode.TALK)
        val fixture = Fixture(base.copy(configuration = base.configuration.copy(specialistAdvice = true)))
        val claim = fixture.claim("Review this code")
        val hint = SpecialistAdvice(fixture.implementer.id, id(), claim.message.id, claim.brain.revision, 0.95, "jev-1.14.0", System.currentTimeMillis() + 20_000)
        assertEquals(MessageState.ANSWERED, fixture.run(claim.copy(specialistAdvice = hint)))
        assertTrue(fixture.coordinatorTurns().single().text.contains("specialistRecommendation"))
        assertTrue(fixture.server.appended().any { it.author == MessageAuthor.SYSTEM && it.text.contains("Jev recommends") })
        assertTrue(fixture.server.appended().any { it.text.contains("differs from the recommendation") })
        assertTrue(fixture.server.threads().isEmpty(), "advice must not dispatch a specialist")
        assertNull(validSpecialistAdvice(claim.copy(specialistAdvice = hint.copy(messageId = id()))))
        assertNull(validSpecialistAdvice(claim.copy(specialistAdvice = hint.copy(configurationRevision = claim.brain.revision + 1))))
        assertNull(validSpecialistAdvice(claim.copy(specialistAdvice = hint.copy(expiresAt = 1))))
        assertNull(validSpecialistAdvice(claim.copy(specialistAdvice = hint.copy(roleId = id()))))
        assertNull(validSpecialistAdvice(claim.copy(specialistAdvice = hint, brain = base)))
    }

    @Test fun anUnexpectedErrorStillSaysToTryAgain() {
        val fixture = Fixture(brain())
        fixture.chat.reply = { error("a bug in this build") }
        assertEquals(MessageState.FAILED, fixture.send("Hi"))
        assertEquals("Hovel could not finish this message on this Mac. Try sending it again.",
            fixture.server.appended().last { it.author == MessageAuthor.SYSTEM }.text)
    }

    @Test fun aSignedOutRuntimeFailsWithItsProvidersReason() {
        val fixture = Fixture(brain())
        fixture.chat.failure = ChatFailure.SignedOut
        assertEquals(MessageState.FAILED, fixture.send("Hi"))
        assertEquals(MessageAuthor.SYSTEM, fixture.server.appended().single().author)
    }
}
