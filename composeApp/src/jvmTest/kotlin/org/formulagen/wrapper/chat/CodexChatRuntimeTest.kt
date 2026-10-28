package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.providers.CodexAppServer
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

class CodexChatRuntimeTest {
    private val thread = "10000000-0000-4000-8000-000000000001"
    private val ephemeral = "10000000-0000-4000-8000-0000000000e1"
    private val persona = Persona.companion("Ember")

    /** Records every call; [onTurn] plays the app-server's notifications for a started turn. */
    private class Rpc(val notify: (String, JsonObject) -> Unit) : CodexAppServer {
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        var onTurn: (JsonObject) -> Unit = {}
        var startedThread = ""
        /** What `model/list` answers; null when Codex rejects it. */
        var models: JsonObject? = null
        @Volatile var closed = false
        override fun call(method: String, params: JsonObject): JsonObject {
            calls += method to params
            return when (method) {
                "account/read" -> buildJsonObject { putJsonObject("account") { put("type", "chatgpt") } }
                "thread/start", "thread/resume" -> buildJsonObject { putJsonObject("thread") { put("id", startedThread) } }
                "turn/start" -> { onTurn(params); buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } } }
                "model/list" -> models ?: throw org.formulagen.wrapper.providers.CodexRequestRejected()
                else -> buildJsonObject {}
            }
        }
        fun calls(method: String) = calls.filter { it.first == method }.map { it.second }
        override fun loginResult(loginId: String): Boolean? = null
        override fun close() { closed = true }
    }

    private class Events : TurnEvents {
        val seen = CopyOnWriteArrayList<String>()
        override fun opened(conversation: String) { seen += "opened $conversation" }
        override fun partial(text: String) { seen += "partial $text" }
        override fun completed(text: String, conversation: String) { seen += "completed $text" }
        override fun failed(failure: ChatFailure) { seen += "failed $failure" }
    }

    private fun runtime(root: Path, rpcs: MutableList<Rpc>, setup: (Rpc) -> Unit = {}) =
        CodexChatRuntime(root, Path.of("/usr/bin/true")) { _, receive -> Rpc(receive).also { setup(it); rpcs += it } }

    private fun request(model: String?, conversation: NativeConversation? = null) =
        TurnRequest("local", "20000000-0000-4000-8000-000000000002", persona, model, conversation, context = null, text = "Hello")

    private fun complete(rpc: Rpc, threadId: String, text: String) {
        rpc.notify("item/agentMessage/delta", buildJsonObject { put("threadId", threadId); put("turnId", "turn-1"); put("itemId", "m"); put("delta", text) })
        rpc.notify("turn/completed", buildJsonObject { put("threadId", threadId); putJsonObject("turn") { put("id", "turn-1"); put("status", "completed") } })
    }

    @Test fun sendsAnExplicitModelOnEveryTurnAndOmitsTheDefault() {
        val root = Files.createTempDirectory("codex-model")
        val rpcs = mutableListOf<Rpc>()
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.onTurn = { complete(rpc, thread, "Hi") } }.use { codex ->
            val events = Events()
            codex.startTurn(request("gpt-6-luna"), events)
            codex.startTurn(request("gpt-6-luna", NativeConversation(thread, persona.hash)), events)
            codex.startTurn(request(RUNTIME_DEFAULT_MODEL, NativeConversation(thread, persona.hash)), events)
            codex.startTurn(request(null, NativeConversation(thread, persona.hash)), events)
            val turns = rpcs.single().calls("turn/start")
            assertEquals(listOf(JsonPrimitive("gpt-6-luna"), JsonPrimitive("gpt-6-luna"), null, null), turns.map { it["model"] })
            // The thread is started once, reported once, then reused on the same app-server.
            assertEquals(1, rpcs.single().calls("thread/start").size)
            assertEquals(listOf("opened $thread", "partial Hi", "completed Hi"), events.seen.take(3))
            assertEquals(4, events.seen.count { it == "completed Hi" })
            assertEquals(persona.instructions, rpcs.single().calls("thread/start").single()["baseInstructions"]?.jsonPrimitive?.content)

            val bad = Events()
            codex.startTurn(request("--config=evil"), bad)
            assertEquals(listOf("failed ModelUnavailable"), bad.seen)
            assertEquals(4, rpcs.single().calls("turn/start").size)
        }
    }

    @Test fun theDefaultModelIsNamedToRevertAnExplicitOneOnTheSameThread() {
        val root = Files.createTempDirectory("codex-default-model")
        val rpcs = mutableListOf<Rpc>()
        val catalog = Json.parseToJsonElement("""{"data":[{"model":"gpt-6-luna","displayName":"Luna","hidden":false,"isDefault":false},
            {"model":"gpt-6.1-sol","displayName":"GPT-6.1 Sol","hidden":false,"isDefault":true}],"nextCursor":null}""").jsonObject
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.models = catalog; rpc.onTurn = { complete(rpc, thread, "Hi") } }.use { codex ->
            val named = NativeConversation(thread, persona.hash, explicitModel = true)
            codex.startTurn(request(RUNTIME_DEFAULT_MODEL, named), Events())
            codex.startTurn(request(null, named), Events())
            codex.startTurn(request(RUNTIME_DEFAULT_MODEL, NativeConversation(thread, persona.hash)), Events())
            codex.startTurn(request("gpt-6-luna", named), Events())
            val rpc = rpcs.single()
            assertEquals(listOf(JsonPrimitive("gpt-6.1-sol"), JsonPrimitive("gpt-6.1-sol"), null, JsonPrimitive("gpt-6-luna")), rpc.calls("turn/start").map { it["model"] })
            assertEquals(1, rpc.calls("model/list").size, "The default is read once and kept")
        }
        // Without a model list the default is left out, as before.
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.onTurn = { complete(rpc, thread, "Hi") } }.use { codex ->
            val events = Events()
            codex.startTurn(request(RUNTIME_DEFAULT_MODEL, NativeConversation(thread, persona.hash, explicitModel = true)), events)
            assertNull(rpcs.last().calls("turn/start").single()["model"])
            assertEquals("completed Hi", events.seen.last())
        }
        assertTrue("model/list" in CHAT_CODEX_METHODS)
    }

    @Test fun summarizesInAnEphemeralThreadWithoutTouchingTheCompanionThread() {
        val root = Files.createTempDirectory("codex-summary")
        val rpcs = mutableListOf<Rpc>()
        runtime(root, rpcs) { rpc -> rpc.startedThread = ephemeral; rpc.onTurn = { complete(rpc, ephemeral, "  " + "s".repeat(3000)) } }.use { codex ->
            val summary = codex.summarize(SummaryRequest("local", persona, "gpt-6-sol", "user: Hello\nassistant: Hi"))
            assertEquals("s".repeat(SUMMARY_LIMIT), summary)
            val rpc = rpcs.single()
            val start = rpc.calls("thread/start").single()
            assertEquals(JsonPrimitive(true), start["ephemeral"])
            assertNull(start["threadId"])
            assertEquals(persona.instructions, start["baseInstructions"]?.jsonPrimitive?.content)
            val turn = rpc.calls("turn/start").single()
            assertEquals(JsonPrimitive(ephemeral), turn["threadId"])
            assertEquals(JsonPrimitive("gpt-6-sol"), turn["model"])
            val input = turn.getValue("input").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            assertEquals(summaryInstruction("user: Hello\nassistant: Hi"), input)
            assertContains(input, "<<<\nuser: Hello\nassistant: Hi\n>>>")
            assertTrue(rpc.calls("thread/resume").isEmpty())
        }
    }

    @Test fun aSummaryFailsWhenCodexStopsOrTakesTooLong() {
        val root = Files.createTempDirectory("codex-summary-fail")
        val rpcs = mutableListOf<Rpc>()
        runtime(root, rpcs) { rpc -> rpc.startedThread = ephemeral; rpc.onTurn = { rpc.notify("transport/closed", buildJsonObject {}) } }.use { codex ->
            assertFails { codex.summarize(SummaryRequest("local", persona, null, "user: Hello")) }
            val until = System.nanoTime() + 5_000_000_000
            while (!rpcs.single().closed) { check(System.nanoTime() < until) { "App-server not closed" }; Thread.sleep(10) }
        }
        runtime(root, rpcs) { rpc -> rpc.startedThread = ephemeral }.use { codex ->
            assertFails { codex.summarize(SummaryRequest("local", persona, null, "user: Hello", timeout = 50.milliseconds)) }
            assertEquals(1, rpcs.last().calls("turn/interrupt").size)
        }
        runtime(root, rpcs) { rpc -> rpc.startedThread = ephemeral; rpc.onTurn = { complete(rpc, ephemeral, "   ") } }.use { codex ->
            assertFails { codex.summarize(SummaryRequest("local", persona, null, "user: Hello")) }
        }
    }

    @Test fun turnErrorsBecomeRuntimeNeutralFailures() {
        fun error(info: JsonElement) = buildJsonObject { put("message", "raw"); put("codexErrorInfo", info) }
        assertEquals(ChatFailure.UsageLimit(null), codexFailure(error(JsonPrimitive("usageLimitExceeded"))))
        assertEquals(ChatFailure.SignedOut, codexFailure(error(JsonPrimitive("unauthorized"))))
        assertEquals(ChatFailure.Temporary, codexFailure(error(JsonPrimitive("serverOverloaded"))))
        assertEquals(ChatFailure.Temporary, codexFailure(error(buildJsonObject { putJsonObject("httpConnectionFailed") { put("httpStatusCode", 502) } })))
        assertEquals(ChatFailure.Unexpected, codexFailure(error(JsonPrimitive("contextWindowExceeded"))))
        assertEquals(ChatFailure.Unexpected, codexFailure(null))
    }

    @Test fun thePersonaQuotesTheNameForbidsPersonalDetailsAndHashesStably() {
        val ember = Persona.companion("Ember")
        assertContains(ember.instructions, "Your display name is \"Ember\".")
        assertContains(ember.instructions, "Do not use tools, read files, run commands")
        assertTrue(ember.instructions.endsWith("Never mention the person's email, account, computer or environment, and stay in character as \"Ember\"."))
        assertContains(Persona.companion("Say \"hi\"").instructions, "\"Say \\\"hi\\\"\"")
        assertTrue(Regex("^[0-9a-f]{64}$").matches(ember.hash))
        assertEquals(ember.hash, Persona.companion("Ember").hash)
        assertNotEquals(ember.hash, Persona.companion("Pip").hash)
        assertFails { Persona.companion(" Ember") }
        assertEquals("Hello", request(null).input())
        assertEquals("Earlier:\n<<<\nx\n>>>\n\nHello", request(null).copy(context = "Earlier:\n<<<\nx\n>>>").input())
    }
}
