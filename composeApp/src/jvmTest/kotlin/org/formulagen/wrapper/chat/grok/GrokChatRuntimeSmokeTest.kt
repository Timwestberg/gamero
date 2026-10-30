package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import kotlin.test.*
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.chat.NativeConversation
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.providers.GrokProvider
import org.formulagen.wrapper.providers.grok.GrokSmoke

/** Full production adapter opt-in, including a saved handle reused by a new adapter instance. */
class GrokChatRuntimeSmokeTest {
    @Test fun nativeAdapterUsesTheSubscriptionAndRestoresConversationAfterRestart() {
        val env = GrokSmoke.environment(GrokSmoke.turnsExecutable())
        val root = Files.createTempDirectory("grok-adapter-smoke")
        try {
            val connection = GrokConnection(env)
            assertEquals(ProviderState.CONNECTED, GrokProvider(connection).status(ProviderAction.STATUS).state)
            val first = TalkEvents()
            GrokChatRuntime(root, env, connection).use { runtime ->
                runtime.startTurn(talkRequest().copy(text = "Remember HOVEL_ORCHID for this conversation. Reply briefly."), first)
                first.await(60)
                assertNull(first.failure)
                assertTrue("accepted" in first.seen)
            }
            GrokChatRuntime(root, env, connection).use { runtime ->
                val next = TalkEvents()
                runtime.startTurn(talkRequest(NativeConversation(checkNotNull(first.handle), talkPersona.hash)).copy(
                    text = "What word did I ask you to remember? Reply with the word only."), next)
                next.await(60)
                assertNull(next.failure)
                assertTrue(next.reply.orEmpty().contains("HOVEL_ORCHID"))
                assertEquals(first.handle, next.handle)
            }
            println("GROK_CHAT_SMOKE: provider connected, accepted/partial/completed delivered, private handle resumed after adapter restart")
        } finally { root.toFile().deleteRecursively() }
    }
}
