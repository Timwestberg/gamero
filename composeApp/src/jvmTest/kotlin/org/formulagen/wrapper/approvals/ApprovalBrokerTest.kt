package org.formulagen.wrapper.approvals

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.conversation.TeamMember
import org.formulagen.wrapper.runtimes.ChatFailure
import org.formulagen.wrapper.runtimes.TurnEvents
import kotlin.test.*

class ApprovalBrokerTest {
    private val opus = TeamMember(RuntimeId.CLAUDE_CODE, "opus")
    private val folder = Path.of("/Users/player/Projects/game")

    /** A screen the test answers: every dialog shown, and whether it was taken down. */
    private class Screen : DialogPresenter {
        inner class Shown(val dialog: NativeDialog<*>, val answer: (Int) -> Unit) { @Volatile var dismissed = false }
        val shown = CopyOnWriteArrayList<Shown>()
        override fun present(dialog: NativeDialog<*>, answer: (Int) -> Unit): () -> Unit {
            val entry = Shown(dialog, answer); shown += entry
            return { entry.dismissed = true }
        }
        fun await(count: Int): Shown {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (shown.size < count) { check(System.nanoTime() < until) { "Dialog $count never shown" }; Thread.sleep(5) }
            return shown[count - 1]
        }
    }

    private fun edit(sessionLabel: String? = "Allow all edits in game for this conversation", file: String = "notes.txt") =
        ApprovalRequest("Ember", opus, folder, ApprovalSubject.Edit("Edit", listOf(folder.resolve(file)), "+ hello from Ember"), sessionLabel)
    private fun command(command: String, needs: Path? = null) =
        ApprovalRequest("Ember", opus, folder, ApprovalSubject.Command(command, null, needs, "Clean the build"), "Allow `$command` for this conversation")

