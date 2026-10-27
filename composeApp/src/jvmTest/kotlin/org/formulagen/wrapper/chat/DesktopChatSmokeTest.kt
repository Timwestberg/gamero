package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.VoiceState
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
                val result=rpc.call("thread/start",chatThreadParams("Ember",home.toString(),null))
                assertNotNull(result["thread"]?.jsonObject?.get("id"))
                println("CHAT_CODEX_SMOKE: real app-server accepted isolated unauthenticated conversation configuration")
            }
        } finally { home.toFile().deleteRecursively() }
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
