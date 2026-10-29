package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.workspace.FolderPicker
import org.formulagen.wrapper.workspace.WorkspaceStore

/** Process fixtures exercise cancellation/resume ownership without a model or real provider configuration. */
class GrokWorkRuntimeTest {
    @Test fun switchingProfilesCancelsGrokApprovalAndKeepsHistoriesAndWorkspaceSeparate() = runBlocking {
        FakeGrokRuntime().use { fake ->
            fake.mode("approval")
            val shown = CompletableFuture<Unit>()
            val cancelled = CompletableFuture<Unit>()
            val broker = ApprovalBroker { _, cancel, visible ->
                visible(); shown.complete(Unit)
                cancel.onCancel { cancelled.complete(Unit) }.use { cancelled.get(10, TimeUnit.SECONDS) }
                ApprovalDecision.ALLOW_ONCE // Deliberately late; must never grant the old operation.
            }
            val root = fake.root.resolve("providers")
            val workspaces = WorkspaceStore(root, FolderPicker { _, _ -> fake.root })
            val dialogs = NativeDialogs(DialogPresenter { _, answer -> answer(0); {} })
            val old = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
            val next = old.copy(profile = UUID.randomUUID().toString(), sessionId = UUID.randomUUID().toString())
            val connection = GrokConnection(fake.env)
            val speech = object : ChatSpeech {
                override fun status() = ChatVoice()
                override fun speak(text: String) = Unit
                override fun stop() = Unit
                override fun close() = Unit
            }
            val member = TeamMember(RuntimeId.GROK_BUILD, "runtime-default")
            DesktopChat(root, listOf(GrokChatRuntime(root, fake.env, connection)), speech,
                work = ChatWork(workspaces, StartOfWork(dialogs), listOf(GrokWorkRuntime(root, fake.env, connection, broker)))).use { chat ->
                fun args(scope: ChatScope) = ChatOpenForClient(scope.profile, scope.companionId, scope.name, scope.sessionId, listOf("grok-build"))
                workspaces.choose(old.profile, old.companionId)
                chat.openForClient(args(old))
                chat.send(ChatSend(old.profile, old.companionId, old.name, old.sessionId, member,
                    UUID.randomUUID().toString(), "Old profile message", false, MemberMode.WORK))
                shown.get(10, TimeUnit.SECONDS)
                assertEquals(ChatState.AWAITING_YOU, chat.snapshot(old).state)
                assertTrue(chat.openForClient(args(next)).messages.isEmpty())
                cancelled.get(2, TimeUnit.SECONDS)
                awaitCondition { Files.readAllLines(fake.root.resolve("pids")).none { ProcessHandle.of(it.toLong()).map(ProcessHandle::isAlive).orElse(false) } }
                assertNull(workspaces.folder(next.profile, next.companionId))
                // Cleanup from the old web view must not close the replacement profile's chat.
                chat.closeSession(old)
                fake.mode("normal")
                chat.send(ChatSend(next.profile, next.companionId, next.name, next.sessionId, member,
                    UUID.randomUUID().toString(), "New profile message", false))
                awaitCondition { runBlocking { chat.snapshot(next).state == ChatState.READY } }
                val entries = chat.snapshot(next).messages
                assertEquals(listOf("New profile message"), entries.filterIsInstance<ChatEntry.User>().map { it.text })
                assertEquals("Hello", entries.filterIsInstance<ChatEntry.Assistant>().single().text)
                val restored = chat.openForClient(args(old.copy(sessionId = UUID.randomUUID().toString())))
                assertEquals(listOf("Old profile message"), restored.messages.filterIsInstance<ChatEntry.User>().map { it.text })
                assertTrue(restored.messages.filterIsInstance<ChatEntry.Assistant>().none { it.text == "Hello" })
            }
        }
    }

