package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.VoiceState
import org.formulagen.wrapper.providers.ClaudeRuntime
import org.formulagen.wrapper.providers.CodexRuntime
import kotlin.test.*
import org.junit.Assume.assumeTrue

/** Explicit local checks, not evidence of authenticated inference or other hardware. */
class TalkRuntimeSmokeTest {
    @Test fun installedCodexReadinessSurvivesAccountNotifications() {
        val executable = System.getenv("HOVEL_CHAT_CODEX_SMOKE")
        assumeTrue("Set HOVEL_CHAT_CODEX_SMOKE to a signed-in codex executable", executable != null)
        val root = Files.createTempDirectory("hovel-codex-readiness-")
        try {
            CodexChatRuntime(root, Path.of(requireNotNull(executable))).use { runtime ->
                assertEquals(Readiness.Ready, runtime.readiness(ACCOUNT))
                assertEquals(Readiness.Ready, runtime.readiness(ACCOUNT))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun installedCodexAcceptsConversationConfiguration() {
        val executable = System.getenv("HOVEL_CHAT_CODEX_SMOKE")
        assumeTrue("Set HOVEL_CHAT_CODEX_SMOKE to an installed codex executable", executable != null)
        // The player's own Codex home, read only: the account and configuration are read, and one
        // ephemeral thread is started (no turn) to check that no MCP server starts under the overrides.
        val root = Files.createTempDirectory("hovel-chat-config-smoke")
        try {
            val runtime = CodexRuntime(Path.of(requireNotNull(executable)), root)
            val startups = java.util.concurrent.CopyOnWriteArrayList<String>()
            chatCodex(runtime) { method, params -> if (method == "mcpServer/startupStatus/updated") startups += params["name"].toString() }.use { rpc ->
                assertTrue(rpc.call("account/read", buildJsonObject { put("refreshToken", false) }).containsKey("account"))
                val folder = runtime.conversationFolder(ACCOUNT)
                val config = rpc.call("config/read", buildJsonObject { put("cwd", folder.toString()) }).getValue("config").jsonObject
                val result = rpc.call("thread/start", chatThreadParams(Persona.companion("Ember"), folder.toString(), null, ephemeral = true, talkOnlyOverrides(config)))
                assertNotNull(result["thread"]?.jsonObject?.get("id"))
                Thread.sleep(3000)
                assertTrue(startups.isEmpty(), "No MCP server starts for a talk-only thread: $startups")
                val sources = result["instructionSources"]?.jsonArray?.size
                println("CHAT_CODEX_SMOKE: the player's Codex accepted the talk-only configuration, started no MCP server, instruction sources=$sources")
            }
        } finally { root.toFile().deleteRecursively() }
    }

    /**
     * Two short Haiku turns on the owner's own Claude Code sign-in: a new session, then a resume.
     * Never signs in or out; runs in a fresh, empty workspace; prints states only, never output.
     */
    @Test fun installedClaudeRunsALockedDownTurnAndResumesIt() {
        val executable = System.getenv("HOVEL_CHAT_CLAUDE_SMOKE")
        assumeTrue("Set HOVEL_CHAT_CLAUDE_SMOKE to an installed claude executable", executable != null)
        val root = Files.createTempDirectory("hovel-claude-smoke")
        val runtime = ClaudeRuntime(Path.of(requireNotNull(executable)), root)
        val claude = ClaudeChatRuntime(runtime, org.formulagen.wrapper.providers.ClaudeConnection(runtime))
        claude.use {
            assertEquals(Readiness.Ready, claude.readiness(ACCOUNT))
            val persona = Persona.companion("Ember")
            fun turn(conversation: NativeConversation?, text: String): Pair<String, String> {
                val done = java.util.concurrent.CompletableFuture<Pair<String, String>>()
                claude.startTurn(TurnRequest(ACCOUNT, java.util.UUID.randomUUID().toString(), persona, "haiku", conversation, text), object : TurnEvents {
                    override fun opened(conversation: String) = Unit
                    override fun accepted() = Unit
                    override fun partial(text: String) = Unit
                    override fun completed(text: String, conversation: String) { done.complete(text to conversation) }
                    override fun failed(failure: ChatFailure) { done.completeExceptionally(AssertionError("Turn failed: $failure")) }
                })
                return done.get(90, TimeUnit.SECONDS)
            }
            val (first, session) = turn(null, "Reply with exactly one word: apple")
            assertTrue(first.contains("apple", ignoreCase = true))
            println("CLAUDE_SMOKE: new locked-down session passed system/init and replied (${first.length} chars)")
            val (second, resumed) = turn(NativeConversation(session, persona.hash), "Which fruit word did you just reply with? Answer with that one word.")
            assertEquals(session, resumed)
            assertTrue(second.contains("apple", ignoreCase = true))
            println("CLAUDE_SMOKE: resumed session remembered the first turn (${second.length} chars)")
        }
        root.toFile().deleteRecursively()
    }

    /**
     * One short Haiku working turn on the owner's own Claude Code and full setup, in a fresh scratch
     * folder (never a real project): the test's broker allows only an edit of notes.txt and denies
     * everything else, connectors included. Prints states only, never output.
     */
    @Test fun installedClaudeWorksInAScratchFolderAfterAnApproval() {
        val executable = System.getenv("HOVEL_CLAUDE_WORK_SMOKE")
        assumeTrue("Set HOVEL_CLAUDE_WORK_SMOKE to an installed claude executable", executable != null)
        val root = Files.createTempDirectory("hovel-claude-work-smoke")
        val folder = Files.createDirectories(root.resolve("project")).toRealPath()
        val notes = Files.writeString(folder.resolve("notes.txt"), "first note line\n")
        val runtime = ClaudeRuntime(Path.of(requireNotNull(executable)), root.resolve("providers"))
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val broker = org.formulagen.wrapper.approvals.ApprovalBroker { request, _, shown ->
            shown()
            asked += "${request.kind} ${request.title}"
            // The dialog names the file in full; only notes.txt in the scratch folder is allowed.
            val edit = request.subject as? org.formulagen.wrapper.approvals.ApprovalSubject.Edit
            if (edit != null && edit.files.map { it.toAbsolutePath().normalize() } == listOf(notes.toAbsolutePath().normalize()))
                org.formulagen.wrapper.approvals.ApprovalDecision.ALLOW_ONCE else org.formulagen.wrapper.approvals.ApprovalDecision.DENY
        }
        val haiku = org.formulagen.wrapper.bridge.TeamMember(org.formulagen.wrapper.bridge.RuntimeId.CLAUDE_CODE, "haiku")
        org.formulagen.wrapper.chat.claude.ClaudeWorkRuntime(runtime, org.formulagen.wrapper.providers.ClaudeConnection(runtime), broker).use { claude ->
            assertEquals(Readiness.Ready, claude.readiness(ACCOUNT))
            val done = java.util.concurrent.CompletableFuture<String>()
            val last = java.util.concurrent.atomic.AtomicReference<org.formulagen.wrapper.bridge.Activity?>()
            claude.startTurn(TurnRequest(ACCOUNT, java.util.UUID.randomUUID().toString(), Persona.working("Ember"), "haiku", null,
                "Read notes.txt, then append the line 'hello from Ember' to it with your edit tool. Do not run any commands.",
                WorkScope(folder, "smoke", "Ember", haiku)), object : TurnEvents {
                override fun opened(conversation: String) = Unit
                override fun accepted() = Unit
                override fun partial(text: String) = Unit
                override fun activity(activity: org.formulagen.wrapper.bridge.Activity) { last.set(activity) }
                override fun completed(text: String, conversation: String) { done.complete("completed (${text.length} chars)") }
                override fun failed(failure: ChatFailure) { done.complete("failed $failure") }
            })
            val outcome = done.get(300, TimeUnit.SECONDS)
            println("CLAUDE_WORK_SMOKE: $outcome; asked=${asked.map { it.substringBefore(' ') }}; steps=${last.get()?.steps?.map { "${it.kind}:${it.status}" }}")
            assertTrue(outcome.startsWith("completed"), outcome)
            assertTrue(Files.readString(notes).contains("hello from Ember"), "The approved edit changed the file")
        }
        root.toFile().deleteRecursively()
    }

    @Test fun localModelProducesDesktopPlaybackAndStops() {
        assumeTrue("Set HOVEL_CHAT_VOICE_SMOKE=1 with the voice files installed", System.getenv("HOVEL_CHAT_VOICE_SMOKE") == "1")
        val config=checkNotNull(desktopVoiceConfig(System.getenv()))
        DesktopSpeech(config).use { speech ->
            val started=System.nanoTime()
            speech.speak("Hello. I am ready to chat with you.")
            val deadline=started + TimeUnit.MINUTES.toNanos(2)
            while (speech.status().state == VoiceState.PREPARING && System.nanoTime()<deadline) Thread.sleep(20)
            assertEquals(VoiceState.SPEAKING,speech.status().state, speech.status().notice)
            println("CHAT_VOICE_SMOKE: playback started after ${(System.nanoTime()-started)/1_000_000} ms")
            Thread.sleep(250)
            speech.stop(); assertEquals(VoiceState.IDLE,speech.status().state)
            println("CHAT_VOICE_SMOKE: desktop audio stopped")
        }
    }
}

private const val ACCOUNT = "50000000-0000-4000-8000-000000000005"
