package org.formulagen.wrapper.runtimes.claude

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalBroker
import org.formulagen.wrapper.approvals.ApprovalDecision
import org.formulagen.wrapper.approvals.ApprovalDecision.*
import org.formulagen.wrapper.approvals.ApprovalKind
import org.formulagen.wrapper.approvals.ApprovalRequest
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.conversation.Activity
import org.formulagen.wrapper.conversation.ActivityKind
import org.formulagen.wrapper.conversation.ActivityStatus
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.conversation.TeamMember
import org.formulagen.wrapper.runtimes.*
import org.formulagen.wrapper.conversation.voice.*
import org.formulagen.wrapper.util.*
import org.formulagen.wrapper.providers.ClaudeConnection
import org.formulagen.wrapper.providers.ClaudeRuntime
import org.formulagen.wrapper.providers.claudeWorkEnvironment
import kotlin.test.*
import org.formulagen.wrapper.runtimes.claude.FakeClaudeWork.Companion.ask
import org.formulagen.wrapper.runtimes.claude.FakeClaudeWork.Companion.cancel
import org.formulagen.wrapper.runtimes.claude.FakeClaudeWork.Companion.emit
import org.formulagen.wrapper.runtimes.claude.FakeClaudeWork.Companion.exit
import org.formulagen.wrapper.runtimes.claude.FakeClaudeWork.Companion.waitInterrupt

/** A working Claude member against a fake long-lived `claude -p`, replaying the spike's recorded lines. */
class ClaudeWorkRuntimeTest {
    private val sonnet = TeamMember(RuntimeId.CLAUDE_CODE, "sonnet")
    private val persona = Persona.working("Ember")
    private val fake = FakeClaudeWork()
    /** The recorded lines name this folder; the fake's process runs in it. */
    private val folder: Path = Files.createDirectories(Path.of("/private/tmp/hovel-fixture/project"))
    private val init = """{"type":"system","subtype":"init","session_id":"SESSION_ID","tools":["Bash","Edit","Read"],"mcp_servers":[],"permissionMode":"default"}"""
    private val parent = mapOf("HOME" to System.getProperty("user.home"), "USER" to "player", "PATH" to "/usr/bin:/bin", "LANG" to "en_US.UTF-8",
        "ANTHROPIC_API_KEY" to "sk-ant-test", "ANTHROPIC_BASE_URL" to "https://proxy.example.test", "CLAUDECODE" to "1",
        "CLAUDE_CODE_ENTRYPOINT" to "parent-session", "CLAUDE_CONFIG_DIR" to "/Users/player/.claude-work", "PROJECT_TOOL_HOME" to "/opt/tool")

    /** Answers in order; [hold] waits for the request's cancel instead, and then denies. */
    private class Broker(vararg answers: ApprovalDecision, val hold: Boolean = false) : ApprovalBroker {
        val queue = ArrayDeque(answers.toList())
        val asked = CopyOnWriteArrayList<ApprovalRequest>()
        val cancelled = CopyOnWriteArrayList<Boolean>()
        override fun ask(request: ApprovalRequest, cancel: CancelSignal, shown: () -> Unit): ApprovalDecision {
            asked += request
            shown()
            if (hold) {
                val until = System.nanoTime() + 10_000_000_000L
                while (!cancel.isCancelled && System.nanoTime() < until) Thread.sleep(10)
                cancelled += cancel.isCancelled
                return DENY
            }
            return synchronized(queue) { queue.removeFirst() }
        }
    }

    private class Events : TurnEvents {
        val seen = CopyOnWriteArrayList<String>()
        val activities = CopyOnWriteArrayList<Activity>()
        val end = CompletableFuture<String>()
        override fun opened(conversation: String) { seen += "opened $conversation" }
        override fun accepted() { seen += "accepted" }
        override fun partial(text: String) { seen += "partial $text" }
        override fun completed(text: String, conversation: String) { seen += "completed $text"; end.complete("completed") }
        override fun failed(failure: ChatFailure) { seen += "failed $failure"; end.complete("failed $failure") }
        override fun activity(activity: Activity) { activities += activity }
        fun outcome() = end.get(20, TimeUnit.SECONDS)
    }

    private fun runtime(broker: ApprovalBroker, lineLimit: Int = WORK_LINE_LIMIT) = ClaudeWorkRuntime(ClaudeRuntime(fake.executable, fake.dir.resolve("providers"), parent),
        ClaudeConnection(null), broker, environment = { claudeWorkEnvironment(parent, "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin", fake.executable) },
        lineLimit = lineLimit)

