package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.NativeConversation
import org.formulagen.wrapper.chat.Persona
import org.formulagen.wrapper.chat.TurnEvents
import org.formulagen.wrapper.chat.TurnRequest
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.providers.GrokProvider
import org.junit.Assume.assumeTrue

/** Full production adapter opt-in, including a saved handle reused by a new adapter instance. */
class GrokChatRuntimeSmokeTest {
    @Test fun nativeAdapterUsesTheSubscriptionAndRestoresConversationAfterRestart() {
        val executable = System.getenv("HOVEL_GROK_CHAT_SMOKE")
        assumeTrue("Set HOVEL_GROK_CHAT_SMOKE for the two-prompt production adapter test", executable != null)
        val root = Files.createTempDirectory("grok-adapter-smoke")
        val env = System.getenv() + ("GAMERO_GROK_PATH" to requireNotNull(executable))
        try {
            val connection = GrokConnection(env)
            assertEquals(ProviderState.CONNECTED, GrokProvider(connection).status("local", ProviderAction.STATUS).state)
            val first = TalkSmokeEvents()
            GrokChatRuntime(root, env, connection).use { runtime ->
                runtime.startTurn(request().copy(text = "Remember HOVEL_ORCHID for this conversation. Reply briefly."), first)
                first.await(60)
                assertNull(first.failure)
                assertTrue("accepted" in first.seen)
            }
            GrokChatRuntime(root, env, connection).use { runtime ->
                val next = TalkSmokeEvents()
                runtime.startTurn(request(NativeConversation(checkNotNull(first.handle), persona.hash)).copy(
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

private val persona = Persona.companion("Ember")
private fun request(conversation: NativeConversation? = null) = TurnRequest("local", "20000000-0000-4000-8000-000000000002",
    persona, null, conversation, null, "Hello")

private class TalkSmokeEvents : TurnEvents {
    val seen = CopyOnWriteArrayList<String>()
    val done = CompletableFuture<Unit>()
    var handle: String? = null
    var reply: String? = null
    var failure: ChatFailure? = null
    override fun opened(conversation: String) { handle = conversation; seen += "opened" }
    override fun accepted() { seen += "accepted" }
    override fun partial(text: String) { reply = text; seen += "partial" }
    override fun completed(text: String, conversation: String) { reply = text; handle = conversation; seen += "completed"; done.complete(Unit) }
    override fun failed(failure: ChatFailure) { this.failure = failure; seen += "failed"; done.complete(Unit) }
    fun await(seconds: Long) { done.get(seconds, TimeUnit.SECONDS) }
}
