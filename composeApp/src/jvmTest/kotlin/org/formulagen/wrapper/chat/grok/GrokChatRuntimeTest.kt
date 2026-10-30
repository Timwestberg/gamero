package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.brain.BrainRecord
import org.formulagen.wrapper.conversations.ConversationRecord
import org.formulagen.wrapper.conversations.ConversationStore
import java.util.UUID
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.providers.GrokProvider

class GrokChatRuntimeTest {
    @Test fun providerAndChatShareReadinessAndResumeOnlyTheirPrivateBinding() {
        FakeGrokRuntime().use { fake ->
            val connection = GrokConnection(fake.env)
            val provider = GrokProvider(connection)
            assertEquals(ProviderState.CONNECTED, provider.status(ProviderAction.STATUS).state)
            assertEquals("grok-test", provider.models().single().modelId)
            val first = TalkEvents()
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, connection, nothingRetained).use { runtime ->
                runtime.startTurn(talkRequest(), first)
                first.await()
                assertEquals(listOf("opened", "accepted", "partial", "completed"), first.seen)
                assertEquals("Hello", first.reply)
                assertNotEquals("provider/opaque-id", first.handle)
                val next = TalkEvents()
                runtime.startTurn(talkRequest(NativeConversation(checkNotNull(first.handle), talkPersona.hash)), next)
                next.await()
                assertEquals(listOf("accepted", "partial", "completed"), next.seen)
                assertEquals(first.handle, next.handle)
                assertEquals(2, fake.requests().count { it == "session/prompt" })
                assertEquals(1, fake.requests().count { it == "session/load" })
            }
            val restarted = TalkEvents()
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, connection, nothingRetained).use { runtime ->
                runtime.startTurn(talkRequest(NativeConversation(checkNotNull(first.handle), talkPersona.hash)), restarted)
                restarted.await()
                assertEquals("completed", restarted.seen.last())
                assertEquals(first.handle, restarted.handle)
            }
        }
    }

    @Test fun missingBindingAndPersonaChangeFailBeforeSubmissionWhileModelAndTransportErrorsNeverReplay() {
        FakeGrokRuntime().use { fake ->
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), nothingRetained).use { runtime ->
                for (saved in listOf(NativeConversation("11111111-1111-4111-8111-111111111111", talkPersona.hash),
                    NativeConversation("11111111-1111-4111-8111-111111111111", Persona.role("Other", "Coordinator", "Help the user.", working = false).hash))) {
                    val events = TalkEvents()
                    runtime.startTurn(talkRequest(saved), events); events.await()
                    assertEquals(ChatFailure.ConversationLost, events.failure)
                }
                val model = TalkEvents()
                runtime.startTurn(talkRequest().copy(model = "missing"), model); model.await()
                assertEquals(ChatFailure.ModelUnavailable, model.failure)
                assertFalse("session/prompt" in fake.requests())
                fake.mode("drop")
                val dropped = TalkEvents()
                runtime.startTurn(talkRequest(), dropped); dropped.await()
                assertEquals(ChatFailure.Temporary, dropped.failure)
                assertEquals(1, fake.requests().count { it == "session/prompt" })
                assertFalse("accepted" in dropped.seen)
            }
        }
    }

    @Test fun aRejectedSessionLoadLosesOnlyThatConversationAndDropsItsBinding() {
        FakeGrokRuntime().use { fake ->
            val providers = fake.root.resolve("providers")
            GrokChatRuntime(providers, fake.env, GrokConnection(fake.env), nothingRetained).use { runtime ->
                val first = TalkEvents()
                runtime.startTurn(talkRequest(), first); first.await()
                val saved = NativeConversation(checkNotNull(first.handle), talkPersona.hash)
                assertTrue(Files.exists(providers.resolve("grok-bindings/${saved.id}.json")))
                fake.mode("reject-load")
                val rejected = TalkEvents()
                runtime.startTurn(talkRequest(saved), rejected); rejected.await()
                assertEquals(ChatFailure.ConversationLost, rejected.failure)
                assertFalse(Files.exists(providers.resolve("grok-bindings/${saved.id}.json")))
                // The handle no longer names a session, so a repeat cannot repeat the failing load.
                val again = TalkEvents()
                runtime.startTurn(talkRequest(saved), again); again.await()
                assertEquals(ChatFailure.ConversationLost, again.failure)
                assertEquals(1, fake.requests().count { it == "session/load" })
                assertEquals(1, fake.requests().count { it == "session/prompt" })
            }
        }
    }

    @Test fun toolsFailBeforePrompt() {
        FakeGrokRuntime().use { fake ->
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), nothingRetained).use { runtime ->
                fake.mode("tools")
                val unsafe = TalkEvents()
                runtime.startTurn(talkRequest(), unsafe); unsafe.await()
                assertEquals(ChatFailure.Lockdown, unsafe.failure)
                assertFalse("session/prompt" in fake.requests())
            }
        }
    }

    @Test fun aCoordinatorsSessionIsKeptWhileItsConversationCanResumeIt() {
        FakeGrokRuntime().use { fake ->
            val providers = fake.root.resolve("providers")
            ConversationStore(fake.root.resolve("conversations"), ACCOUNT).open().use { store ->
                GrokChatRuntime(providers, fake.env, GrokConnection(fake.env), retained = { store.bindingHandles() }).use { runtime ->
                    val kept = TalkEvents(); runtime.startTurn(talkRequest(), kept); kept.await()
                    // The coordinator's conversation, as the engine records it once the turn opens it.
                    store.write(UUID.randomUUID().toString(), ConversationRecord(brain = BrainRecord(coordinatorContexts = mapOf(
                        "coordinator:grok-build" to NativeConversation(checkNotNull(kept.handle), talkPersona.hash)))))
                    val orphan = TalkEvents(); runtime.startTurn(talkRequest(), orphan); orphan.await()
                    val next = TalkEvents(); runtime.startTurn(talkRequest(), next); next.await()
                    assertTrue(Files.exists(providers.resolve("grok-bindings/${kept.handle}.json")))
                    assertFalse(Files.exists(providers.resolve("grok-bindings/${orphan.handle}.json")))
                    assertTrue(Files.exists(providers.resolve("grok-bindings/${next.handle}.json")))
                    val resumed = TalkEvents()
                    runtime.startTurn(talkRequest(NativeConversation(checkNotNull(kept.handle), talkPersona.hash)), resumed); resumed.await()
                    assertEquals(kept.handle, resumed.handle)
                }
            }
        }
    }

    @Test fun anUnreadableConversationPrunesNothing() {
        FakeGrokRuntime().use { fake ->
            val providers = fake.root.resolve("providers")
            GrokChatRuntime(providers, fake.env, GrokConnection(fake.env), retained = { null }).use { runtime ->
                val first = TalkEvents(); runtime.startTurn(talkRequest(), first); first.await()
                val next = TalkEvents(); runtime.startTurn(talkRequest(), next); next.await()
                assertTrue(Files.exists(providers.resolve("grok-bindings/${first.handle}.json")))
            }
        }
    }

    @Test fun aMessageSentRightAfterStoppingAnUnresponsiveTurnWaitsForItsTeardown() {
        FakeGrokRuntime().use { fake ->
            fake.mode("stuck")
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), nothingRetained).use { runtime ->
                val stuck = TalkEvents()
                val handle = runtime.startTurn(talkRequest(), stuck)
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while ("session/prompt" !in fake.requests()) { check(System.nanoTime() < until); Thread.sleep(10) }
                fake.mode("normal")
                handle.stop()
                // Grok ignores the cancellation, so the old turn holds the companion for its whole grace and teardown.
                val next = TalkEvents()
                runtime.startTurn(talkRequest(), next); next.await(GROK_TURN_HANDOFF.inWholeSeconds + 5)
                assertNull(next.failure)
                assertEquals("Hello", next.reply)
                assertFalse("completed" in stuck.seen || "failed" in stuck.seen)
            }
        }
    }

    @Test fun stopWaitsForCancellationAndDoesNotDeliverLateEvents() {
        FakeGrokRuntime().use { fake ->
            fake.mode("wait")
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), nothingRetained).use { runtime ->
                val events = TalkEvents()
                val handle = runtime.startTurn(talkRequest(), events)
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while ("session/prompt" !in fake.requests()) { check(System.nanoTime() < until); Thread.sleep(10) }
                handle.stop()
                while ("session/cancel" !in fake.requests()) { check(System.nanoTime() < until); Thread.sleep(10) }
                Thread.sleep(50)
                assertEquals(listOf("opened"), events.seen)
            }
        }
    }
}
