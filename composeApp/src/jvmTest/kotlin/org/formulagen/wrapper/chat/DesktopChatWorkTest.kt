package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.approvals.DialogPresenter
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.workspace.FolderPicker
import org.formulagen.wrapper.workspace.WorkspaceStore
import kotlin.test.*

/** Working members in the chat: the start of work, `awaiting-you`, activity, falling back to talk, and work logs. */
class DesktopChatWorkTest {
    private val sonnet = TeamMember(RuntimeId.CLAUDE_CODE, "sonnet")
    private val codex = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)
    private val root: Path = Files.createTempDirectory("chat-work")
    private val project: Path = Files.createDirectories(root.resolve("projects/game")).toRealPath()
    private val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())

    private class Speech : ChatSpeech {
        val said = CopyOnWriteArrayList<String>()
        override fun status() = ChatVoice()
        override fun speak(text: String) { said += text }
        override fun stop() = Unit
        override fun close() = Unit
    }

    /** A runtime the test drives by hand. */
    private class Fake(override val id: RuntimeId) : ChatRuntime, WorkRuntime {
        val requests = CopyOnWriteArrayList<TurnRequest>()
        val events = CopyOnWriteArrayList<TurnEvents>()
        @Volatile var stops = 0
        @Volatile var releases = 0
        override fun readiness(profile: String) = Readiness.Ready
        override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle { requests += turn; this.events += events; return TurnHandle { stops++ } }
        override fun summarize(request: SummaryRequest) = "They worked on the game."
        override fun release() { releases++ }
        override fun release(lease: String) { releases++ }
        override fun close() = Unit
    }

    /** The native dialogs as the test answers them: each one shown, and its answer when the test gives it. */
    private class Screen : DialogPresenter {
        val shown = CopyOnWriteArrayList<Pair<NativeDialog<*>, (Int) -> Unit>>()
        val dismissed = CopyOnWriteArrayList<NativeDialog<*>>()
        override fun present(dialog: NativeDialog<*>, answer: (Int) -> Unit): () -> Unit { shown += Pair(dialog, answer); return { dismissed += dialog } }
    }

    private val talkClaude = Fake(RuntimeId.CLAUDE_CODE)
    private val talkCodex = Fake(RuntimeId.CODEX)
    private val workClaude = Fake(RuntimeId.CLAUDE_CODE)
    private val screen = Screen()
    private var picked: Path? = project
    private val workspaces = WorkspaceStore(root, FolderPicker { _, _ -> picked })

    private fun chat() = DesktopChat(root, listOf(talkClaude, talkCodex), Speech(),
        work = ChatWork(workspaces, StartOfWork(NativeDialogs(screen, timeout = 10.seconds)), listOf(workClaude)))

    private fun send(member: TeamMember = sonnet, mode: MemberMode? = MemberMode.WORK, text: String = "Fix the date test") =
        ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), text, false, mode)
    private fun state(chat: DesktopChat) = runBlocking { chat.snapshot(scope) }
    private fun await(check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) { if (System.nanoTime() > until) fail("Condition timed out"); Thread.sleep(10) }
    }
    private fun reply(chat: DesktopChat) = state(chat).messages.filterIsInstance<ChatEntry.Assistant>().last()

    @AfterTest fun cleanUp() { root.toFile().deleteRecursively() }

    /** The hearing handshake is not a change, so a press refused while a dialog waits still opts the lease in. */
    @Test fun aHearingPressRefusedWhileADialogWaitsStillOptsTheLeaseIn() = runBlocking<Unit> {
        workspaces.choose(scope.profile, scope.companionId)
        chat().use { chat ->
            chat.open(scope)
            chat.send(send())
            await { screen.shown.isNotEmpty() }
            assertNull(state(chat).hearing)
            val refused = chat.hearing(ChatHearingRequest(scope.profile, scope.companionId, scope.name, scope.sessionId, HearingAction.PRESS, sonnet))
            assertEquals(ANSWER_FIRST, refused.notice)
            assertNotNull(refused.hearing)
            assertNotNull(state(chat).hearing)
            chat.stop(scope)
        }
    }

    @Test fun aWorkingMessageAsksToStartThenWorksInTheFolderWithLiveActivity() = runBlocking {
        workspaces.choose(scope.profile, scope.companionId)
        chat().use { chat ->
            chat.open(scope)
            chat.send(send())
            await { screen.shown.isNotEmpty() }
            val entries = state(chat).messages
            // The page sees what the native dialog asks, and cannot answer; everything else waits.
            val waiting = state(chat)
            assertEquals(ChatState.AWAITING_YOU, waiting.state)
            assertEquals(ChatWaiting(WaitingKind.START, "Ember wants to start working in game with Claude · Sonnet."), waiting.waiting)
            assertEquals("Ember wants to start working in game with Claude · Sonnet.", screen.shown.single().first.title)
            assertEquals(ANSWER_FIRST, chat.send(send(text = "Hello?")).notice)
            assertEquals(ANSWER_FIRST, state(chat).notice, "A refusal's notice stays until the state changes")
            assertEquals(ANSWER_FIRST, chat.retry(ChatRetry(scope.profile, scope.companionId, scope.name, scope.sessionId, UUID.randomUUID().toString(),
                sonnet, UUID.randomUUID().toString(), false, MemberMode.WORK)).notice)
            assertEquals(ANSWER_FIRST, chat.handover(ChatHandover(scope.profile, scope.companionId, scope.name, scope.sessionId, sonnet,
                HandoverReason.MANUAL, UUID.randomUUID().toString(), codex)).notice)
            assertEquals(entries, state(chat).messages, "Refusals change nothing")
            screen.shown.single().second(0)
            await { workClaude.requests.isNotEmpty() }
            assertNull(state(chat).notice, "The refusal ends with the state it was given in")
            val request = workClaude.requests.single()
            assertEquals(WorkScope(project, scope.sessionId, "Ember", sonnet), request.work)
            assertEquals(Persona.working("Ember"), request.persona)
            assertTrue(talkClaude.requests.isEmpty())
            // A working reply is labelled by its activity from the start, and shows each step as it finishes.
            assertEquals(Activity(emptyList(), false), reply(chat).activity)
            val events = workClaude.events.single()
            events.opened("40000000-0000-4000-8000-000000000004"); events.accepted()
            val step = ActivityStep(ActivityKind.EDIT, "src/date.ts", ActivityStatus.DONE, "+3 -1")
            events.activity(Activity(listOf(step), false))
            assertEquals(listOf(step), reply(chat).activity?.steps)
            events.waiting(ChatWaiting(WaitingKind.APPROVAL, "Ember wants to run a command: npm test"))
            assertEquals(ChatState.AWAITING_YOU, state(chat).state)
            assertEquals(WaitingKind.APPROVAL, state(chat).waiting?.kind)
            events.waiting(null)
            assertEquals(ChatState.RESPONDING, state(chat).state); assertNull(state(chat).waiting)
            events.completed("Fixed the parser.", "40000000-0000-4000-8000-000000000004")
            assertEquals(ChatState.READY, state(chat).state)
            assertEquals("Fixed the parser.", reply(chat).text)
            val saved = ChatHistory(root.resolve("local/companion-chats/${scope.companionId}.json")).read()
            assertEquals(NativeConversation("40000000-0000-4000-8000-000000000004", Persona.working("Ember").hash, playerLogin = true), saved.workConversations[RuntimeId.CLAUDE_CODE])
            assertNull(saved.conversations[RuntimeId.CLAUDE_CODE], "The talk conversation is separate")
            // The next working message in this conversation does not ask again, and resumes the working session.
            chat.send(send(text = "Now add a test"))
            await { workClaude.requests.size == 2 }
            assertEquals(1, screen.shown.size)
            assertEquals("40000000-0000-4000-8000-000000000004", workClaude.requests[1].conversation?.id)
        }
    }

    @Test fun notNowNoFolderAMovedFolderOrAGatedRuntimeAnswerAsTalkWithANotice() = runBlocking {
        chat().use { chat ->
            chat.open(scope)
            fun talked(runtime: Fake, notice: String?) {
                val before = runtime.events.size
                await { runtime.events.size == before + 1 }
                val request = runtime.requests.last()
                assertNull(request.work); assertEquals(Persona.companion("Ember"), request.persona)
                runtime.events.last().completed("Just talking.", "40000000-0000-4000-8000-00000000000${runtime.requests.size}")
                val snapshot = state(chat)
                assertEquals(ChatState.READY, snapshot.state)
                assertEquals(notice, snapshot.notice)
                assertNull(snapshot.messages.filterIsInstance<ChatEntry.Assistant>().last().activity, "A reply that did not work carries no activity")
            }
            chat.send(send())
            talked(talkClaude, "Choose a project folder to let Claude · Sonnet work.")
            workspaces.choose(scope.profile, scope.companionId)
            chat.send(send())
            await { screen.shown.size == 1 }
            screen.shown.last().second(1)
            talked(talkClaude, "Claude · Sonnet answered without working. Choose Start working to let them work in game.")
            // Not now is not remembered: the next working message asks again.
            chat.send(send())
            await { screen.shown.size == 2 }
            screen.shown.last().second(1)
            talked(talkClaude, "Claude · Sonnet answered without working. Choose Start working to let them work in game.")
            chat.send(send(codex))
            talked(talkCodex, "Codex can't work in projects yet in this version of Hovel.")
            Files.delete(project)
            chat.send(send())
            talked(talkClaude, "The project folder game was moved or deleted. Choose it again to let Claude · Sonnet work.")
            assertTrue(workClaude.requests.isEmpty())
            // A talk message is today's chat, with no notice.
            chat.send(send(mode = null))
            talked(talkClaude, null)
        }
    }

    @Test fun stopAndCloseDenyWhatTheDialogAsks() = runBlocking {
        workspaces.choose(scope.profile, scope.companionId)
        chat().use { chat ->
            chat.open(scope)
            chat.send(send())
            await { screen.shown.size == 1 }
            assertEquals(ChatState.CANCELLED, chat.stop(scope).state)
            await { screen.dismissed.size == 1 }
            assertNull(chat.stop(scope).waiting)
            assertTrue(workClaude.requests.isEmpty() && talkClaude.requests.isEmpty(), "A stopped start runs nothing")
            // An approval the runtime is waiting on is denied by its stop.
            chat.send(send())
            await { screen.shown.size == 2 }
            screen.shown.last().second(0)
            await { workClaude.requests.size == 1 }
            workClaude.events.single().waiting(ChatWaiting(WaitingKind.APPROVAL, "Ember wants to edit files: Edit a.txt"))
            assertEquals(ChatState.CANCELLED, chat.stop(scope).state)
            assertEquals(1, workClaude.stops)
            // This conversation confirmed the start already; closing it ends its work.
            chat.send(send())
            await { workClaude.requests.size == 2 }
            chat.closeSession(scope)
            assertTrue(workClaude.releases > 0)
            // A new conversation asks again, and closing it while the dialog is up denies it.
            val next = scope.copy(sessionId = UUID.randomUUID().toString())
            chat.open(next)
            chat.send(ChatSend(next.profile, next.companionId, next.name, next.sessionId, sonnet, UUID.randomUUID().toString(), "Again", false, MemberMode.WORK))
            await { screen.shown.size == 3 }
            chat.closeSession(next)
            await { screen.dismissed.size == 3 }
            assertEquals(2, workClaude.requests.size)
        }
    }

    @Test fun aHandoverFromAWorkingMemberCarriesItsWorkLog() = runBlocking {
        workspaces.choose(scope.profile, scope.companionId)
        chat().use { chat ->
            chat.open(scope)
            chat.send(send())
            await { screen.shown.size == 1 }
            screen.shown.single().second(0)
            await { workClaude.requests.size == 1 }
            val events = workClaude.events.single()
            events.opened("40000000-0000-4000-8000-000000000004")
            events.activity(Activity(listOf(ActivityStep(ActivityKind.EDIT, "src/date.ts", ActivityStatus.DONE, "+3 -1"),
                ActivityStep(ActivityKind.COMMAND, "git push", ActivityStatus.DENIED)), false))
            events.completed("Fixed; not pushed.", "40000000-0000-4000-8000-000000000004")
            chat.handover(ChatHandover(scope.profile, scope.companionId, scope.name, scope.sessionId, sonnet, HandoverReason.MANUAL,
                UUID.randomUUID().toString(), codex))
            await { state(chat).state == ChatState.READY && state(chat).messages.last() is ChatEntry.Handover }
            val note = state(chat).messages.last() as ChatEntry.Handover
            assertEquals(HandoverMethod.SUMMARY, note.method)
            assertEquals(WorkLog(listOf("src/date.ts"), listOf(ActivityStep(ActivityKind.COMMAND, "git push", ActivityStatus.DENIED)), listOf("git push"),
                "Fixed; not pushed."), note.workLog)
            chat.send(send(codex, mode = null, text = "Can you push it?"))
            await { talkCodex.requests.size == 1 }
            assertContains(talkCodex.requests.single().context!!, "Earlier in this conversation (the work log):\n<<<\nFiles changed: src/date.ts.")
        }
    }

    @Test fun theStartIsConfirmedPerMemberAndForgottenWithTheView() = runBlocking {
        workspaces.choose(scope.profile, scope.companionId)
        val opus = TeamMember(RuntimeId.CLAUDE_CODE, "opus")
        chat().use { chat ->
            chat.open(scope)
            chat.send(send())
            await { screen.shown.size == 1 }
            screen.shown.last().second(0)
            await { workClaude.requests.size == 1 }
            workClaude.events.last().completed("Done.", "40000000-0000-4000-8000-000000000004")
            // The dialog named Sonnet; Opus asks again.
            chat.send(send(opus))
            await { screen.shown.size == 2 }
            assertEquals("Ember wants to start working in game with Claude · Opus.", screen.shown.last().first.title)
            screen.shown.last().second(0)
            await { workClaude.requests.size == 2 }
            workClaude.events.last().completed("Done.", "40000000-0000-4000-8000-000000000004")
            // Another companion's view replaces this one, and this conversation's confirmations go with it.
            chat.open(scope.copy(companionId = UUID.randomUUID().toString()))
            chat.open(scope.copy(sessionId = scope.sessionId))
            chat.send(send())
            await { screen.shown.size == 3 }
        }
    }

    @Test fun aRetryOfAFailedWorkingReplyKeepsItsFinishedSteps() = runBlocking {
        workspaces.choose(scope.profile, scope.companionId)
        chat().use { chat ->
            chat.open(scope)
            chat.send(send())
            await { screen.shown.size == 1 }
            screen.shown.last().second(0)
            await { workClaude.requests.size == 1 }
            val read = ActivityStep(ActivityKind.READ, "src/date.ts", ActivityStatus.DONE)
            workClaude.events.last().activity(Activity(listOf(read), false))
            workClaude.events.last().failed(ChatFailure.Temporary)
            val failed = state(chat)
            assertEquals(ChatState.ERROR, failed.state)
            assertEquals(listOf(read), reply(chat).activity?.steps, "A failed working reply keeps what it finished")
            val message = failed.messages.filterIsInstance<ChatEntry.User>().last()
            chat.retry(ChatRetry(scope.profile, scope.companionId, scope.name, scope.sessionId, message.id, sonnet, UUID.randomUUID().toString(), false, MemberMode.WORK))
            await { workClaude.requests.size == 2 }
            assertEquals(listOf(read), reply(chat).activity?.steps, "The retry starts with the steps already done")
            val edit = ActivityStep(ActivityKind.EDIT, "src/date.ts", ActivityStatus.DONE, "+1 -1")
            workClaude.events.last().activity(Activity(listOf(edit), false))
            assertEquals(listOf(read, edit), reply(chat).activity?.steps)
            assertEquals(1, state(chat).messages.filterIsInstance<ChatEntry.Assistant>().size, "The failed reply was replaced")
        }
    }
}
