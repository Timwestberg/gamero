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
class DesktopChatSmokeTest {
    @Test fun installedCodexAcceptsConversationConfiguration() {
        val executable = System.getenv("HOVEL_CHAT_CODEX_SMOKE")
        assumeTrue("Set HOVEL_CHAT_CODEX_SMOKE to an installed codex executable", executable != null)
        val home = Files.createTempDirectory("hovel-chat-config-smoke")
        try {
            val runtime=CodexRuntime(Path.of(requireNotNull(executable)), home); runtime.prepare()
            chatCodex(runtime) { _, _ -> }.use { rpc ->
                assertEquals(JsonNull,rpc.call("account/read",buildJsonObject { put("refreshToken",false) })["account"])
                val result=rpc.call("thread/start",chatThreadParams(Persona.companion("Ember"),home.toString(),null))
                assertNotNull(result["thread"]?.jsonObject?.get("id"))
                println("CHAT_CODEX_SMOKE: real app-server accepted isolated unauthenticated conversation configuration")
            }
        } finally { home.toFile().deleteRecursively() }
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
            assertEquals(Readiness.Ready, claude.readiness("local"))
            val persona = Persona.companion("Ember")
            fun turn(conversation: NativeConversation?, text: String): Pair<String, String> {
                val done = java.util.concurrent.CompletableFuture<Pair<String, String>>()
                claude.startTurn(TurnRequest("local", java.util.UUID.randomUUID().toString(), persona, "haiku", conversation, null, text), object : TurnEvents {
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