    private fun <T> async(work: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(work)
    /** The native dialog alone, as `RemoteApprovals` asks a request no phone can answer. */
    private class DialogBroker(private val dialogs: NativeDialogs) {
        fun ask(request: ApprovalRequest, cancel: CancelSignal = CancelSignal()): ApprovalDecision =
            dialogs.ask(approvalDialog(request), cancel) {} ?: ApprovalDecision.DENY
        fun asBroker() = ApprovalBroker { request, cancel, shown -> dialogs.ask(approvalDialog(request), cancel, shown) ?: ApprovalDecision.DENY }
    }

    @Test fun eachButtonIsItsDecisionAndClosingTheDialogDenies() {
        val screen = Screen()
        val broker = DialogBroker(NativeDialogs(screen))
        for ((button, decision) in listOf(0 to ApprovalDecision.ALLOW_ONCE, 1 to ApprovalDecision.ALLOW_FOR_CONVERSATION, 2 to ApprovalDecision.DENY)) {
            val asked = async { broker.ask(edit()) }
            val shown = screen.await(screen.shown.size + 1)
            assertFalse(shown.dismissed)
            shown.answer(button)
            assertEquals(decision, asked.get(5, TimeUnit.SECONDS))
            assertTrue(shown.dismissed)
        }
        val closed = async { broker.ask(edit()) }
        screen.await(4).let { it.answer(it.dialog.dismissIndex) }
        assertEquals(ApprovalDecision.DENY, closed.get(5, TimeUnit.SECONDS))
        // An answer that is not one of the buttons is no answer.
        val odd = async { broker.ask(edit()) }
        screen.await(5).answer(7)
        assertEquals(ApprovalDecision.DENY, odd.get(5, TimeUnit.SECONDS))
    }

    @Test fun theDialogShowsTheExactAction() {
        val dialog = approvalDialog(edit())
        assertEquals("Ember wants to edit files", dialog.title)
        assertEquals(listOf("Edit /Users/player/Projects/game/notes.txt", "+ hello from Ember", "Member: Claude · Opus",
            "Project folder: /Users/player/Projects/game"), dialog.lines)
        assertEquals(listOf("Allow once", "Allow all edits in game for this conversation", "Deny"), dialog.buttons.map { it.first })
        // Deny is the default: what closing, Escape and Enter choose.
        assertEquals(ApprovalDecision.DENY, dialog.dismiss); assertEquals(2, dialog.dismissIndex)
        // A command that reaches outside the project is shown verbatim, its path in full.
        val outside = command("rm -rf /Users/player/Documents/old-build && ls", needs = Path.of("/Users/player/Documents/old-build"))
        val shown = approvalDialog(outside)
        assertEquals("rm -rf /Users/player/Documents/old-build && ls", shown.lines[0])
        assertEquals("Clean the build\nNeeds access to /Users/player/Documents/old-build", shown.lines[1])
        val talkless = approvalDialog(ApprovalRequest("Ember", TeamMember(RuntimeId.CODEX, RUNTIME_DEFAULT_MODEL), folder,
            ApprovalSubject.Command("npm test", null, null, null), null))
        assertEquals(listOf("npm test", "Member: Codex · default model", "Project folder: /Users/player/Projects/game"), talkless.lines)
        assertEquals(listOf("Allow once", "Deny"), talkless.buttons.map { it.first })
    }

    @Test fun formatCharactersAreShownAsCodePointsSoNoProviderCanReorderOrHideTheDialogText() {
        // A right-to-left override would show this command as "echo hello ;txt.nimda/ mr", hiding what runs.
        val spoofed = ApprovalRequest("Em​ber", opus, folder.resolve("ga⁦me"),
            ApprovalSubject.Command("echo hello ‮rm /admin.txt", null, null, "Clean﻿ the build"), "Allow ⁧this⁩ for this conversation")
        val dialog = approvalDialog(spoofed)
        assertEquals("Em\\u200Bber wants to run a command", dialog.title)
        assertEquals(listOf("echo hello \\u202Erm /admin.txt", "Clean\\uFEFF the build", "Member: Claude · Opus",
            "Project folder: /Users/player/Projects/game/ga\\u2066me"), dialog.lines)
        assertEquals("Allow \\u2067this\\u2069 for this conversation", dialog.buttons[1].first)
        val all = (listOf(dialog.title) + dialog.lines + dialog.buttons.map { it.first }).joinToString("")
        assertTrue(all.codePoints().noneMatch(::isFormatCharacter))
        // Supplementary format characters are escaped whole; ordinary text, tabs and line breaks are kept.
        assertEquals("a\\uE0001b", visibleText("a" + String(Character.toChars(0xE0001)) + "b"))
        assertEquals("café 世界\t\n", visibleText("café 世界\t\n"))
    }

    @Test fun noAnswerInTimeIsDenyAndTheDialogGoes() {
        val screen = Screen()
        val broker = DialogBroker(NativeDialogs(screen, timeout = 100.milliseconds))
        assertEquals(ApprovalDecision.DENY, broker.ask(edit()))
        assertTrue(screen.shown.single().dismissed)
        assertEquals(10L, APPROVAL_TIMEOUT.inWholeMinutes)
        assertEquals(750L, DIALOG_ARMING_MILLIS)
    }

    @Test fun aStoppedReplyDeniesWhatItAskedWhetherShowingOrNot() {
        val screen = Screen()
        val broker = DialogBroker(NativeDialogs(screen, timeout = 30.seconds))
        assertEquals(ApprovalDecision.DENY, broker.ask(edit(), CancelSignal().also { it.cancel() }))
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
        val broker = DialogBroker(NativeDialogs(screen, timeout = 30.seconds))
        val first = async { broker.ask(edit()) }
        screen.await(1)
        val queuedCancel = CancelSignal()
        val queued = async { broker.ask(edit(file = "queued.txt"), queuedCancel) }
        val second = async { Thread.sleep(50); broker.ask(edit(file = "second.txt")) }
        Thread.sleep(200)
        assertEquals(1, screen.shown.size, "Nothing else shows while a dialog is up")
        queuedCancel.cancel()
        assertEquals(ApprovalDecision.DENY, queued.get(5, TimeUnit.SECONDS))
        screen.shown[0].answer(0)
        assertEquals(ApprovalDecision.ALLOW_ONCE, first.get(5, TimeUnit.SECONDS))
        val next = screen.await(2)
        assertEquals("Edit /Users/player/Projects/game/second.txt", next.dialog.lines.first(), "The cancelled request was never shown")
        next.answer(2)
        assertEquals(ApprovalDecision.DENY, second.get(5, TimeUnit.SECONDS))
    }

    @Test fun theRelayDeniesWhatWasCancelledOrCouldNotBeAsked() {
        val events = object : TurnEvents {
            override fun opened(conversation: String) = Unit
            override fun accepted() = Unit
            override fun partial(text: String) = Unit
            override fun completed(text: String, conversation: String) = Unit
            override fun failed(failure: ChatFailure) = Unit
        }
        val screen = Screen()
        val relay = ApprovalRelay(DialogBroker(NativeDialogs(screen, timeout = 30.seconds)).asBroker())
        val first = relay.register("a", edit(), events)
        val second = relay.register("b", command("npm test"), events)
        val asked = async { relay.ask("a", first) }
        screen.await(1)
        // ask the second only once the first is showing, so arrival order is fixed
        val queued = async { relay.ask("b", second) }
        screen.shown[0].answer(0)
        assertEquals(ApprovalDecision.ALLOW_ONCE, asked.get(5, TimeUnit.SECONDS))
        screen.await(2)
        relay.cancelAll()
        assertEquals(ApprovalDecision.DENY, queued.get(5, TimeUnit.SECONDS))
        // A request no turn can show is denied without a dialog; a broker failure denies too.
        assertEquals(ApprovalDecision.DENY, relay.ask("c", relay.register("c", edit(), null)))
        val failing = ApprovalRelay { _, _, _ -> error("dialog failed") }
        assertEquals(ApprovalDecision.DENY, failing.ask("d", failing.register("d", edit(), events)))
        // An allow that comes back after a cancel is still a denial.
        val late = ApprovalRelay { _, cancel, _ -> cancel.cancel(); ApprovalDecision.ALLOW_ONCE }
        assertEquals(ApprovalDecision.DENY, late.ask("e", late.register("e", edit(), events)))
        assertEquals(2, screen.shown.size)
    }

    @Test fun startOfWorkNamesTheFolderAndMemberAndIsRememberedForExactlyThat() {
        val dialog = startOfWorkDialog("Ember", folder, opus)
        assertEquals("Ember wants to start working in game with Claude · Opus.", dialog.title)
        assertEquals(listOf("Start working", "Not now"), dialog.buttons.map { it.first })
        assertEquals(1, dialog.dismissIndex, "Not now is the default")
        val start = StartOfWork()
        val project = "20000000-0000-4000-8000-000000000002"
        val sonnet = TeamMember(RuntimeId.CLAUDE_CODE, "sonnet")
        assertFalse(start.isConfirmed("conversation-1", project, folder, opus))
        start.remember("conversation-1", project, folder, opus)
        assertTrue(start.isConfirmed("conversation-1", project, folder, opus))
        // Another member, folder or conversation was never confirmed.
        assertFalse(start.isConfirmed("conversation-1", project, folder, sonnet))
        assertFalse(start.isConfirmed("conversation-1", project, Path.of("/Users/player/Projects/other"), opus))
        assertFalse(start.isConfirmed("conversation-2", project, folder, opus))
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
