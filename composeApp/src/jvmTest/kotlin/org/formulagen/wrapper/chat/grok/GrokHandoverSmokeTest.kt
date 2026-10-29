package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.chat.claude.ClaudeWorkRuntime
import org.formulagen.wrapper.providers.*
import org.formulagen.wrapper.workspace.FolderPicker
import org.formulagen.wrapper.workspace.WorkspaceStore
import org.junit.Assume.assumeTrue

/** Opt-in production coordinator acceptance, using signed-in Grok and Claude in a disposable project. */
class GrokHandoverSmokeTest {
    @Test fun workingProvidersHandOverContextAndWorkLogsInBothDirections() = runBlocking {
        val grokExecutable = System.getenv("HOVEL_GROK_HANDOVER_SMOKE")
        val claudeExecutable = System.getenv("HOVEL_CLAUDE_HANDOVER_SMOKE")
        assumeTrue("Set both provider executable paths to exercise live handovers", grokExecutable != null && claudeExecutable != null)
        val root = Files.createTempDirectory("hovel-grok-handover-")
        val project = Files.createDirectory(root.resolve("project")).toRealPath()
        val marker = project.resolve("test.txt")
        Files.writeString(marker, "BEFORE")
        val home = Files.createDirectory(root.resolve("grok-home"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val source = Path.of(System.getenv("GROK_HOME") ?: System.getenv("HOME") + "/.grok")
        Files.copy(source.resolve("config.toml"), home.resolve("config.toml"))
        Files.setPosixFilePermissions(home.resolve("config.toml"), PosixFilePermissions.fromString("rw-------"))
        val env = System.getenv() + mapOf("GAMERO_GROK_PATH" to requireNotNull(grokExecutable), "GROK_HOME" to home.toString(),
            "GROK_AUTH_PATH" to source.resolve("auth.json").toString())
        val providers = root.resolve("providers")
        val connection = GrokConnection(env)
        val claude = ClaudeRuntime(Path.of(requireNotNull(claudeExecutable)), providers)
        val claudeConnection = ClaudeConnection(claude)
        val broker = ApprovalBroker { request, _, shown ->
            shown()
            if (request.subject == ApprovalSubject.Edit("Edit", listOf(marker), "Replace one matching occurrence\nBefore:\nBEFORE\nAfter:\nAFTER"))
                ApprovalDecision.ALLOW_ONCE else ApprovalDecision.DENY
        }
        val dialogs = NativeDialogs(DialogPresenter { _, answer -> answer(0); {} })
        val workspace = WorkspaceStore(providers, FolderPicker { _, _ -> project })
        val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
        val grok = TeamMember(RuntimeId.GROK_BUILD, "runtime-default")
        val sonnet = TeamMember(RuntimeId.CLAUDE_CODE, "sonnet")
        val speech = object : ChatSpeech {
            override fun status() = ChatVoice()
            override fun speak(text: String) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        try {
            workspace.choose(scope.profile, scope.companionId)
            DesktopChat(providers, listOf(GrokChatRuntime(providers, env, connection), ClaudeChatRuntime(claude, claudeConnection)), speech,
                work = ChatWork(workspace, StartOfWork(dialogs), listOf(
                    GrokWorkRuntime(providers, env, connection, broker, GrokWorkTrust { _, trusted, cancel -> trusted == project && !cancel.isCancelled }),
                    ClaudeWorkRuntime(claude, claudeConnection, broker)))).use { chat ->
                chat.openForClient(ChatOpenForClient(scope.profile, scope.companionId, scope.name, scope.sessionId, listOf("grok-build", "claude-code")))
                suspend fun send(member: TeamMember, text: String): ChatEntry.Assistant {
                    chat.send(ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), text, false, MemberMode.WORK))
                    val result = settled(chat, scope)
                    assertEquals(ChatState.READY, result.state, result.notice)
                    return result.messages.filterIsInstance<ChatEntry.Assistant>().last()
                }
                suspend fun handover(from: TeamMember, to: TeamMember): ChatEntry.Handover {
                    chat.handover(ChatHandover(scope.profile, scope.companionId, scope.name, scope.sessionId, from, HandoverReason.MANUAL, UUID.randomUUID().toString(), to))
                    val result = settled(chat, scope)
                    assertEquals(ChatState.READY, result.state, result.notice)
                    return assertIs<ChatEntry.Handover>(result.messages.last())
                }
                val first = send(grok, "Disposable integration test: use search_replace exactly once to replace BEFORE with AFTER in $marker. " +
                    "Do not use other tools or change other files. If denied, stop. Remember the handover phrase ORCHID_HANDOVER.")
                assertEquals("AFTER", Files.readString(marker))
                assertTrue(first.activity?.steps.orEmpty().any { it.kind == ActivityKind.EDIT && it.status == ActivityStatus.DONE })
                val toClaude = handover(grok, sonnet)
                assertTrue(toClaude.workLog?.changed.orEmpty().any { it.endsWith("test.txt") })
                val second = send(sonnet, "Read test.txt without modifying anything or running commands. Report its content and the handover phrase from the prior conversation.")
                assertTrue(second.text.contains("AFTER") && second.text.contains("ORCHID_HANDOVER"), second.text)
                assertTrue(second.activity?.steps.orEmpty().any { it.kind == ActivityKind.READ && it.status == ActivityStatus.DONE })
                handover(sonnet, grok)
                val third = send(grok, "Without tools, report the content Claude just read from test.txt and the handover phrase.")
                assertTrue(third.text.contains("AFTER") && third.text.contains("ORCHID_HANDOVER"), third.text)
                chat.closeSession(scope)
                println("GROK_HANDOVER_SMOKE: Grok Work -> Claude Work -> Grok Work; edit/read activity; work log; phrase preserved")
            }
        } finally { root.toFile().deleteRecursively() }
    }

    private suspend fun settled(chat: DesktopChat, scope: ChatScope): ChatSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150)
        while (true) {
            val snapshot = chat.snapshot(scope)
            if (snapshot.state !in setOf(ChatState.RESPONDING, ChatState.HANDING_OVER, ChatState.AWAITING_YOU)) return snapshot
            check(System.nanoTime() < deadline) { "Live provider transition timed out" }
            Thread.sleep(25)
        }
    }
}