    @Test fun ambiguousFailureWaitsForExplicitRetryAndDuplicateRetriesDoNotReplay() = runBlocking {
        FakeGrokRuntime().use { fake ->
            fake.mode("drop")
            val shown = CompletableFuture<Unit>()
            val cancelled = CompletableFuture<Unit>()
            val broker = ApprovalBroker { _, cancel, visible ->
                visible(); shown.complete(Unit)
                cancel.onCancel { cancelled.complete(Unit) }.use { cancelled.get(10, TimeUnit.SECONDS) }
                ApprovalDecision.ALLOW_ONCE
            }
            val root = fake.root.resolve("providers")
            val workspace = WorkspaceStore(root, FolderPicker { _, _ -> fake.root })
            val dialogs = NativeDialogs(DialogPresenter { _, answer -> answer(0); {} })
            val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
            val connection = GrokConnection(fake.env)
            val speech = object : ChatSpeech {
                override fun status() = ChatVoice()
                override fun speak(text: String) = Unit
                override fun stop() = Unit
                override fun close() = Unit
            }
            val member = TeamMember(RuntimeId.GROK_BUILD, "runtime-default")
            DesktopChat(root, listOf(GrokChatRuntime(root, fake.env, connection)), speech,
                work = ChatWork(workspace, StartOfWork(dialogs), listOf(GrokWorkRuntime(root, fake.env, connection, broker)))).use { chat ->
                workspace.choose(scope.profile, scope.companionId)
                chat.openForClient(ChatOpenForClient(scope.profile, scope.companionId, scope.name, scope.sessionId, listOf("grok-build")))
                val message = UUID.randomUUID().toString()
                chat.send(ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, message, "Do this once", false, MemberMode.WORK))
                awaitCondition { runBlocking { chat.snapshot(scope).state == ChatState.ERROR } }
                assertEquals(UserStatus.UNANSWERED, chat.snapshot(scope).messages.filterIsInstance<ChatEntry.User>().single().status)
                assertEquals(1, fake.requests().count { it == "session/prompt" })
                fake.mode("approval")
                val retry = ChatRetry(scope.profile, scope.companionId, scope.name, scope.sessionId, message, member,
                    UUID.randomUUID().toString(), false, MemberMode.WORK)
                chat.retry(retry)
                shown.get(10, TimeUnit.SECONDS)
                chat.retry(retry)
                val busy = chat.retry(retry.copy(requestId = UUID.randomUUID().toString()))
                assertEquals(ChatState.AWAITING_YOU, busy.state)
                assertEquals(2, fake.requests().count { it == "session/prompt" })
                chat.stop(scope)
                cancelled.get(2, TimeUnit.SECONDS)
                fake.mode("normal")
                val finalRetry = retry.copy(requestId = UUID.randomUUID().toString())
                chat.retry(finalRetry)
                awaitCondition { runBlocking { chat.snapshot(scope).state == ChatState.READY } }
                val entries = chat.snapshot(scope).messages
                assertEquals(listOf(message), entries.filterIsInstance<ChatEntry.User>().map { it.id })
                assertEquals("Hello", entries.filterIsInstance<ChatEntry.Assistant>().single().text)
                chat.retry(finalRetry)
                assertEquals(3, fake.requests().count { it == "session/prompt" })
            }
        }
    }

    @Test fun stopReleaseAndCloseCancelPendingApprovalsAndReapBeforeReplacement() {
        for (action in listOf("stop", "release", "close")) FakeGrokRuntime().use { fake ->
            fake.mode("approval")
            val shown = CompletableFuture<Unit>()
            val cancelled = CompletableFuture<Unit>()
            val broker = ApprovalBroker { _, cancel, visible ->
                visible(); shown.complete(Unit)
                cancel.onCancel { cancelled.complete(Unit) }.use { cancelled.get(10, TimeUnit.SECONDS) }
                ApprovalDecision.ALLOW_ONCE // A late native click must not resurrect an ended turn.
            }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker).use { runtime ->
                val events = WorkFixtureEvents()
                val handle = runtime.startTurn(workRequest(fake), events)
                shown.get(10, TimeUnit.SECONDS)
                when (action) { "stop" -> handle.stop(); "release" -> runtime.release(); else -> runtime.close() }
                cancelled.get(2, TimeUnit.SECONDS)
                awaitCondition { fake.requests().contains("session/cancel") }
                awaitCondition { Files.readAllLines(fake.root.resolve("pids")).none { ProcessHandle.of(it.toLong()).map(ProcessHandle::isAlive).orElse(false) } }
                assertFalse(events.seen.any { it == "failed" || it == "completed" })
                if (action != "close") {
                    fake.mode("normal")
                    val next = WorkFixtureEvents()
                    runtime.startTurn(workRequest(fake), next)
                    next.done.get(10, TimeUnit.SECONDS)
                    assertNull(next.failure)
                    assertEquals("Hello", next.reply)
                }
            }
        }
    }

    @Test fun workBindingsCannotResumeInAnotherWorkspaceOrReplayAnAmbiguousPrompt() {
        FakeGrokRuntime().use { fake ->
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker).use { runtime ->
                val first = WorkFixtureEvents()
                val request = workRequest(fake)
                runtime.startTurn(request, first); first.done.get(10, TimeUnit.SECONDS)
                assertNull(first.failure)
                val saved = NativeConversation(checkNotNull(first.handle), request.persona.hash)
                val different = Files.createDirectory(fake.root.resolve("another-project"))
                val mismatch = WorkFixtureEvents()
                runtime.startTurn(request.copy(conversation = saved, work = request.work!!.copy(folder = different)), mismatch)
                mismatch.done.get(10, TimeUnit.SECONDS)
                assertEquals(ChatFailure.Unexpected, mismatch.failure)
                assertEquals(1, fake.requests().count { it == "session/prompt" })
                fake.mode("drop")
                val dropped = WorkFixtureEvents()
                runtime.startTurn(request.copy(conversation = saved), dropped); dropped.done.get(10, TimeUnit.SECONDS)
                assertEquals(ChatFailure.Temporary, dropped.failure)
                assertEquals(2, fake.requests().count { it == "session/prompt" })
            }
        }
    }
}

private fun workRequest(fake: FakeGrokRuntime) = TurnRequest("local", "20000000-0000-4000-8000-000000000002",
    Persona.working("Ember"), null, null, null, "Hello",
    WorkScope(fake.root, "lease", "Ember", TeamMember(RuntimeId.GROK_BUILD, "runtime-default")))

private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition()) { check(System.nanoTime() < deadline) { "Condition did not complete" }; Thread.sleep(20) }
}

private class WorkFixtureEvents : TurnEvents {
    val done = CompletableFuture<Unit>()
    val seen = CopyOnWriteArrayList<String>()
    var handle: String? = null
    var reply: String? = null
    var failure: ChatFailure? = null
    override fun opened(conversation: String) { handle = conversation; seen += "opened" }
    override fun accepted() { seen += "accepted" }
    override fun partial(text: String) { reply = text; seen += "partial" }
    override fun completed(text: String, conversation: String) { reply = text; handle = conversation; seen += "completed"; done.complete(Unit) }
    override fun failed(failure: ChatFailure) { this.failure = failure; seen += "failed"; done.complete(Unit) }
}
