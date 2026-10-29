package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.grok.GrokWorkTrust
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.workspace.FolderPicker
import org.formulagen.wrapper.workspace.WorkspaceStore
import org.junit.Assume.assumeTrue

/** Real Grok sessions across Hovel profile leases; this does not exercise Hovel's account sign-in UI. */
class GrokProfileSmokeTest {
    @Test fun profileReplacementCancelsRealApprovalAndKeepsWorkspacesAndContextSeparate() = runBlocking {
        val executable = System.getenv("HOVEL_GROK_PROFILE_SMOKE")
        assumeTrue("Set HOVEL_GROK_PROFILE_SMOKE to exercise real profile transitions", executable != null)
        val root = Files.createTempDirectory("hovel-grok-profiles-")
        val projectA = Files.createDirectory(root.resolve("project-a")).toRealPath()
        val projectB = Files.createDirectory(root.resolve("project-b")).toRealPath()
        val markerA = Files.writeString(projectA.resolve("test.txt"), "BEFORE")
        Files.writeString(projectB.resolve("test.txt"), "PROFILE_B_FILE")
        val home = Files.createDirectory(root.resolve("grok-home"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val source = Path.of(System.getenv("GROK_HOME") ?: System.getenv("HOME") + "/.grok")
        Files.copy(source.resolve("config.toml"), home.resolve("config.toml"))
        Files.setPosixFilePermissions(home.resolve("config.toml"), PosixFilePermissions.fromString("rw-------"))
        val env = System.getenv() + mapOf("GAMERO_GROK_PATH" to requireNotNull(executable), "GROK_HOME" to home.toString(),
            "GROK_AUTH_PATH" to (System.getenv("GROK_AUTH_PATH") ?: source.resolve("auth.json").toString()))
        val providers = root.resolve("providers")
        val connection = GrokConnection(env)
        val shown = CompletableFuture<Unit>()
        val cancelled = CompletableFuture<Unit>()
        val broker = ApprovalBroker { request, cancel, visible ->
            val expected = ApprovalSubject.Edit("Edit", listOf(markerA), "Replace one matching occurrence\nBefore:\nBEFORE\nAfter:\nBLOCKED")
            if (request.subject != expected) ApprovalDecision.DENY else {
                visible(); shown.complete(Unit)
                cancel.onCancel { cancelled.complete(Unit) }.use { cancelled.get(45, TimeUnit.SECONDS) }
                ApprovalDecision.ALLOW_ONCE // Simulate a late click after profile replacement.
            }
        }
        val scopeA = ChatScope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
        val scopeB = scopeA.copy(profile = UUID.randomUUID().toString(), sessionId = UUID.randomUUID().toString())
        val folderChoices = ArrayDeque(listOf(projectA, projectB))
        val workspace = WorkspaceStore(providers, FolderPicker { _, _ -> folderChoices.removeFirst() })
        val dialogs = NativeDialogs(DialogPresenter { _, answer -> answer(0); {} })
        val speech = object : ChatSpeech {
            override fun status() = ChatVoice()
            override fun speak(text: String) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val member = TeamMember(RuntimeId.GROK_BUILD, "runtime-default")
        val workers = listOf(GrokWorkRuntime(providers, env, connection, broker,
            GrokWorkTrust { folder, cancel -> folder in setOf(projectA, projectB) && !cancel.isCancelled }))
        try {
            DesktopChat(providers, listOf(GrokChatRuntime(providers, env, connection)), speech,
                work = ChatWork(workspace, StartOfWork(dialogs), workers)).use { chat ->
                fun open(scope: ChatScope) = ChatOpenForClient(scope.profile, scope.companionId, scope.name, scope.sessionId, listOf("grok-build"))
                suspend fun send(scope: ChatScope, text: String): ChatSnapshot {
                    chat.send(ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), text, false, MemberMode.WORK))
                    return settled(chat, scope)
                }
                workspace.choose(scopeA.profile, scopeA.companionId)
                assertEquals(projectA, workspace.folder(scopeA.profile, scopeA.companionId)?.path)
                chat.openForClient(open(scopeA))
                val first = send(scopeA, "Remember the private phrase PROFILE_A_ORCHID. Reply with that phrase only, without tools.")
                assertEquals(ChatState.READY, first.state, first.notice)
                assertTrue(first.messages.filterIsInstance<ChatEntry.Assistant>().last().text.contains("PROFILE_A_ORCHID"))
                chat.send(ChatSend(scopeA.profile, scopeA.companionId, scopeA.name, scopeA.sessionId, member, UUID.randomUUID().toString(),
                    "Disposable cancellation test: use search_replace exactly once to replace BEFORE with BLOCKED in $markerA. Do not use other tools. Stop if denied.", false, MemberMode.WORK))
                shown.get(120, TimeUnit.SECONDS)
                val oldProcesses = ProcessHandle.current().descendants().use { stream ->
                    stream.filter { it.info().command().orElse("").endsWith("/grok") }.toList()
                }
                assertTrue(oldProcesses.isNotEmpty(), "Expected an active Grok process at approval")
                assertTrue(chat.openForClient(open(scopeB)).messages.isEmpty())
                cancelled.get(5, TimeUnit.SECONDS)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (oldProcesses.any { it.isAlive }) {
                    check(System.nanoTime() < deadline) { "Old profile process survived replacement" }; Thread.sleep(20)
                }
                assertEquals("BEFORE", Files.readString(markerA))
                assertNull(workspace.folder(scopeB.profile, scopeB.companionId))
                chat.closeSession(scopeA)
                workspace.choose(scopeB.profile, scopeB.companionId)
                val second = send(scopeB, "Read test.txt without changing it or running commands. Report only its content.")
                assertEquals(ChatState.READY, second.state, second.notice)
                val answerB = second.messages.filterIsInstance<ChatEntry.Assistant>().last()
                assertTrue(answerB.text.contains("PROFILE_B_FILE"))
                assertFalse(second.messages.any { it is ChatEntry.User && it.text.contains("PROFILE_A_ORCHID") })
                assertFalse(answerB.text.contains("PROFILE_A_ORCHID"))
                assertTrue(answerB.activity?.steps.orEmpty().any { it.kind == ActivityKind.READ && it.status == ActivityStatus.DONE })
                val restoredScope = scopeA.copy(sessionId = UUID.randomUUID().toString())
                val restored = chat.openForClient(open(restoredScope))
                assertTrue(restored.messages.filterIsInstance<ChatEntry.User>().last().status == UserStatus.UNANSWERED)
                assertEquals(projectA, workspace.folder(scopeA.profile, scopeA.companionId)?.path)
                val recalled = send(restoredScope, "Without tools, repeat the private phrase I asked you to remember earlier.")
                assertEquals(ChatState.READY, recalled.state, recalled.notice)
                assertTrue(recalled.messages.filterIsInstance<ChatEntry.Assistant>().last().text.contains("PROFILE_A_ORCHID"))
                assertEquals("BEFORE", Files.readString(markerA))
                println("GROK_PROFILE_SMOKE: pending approval cancelled; late Allow rejected; process reaped; separate workspace/history; original context restored")
            }
        } finally { root.toFile().deleteRecursively() }
    }

    /** Wait for a terminal coordinator state, retaining a bounded provider timeout. */
    private suspend fun settled(chat: DesktopChat, scope: ChatScope): ChatSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150)
        while (true) {
            val value = chat.snapshot(scope)
            if (value.state !in setOf(ChatState.RESPONDING, ChatState.HANDING_OVER, ChatState.AWAITING_YOU)) return value
            check(System.nanoTime() < deadline) { "Real profile turn timed out" }; Thread.sleep(25)
        }
    }
}
