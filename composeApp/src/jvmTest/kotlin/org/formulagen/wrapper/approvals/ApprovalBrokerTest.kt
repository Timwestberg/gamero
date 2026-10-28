package org.formulagen.wrapper.approvals

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.bridge.TeamMember
import kotlin.test.*

class ApprovalBrokerTest {
    private val opus = TeamMember(RuntimeId.CLAUDE_CODE, "opus")
    private val folder = Path.of("/Users/player/Projects/game")

    /** A screen the test answers: every dialog shown, and whether it was taken down. */
    private class Screen : DialogPresenter {
        inner class Shown(val dialog: NativeDialog, val answer: (Int) -> Unit) { @Volatile var dismissed = false }
        val shown = CopyOnWriteArrayList<Shown>()
        override fun present(dialog: NativeDialog, answer: (Int) -> Unit): () -> Unit {
            val entry = Shown(dialog, answer); shown += entry
            return { entry.dismissed = true }
        }
        fun await(count: Int): Shown {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (shown.size < count) { check(System.nanoTime() < until) { "Dialog $count never shown" }; Thread.sleep(5) }
            return shown[count - 1]
        }
    }

    private fun edit(sessionLabel: String? = "Allow all edits in game for this conversation") =
        ApprovalRequest("Ember", opus, folder, ApprovalKind.EDIT, "Edit notes.txt", "+ hello from Ember", sessionLabel)

