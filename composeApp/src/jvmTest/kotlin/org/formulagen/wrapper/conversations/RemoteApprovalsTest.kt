package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*

class RemoteApprovalsTest {
    private val server = FakeServer()
    private val talk = TestConversation()
    private val claim = run {
        server.queue(talk.id, Queued(talk.message("Go")) { claimId, message -> talk.claim(claimId, message, brain()) })
        (server.claim(ClaimRequest(id(), talk.id)) as ClaimResponse.Claimed).claim
    }
    private val output = ClaimOutput(server, claim.claimId, claim.message.id, attempts = 1)
    private val working = Files.createDirectories(folder("approvals").resolve("hovel-notes"))
    private val folders = listOf(LocalFolder(id(), "hovel-notes", working, FolderRole.WORKING))
    private val thread = id()
    private val queued = java.util.concurrent.CopyOnWriteArrayList<MessageCheckpoint>()
    private val scope = ApprovalScope(output, talk.id, thread, folders, "Ember") { queued += it }
    private val screen = Screen { null }
    private val clock = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC)
    private val approvals = RemoteApprovals(NativeDialogs(screen), server, server.hostId, clock)
    private val member = TeamMember(RuntimeId.CLAUDE_CODE, "claude-sonnet")

    private fun request(lease: String? = thread, label: String? = null) =
        ApprovalRequest("Ember", member, working, ApprovalSubject.Edit("Edit", listOf(working.resolve("src/app.ts")), "+ hi"), label, lease)

    private fun asking(request: ApprovalRequest = request()): CompletableFuture<ApprovalDecision> {
        val cancel = CancelSignal()
        return CompletableFuture.supplyAsync { approvals.ask(request, cancel) {} }
    }

    private fun opened(): AgentApproval {
        eventually("an approval opened") { server.approvals.isNotEmpty() && screen.shown.isNotEmpty() }
        return server.approvals.values.single()
    }

    @Test fun aMacAnswerIsPostedAndWins() {
        approvals.register(thread, scope).use {
            val decision = asking()
            val approval = opened()
            assertEquals(ApprovalKind.PERMISSION, approval.kind)
            assertEquals(listOf(ApprovalOption.ALLOW_ONCE, ApprovalOption.DENY), approval.options)
            assertEquals(ApprovalPresentation.Anywhere(listOf("Edit ./src/app.ts")), approval.presentation)
            assertEquals(thread, approval.threadId)
            assertEquals(server.hostId, approval.computerId)
            assertEquals("2026-10-29T12:30:00Z", approval.expiresAt, "past the Mac's dialog, with room for dialogs before it")
            screen.open.single()(0)
            assertEquals(ApprovalDecision.ALLOW_ONCE, decision.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(approval.id to ApprovalOption.ALLOW_ONCE), server.answers, "recorded as the approval's close, fenced by the claim")
            assertEquals(ApprovalReceipt(approval.id, ApprovalKind.PERMISSION, ApprovalOption.ALLOW_ONCE, ApprovalSurface.MAC), approvals.receipt(approval.id))
            assertEquals(emptySet(), approvals.openIds)
        }
    }

    @Test fun aPhoneAnswerClosesTheMacDialog() {
        approvals.register(thread, scope).use {
            val decision = asking()
            val approval = opened()
            assertTrue(server.phoneAnswers(approval.id, ApprovalOption.ALLOW_ONCE))
            approvals.answered(approval.id)
            assertEquals(ApprovalDecision.ALLOW_ONCE, decision.get(5, TimeUnit.SECONDS))
            eventually("the dialog closed") { screen.dismissed.isNotEmpty() }
            assertEquals(emptyList(), server.answers, "the Mac posts nothing")
            assertEquals(ApprovalSurface.PHONE, approvals.receipt(approval.id)?.by)
            // The Mac's own click after that changes nothing.
            screen.open.single()(1)
            assertEquals(ApprovalDecision.ALLOW_ONCE, decision.get())
        }
    }

    @Test fun aPhoneAnswerThatReachedTheServerFirstWinsOverALaterMacClick() {
        approvals.register(thread, scope).use {
            val decision = asking()
            val approval = opened()
            server.phoneAnswers(approval.id, ApprovalOption.DENY)
            screen.open.single()(0)
            assertEquals(ApprovalDecision.DENY, decision.get(5, TimeUnit.SECONDS), "the stored answer applies")
            assertEquals(ApprovalReceipt(approval.id, ApprovalKind.PERMISSION, ApprovalOption.DENY, ApprovalSurface.PHONE), approvals.receipt(approval.id))
        }
    }

    @Test fun aPhoneAnswerTheMacMissedWhileReconnectingClosesItsDialogOnReconnect() {
        approvals.register(thread, scope).use {
            val decision = asking()
            val approval = opened()
            // the stream is down: the server holds the phone's answer, and no approval.answered reaches the Mac
            assertTrue(server.phoneAnswers(approval.id, ApprovalOption.DENY))
            assertFalse(decision.isDone)
            approvals.catchUp()
            assertEquals(ApprovalDecision.DENY, decision.get(5, TimeUnit.SECONDS))
            eventually("the dialog closed") { screen.dismissed.isNotEmpty() }
            assertEquals(ApprovalSurface.PHONE, approvals.receipt(approval.id)?.by)
            assertEquals(emptyList(), server.answers)
        }
    }

    @Test fun aWithdrawalRefusedBecauseThePhoneAnsweredAppliesThePhonesAnswer() {
        approvals.register(thread, scope).use {
            val cancel = CancelSignal()
            val decision = CompletableFuture.supplyAsync { approvals.ask(request(), cancel) {} }
            val approval = opened()
            server.phoneAnswers(approval.id, ApprovalOption.ALLOW_ONCE)
            // the work stops before the Mac heard of it: its withdrawal is refused, and the stored answer applies
            cancel.cancel()
            assertEquals(ApprovalDecision.ALLOW_ONCE, decision.get(5, TimeUnit.SECONDS))
            assertEquals(ApprovalReceipt(approval.id, ApprovalKind.PERMISSION, ApprovalOption.ALLOW_ONCE, ApprovalSurface.PHONE), approvals.receipt(approval.id))
            assertEquals(emptyList(), queued, "nothing waits to be sent")
        }
    }

    @Test fun aMacAllowGivenOfflineThatLosesToThePhonesDenyIsRecordedAsAConflict() {
        approvals.register(thread, scope).use {
            val decision = asking()
            val approval = opened()
            server.offline = true
            screen.open.single()(0)
            assertEquals(ApprovalDecision.ALLOW_ONCE, decision.get(5, TimeUnit.SECONDS), "the person's own answer runs")
            val waiting = queued.single() as MessageCheckpoint.ApprovalClosed
            assertEquals(ApprovalOutcome.Answered(ApprovalOption.ALLOW_ONCE), waiting.outcome)
            // the phone's Deny reached the server first; the queued close is then refused
            server.offline = false
            server.phoneAnswers(approval.id, ApprovalOption.DENY)
            assertFailsWith<org.formulagen.wrapper.hovel.HovelRejectedException> { output.once(waiting) }
            val conflict = assertNotNull(approvals.conflict(waiting))
            assertEquals(thread, conflict.threadId)
            assertTrue("chose “Allow once”" in conflict.text && "answered “Deny”" in conflict.text, conflict.text)
            assertTrue("went on with its own" in conflict.text, conflict.text)
            // after a restart nothing is in memory: the outbox's own close still says what ran here
            val restarted = RemoteApprovals(NativeDialogs(screen), server, server.hostId, clock)
            assertNull(restarted.receipt(approval.id))
            assertEquals(conflict, restarted.conflict(waiting))
            // when the record agrees with what ran, there is nothing to say
            server.approvals[approval.id] = server.approvals.getValue(approval.id).copy(
                answer = ApprovalAnswerRecord(ApprovalSurface.PHONE, id(), ApprovalOption.ALLOW_ONCE))
            assertNull(approvals.conflict(waiting))
        }
    }

    @Test fun aConflictNoteUsesTheMacsOwnButtonsAndSaysWhenItWentOnWithNone() {
        val restarted = RemoteApprovals(NativeDialogs(screen), server, server.hostId, clock)
        val approval = AgentApproval(ApprovalAnswerRecord(ApprovalSurface.PHONE, id(), ApprovalOption.CONTINUE), server.hostId,
            talk.id, "2026-10-29T12:10:00.000Z", id(), ApprovalKind.START_OF_WORK, listOf(ApprovalOption.CONTINUE, ApprovalOption.NOT_NOW),
            ApprovalPresentation.Anywhere(listOf("Start work")), 1, ApprovalState.ANSWERED, thread)
        server.approvals[approval.id] = approval
        val chose = restarted.conflict(MessageCheckpoint.ApprovalClosed(approval.id, ApprovalOutcome.Answered(ApprovalOption.NOT_NOW)))
        assertTrue("chose “Not now”" in assertNotNull(chose).text && "answered “Start working”" in chose.text, chose.text)
        val none = restarted.conflict(MessageCheckpoint.ApprovalClosed(approval.id, ApprovalOutcome.Withdrawn))
        assertTrue("went on with no answer" in assertNotNull(none).text, none.text)
    }

    @Test fun theMacsLabelsAreItsDialogsButtons() {
        val permission = org.formulagen.wrapper.approvals.approvalDialog(request()).buttons.map { it.first }
        assertTrue(MacApprovalLabels.of(ApprovalKind.PERMISSION, ApprovalOption.ALLOW_ONCE) in permission, permission.toString())
        assertTrue(MacApprovalLabels.of(ApprovalKind.PERMISSION, ApprovalOption.DENY) in permission, permission.toString())
        val start = org.formulagen.wrapper.approvals.startOfWorkDialog("Ember", working, member).buttons.map { it.first }
        assertEquals(listOf(ApprovalOption.CONTINUE, ApprovalOption.NOT_NOW).map { MacApprovalLabels.of(ApprovalKind.START_OF_WORK, it) }, start)
        // a stage and the resume review, built as the engine builds them (CoordinatorEngine's option order)
        val document = brain("Implementer" to org.formulagen.wrapper.bridge.MemberMode.WORK, workflow = true)
        val specialist = document.configuration.specialists.single()
        val role = BrainRoleSnapshot(specialist.id, specialist.name, checkNotNull(specialist.binding), specialist.instructions, specialist.mode)
        val run = org.formulagen.wrapper.brain.RunRecord(BrainRun(id(), 1, 4, null, BrainRunState.RUNNING, null), document.configuration,
            null, "request", emptyList(), attempts = emptyList(), task = "add sign-in")
        val stage = org.formulagen.wrapper.brain.stageDialog(role, run, null, "").buttons.map { it.first }
        val stageOptions = listOf(ApprovalOption.CONTINUE, ApprovalOption.REQUEST_CHANGES, ApprovalOption.NOT_NOW, ApprovalOption.STOP)
        assertEquals(stageOptions.map { MacApprovalLabels.of(ApprovalKind.STAGE, it) }, stage)
        val resume = org.formulagen.wrapper.brain.resumeDialog(run, rerun = false, uncertain = false, target = null, limit = false)
            .buttons.map { it.first }
        assertEquals(listOf(ApprovalOption.CONTINUE, ApprovalOption.NOT_NOW).map { MacApprovalLabels.of(ApprovalKind.RESUME_REVIEW, it) }, resume)
    }

    @Test fun aMacAnswerToAnApprovalTheServerLetExpireStillStands() {
        approvals.register(thread, scope).use {
            val decision = asking()
            val approval = opened()
            server.approvals[approval.id] = approval.copy(state = ApprovalState.EXPIRED)
            screen.open.single()(0)
            assertEquals(ApprovalDecision.ALLOW_ONCE, decision.get(5, TimeUnit.SECONDS), "nobody else answered it")
            assertEquals(ApprovalSurface.MAC, approvals.receipt(approval.id)?.by)
        }
    }

    @Test fun aRefusedMacAnswerWhoseWinnerCannotBeReadKeepsThePersonsAnswer() {
        approvals.register(thread, scope).use {
            val decision = asking()
            val approval = opened()
            server.approvals[approval.id] = approval.copy(state = ApprovalState.EXPIRED)
            server.approvalsReadable = false
            screen.open.single()(0)
            assertEquals(ApprovalDecision.ALLOW_ONCE, decision.get(5, TimeUnit.SECONDS), "never the dialog's default in place of the person's answer")
        }
    }

    @Test fun aLateOrRepeatedAnswerChangesNothing() {
        approvals.register(thread, scope).use {
            val decision = asking()
            val approval = opened()
            screen.open.single()(1)
            assertEquals(ApprovalDecision.DENY, decision.get(5, TimeUnit.SECONDS))
            server.approvals[approval.id] = approval.copy(state = ApprovalState.ANSWERED,
                answer = ApprovalAnswerRecord(ApprovalSurface.PHONE, id(), ApprovalOption.ALLOW_ONCE))
            approvals.answered(approval.id)
            approvals.answered(approval.id)
            approvals.answered(id())
            assertEquals(ApprovalOption.DENY, approvals.receipt(approval.id)?.option)
            assertEquals(1, screen.shown.size)
        }
    }

    @Test fun anAnswerFromThisMacOnTheStreamIsNotAnotherSurfaces() {
        approvals.register(thread, scope).use {
            val decision = asking()
            val approval = opened()
            server.approvals[approval.id] = approval.copy(state = ApprovalState.ANSWERED, answer = ApprovalAnswerRecord(ApprovalSurface.MAC, id(), ApprovalOption.ALLOW_ONCE))
            approvals.answered(approval.id)
            assertFalse(decision.isDone)
            server.approvals[approval.id] = approval
            screen.open.single()(1)
            assertEquals(ApprovalDecision.DENY, decision.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun nobodyAnsweringWithdrawsTheApproval() {
        approvals.register(thread, scope).use {
            val cancel = CancelSignal()
            val decision = CompletableFuture.supplyAsync { approvals.ask(request(), cancel) {} }
            val approval = opened()
            cancel.cancel()
            assertEquals(ApprovalDecision.DENY, decision.get(5, TimeUnit.SECONDS))
            assertEquals(MessageCheckpoint.ApprovalClosed(approval.id, ApprovalOutcome.Withdrawn), server.checkpoints.last())
            assertEquals(ApprovalReceipt(approval.id, ApprovalKind.PERMISSION, null, null), approvals.receipt(approval.id))
        }
    }

    @Test fun anUnreachableServerLeavesTheMacAnswerStanding() {
        approvals.register(thread, scope).use {
            val decision = asking()
            opened()
            server.offline = true
            screen.open.single()(0)
            assertEquals(ApprovalDecision.ALLOW_ONCE, decision.get(5, TimeUnit.SECONDS))
            assertEquals(listOf<MessageCheckpoint>(MessageCheckpoint.ApprovalClosed(approvalId(), ApprovalOutcome.Answered(ApprovalOption.ALLOW_ONCE))),
                queued, "its close waits in the conversation's outbox")
        }
    }

    private fun approvalId() = server.approvals.keys.single()

    @Test fun anApprovalTheServerNeverGotIsTheMacsAlone() {
        approvals.register(thread, scope).use {
            server.offline = true
            val decision = asking()
            eventually { screen.shown.isNotEmpty() }
            server.offline = false
            screen.open.single()(0)
            assertEquals(ApprovalDecision.ALLOW_ONCE, decision.get(5, TimeUnit.SECONDS))
            assertEquals(emptyList(), server.answers, "nothing to answer on a server that never had it")
            assertEquals(emptyList(), server.checkpoints)
        }
    }

    @Test fun aGrantForTheConversationIsRecordedAsAnAllow() {
        approvals.register(thread, scope).use {
            val decision = asking(request(label = "Allow these edits for this conversation"))
            val approval = opened()
            screen.open.single()(1)
            assertEquals(ApprovalDecision.ALLOW_FOR_CONVERSATION, decision.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(approval.id to ApprovalOption.ALLOW_ONCE), server.answers)
        }
    }

    @Test fun aRequestFromAnotherLeaseIsTheMacDialogAlone() {
        val decision = asking(request(lease = "team-chat"))
        eventually { screen.shown.isNotEmpty() }
        screen.open.single()(0)
        assertEquals(ApprovalDecision.ALLOW_ONCE, decision.get(5, TimeUnit.SECONDS))
        assertEquals(emptyList(), server.checkpoints)
        val closed = approvals.register(thread, scope)
        closed.close()
        val after = asking()
        eventually { screen.shown.size == 2 }
        screen.open.last()(1)
        assertEquals(ApprovalDecision.DENY, after.get(5, TimeUnit.SECONDS))
        assertEquals(emptyList(), server.checkpoints, "a closed registration relays nothing")
    }

    @Test fun theEnginesOwnQuestionsRaceTheSameWay() {
        val dialog = NativeDialog("Approve Step 2", listOf("Workspace: hovel-notes"), listOf("Continue" to "go", "Request changes" to "changes",
            "Not now" to "later", "Stop" to "stop"), "later")
        val options = listOf(ApprovalOption.CONTINUE to "go", ApprovalOption.REQUEST_CHANGES to "changes", ApprovalOption.NOT_NOW to "later",
            ApprovalOption.STOP to "stop")
        val answer = CompletableFuture.supplyAsync { approvals.ask(scope, ApprovalKind.STAGE, dialog, options, CancelSignal()) }
        val approval = opened()
        assertEquals(ApprovalKind.STAGE, approval.kind)
        assertEquals(listOf(ApprovalOption.CONTINUE, ApprovalOption.REQUEST_CHANGES, ApprovalOption.NOT_NOW, ApprovalOption.STOP), approval.options)
        assertEquals(ApprovalPresentation.Anywhere(listOf("Approve Step 2", "Workspace: hovel-notes")), approval.presentation)
        server.phoneAnswers(approval.id, ApprovalOption.REQUEST_CHANGES)
        approvals.answered(approval.id)
        assertEquals("changes", answer.get(5, TimeUnit.SECONDS))
    }

    @Test fun aQuestionAnsweredElsewhereBeforeItsTurnIsNeverShown() {
        val dialogs = NativeDialogs(screen)
        val first = CompletableFuture.supplyAsync { dialogs.ask(NativeDialog("First", emptyList(), listOf("OK" to 1), 1), CancelSignal()) }
        eventually { screen.shown.size == 1 }
        val elsewhere = CancelSignal()
        val second = CompletableFuture.supplyAsync {
            dialogs.answer(NativeDialog("Second", emptyList(), listOf("OK" to 2), 2), CancelSignal(), elsewhere)
        }
        Thread.sleep(100)
        elsewhere.cancel()
        assertEquals(DialogOutcome.AnsweredElsewhere, second.get(5, TimeUnit.SECONDS))
        screen.open.single()(0)
        assertEquals(1, first.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("First"), screen.shown.map { it.title })
    }
}