    private fun request(conversation: NativeConversation? = null, text: String = "Read notes.txt, then append a line.", model: String = "sonnet") =
        TurnRequest("50000000-0000-4000-8000-000000000005", "20000000-0000-4000-8000-000000000002", persona, model, conversation, text = text,
            work = WorkScope(folder, "lease-1", "Ember", TeamMember(RuntimeId.CLAUDE_CODE, model)))

    /** The recorded turns of [name] as fake turns: each permission request is asked, everything else emitted, split at each result. */
    private fun recordedTurns(name: String): List<List<JsonObject>> {
        val turns = mutableListOf(mutableListOf(emit(init)))
        for (line in checkNotNull(javaClass.getResource("/claude/work/$name")).readText().lines().filter { it.isNotBlank() }) {
            val message = Json.parseToJsonElement(line).jsonObject
            turns.last() += if (message["type"]?.jsonPrimitive?.content == "control_request") ask(message) else emit(message)
            if (message["type"]?.jsonPrimitive?.content == "result") turns += mutableListOf(emit(init))
        }
        return turns.filter { it.size > 1 }
    }

    private fun recordedResponses(): List<JsonElement> = checkNotNull(javaClass.getResource("/claude/work/decisions.jsonl")).readText()
        .lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject.getValue("response") }

    private fun JsonObject.type() = this["type"]?.jsonPrimitive?.content

    @AfterTest fun cleanUp() { fake.dir.toFile().deleteRecursively() }

    @Test fun everyApprovalIsAnsweredAsRecordedAndOneProcessServesTheConversation() {
        fake.turns(*recordedTurns("turn-edit-and-denials.ndjson").toTypedArray())
        val broker = Broker(ALLOW_ONCE, DENY, DENY, ALLOW_FOR_CONVERSATION)
        runtime(broker).use { claude ->
            val first = Events()
            claude.startTurn(request(), first)
            assertEquals("completed", first.outcome())
            val session = first.seen.first().removePrefix("opened ")
            val second = Events()
            claude.startTurn(request(NativeConversation(session, persona.hash, playerLogin = true), "Append three lines."), second)
            assertEquals("completed", second.outcome())

            // Hovel's lines, in order: initialize first, each message, and each answer exactly as Claude Code accepted it.
            val lines = fake.stdin()
            assertEquals(JsonPrimitive("initialize"), lines[0]["request"]?.jsonObject?.get("subtype"))
            assertEquals(listOf("control_request", "user", "control_response", "control_response", "user", "control_response", "control_response"), lines.map { it.type() })
            assertEquals(recordedResponses().take(4), lines.filter { it.type() == "control_response" })
            // The dialog showed each request, relative to the project folder, with Claude's own grant named.
            assertEquals(listOf(ApprovalKind.EDIT, ApprovalKind.COMMAND, ApprovalKind.EDIT, ApprovalKind.EDIT), broker.asked.map { it.kind })
            assertEquals("Allow all edits in project for this conversation", broker.asked[0].sessionLabel)
            assertEquals(listOf("Ember", sonnet), listOf(broker.asked[0].companionName, broker.asked[0].member))
            // Each step as it finished.
            assertEquals(listOf(ActivityKind.READ to ActivityStatus.DONE, ActivityKind.EDIT to ActivityStatus.DONE, ActivityKind.COMMAND to ActivityStatus.DENIED),
                first.activities.last().steps.map { it.kind to it.status })
            assertEquals(listOf("opened $session", "accepted"), first.seen.take(2))
            assertEquals(listOf("accepted"), second.seen.filter { it.startsWith("opened") || it == "accepted" }, "The session opened once")
            assertTrue(first.seen.last().startsWith("completed The `Edit` worked, but the Bash step was denied"))
            // Nothing from initialize (the account) reaches an event.
            assertFalse((first.seen + second.seen + first.activities.map { it.toString() }).any { "player@example.test" in it })

            // One process for both turns: the player's own setup, the prompt routed to Hovel, the persona appended.
            val call = fake.calls().single()
            val argv = call.getValue("argv").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(claudeWorkArguments("sonnet", persona) + listOf("--session-id", session), argv)
            for (narrowing in listOf("--safe-mode", "--tools", "--strict-mcp-config", "--permission-mode", "--system-prompt", "--disallowedTools")) assertFalse(narrowing in argv, narrowing)
            assertEquals(folder.toRealPath().toString(), call.getValue("cwd").jsonPrimitive.content)
            val env = call.getValue("env").jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertFalse(env.keys.any { it.startsWith("ANTHROPIC_") || it == "CLAUDECODE" || it == "CLAUDE_CODE_ENTRYPOINT" }, env.keys.toString())
            assertEquals("/Users/player/.claude-work", env["CLAUDE_CONFIG_DIR"])
            assertEquals("/opt/tool", env["PROJECT_TOOL_HOME"], "The player's own environment stays")
            assertEquals(listOf(fake.dir.toString(), "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"), env.getValue("PATH").split(":"))
        }
    }

    @Test fun aStopWhileWaitingDeniesTheRequestAndInterruptsButKeepsTheProcess() {
        val edit = recordedTurns("turn-session-command.ndjson")[0].first { it.containsKey("ask") }
        fake.turns(listOf(emit(init), edit, waitInterrupt(), emit("""{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"SESSION_ID"}""")),
            listOf(emit(init), emit("""{"type":"result","subtype":"success","is_error":false,"result":"Done.","session_id":"SESSION_ID","terminal_reason":"completed"}""")))
        val broker = Broker(hold = true)
        runtime(broker).use { claude ->
            val stopped = Events()
            val handle = claude.startTurn(request(), stopped)
            val until = System.nanoTime() + 10_000_000_000L
            while (broker.asked.isEmpty()) { check(System.nanoTime() < until) { "Never asked" }; Thread.sleep(10) }
            handle.stop()
            while (fake.stdin().none { it.type() == "control_request" && it["request"]?.jsonObject?.get("subtype") == JsonPrimitive("interrupt") }) {
                check(System.nanoTime() < until) { "Never interrupted" }; Thread.sleep(10)
            }
            // The interrupt and the dialog's denial run on separate threads. Observe both before asserting.
            while (broker.cancelled.isEmpty() || fake.stdin().none { it.type() == "control_response" }) {
                check(System.nanoTime() < until) { "Dialog denial did not arrive" }; Thread.sleep(10)
            }
            assertEquals(listOf(true), broker.cancelled.toList(), "Stop cancels the dialog")
            val deny = fake.stdin().single { it.type() == "control_response" }.getValue("response").jsonObject.getValue("response").jsonObject
            assertEquals(JsonPrimitive("deny"), deny["behavior"])
            // The next message waits for the stopped turn's end, then runs on the same process.
            val next = Events()
            claude.startTurn(request(NativeConversation(stopped.seen.first().removePrefix("opened "), persona.hash, true), "Go on."), next)
            assertEquals("completed", next.outcome())
            assertEquals("completed Done.", next.seen.last())
            assertFalse(stopped.end.isDone, "A stopped turn reports nothing more")
            assertEquals(1, fake.calls().size)
        }
    }

    @Test fun anUnansweredDialogDeniesAndAProcessThatDiesFailsTheTurnKeepingItsSteps() {
        val turn = recordedTurns("turn-edit-and-denials.ndjson")[0]
        fake.turns(turn.take(4) + listOf(ask(turn[4].getValue("ask").jsonObject), exit(1)))
        val broker = Broker(DENY)
        runtime(broker).use { claude ->
            val events = Events()
            claude.startTurn(request(), events)
            assertEquals("failed Temporary", events.outcome())
            assertEquals(listOf(ActivityKind.READ), events.activities.last().steps.map { it.kind })
            assertEquals(JsonPrimitive("deny"), fake.stdin().last { it.type() == "control_response" }
                .getValue("response").jsonObject.getValue("response").jsonObject["behavior"])
        }
    }

    @Test fun aDenialThatNeverReachedHovelFailsTheTurn() {
        val lines = checkNotNull(javaClass.getResource("/claude/work/turn-without-host.ndjson")).readText().lines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
        fake.turns(listOf(emit(init)) + lines.takeWhile { it.type() != "result" }.map(::emit) + emit(lines.first { it.type() == "result" }))
        runtime(Broker()).use { claude ->
            val events = Events()
            claude.startTurn(request(), events)
            assertEquals("failed Unexpected", events.outcome())
        }
    }

    @Test fun otherRequestsAreRefusedUnreadableOnesDeniedAndWithdrawnOnesClosed() {
        val withdrawn = buildJsonObject {
            put("type", "control_request"); put("request_id", "withdrawn")
            putJsonObject("request") { put("subtype", "can_use_tool"); put("tool_name", "Bash"); putJsonObject("input") { put("command", "npm test") }; put("tool_use_id", "t3") }
        }
        fake.turns(listOf(emit(init),
            emit("""{"type":"control_request","request_id":"hook","request":{"subtype":"hook_callback","callback_id":"x"}}"""),
            ask(Json.parseToJsonElement("""{"type":"control_request","request_id":"odd","request":{"subtype":"can_use_tool","tool_name":"Edit","input":"x","tool_use_id":"t2"}}""").jsonObject),
            emit(withdrawn), cancel("withdrawn"),
            ask(Json.parseToJsonElement("""{"type":"control_request","request_id":"last","request":{"subtype":"can_use_tool","tool_name":"Read","input":{"file_path":"notes.txt"},"tool_use_id":"t4"}}""").jsonObject),
            emit("""{"type":"result","subtype":"success","is_error":false,"result":"Done.","session_id":"SESSION_ID","terminal_reason":"completed"}""")))
        val broker = ApprovalBroker { request, cancel, _ ->
            if (request.title == "npm test") { while (!cancel.isCancelled) Thread.sleep(10) }
            ALLOW_ONCE
        }
        runtime(broker).use { claude ->
            val events = Events()
            claude.startTurn(request(), events)
            assertEquals("completed", events.outcome())
            val answers = fake.stdin().filter { it.type() == "control_response" }.associate {
                val response = it.getValue("response").jsonObject
                response.getValue("request_id").jsonPrimitive.content to response
            }
            assertEquals(JsonPrimitive("error"), answers.getValue("hook")["subtype"])
            assertEquals(JsonPrimitive(ClaudeControl.UNREADABLE), answers.getValue("odd").getValue("response").jsonObject["message"])
            val until = System.nanoTime() + 5_000_000_000L
            while ("withdrawn" !in fake.stdin().filter { it.type() == "control_response" }.map { it.getValue("response").jsonObject.getValue("request_id").jsonPrimitive.content }) {
                check(System.nanoTime() < until) { "The withdrawn request was never closed" }; Thread.sleep(10)
            }
            assertEquals(listOf("A request Hovel could not show"), events.activities.last().steps.map { it.label })
        }
    }

    @Test fun theWorkingEnvironmentIsThePlayersMinusAnotherSessionsAndApiKeys() {
        val env = claudeWorkEnvironment(parent, null, Path.of("/Users/player/.local/bin/claude"))
        assertEquals("/Users/player/.local/bin:/usr/bin:/bin:/usr/sbin:/sbin", env["PATH"])
        assertEquals(setOf("HOME", "USER", "PATH", "LANG", "CLAUDE_CONFIG_DIR", "PROJECT_TOOL_HOME"), env.keys)
        val path = org.formulagen.wrapper.process.readLoginShellPath(System.getenv())
        assertTrue(path == null || path.split(":").all { it.isEmpty() || it.startsWith("/") })
    }

    private val success = """{"type":"result","subtype":"success","is_error":false,"result":"Done.","session_id":"SESSION_ID","terminal_reason":"completed"}"""

    @Test fun aSessionClaudeCannotResumeIsALostConversationAndANewOneStarts() {
        val gone = "50000000-0000-4000-8000-000000000005"
        fake.turns(listOf(emit(init), emit(success)), missing = listOf(gone))
        runtime(Broker()).use { claude ->
            val lost = Events()
            claude.startTurn(request(NativeConversation(gone, persona.hash, playerLogin = true)), lost)
            assertEquals("failed ConversationLost", lost.outcome())
            val fresh = Events()
            claude.startTurn(request(), fresh)
            assertEquals("completed", fresh.outcome())
            assertEquals(listOf("--resume", "--session-id"), fake.calls().map { call -> call.getValue("argv").jsonArray.map { it.jsonPrimitive.content }.first { it.startsWith("--") && it in setOf("--resume", "--session-id") } })
        }
    }

    @Test fun anotherModelReplacesTheProcessResumingTheSessionAfterTheOldOneExits() {
        fake.turns(listOf(emit(init), emit(success)), listOf(emit(init), emit(success)))
        runtime(Broker()).use { claude ->
            val first = Events()
            claude.startTurn(request(), first)
            assertEquals("completed", first.outcome())
            val session = first.seen.first().removePrefix("opened ")
            val second = Events()
            claude.startTurn(request(NativeConversation(session, persona.hash, true), model = "opus"), second)
            assertEquals("completed", second.outcome())
            val calls = fake.calls()
            assertEquals(2, calls.size)
            val argv = calls[1].getValue("argv").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("--model", "opus"), argv.subList(argv.indexOf("--model"), argv.indexOf("--model") + 2))
            assertEquals(listOf("--resume", session), argv.takeLast(2))
            // The old process had ended before the new one started, so the two never shared the session.
            val exited = fake.exits().first().getValue("ended").jsonPrimitive.double
            assertTrue(exited <= calls[1].getValue("started").jsonPrimitive.double)
        }
    }

    @Test fun linesTooLongToKeepAreStillAnswered() {
        val huge = "x".repeat(5_000)
        fake.turns(listOf(emit(init),
            ask(Json.parseToJsonElement("""{"type":"control_request","request_id":"big","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"file_path":"notes.txt","content":"$huge"},"tool_use_id":"t9"}}""").jsonObject),
            emit("""{"type":"result","subtype":"success","is_error":false,"result":"$huge","session_id":"SESSION_ID"}""")))
        runtime(Broker(ALLOW_ONCE), lineLimit = 2_000).use { claude ->
            val events = Events()
            claude.startTurn(request(), events)
            // The request is denied without a dialog; the result too long to read ends the turn.
            assertEquals("failed Unexpected", events.outcome())
            val answer = fake.stdin().single { it.type() == "control_response" }.getValue("response").jsonObject
            assertEquals(JsonPrimitive("big"), answer["request_id"])
            assertEquals(JsonPrimitive("deny"), answer.getValue("response").jsonObject["behavior"])
            assertEquals(listOf("A request too long to show"), events.activities.last().steps.map { it.label })
        }
    }

    @Test fun aRequestAfterTheTurnEndedIsDeniedWithoutADialog() {
        val late = Json.parseToJsonElement("""{"type":"control_request","request_id":"late","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"},"tool_use_id":"t5"}}""").jsonObject
        fake.turns(listOf(emit(init), emit(success), ask(late)))
        val broker = Broker(ALLOW_ONCE)
        runtime(broker).use { claude ->
            val events = Events()
            claude.startTurn(request(), events)
            assertEquals("completed", events.outcome())
            val until = System.nanoTime() + 5_000_000_000L
            while (fake.stdin().none { it.type() == "control_response" }) { check(System.nanoTime() < until) { "Never answered" }; Thread.sleep(10) }
            assertEquals(JsonPrimitive("deny"), fake.stdin().single { it.type() == "control_response" }.getValue("response").jsonObject.getValue("response").jsonObject["behavior"])
            assertTrue(broker.asked.isEmpty())
        }
    }

    @Test fun aModeThatDeniesOnItsOwnIsNotABrokenPromptAndTheReplyNeverShowsTheFoldersPath() {
        fake.turns(listOf(emit(init.replace("\"default\"", "\"dontAsk\"")),
            emit("""{"type":"system","subtype":"permission_denied","tool_name":"Edit","tool_use_id":"t","message":"Claude requested permissions to write to notes.txt, but you haven't granted it yet."}"""),
            emit("""{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"I left $folder/notes.txt as it was."}},"parent_tool_use_id":null}"""),
            emit(success)))
        runtime(Broker()).use { claude ->
            val events = Events()
            claude.startTurn(request(), events)
            assertEquals("completed", events.outcome())
            assertEquals("completed I left ./notes.txt as it was.", events.seen.last())
            assertTrue(events.seen.none { "/private/tmp" in it })
        }
    }
    @Test fun changingEffortReplacesTheProcessAndResumesTheSameConversation() {
        fake.turns(listOf(emit(init), emit(success)), listOf(emit(init), emit(success)))
        runtime(Broker()).use { claude ->
            val first = Events()
            claude.startTurn(request().copy(effort = org.formulagen.wrapper.bridge.ModelEffort.HIGH), first)
            assertEquals("completed", first.outcome())
            val session = first.seen.first().removePrefix("opened ")
            val second = Events()
            claude.startTurn(request(NativeConversation(session, persona.hash, true)), second)
            assertEquals("completed", second.outcome())
            val calls = fake.calls()
            assertEquals(2, calls.size)
            val args = calls.map { call -> call.getValue("argv").jsonArray.map { it.jsonPrimitive.content } }
            assertEquals("high", args[0][args[0].indexOf("--effort") + 1])
            assertFalse("--effort" in args[1])
            assertEquals(listOf("--resume", session), args[1].takeLast(2))
            assertTrue(fake.exits().first().getValue("ended").jsonPrimitive.double <= calls[1].getValue("started").jsonPrimitive.double)
        }
    }

}
