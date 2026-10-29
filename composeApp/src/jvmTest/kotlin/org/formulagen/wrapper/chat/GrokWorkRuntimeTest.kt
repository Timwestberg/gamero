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
import org.formulagen.wrapper.chat.grok.GrokWorkTrust
import org.formulagen.wrapper.chat.grok.grokWorkTrustDialog
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

    @Test fun trustAsksWithBothFoldersAndRequiresGrokToTrustExactlyTheConfirmedRoot() {
        FakeGrokRuntime().use { fake ->
            val repository = Files.createDirectories(fake.root.resolve("repository")).toRealPath()
            val project = Files.createDirectories(repository.resolve("game")).toRealPath()
            val asked = CopyOnWriteArrayList<Pair<java.nio.file.Path, java.nio.file.Path>>()
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            val trust = GrokWorkTrust { selected, root, _ -> asked += selected to root; true }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker, trust).use { runtime ->
                fake.untrusted(repository, rootAfterTrust = project)
                val moved = WorkFixtureEvents()
                runtime.startTurn(workRequest(fake, project), moved); moved.done.get(10, TimeUnit.SECONDS)
                assertEquals(ChatFailure.Temporary, moved.failure)
                assertEquals(listOf(project to repository), asked)
                assertFalse("session/prompt" in fake.requests())
                fake.untrusted(repository)
                val trusted = WorkFixtureEvents()
                runtime.startTurn(workRequest(fake, project), trusted); trusted.done.get(10, TimeUnit.SECONDS)
                assertNull(trusted.failure)
                assertEquals(listOf(project to repository, project to repository), asked)
                assertEquals(2, fake.requests().count { it == "trust" })
            }
        }
    }

    @Test fun aTrustRootCoveringTheHomeFolderOrTheDiskIsRefusedWithoutAsking() {
        FakeGrokRuntime().use { fake ->
            val project = Files.createDirectories(fake.root.resolve("notes")).toRealPath()
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            val trust = GrokWorkTrust { _, _, _ -> error("A trust root above the home folder must not be offered") }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker, trust).use { runtime ->
                for (root in listOf(fake.root, fake.root.parent, java.nio.file.Path.of("/"))) {
                    fake.untrusted(root)
                    val events = WorkFixtureEvents()
                    runtime.startTurn(workRequest(fake, project), events); events.done.get(10, TimeUnit.SECONDS)
                    assertEquals(ChatFailure.ProjectTrustTooBroad, events.failure, root.toString())
                }
                assertFalse("trust" in fake.requests())
                assertFalse("session/prompt" in fake.requests())
            }
        }
    }

    @Test fun theTrustDialogNamesBothFoldersAndSaysWhenGrokTrustsMore() {
        val project = java.nio.file.Path.of("/work/repository/game")
        val enclosing = grokWorkTrustDialog(project, project.parent)
        assertEquals(listOf("Project folder in Hovel: /work/repository/game", "Folder Grok will trust: /work/repository",
            "These are different folders. Grok trusts the whole folder /work/repository, including everything in it outside your project folder."),
            enclosing.lines.take(3))
        assertEquals(false, enclosing.dismiss)
        val same = grokWorkTrustDialog(project, project)
        assertEquals(listOf("Project folder in Hovel: /work/repository/game", "Folder Grok will trust: /work/repository/game"), same.lines.take(2))
        assertTrue(same.lines.none { it.startsWith("These are different folders") })
        assertEquals("Folder Grok will trust: /work/\\u202Erepo", grokWorkTrustDialog(project, java.nio.file.Path.of("/work/\u202Erepo")).lines[1])
    }

    @Test fun aRejectedWorkSessionLoadStartsFreshOnceAndDropsTheBinding() = runBlocking {
        FakeGrokRuntime().use { fake ->
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
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
                suspend fun send(text: String) = chat.send(ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member,
                    UUID.randomUUID().toString(), text, false, MemberMode.WORK))
                send("First"); awaitCondition { runBlocking { chat.snapshot(scope).state == ChatState.READY } }
                val bindings = root.resolve("grok-work-bindings")
                val first = Files.list(bindings).use { it.toList() }.single()
                fake.mode("reject-load")
                send("Second"); awaitCondition { runBlocking { chat.snapshot(scope).state == ChatState.READY } }
                assertEquals(listOf("Hello", "Hello"), chat.snapshot(scope).messages.filterIsInstance<ChatEntry.Assistant>().map { it.text })
                assertFalse(Files.exists(first))
                assertEquals(1, fake.requests().count { it == "session/load" })
                assertEquals(2, fake.requests().count { it == "session/new" })
                assertEquals(2, fake.requests().count { it == "session/prompt" })
            }
        }
    }

    @Test fun workRunsInThePlayersEnvironmentWithTheirLoginPathAndNoInheritedGrokOrXaiSettings() {
        FakeGrokRuntime().use { fake ->
            // A Mac app started from Finder: system folders only, plus whatever the player's session exported.
            val parent = fake.env + mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "LANG" to "en_US.UTF-8", "SHELL" to "/bin/zsh",
                "TMPDIR" to fake.root.toString(), "XAI_API_KEY" to "fixture-secret", "GROK_API_KEY" to "fixture-secret",
                "GROK_ENDPOINT" to "https://example.invalid")
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            GrokWorkRuntime(fake.root.resolve("providers"), parent, GrokConnection(parent), broker,
                loginPath = { "/opt/homebrew/bin:/usr/bin:/bin" }).use { runtime ->
                val events = WorkFixtureEvents()
                runtime.startTurn(workRequest(fake), events); events.done.get(10, TimeUnit.SECONDS)
                assertNull(events.failure)
            }
            val child = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(fake.root.resolve("agent-environment.json")))
                .let { it as kotlinx.serialization.json.JsonObject }.mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
            assertEquals("${fake.root}:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin", child["PATH"])
            assertEquals("en_US.UTF-8", child["LANG"]); assertEquals("/bin/zsh", child["SHELL"]); assertEquals(fake.root.toString(), child["TMPDIR"])
            assertEquals(fake.root.resolve(".grok").toString(), child["GROK_HOME"])
            assertEquals("1", child["GROK_DISABLE_AUTOUPDATER"])
            assertFalse(child.values.any { it == "fixture-secret" })
            assertFalse("GROK_ENDPOINT" in child)
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

private fun workRequest(fake: FakeGrokRuntime, folder: java.nio.file.Path = fake.root) = TurnRequest("local", "20000000-0000-4000-8000-000000000002",
    Persona.working("Ember"), null, null, null, "Hello",
    WorkScope(folder, "lease", "Ember", TeamMember(RuntimeId.GROK_BUILD, "runtime-default")))

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
