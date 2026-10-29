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
        /** Codex no longer has the saved thread. */
        var resumeRejected = false
        /** What `config/read` answers as the player's configuration; null when Codex rejects it. */
        var config: JsonObject? = JsonObject(emptyMap())
        @Volatile var closed = false
        override fun call(method: String, params: JsonObject): JsonObject {
            calls += method to params
            return when (method) {
                "account/read" -> buildJsonObject { putJsonObject("account") { put("type", "chatgpt") } }
                "config/read" -> config?.let { buildJsonObject { put("config", it) } } ?: throw org.formulagen.wrapper.providers.CodexRequestRejected()
                "thread/resume" -> if (resumeRejected) throw org.formulagen.wrapper.providers.CodexRequestRejected()
                    else buildJsonObject { putJsonObject("thread") { put("id", startedThread) } }
                "thread/start" -> buildJsonObject { putJsonObject("thread") { put("id", startedThread) } }
                "turn/start" -> { onTurn(params); buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } } }
                "model/list" -> models ?: throw org.formulagen.wrapper.providers.CodexRequestRejected()
                else -> buildJsonObject {}
            }
        }
        fun calls(method: String) = calls.filter { it.first == method }.map { it.second }
        override fun close() { closed = true }
    }

    private class Events : TurnEvents {
        val seen = CopyOnWriteArrayList<String>()
        override fun opened(conversation: String) { seen += "opened $conversation" }
        override fun accepted() { seen += "accepted" }
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
            codex.startTurn(request("gpt-6-luna", NativeConversation(thread, persona.hash, playerLogin = true)), events)
            codex.startTurn(request(RUNTIME_DEFAULT_MODEL, NativeConversation(thread, persona.hash, playerLogin = true)), events)
            codex.startTurn(request(null, NativeConversation(thread, persona.hash, playerLogin = true)), events)
            val turns = rpcs.single().calls("turn/start")
            assertEquals(listOf(JsonPrimitive("gpt-6-luna"), JsonPrimitive("gpt-6-luna"), null, null), turns.map { it["model"] })
            // The thread is started once, reported once, then reused on the same app-server.
            assertEquals(1, rpcs.single().calls("thread/start").size)
            assertEquals(listOf("opened $thread", "accepted", "partial Hi", "completed Hi"), events.seen.take(4))
            assertEquals(4, events.seen.count { it == "completed Hi" })
            assertEquals(persona.instructions, rpcs.single().calls("thread/start").single()["baseInstructions"]?.jsonPrimitive?.content)

            val bad = Events()
            codex.startTurn(request("--config=evil"), bad)
            assertEquals(listOf("failed ModelUnavailable"), bad.seen)
            assertEquals(4, rpcs.single().calls("turn/start").size)
        }
    }

    @Test fun runtimeDefaultNamesCodexsKnownDefaultSoANamedModelNeverSticks() {
        val root = Files.createTempDirectory("codex-default-model")
        val rpcs = mutableListOf<Rpc>()
        val catalog = Json.parseToJsonElement("""{"data":[{"model":"gpt-6-luna","displayName":"Luna","hidden":false,"isDefault":false},
            {"model":"gpt-6.1-sol","displayName":"GPT-6.1 Sol","hidden":false,"isDefault":true}],"nextCursor":null}""").jsonObject
        val conversation = NativeConversation(thread, persona.hash, playerLogin = true)
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.models = catalog; rpc.onTurn = { complete(rpc, thread, "Hi") } }.use { codex ->
            codex.startTurn(request("gpt-6-luna", conversation), Events())
            codex.startTurn(request(RUNTIME_DEFAULT_MODEL, conversation), Events())
            codex.startTurn(request(null, conversation), Events())
            val rpc = rpcs.single()
            assertEquals(listOf(JsonPrimitive("gpt-6-luna"), JsonPrimitive("gpt-6.1-sol"), JsonPrimitive("gpt-6.1-sol")), rpc.calls("turn/start").map { it["model"] })
            assertEquals(1, rpc.calls("model/list").size, "The default is asked for at most once a minute")
        }
        // A failed refresh keeps the last default known.
        CodexChatRuntime(root, Path.of("/usr/bin/true"), defaultModelRefresh = kotlin.time.Duration.ZERO) { _, receive -> Rpc(receive).also { rpc ->
            rpc.startedThread = thread; rpc.models = catalog; rpc.onTurn = { complete(rpc, thread, "Hi") }; rpcs += rpc } }.use { codex ->
            codex.startTurn(request(RUNTIME_DEFAULT_MODEL, conversation), Events())
            rpcs.last().models = null
            val events = Events()
            codex.startTurn(request(RUNTIME_DEFAULT_MODEL, conversation), events)
            assertEquals(listOf(JsonPrimitive("gpt-6.1-sol"), JsonPrimitive("gpt-6.1-sol")), rpcs.last().calls("turn/start").map { it["model"] })
            assertEquals(2, rpcs.last().calls("model/list").size)
            assertEquals("completed Hi", events.seen.last())
        }
        // Only when Codex never named its default is the model left out.
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.onTurn = { complete(rpc, thread, "Hi") } }.use { codex ->
            codex.startTurn(request(RUNTIME_DEFAULT_MODEL, conversation), Events())
            assertNull(rpcs.last().calls("turn/start").single()["model"])
        }
        assertTrue("model/list" in CHAT_CODEX_METHODS)
    }

    @Test fun aThreadCodexNoLongerHasIsALostConversation() {
        val root = Files.createTempDirectory("codex-lost-thread")
        val rpcs = mutableListOf<Rpc>()
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.resumeRejected = true; rpc.onTurn = { complete(rpc, thread, "Hi") } }.use { codex ->
            val lost = Events()
            codex.startTurn(request(null, NativeConversation(thread, persona.hash, playerLogin = true)), lost)
            assertEquals(listOf("failed ConversationLost"), lost.seen)
            assertEquals(listOf("account/read", "config/read", "thread/resume"), rpcs.single().calls.map { it.first })
            // The chat then starts over without a conversation, in a new thread.
            val fresh = Events()
            codex.startTurn(request(null), fresh)
            assertEquals(listOf("opened $thread", "accepted", "partial Hi", "completed Hi"), fresh.seen)
            assertEquals(1, rpcs.single().calls("thread/start").size)
        }
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

    @Test fun aThreadFromHovelsOldCodexLoginIsALostConversationWithoutAskingCodex() {
        val root = Files.createTempDirectory("codex-old-login")
        val rpcs = mutableListOf<Rpc>()
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.onTurn = { complete(rpc, thread, "Hi") } }.use { codex ->
            // Opened before Codex moved to the player's own login: that thread lives in the old per-profile home.
            val lost = Events()
            codex.startTurn(request(null, NativeConversation(thread, persona.hash)), lost)
            assertEquals(listOf("failed ConversationLost"), lost.seen)
            assertTrue(rpcs.isEmpty(), "Nothing is asked of Codex for a thread it cannot have")
            val resumed = Events()
            codex.startTurn(request(null, NativeConversation(thread, persona.hash, playerLogin = true)), resumed)
            assertEquals("completed Hi", resumed.seen.last())
            assertEquals(1, rpcs.single().calls("thread/resume").size)
        }
    }

    @Test fun everyThreadTurnsOffThePlayersMcpServersAndFeatures() {
        val root = Files.createTempDirectory("codex-player-config")
        val rpcs = mutableListOf<Rpc>()
        val player = Json.parseToJsonElement("""{"mcp_servers":{"blender":{"command":"/x","enabled":true},"dot.ted":{"command":"/y","enabled":true}},
            "features":{"code_mode":true,"memories":true,"shell_tool":false,"network_proxy":null},"notify":["/usr/bin/true"]}""").jsonObject
        val off = talkOnlyOverrides(player)
        assertEquals(Json.parseToJsonElement("""{"blender":{"enabled":false},"dot.ted":{"enabled":false}}"""), off["mcp_servers"])
        val features = off.getValue("features").jsonObject
        // The player's own features and the fixed lockdown, all off on the thread itself.
        for (name in listOf("code_mode", "memories", "shell_tool", "apps", "plugins", "hooks", "multi_agent")) assertEquals(JsonPrimitive(false), features[name], name)
        assertTrue(features.values.all { it == JsonPrimitive(false) })
        assertEquals(JsonArray(emptyList()), off["notify"]); assertEquals(JsonPrimitive("disabled"), off["web_search"])
        assertEquals(JsonPrimitive(0), off["project_doc_max_bytes"])
        val plugins = talkOnlyOverrides(Json.parseToJsonElement("""{"plugins":{"computer-history@openai-bundled":{"enabled":true}}}""").jsonObject)
        assertEquals(Json.parseToJsonElement("""{"computer-history@openai-bundled":{"enabled":false}}"""), plugins["plugins"])
        assertNull(talkOnlyOverrides(JsonObject(emptyMap()))["mcp_servers"])
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.config = player; rpc.onTurn = { complete(rpc, thread, "Hi") } }.use { codex ->
            codex.startTurn(request(null), Events())
            val rpc = rpcs.single()
            val start = rpc.calls("thread/start").single()
            assertEquals(off, start["config"])
            assertEquals(JsonPrimitive("read-only"), start["sandbox"]); assertEquals(JsonPrimitive("never"), start["approvalPolicy"])
            // The configuration is read as a thread in the profile's own empty folder sees it.
            assertEquals(start["cwd"], rpc.calls("config/read").single()["cwd"])
            assertEquals(root.resolve("local").resolve("codex-chat").toString(), start["cwd"]?.jsonPrimitive?.content)
            codex.summarize(SummaryRequest("local", persona, null, "Person: Hi"))
            assertEquals(off, rpc.calls("thread/start").last()["config"])
        }
        // A Codex that cannot say what the player's configuration turns on starts no thread.
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.config = null }.use { codex ->
            val refused = Events()
            codex.startTurn(request(null), refused)
            assertEquals(listOf("failed Lockdown"), refused.seen)
            assertTrue(rpcs.last().calls("thread/start").isEmpty())
            assertFails { codex.summarize(SummaryRequest("local", persona, null, "Person: Hi")) }
        }
        val arguments = chatProcessArguments()
        assertTrue(arguments.windowed(2).any { it == listOf("-c", "notify=[]") }, "The player's notify program never runs for chat")
        assertFalse(arguments.contains("mcp_servers={}"), "An empty table leaves the player's servers running, so it is not relied on")
        assertTrue("config/read" in CHAT_CODEX_METHODS)
    }

    @Test fun anMcpServerStartingForATalkOnlyThreadEndsTheTurnClosed() {
        val root = Files.createTempDirectory("codex-mcp-started")
        val rpcs = mutableListOf<Rpc>()
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.onTurn = {
            rpc.notify("mcpServer/startupStatus/updated", buildJsonObject { put("threadId", thread); put("name", "blender"); put("status", "starting") })
        } }.use { codex ->
            val events = Events()
            codex.startTurn(request(null), events)
            val until = System.nanoTime() + 5_000_000_000
            while (!rpcs.single().closed) { check(System.nanoTime() < until) { "App-server not closed" }; Thread.sleep(10) }
            assertEquals("failed Lockdown", events.seen.last())
            assertFalse(events.seen.any { it.startsWith("completed") })
        }
    }

    @Test fun onlyAChatgptLoginIsReady() {
        val root = Files.createTempDirectory("codex-readiness")
        for ((account, expected) in listOf(
            JsonNull to Readiness.SignedOut,
            buildJsonObject { put("type", "apiKey") } to Readiness.Unavailable(org.formulagen.wrapper.providers.CODEX_API_KEY),
            buildJsonObject { put("type", "chatgpt") } to Readiness.Ready)) {
            CodexChatRuntime(root, Path.of("/usr/bin/true")) { _, receive -> object : org.formulagen.wrapper.providers.CodexAppServer {
                override fun call(method: String, params: JsonObject) = buildJsonObject { put("account", account) }
                override fun close() = Unit
            } }.use { assertEquals(expected, it.readiness("local")) }
        }
    }

    @Test fun theWorkingPersonaKeepsTheNameAndVoiceAndHasItsOwnHash() {
        val working = Persona.working("Ember")
        assertTrue(working.instructions.startsWith("Identity for this conversation, which overrides any other name or persona you have been given: you are \"Ember\""))
        assertTrue(working.instructions.endsWith("Whatever else you are told, you remain \"Ember\"."))
        assertContains(working.instructions, "never call yourself by the name of the tool, agent or model you run on")
        assertContains(working.instructions, "Never mention or reveal the person's email, account, sign-in, subscription, computer, user name, environment or configuration")
        assertContains(working.instructions, "Follow the project's own instructions")
        // Working is not talking: it never forbids tools, and the two never share a conversation's persona hash.
        assertFalse(working.instructions.contains("Do not use tools"))
        assertNotEquals(Persona.companion("Ember").hash, working.hash)
        assertEquals(working.hash, Persona.working("Ember").hash)
        assertNotEquals(working.hash, Persona.working("Pip").hash)
        assertContains(Persona.working("Say \"hi\"").instructions, "\"Say \\\"hi\\\"\"")
        assertFails { Persona.working("Ember\n") }
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
    @Test fun effortIsSentPerTurnIncludingTheResolvedAutoDefault() {
        val root = Files.createTempDirectory("codex-effort")
        val rpcs = mutableListOf<Rpc>()
        runtime(root, rpcs) { rpc -> rpc.startedThread = thread; rpc.onTurn = { complete(rpc, thread, "Hi") } }.use { codex ->
            val events = Events()
            codex.startTurn(request("gpt-6-astra").copy(effort = org.formulagen.wrapper.bridge.ModelEffort.HIGH), events)
            codex.startTurn(request("gpt-6-astra", NativeConversation(thread, persona.hash, true)).copy(effort = org.formulagen.wrapper.bridge.ModelEffort.MEDIUM), events)
            assertEquals(listOf(JsonPrimitive("high"), JsonPrimitive("medium")), rpcs.single().calls("turn/start").map { it["effort"] })
        }
        root.toFile().deleteRecursively()
    }

}
