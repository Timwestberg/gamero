package org.formulagen.wrapper.chat.codex

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalBroker
import org.formulagen.wrapper.approvals.ApprovalDecision
import org.formulagen.wrapper.approvals.ApprovalKind
import org.formulagen.wrapper.approvals.ApprovalRequest
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.providers.CodexAppServer
import kotlin.test.*

/** A working Codex member against a fake app-server that replays requests shaped like Codex's schema (not yet seen live). */
class CodexWorkRuntimeTest {
    private val member = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)
    private val thread = "01a18132-e43c-7a73-87f7-edb57ac9d74c"
    private val root: Path = Files.createTempDirectory("codex-work")
    private val folder: Path = Files.createDirectories(root.resolve("hovel-notes")).toRealPath()

    /** The app-server: records calls; [onTurn] plays a started turn through [notify] and [request]. */
    private inner class Server(val notify: (String, JsonObject) -> Unit, val request: (String, JsonObject, (JsonObject?) -> Unit) -> Unit) : CodexAppServer {
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        var onTurn: Server.() -> Unit = {}
        override fun call(method: String, params: JsonObject): JsonObject {
            calls += method to params
            return when (method) {
                "account/read" -> buildJsonObject { putJsonObject("account") { put("type", "chatgpt") } }
                "thread/start", "thread/resume" -> buildJsonObject { putJsonObject("thread") { put("id", thread) } }
                "turn/start" -> { Thread.ofPlatform().daemon().start { onTurn() }; buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } } }
                else -> buildJsonObject {}
            }
        }
        /** Ask as Codex would and wait for the answer. */
        fun ask(method: String, params: JsonObject): JsonObject? {
            val answer = CompletableFuture<JsonObject?>()
            request(method, params) { answer.complete(it) }
            return answer.get(10, TimeUnit.SECONDS)
        }
        fun item(state: String, item: JsonObject) = notify("item/$state", buildJsonObject { put("threadId", thread); put("turnId", "turn-1"); put("item", item) })
        override fun close() = Unit
    }

    private class Events : TurnEvents {
        val seen = CopyOnWriteArrayList<String>()
        val activities = CopyOnWriteArrayList<Activity>()
        val waitings = CopyOnWriteArrayList<ChatWaiting?>()
        val end = CompletableFuture<String>()
        override fun opened(conversation: String) { seen += "opened $conversation" }
        override fun accepted() { seen += "accepted" }
        override fun partial(text: String) { seen += "partial $text" }
        override fun completed(text: String, conversation: String) { seen += "completed $text"; end.complete("completed") }
        override fun failed(failure: ChatFailure) { seen += "failed $failure"; end.complete("failed $failure") }
        override fun activity(activity: Activity) { activities += activity }
        override fun waiting(waiting: ChatWaiting?) { waitings += waiting }
    }

    private class Broker(vararg answers: ApprovalDecision) : ApprovalBroker {
        val queue = ArrayDeque(answers.toList())
        val asked = CopyOnWriteArrayList<ApprovalRequest>()
        override fun ask(request: ApprovalRequest, cancel: CancelSignal, shown: () -> Unit): ApprovalDecision {
            asked += request; shown(); return synchronized(queue) { queue.removeFirst() }
        }
    }

    private fun request() = TurnRequest("local", "20000000-0000-4000-8000-000000000002", Persona.working("Ember"), null, null, null,
        "Add a leap-year test.", WorkScope(folder, "lease-1", "Ember", member))

    @AfterTest fun cleanUp() { root.toFile().deleteRecursively() }

    @Test fun approvalsGoToTheDialogAndActivityFollowsTheItems() {
        val servers = CopyOnWriteArrayList<Server>()
        val answers = CopyOnWriteArrayList<JsonObject?>()
        val broker = Broker(ApprovalDecision.ALLOW_FOR_CONVERSATION, ApprovalDecision.ALLOW_ONCE)
        val codex = CodexWorkRuntime(root, Path.of("/usr/bin/true"), broker) { _, notify, request -> Server(notify, request).also { server ->
            server.onTurn = {
                val change = buildJsonObject { put("type", "fileChange"); put("id", "item-1"); put("status", "inProgress")
                    putJsonArray("changes") { addJsonObject { put("path", "$folder/tests/date.test.ts"); put("kind", "add"); put("diff", "+a\n+b\n-c") } } }
                item("started", change)
                answers += ask("item/fileChange/requestApproval", buildJsonObject { put("itemId", "item-1"); put("threadId", thread); put("turnId", "turn-1"); put("startedAtMs", 1) })
                item("completed", JsonObject(change + ("status" to JsonPrimitive("completed"))))
                answers += ask("item/commandExecution/requestApproval", buildJsonObject { put("itemId", "item-2"); put("threadId", thread); put("turnId", "turn-1")
                    put("command", "/bin/zsh -lc 'npm test'"); put("cwd", folder.toString()); put("startedAtMs", 2) })
                item("completed", buildJsonObject { put("type", "commandExecution"); put("id", "item-2"); put("command", "/bin/zsh -lc 'npm test'")
                    put("cwd", folder.toString()); put("status", "completed"); put("exitCode", 0); put("aggregatedOutput", "13 passing") })
                answers += ask("mcpServer/elicitation/request", buildJsonObject { put("serverName", "linear"); put("threadId", thread) })
                answers += ask("item/tool/requestUserInput", buildJsonObject { put("threadId", thread) })
                answers += ask("item/permissions/requestApproval", buildJsonObject { put("threadId", thread) })
                answers += ask("applyPatchApproval", buildJsonObject {})
                // An approval that does not say exactly what it would run is declined without a dialog.
                answers += ask("item/commandExecution/requestApproval", buildJsonObject { put("itemId", "unknown"); put("threadId", thread) })
                notify("item/agentMessage/delta", buildJsonObject { put("threadId", thread); put("turnId", "turn-1"); put("itemId", "m"); put("delta", "Added it; 13 passing.") })
                notify("turn/completed", buildJsonObject { put("threadId", thread); putJsonObject("turn") { put("id", "turn-1"); put("status", "completed") } })
            }
            servers += server
        } }
        codex.use {
            val events = Events()
            codex.startTurn(request(), events)
            assertEquals("completed", events.end.get(10, TimeUnit.SECONDS))
            val server = servers.single()
            // The player's own Codex decides what to ask: no sandbox or approval override, the persona as developer instructions.
            val start = server.calls.first { it.first == "thread/start" }.second
            assertEquals(setOf("cwd", "approvalsReviewer", "developerInstructions"), start.keys)
            assertEquals(JsonPrimitive("user"), start["approvalsReviewer"])
            assertEquals(Persona.working("Ember").instructions, start["developerInstructions"]?.jsonPrimitive?.content)
            assertEquals(listOf(
                buildJsonObject { put("decision", "acceptForSession") }, buildJsonObject { put("decision", "accept") },
                buildJsonObject { put("action", "decline"); put("content", JsonNull) }, buildJsonObject { putJsonObject("answers") {} },
                buildJsonObject { putJsonObject("permissions") {}; put("scope", "turn") }, null, buildJsonObject { put("decision", "decline") }), answers.toList())
            // The dialog names the file from its item and the command without its shell wrapper.
            assertEquals(listOf(ApprovalKind.EDIT to "Edit $folder/tests/date.test.ts", ApprovalKind.COMMAND to "npm test"), broker.asked.map { it.kind to it.title })
            assertEquals("Ember wants to edit files: Edit tests/date.test.ts", broker.asked[0].summary())
            assertEquals("Allow `npm test` for this conversation", broker.asked[1].sessionLabel)
            assertEquals(WaitingKind.APPROVAL, events.waitings.first()?.kind); assertNull(events.waitings.last())
            assertEquals(listOf(
                ActivityStep(ActivityKind.EDIT, "tests/date.test.ts", ActivityStatus.DONE, "+2 -1"),
                ActivityStep(ActivityKind.COMMAND, "npm test", ActivityStatus.DONE, "13 passing"),
                ActivityStep(ActivityKind.CONNECTOR, "linear", ActivityStatus.DENIED, "Declined: a connector asked a question Hovel cannot show yet."),
                ActivityStep(ActivityKind.OTHER, "A question for you", ActivityStatus.DENIED, "Declined: answer in the chat instead."),
                ActivityStep(ActivityKind.OTHER, "More permissions", ActivityStatus.DENIED, "Declined: Hovel does not grant Codex more permissions than your own settings."),
                ActivityStep(ActivityKind.COMMAND, "A request Hovel could not show", ActivityStatus.DENIED, "Declined: Hovel could not show exactly what it would do."),
            ), events.activities.last().steps)
            assertEquals(listOf("opened $thread", "accepted"), events.seen.take(2))
            assertEquals("completed Added it; 13 passing.", events.seen.last())
        }
    }

    @Test fun aStopCancelsWhatWaitsAndInterruptsTheTurn() {
        val servers = CopyOnWriteArrayList<Server>()
        val answer = CompletableFuture<JsonObject?>()
        val broker = ApprovalBroker { _, cancel, shown ->
            shown()
            while (!cancel.isCancelled) Thread.sleep(10)
            ApprovalDecision.ALLOW_ONCE
        }
        val codex = CodexWorkRuntime(root, Path.of("/usr/bin/true"), broker) { _, notify, request -> Server(notify, request).also { server ->
            server.onTurn = { answer.complete(ask("item/commandExecution/requestApproval", buildJsonObject { put("itemId", "i"); put("command", "rm -rf build") })) }
            servers += server
        } }
        codex.use {
            val events = Events()
            val handle = codex.startTurn(request(), events)
            val until = System.nanoTime() + 5_000_000_000L
            while (events.waitings.isEmpty()) { check(System.nanoTime() < until); Thread.sleep(10) }
            handle.stop()
            assertEquals(buildJsonObject { put("decision", "cancel") }, answer.get(5, TimeUnit.SECONDS))
            while (servers.single().calls.none { it.first == "turn/interrupt" }) { check(System.nanoTime() < until); Thread.sleep(10) }
            assertFalse(events.end.isDone)
        }
    }

    @Test fun shellWrappersAreUnwrappedForThePage() {
        assertEquals("npm test", unwrapShell("/bin/zsh -lc 'npm test'"))
        assertEquals("echo 'hi'", unwrapShell("bash -lc 'echo '\\''hi'\\'''"))
        assertEquals("ls -la", unwrapShell("ls -la"))
        assertEquals(setOf("account/read", "thread/start", "thread/resume", "turn/start", "turn/interrupt"), WORK_CODEX_METHODS)
    }
    @Test fun workingTurnsPassEffortToCodex() {
        val servers = CopyOnWriteArrayList<Server>()
        CodexWorkRuntime(root, Path.of("/usr/bin/true"), Broker()) { _, notify, req -> Server(notify, req).also { servers += it } }.use { codex ->
            codex.startTurn(request().copy(effort = ModelEffort.MAX), Events())
            assertEquals(JsonPrimitive("max"), servers.single().calls.single { it.first == "turn/start" }.second["effort"])
        }
    }

}
