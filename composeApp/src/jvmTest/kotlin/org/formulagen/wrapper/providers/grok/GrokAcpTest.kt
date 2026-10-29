package org.formulagen.wrapper.providers.grok

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import kotlin.test.*

/** Local subprocess integration tests; these are not live Grok inference tests. */
class GrokAcpTest {
    private val methods = setOf("initialize", "session/new", "session/prompt")

    @Test fun initializationDoesNotAuthenticateOrPromptAndEnforcesTheMethodAllowlist(): Unit = FakeGrokAcp().use { fake ->
        val rpc = GrokAcp(fake.builder())
        try {
            assertEquals(GrokHandshake(GrokAuthAvailability.SIGNED_OUT, true), grokHandshake(rpc.call("initialize", grokInitialize())))
            assertFailsWith<IllegalArgumentException> { rpc.call("authenticate") }
            assertFailsWith<IllegalArgumentException> { rpc.call("session/prompt") }
            assertFailsWith<IllegalArgumentException> { rpc.cancel("opaque/session") }
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun textUpdatesArriveBeforeThePromptTerminalResponse(): Unit = FakeGrokAcp().use { fake ->
        val updates = CopyOnWriteArrayList<JsonObject>()
        val rpc = GrokAcp(fake.builder(), methods, onNotification = { method, params ->
            if (method == "session/update") updates += params
        })
        try {
            rpc.call("initialize", grokInitialize())
            val result = rpc.call("session/prompt")
            // The peer wrote the update before the response, so it was dispatched before the call returned.
            val update = updates.single()
            assertEquals(JsonPrimitive("opaque/session"), update["sessionId"])
            assertEquals(JsonPrimitive("agent_message_chunk"), update["update"]!!.jsonObject["sessionUpdate"])
            assertEquals(JsonPrimitive("Hello"), update["update"]!!.jsonObject["content"]!!.jsonObject["text"])
            assertEquals(JsonPrimitive("end_turn"), result["stopReason"])
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun onlyOptedInExactWatcherRepliesCanCoexistWithPendingRequests() {
        FakeGrokAcp("watcher").use { fake ->
            val rpc = GrokAcp(fake.builder(), methods, allowReloadResponses = true)
            try { assertEquals(JsonPrimitive("end_turn"), rpc.call("session/prompt")["stopReason"]) }
            finally { rpc.close(); rpc.awaitClosed() }
        }
        for (mode in listOf("watcher", "unknown-response", "malformed-watcher", "numeric-string")) FakeGrokAcp(mode).use { fake ->
            val rpc = GrokAcp(fake.builder(), methods, allowReloadResponses = mode != "watcher")
            try { assertFailsWith<GrokTransportFailure>(mode) { rpc.call("session/prompt") } }
            finally { rpc.close(); rpc.awaitClosed() }
        }
    }

    @Test fun concurrentResponsesAreCorrelatedByIdInsteadOfArrivalOrder(): Unit = FakeGrokAcp("reverse").use { fake ->
        val rpc = GrokAcp(fake.builder(), methods)
        try {
            rpc.call("initialize", grokInitialize())
            val first = CompletableFuture.supplyAsync { rpc.call("session/new", buildJsonObject { put("label", "first") }) }
            val second = CompletableFuture.supplyAsync { rpc.call("session/new", buildJsonObject { put("label", "second") }) }
            assertEquals(JsonPrimitive("first"), first.get(5, TimeUnit.SECONDS)["sessionId"])
            assertEquals(JsonPrimitive("second"), second.get(5, TimeUnit.SECONDS)["sessionId"])
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun permissionAnswersKeepOpaqueIdsAndAreSentOnlyOnce(): Unit = FakeGrokAcp("permission").use { fake ->
        val rpc = GrokAcp(fake.builder(), methods, onRequest = { _, _, answer ->
            val refusal = buildJsonObject { putJsonObject("outcome") { put("outcome", "selected"); put("optionId", "deny-17") } }
            answer(refusal); answer(refusal)
        })
        try {
            rpc.call("initialize", grokInitialize())
            val reply = rpc.call("session/prompt")["permissionReply"]!!.jsonObject
            assertEquals(JsonPrimitive("opaque-permission"), reply["id"])
            assertEquals(JsonPrimitive("deny-17"), reply["result"]!!.jsonObject["outcome"]!!.jsonObject["optionId"])
            assertEquals(JsonPrimitive("opaque/session"), rpc.call("session/new")["sessionId"])
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun unsupportedClientRequestsFailTheTurnWithoutInvokingAHandler(): Unit = FakeGrokAcp("unsupported").use { fake ->
        val notices = CopyOnWriteArrayList<String>()
        val rpc = GrokAcp(fake.builder(), methods, onNotification = { method, _ -> notices.add(method) },
            onRequest = { _, _, _ -> error("Filesystem request must never reach an approval handler") })
        try {
            rpc.call("initialize", grokInitialize())
            assertFailsWith<GrokTransportFailure> { rpc.call("session/prompt") }
            assertEquals(listOf("request/refused"), notices.toList())
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun closingDuringPermissionWaitInvalidatesALateAnswer(): Unit = FakeGrokAcp("permission").use { fake ->
        val requested = CountDownLatch(1)
        var answer: ((JsonObject?) -> Unit)? = null
        val rpc = GrokAcp(fake.builder(), methods, onRequest = { _, _, callback -> answer = callback; requested.countDown() })
        try {
            rpc.call("initialize", grokInitialize())
            val prompt = CompletableFuture.supplyAsync { assertFailsWith<GrokTransportFailure> { rpc.call("session/prompt") } }
            assertTrue(requested.await(3, TimeUnit.SECONDS))
            rpc.close()
            answer?.invoke(buildJsonObject { putJsonObject("outcome") { put("outcome", "selected"); put("optionId", "allow") } })
            prompt.get(3, TimeUnit.SECONDS)
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun remoteErrorsAreSanitizedAndDoNotReplayThePrompt(): Unit = FakeGrokAcp("error").use { fake ->
        val rpc = GrokAcp(fake.builder(), methods)
        try {
            rpc.call("initialize", grokInitialize())
            val error = assertFailsWith<GrokRequestRejected> { rpc.call("session/prompt") }
            assertEquals(-32000, error.code)
            assertEquals("Grok rejected the request", error.message)
            assertNull(error.cause)
            assertEquals(JsonPrimitive("opaque/session"), rpc.call("session/new")["sessionId"])
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun timeoutInvalidatesTheConnectionBeforeAnyLaterPrompt(): Unit = FakeGrokAcp("hang").use { fake ->
        val rpc = GrokAcp(fake.builder(), methods)
        try {
            rpc.call("initialize", grokInitialize())
            assertFailsWith<GrokTransportFailure> { rpc.call("session/prompt", timeout = 100.milliseconds) }
            assertFailsWith<IllegalStateException> { rpc.call("session/prompt") }
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun deadlineAlsoBoundsBlockedStdin(): Unit = FakeGrokAcp("blocked-stdin").use { fake ->
        val rpc = GrokAcp(fake.builder(), methods)
        try {
            rpc.call("initialize", grokInitialize())
            val result = CompletableFuture.supplyAsync {
                assertFailsWith<GrokTransportFailure> {
                    rpc.call("session/prompt", buildJsonObject { put("text", "x".repeat(200_000)) }, 100.milliseconds)
                }
            }
            result.get(3, TimeUnit.SECONDS)
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun cancelIsNotAnAcknowledgementUntilThePromptReturnsCancelled(): Unit = FakeGrokAcp("hang").use { fake ->
        val received = CountDownLatch(1)
        val rpc = GrokAcp(fake.builder(), methods, onNotification = { name, _ ->
            if (name == "test/prompt-received") received.countDown()
        })
        try {
            rpc.call("initialize", grokInitialize())
            val prompt = CompletableFuture.supplyAsync { rpc.call("session/prompt") }
            assertTrue(received.await(3, TimeUnit.SECONDS))
            rpc.cancel("opaque/session")
            assertEquals(JsonPrimitive("cancelled"), prompt.get(5, TimeUnit.SECONDS)["stopReason"])
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun closeReapsTheOwnedProcessAndItsLocalChildren(): Unit = FakeGrokAcp("child").use { fake ->
        val rpc = GrokAcp(fake.builder(), methods)
        rpc.call("initialize", grokInitialize())
        val pid = Files.readString(fake.root.resolve("child.pid")).toLong()
        assertTrue(ProcessHandle.of(pid).get().isAlive)

        rpc.close(); rpc.close(); rpc.awaitClosed(5.seconds)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test fun malformedOversizedAndExitedPeersNeverLeaveAUsableConnection() {
        for (mode in listOf("exit", "oversized", "malformed", "wrong-envelope")) FakeGrokAcp(mode).use { fake ->
            val rpc = GrokAcp(fake.builder())
            try {
                assertFailsWith<GrokTransportFailure>(mode) { rpc.call("initialize", grokInitialize()) }
                assertFailsWith<IllegalStateException>(mode) { rpc.call("initialize", grokInitialize()) }
            } finally { rpc.close(); rpc.awaitClosed() }
        }
    }

    @Test fun opaqueSessionIdsAreBoundedWithoutAssumingTheyAreUuids() {
        assertTrue(validGrokSessionId("opaque/session:id"))
        assertTrue(validGrokSessionId("x".repeat(4_096)))
        assertFalse(validGrokSessionId("x".repeat(4_097)))
        assertFalse(validGrokSessionId("é".repeat(2_049)))
        assertFalse(validGrokSessionId("line\nbreak"))
        assertFalse(validGrokSessionId(""))
    }
}