    private fun <T> async(work: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(work)

    @Test fun eachButtonIsItsDecisionAndClosingTheDialogDenies() {
        val screen = Screen()
        val broker = DialogApprovalBroker(NativeDialogs(screen))
        for ((button, decision) in listOf(0 to ApprovalDecision.ALLOW_ONCE, 1 to ApprovalDecision.ALLOW_FOR_CONVERSATION, 2 to ApprovalDecision.DENY)) {
            val asked = async { broker.ask(edit(), CancelSignal()) }
            val shown = screen.await(screen.shown.size + 1)
            assertFalse(shown.dismissed)
            shown.answer(button)
            assertEquals(decision, asked.get(5, TimeUnit.SECONDS))
            assertTrue(shown.dismissed)
        }
        val closed = async { broker.ask(edit(), CancelSignal()) }
        screen.await(4).let { it.answer(it.dialog.dismissIndex) }
        assertEquals(ApprovalDecision.DENY, closed.get(5, TimeUnit.SECONDS))
        // An answer that is not one of the buttons is no answer.
        val odd = async { broker.ask(edit(), CancelSignal()) }
        screen.await(5).answer(7)
        assertEquals(ApprovalDecision.DENY, odd.get(5, TimeUnit.SECONDS))
    }

    @Test fun theDialogNamesWhatWhoAndWhereAndOffersTheConversationGrantOnlyWhenThereIsOne() {
        val dialog = approvalDialog(edit())
        assertEquals("Ember wants to edit files", dialog.title)
        assertEquals(listOf("Edit notes.txt", "+ hello from Ember", "Member: Claude · Opus", "Project folder: /Users/player/Projects/game"), dialog.lines)
        assertEquals(listOf("Allow once", "Allow all edits in game for this conversation", "Deny"), dialog.buttons)
        assertEquals(2, dialog.dismissIndex)
        val command = approvalDialog(ApprovalRequest("Ember", TeamMember(RuntimeId.CODEX, RUNTIME_DEFAULT_MODEL), folder, ApprovalKind.COMMAND,
            "npm test", "", null))
        assertEquals("Ember wants to run a command", command.title)
        assertEquals(listOf("npm test", "Member: Codex · default model", "Project folder: /Users/player/Projects/game"), command.lines)
        assertEquals(listOf("Allow once", "Deny"), command.buttons)
        // Without a conversation grant, the second button is Deny.
        val screen = Screen()
        val asked = async { DialogApprovalBroker(NativeDialogs(screen)).ask(edit(sessionLabel = null), CancelSignal()) }
        screen.await(1).answer(1)
        assertEquals(ApprovalDecision.DENY, asked.get(5, TimeUnit.SECONDS))
        assertEquals("Ember wants to run a command: npm test", ApprovalRequest("Ember", opus, folder, ApprovalKind.COMMAND, "npm test", "", null).summary())
        assertEquals(WAITING_SUMMARY_LIMIT, edit().copy(title = "x".repeat(400)).summary().length)
        assertTrue(edit().copy(title = "x".repeat(400)).summary().endsWith("…"))
    }

    @Test fun noAnswerInTimeIsDenyAndTheDialogGoes() {
        val screen = Screen()
        val broker = DialogApprovalBroker(NativeDialogs(screen, timeout = 100.milliseconds))
        assertEquals(ApprovalDecision.DENY, broker.ask(edit(), CancelSignal()))
        assertTrue(screen.shown.single().dismissed)
        assertEquals(10.0, APPROVAL_TIMEOUT.inWholeSeconds / 60.0)
    }

    @Test fun aStoppedReplyDeniesWhatItAskedWhetherShowingOrNot() {
        val screen = Screen()
        val dialogs = NativeDialogs(screen, timeout = 30.seconds)
        val broker = DialogApprovalBroker(dialogs)
        val already = CancelSignal().also { it.cancel() }
        assertEquals(ApprovalDecision.DENY, broker.ask(edit(), already))
        assertTrue(screen.shown.isEmpty(), "A cancelled request is never shown")
        val cancel = CancelSignal()
        val asked = async { broker.ask(edit(), cancel) }
        val shown = screen.await(1)
        cancel.cancel()
        assertEquals(ApprovalDecision.DENY, asked.get(5, TimeUnit.SECONDS))
        assertTrue(shown.dismissed)
        shown.answer(0)
        assertEquals(1, screen.shown.size)
    }

    @Test fun oneDialogAtATimeInTheOrderAskedAndAQueuedOneCanBeCancelled() {
        val screen = Screen()
        val broker = DialogApprovalBroker(NativeDialogs(screen, timeout = 30.seconds))
        val first = async { broker.ask(edit(), CancelSignal()) }
        screen.await(1)
        val queuedCancel = CancelSignal()
        val queued = async { broker.ask(edit().copy(title = "Edit queued.txt"), queuedCancel) }
        val second = async { Thread.sleep(50); broker.ask(edit().copy(title = "Edit second.txt"), CancelSignal()) }
        Thread.sleep(200)
        assertEquals(1, screen.shown.size, "Nothing else shows while a dialog is up")
        queuedCancel.cancel()
        assertEquals(ApprovalDecision.DENY, queued.get(5, TimeUnit.SECONDS))
        screen.shown[0].answer(0)
        assertEquals(ApprovalDecision.ALLOW_ONCE, first.get(5, TimeUnit.SECONDS))
        val next = screen.await(2)
        assertEquals("Edit second.txt", next.dialog.lines.first(), "The cancelled request was never shown")
        next.answer(2)
        assertEquals(ApprovalDecision.DENY, second.get(5, TimeUnit.SECONDS))
    }

    @Test fun startOfWorkIsRememberedPerConversationCompanionAndFolderOnlyWhenStarted() {
        val screen = Screen()
        val start = StartOfWork(NativeDialogs(screen, timeout = 30.seconds))
        val lease = "lease-1"; val companion = "20000000-0000-4000-8000-000000000002"
        fun confirm(lease: String = "lease-1", folder: Path = this.folder, cancel: CancelSignal = CancelSignal()) =
            async { start.confirm(lease, companion, "Ember", folder, opus, cancel) }
        val notNow = confirm()
        val dialog = screen.await(1)
        assertEquals("Ember wants to start working in game with Claude · Opus.", dialog.dialog.title)
        assertEquals(listOf("Start working", "Not now"), dialog.dialog.buttons)
        dialog.answer(1)
        assertFalse(notNow.get(5, TimeUnit.SECONDS))
        assertFalse(start.isConfirmed(lease, companion, folder))
        // Not now is not remembered: the next working message asks again.
        val started = confirm()
        screen.await(2).answer(0)
        assertTrue(started.get(5, TimeUnit.SECONDS))
        assertTrue(confirm().get(5, TimeUnit.SECONDS))
        assertEquals(2, screen.shown.size, "Confirmed once for the conversation")
        // Another folder, or another conversation, asks again; a closed conversation forgets.
        val moved = confirm(folder = Path.of("/Users/player/Projects/other"))
        screen.await(3).answer(1)
        assertFalse(moved.get(5, TimeUnit.SECONDS))
        val otherLease = confirm(lease = "lease-2", cancel = CancelSignal().also { it.cancel() })
        assertFalse(otherLease.get(5, TimeUnit.SECONDS))
        start.forget(lease)
        assertFalse(start.isConfirmed(lease, companion, folder))
        assertEquals("Ember wants to start working in game with Claude · Opus.", startOfWorkSummary("Ember", folder, opus))
    }

    @Test fun aCancelSignalFiresOnceAndLateListenersRunAtOnce() {
        val signal = CancelSignal()
        var fired = 0
        val listening = signal.onCancel { fired++ }
        signal.cancel(); signal.cancel()
        assertEquals(1, fired)
        listening.close()
        signal.onCancel { fired++ }
        assertEquals(2, fired)
        assertTrue(signal.isCancelled)
    }
}
