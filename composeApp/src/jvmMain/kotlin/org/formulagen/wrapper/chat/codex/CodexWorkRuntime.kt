package org.formulagen.wrapper.chat.codex

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalBroker
import org.formulagen.wrapper.approvals.ApprovalDecision
import org.formulagen.wrapper.approvals.ApprovalKind
import org.formulagen.wrapper.approvals.ApprovalRequest
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.approvals.shortened
import org.formulagen.wrapper.bridge.Activity
import org.formulagen.wrapper.bridge.ActivityKind
import org.formulagen.wrapper.bridge.ActivityStatus
import org.formulagen.wrapper.bridge.ChatWaiting
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.bridge.WaitingKind
import org.formulagen.wrapper.chat.ActivityLog
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.ChatRuntime
import org.formulagen.wrapper.chat.Readiness
import org.formulagen.wrapper.chat.ReplyText
import org.formulagen.wrapper.chat.SummaryRequest
import org.formulagen.wrapper.chat.TurnEvents
import org.formulagen.wrapper.chat.TurnHandle
import org.formulagen.wrapper.chat.TurnRequest
import org.formulagen.wrapper.chat.WorkPaths
import org.formulagen.wrapper.chat.codexFailure
import org.formulagen.wrapper.chat.loggingDaemonThreads
import org.formulagen.wrapper.providers.CodexAppServer
import org.formulagen.wrapper.providers.CodexRequestRejected
import org.formulagen.wrapper.providers.CodexRuntime
import org.formulagen.wrapper.providers.StdioCodexAppServer
import org.formulagen.wrapper.providers.codexAccountStatus

/**
 * Codex working in a companion's project folder with the player's own Codex (default home, their
 * configuration, MCP servers and `AGENTS.md`): `codex app-server` with no lockdown, a thread with
 * `{cwd, approvalsReviewer: "user", developerInstructions: <working persona>}` and no sandbox or
 * approval-policy override, so the player's own settings decide what is asked. Approval requests go
 * to [broker]; a file change's paths come from its `fileChange` item. MCP elicitations, permission
 * escalations and user-input requests are declined with a note in the activity.
 *
 * Read from Codex's schema only: the owner's allowance ran out during the spike, so this stays off
 * unless `HOVEL_CODEX_WORK=1` until it is verified live (after 2026-11-01). One app-server per
 * (chat lease, member), kept between turns so "for this conversation" grants last.
 */
internal class CodexWorkRuntime(
    private val providerRoot: Path,
    private val executable: Path,
    private val broker: ApprovalBroker,
    private val createRpc: (CodexRuntime, (String, JsonObject) -> Unit, (String, JsonObject, (JsonObject?) -> Unit) -> Unit) -> CodexAppServer = ::workCodex,
) : ChatRuntime {
    override val id = RuntimeId.CODEX
    private val connections = ConcurrentHashMap<Pair<String, TeamMember>, Connection>()
    private val approvals = Executors.newCachedThreadPool(loggingDaemonThreads("hovel-codex-approval"))
    @Volatile private var closed = false

    override fun readiness(profile: String): Readiness {
        val rpc = createRpc(CodexRuntime(executable, providerRoot), { _, _ -> }, { _, _, respond -> respond(null) })
        return rpc.use {
            val status = runCatching { codexAccountStatus(it.call("account/read", buildJsonObject { put("refreshToken", false) })["account"]) }.getOrNull()
            when (status?.state) {
                ProviderState.CONNECTED -> Readiness.Ready
                ProviderState.ERROR -> Readiness.Unavailable(status.message.orEmpty())
                else -> Readiness.SignedOut
            }
        }
    }

    override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
        val work = checkNotNull(turn.work) { "A working turn needs a project folder" }
        val model = when (turn.model) { null, RUNTIME_DEFAULT_MODEL -> null; else -> turn.model.takeIf(SharedRules::isModelId) ?: run {
            events.failed(ChatFailure.ModelUnavailable); return TurnHandle {} } }
        if (!Files.isDirectory(work.folder)) { events.failed(ChatFailure.Unexpected); return TurnHandle {} }
        check(!closed) { "Chat closed" }
        val key = work.lease to work.member
        val connection = connections[key]?.takeIf { it.folder == work.folder && it.open } ?: Connection(work.folder, work.companion, work.member).also {
            connections.remove(key)?.close(); connections[key] = it
        }
        return connection.turn(turn, model, events)
    }

    /** Summaries come from the member's talk-only runtime. */
    override fun summarize(request: SummaryRequest): String = throw UnsupportedOperationException("Working members summarize through their talk runtime")

    override fun release() { connections.keys.toList().forEach { connections.remove(it)?.close() } }

    override fun close() { closed = true; release(); approvals.shutdownNow() }

    /** One working app-server and its thread. */
    private inner class Connection(val folder: Path, private val companion: String, private val member: TeamMember) {
        private val paths = WorkPaths(folder)
        private val rpc = createRpc(CodexRuntime(executable, providerRoot), ::notify, ::request)
        @Volatile var open = true
            private set
        private var thread: String? = null
        @Volatile private var current: Turn? = null
        /**
         * Every turn event is handled here, in order and holding no lock, because the chat calls stop
         * and release while holding its own.
         */
        private val serial = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-codex-work"))
        /** File-change items by id, for the paths an approval request names. */
        private val fileChanges = ConcurrentHashMap<String, JsonObject>()
        private val pending = ConcurrentHashMap<Any, CancelSignal>()

        fun turn(request: TurnRequest, model: String?, events: TurnEvents): TurnHandle {
            val account = runCatching { codexAccountStatus(rpc.call("account/read", buildJsonObject { put("refreshToken", false) })["account"]) }.getOrNull()
            if (account?.state != ProviderState.CONNECTED) { events.failed(ChatFailure.SignedOut); return TurnHandle {} }
            val saved = request.conversation?.id
            val threadId = if (saved != null && saved == thread) saved else {
                val params = buildJsonObject {
                    saved?.let { put("threadId", it); put("excludeTurns", true) }
                    put("cwd", folder.toString()); put("approvalsReviewer", "user"); put("developerInstructions", request.persona.instructions)
                }
                val result = if (saved == null) rpc.call("thread/start", params)
                    else try { rpc.call("thread/resume", params) } catch (_: CodexRequestRejected) { events.failed(ChatFailure.ConversationLost); return TurnHandle {} }
                val id = result.getValue("thread").jsonObject.getValue("id").jsonPrimitive.content
                require(UUID.fromString(id).toString() == id)
                thread = id
                if (id != saved) events.opened(id)
                id
            }
            val turn = Turn(threadId, events, CodexActivity(paths))
            current = turn
            events.activity(turn.activity.activity())
            val started = rpc.call("turn/start", buildJsonObject {
                put("threadId", threadId)
                putJsonArray("input") { add(buildJsonObject { put("type", "text"); put("text", request.input()) }) }
                model?.let { put("model", it) }
            })
            val id = started.getValue("turn").jsonObject.getValue("id").jsonPrimitive.content
            events.accepted()
            later { turn.id = id; turn.flush() }
            return TurnHandle { stop(turn) }
        }

        /** Deny what waits on the person (`cancel`), then interrupt the turn; the app-server and its grants stay. */
        private fun stop(turn: Turn) {
            if (turn.stopped) return
            turn.stopped = true
            pending.values.forEach { it.cancel() }
            val id = turn.id ?: return
            approvals.execute { runCatching { rpc.call("turn/interrupt", buildJsonObject { put("threadId", turn.thread); put("turnId", id) }) } }
        }

        private fun notify(method: String, params: JsonObject) {
            if (method == "transport/closed") { open = false; later { current?.fail(ChatFailure.Temporary) }; return }
            val item = params["item"] as? JsonObject
            if (item?.text("type") == "fileChange") item.text("id")?.let { fileChanges[it] = item }
            later { current?.receive(method, params) }
        }

        /** A server request, answered on its own thread because the dialog blocks. */
        private fun request(method: String, params: JsonObject, respond: (JsonObject?) -> Unit) {
            approvals.execute { respond(answer(method, params)) }
        }

        private fun answer(method: String, params: JsonObject): JsonObject? {
            val turn = current
            return when (method) {
                "item/commandExecution/requestApproval" -> {
                    val command = paths.scrub(unwrapShell(params.text("command").orEmpty()))
                    val request = ApprovalRequest(companion, member, folder, ApprovalKind.COMMAND, command.shortened(240),
                        listOfNotNull(params.text("reason"), params.text("cwd")?.let { "In ${paths.label(it)}" }).joinToString("\n").shortened(400),
                        "Allow `${command.shortened(120)}` for this conversation")
                    buildJsonObject { put("decision", decide(turn, request)) }
                }
                "item/fileChange/requestApproval" -> {
                    val item = awaitItem(params.text("itemId"))
                    val changed = (item?.get("changes") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("path") }
                    if (changed.isEmpty() || params.text("grantRoot") != null) {
                        note(turn, ActivityKind.EDIT, "A file change Hovel could not show", "Declined: Hovel could not show which files it changes.")
                        return buildJsonObject { put("decision", "decline") }
                    }
                    val labels = changed.map(paths::label)
                    val request = ApprovalRequest(companion, member, folder, ApprovalKind.EDIT, "Edit ${labels.joinToString(", ")}".shortened(240),
                        params.text("reason").orEmpty().shortened(400), "Allow these edits for this conversation")
                    buildJsonObject { put("decision", decide(turn, request)) }
                }
                "mcpServer/elicitation/request" -> {
                    note(turn, ActivityKind.CONNECTOR, params.text("serverName") ?: "connector", "Declined: a connector asked a question Hovel cannot show yet.")
                    buildJsonObject { put("action", "decline"); put("content", JsonNull) }
                }
                "item/permissions/requestApproval" -> {
                    note(turn, ActivityKind.OTHER, "More permissions", "Declined: Hovel does not grant Codex more permissions than your own settings.")
                    buildJsonObject { putJsonObject("permissions") {}; put("scope", "turn") }
                }
                "item/tool/requestUserInput" -> {
                    note(turn, ActivityKind.OTHER, "A question for you", "Declined: answer in the chat instead.")
                    buildJsonObject { putJsonObject("answers") {} }
                }
                else -> null
            }
        }

        /** Runs [task] on [serial]; nothing runs once the connection is closed. */
        private fun later(task: () -> Unit) { try { serial.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) { } }

        private fun note(turn: Turn?, kind: ActivityKind, label: String, detail: String) { turn?.let { later { it.note(kind, label, detail) } } }

        /** The dialog's answer as Codex's decision; a stopped turn or closed chat is `cancel`. */
        private fun decide(turn: Turn?, request: ApprovalRequest): String {
            if (turn == null || turn.stopped || !open) return "cancel"
            val cancel = CancelSignal()
            val key = Any()
            pending[key] = cancel
            turn.events.waiting(ChatWaiting(WaitingKind.APPROVAL, request.summary()))
            val decision = try { broker.ask(request, cancel) } catch (_: Exception) { ApprovalDecision.DENY } finally { pending.remove(key) }
            turn.events.waiting(null)
            return when {
                cancel.isCancelled -> "cancel"
                decision == ApprovalDecision.ALLOW_ONCE -> "accept"
                decision == ApprovalDecision.ALLOW_FOR_CONVERSATION -> "acceptForSession"
                else -> "decline"
            }
        }

        /** The `fileChange` item an approval names; it may arrive just after the request. */
        private fun awaitItem(itemId: String?): JsonObject? {
            itemId ?: return null
            val until = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < until) { fileChanges[itemId]?.let { return it }; Thread.sleep(20) }
            return fileChanges[itemId]
        }

        fun close() {
            open = false
            pending.values.forEach { it.cancel() }
            later { current?.fail(ChatFailure.Temporary) }
            serial.shutdown()
            approvals.execute { runCatching { rpc.close() } }
        }

        /**
         * One turn: its reply text and activity, from its notifications, handled only on [serial].
         * Events before `turn/start` answers wait in [early].
         */
        private inner class Turn(val thread: String, val events: TurnEvents, val activity: CodexActivity) {
            @Volatile var id: String? = null
            @Volatile var stopped = false
            private val reply = ReplyText()
            private var done = false
            private val early = mutableListOf<Pair<String, JsonObject>>()

            fun receive(method: String, params: JsonObject) {
                if (done) return
                if (id == null) { if (early.size < 512) early += method to params; return }
                val eventTurn = (params["turnId"] as? JsonPrimitive)?.contentOrNull ?: ((params["turn"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                if (eventTurn != null && eventTurn != id) return
                if (reply.receive(method, params)) { if (!stopped) events.partial(reply.text); return }
                if (activity.receive(method, params) && !stopped) events.activity(activity.activity())
                when (method) {
                    "turn/completed" -> {
                        done = true
                        if (stopped) return
                        val ended = params["turn"] as? JsonObject
                        if ((ended?.get("status") as? JsonPrimitive)?.contentOrNull == "completed") events.completed(reply.text, thread)
                        else events.failed(codexFailure(ended?.get("error") as? JsonObject))
                    }
                    "error" -> if (params["willRetry"] != JsonPrimitive(true)) { done = true; if (!stopped) events.failed(codexFailure(params["error"] as? JsonObject)) }
                }
            }

            fun flush() { val held = early.toList(); early.clear(); held.forEach { (method, params) -> receive(method, params) } }

            fun note(kind: ActivityKind, label: String, detail: String) {
                if (done) return
                activity.note(kind, label, ActivityStatus.DENIED, detail)
                if (!stopped) events.activity(activity.activity())
            }

            fun fail(failure: ChatFailure) { if (done) return; done = true; if (!stopped) events.failed(failure) }
        }
    }
}

/**
 * A working Codex turn's activity from `item/completed` notifications (Codex's schema; verify live):
 * `commandExecution` (its command, exit and output), `fileChange` (its paths and diff size),
 * `mcpToolCall` (`server · tool`) and `webSearch` (the query).
 */
internal class CodexActivity(private val paths: WorkPaths) {
    private val log = ActivityLog()

    fun activity(): Activity = log.activity()

    fun note(kind: ActivityKind, label: String, status: ActivityStatus, detail: String?) = log.add(kind, label, status, detail)

    /** True when [method] added a step. */
    fun receive(method: String, params: JsonObject): Boolean {
        if (method != "item/completed") return false
        val item = params["item"] as? JsonObject ?: return false
        val status = item.text("status")
        fun outcome(failed: Boolean = false) = when (status) {
            "declined" -> ActivityStatus.DENIED
            "failed" -> ActivityStatus.FAILED
            else -> if (failed) ActivityStatus.FAILED else ActivityStatus.DONE
        }
        when (item.text("type")) {
            "commandExecution" -> {
                val exit = (item["exitCode"] as? JsonPrimitive)?.intOrNull
                log.add(ActivityKind.COMMAND, paths.scrub(unwrapShell(item.text("command").orEmpty())), outcome(failed = exit != null && exit != 0),
                    item.text("aggregatedOutput")?.let(paths::scrub)?.ifBlank { "(no output)" })
            }
            "fileChange" -> {
                val changes = (item["changes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val diff = changes.flatMap { it.text("diff").orEmpty().lines() }
                log.add(ActivityKind.EDIT, changes.mapNotNull { it.text("path") }.joinToString(", ") { paths.label(it) }, outcome(),
                    "+${diff.count { it.startsWith("+") && !it.startsWith("+++") }} -${diff.count { it.startsWith("-") && !it.startsWith("---") }}")
            }
            "mcpToolCall" -> log.add(ActivityKind.CONNECTOR, "${item.text("server")} · ${item.text("tool")}",
                outcome(failed = item["error"].let { it != null && it != JsonNull }),
                (item["error"] as? JsonObject)?.text("message") ?: (item["result"]?.takeIf { it != JsonNull }?.toString()?.let(paths::scrub)))
            "webSearch" -> log.add(ActivityKind.WEB, item.text("query").orEmpty(), ActivityStatus.DONE, null)
            "dynamicToolCall" -> log.add(ActivityKind.OTHER, item.text("tool").orEmpty(), outcome(failed = item["success"] == JsonPrimitive(false)), null)
            else -> return false
        }
        return true
    }
}

/** `/bin/zsh -lc 'npm test'` as `npm test`: Codex runs each command through the player's shell. */
internal fun unwrapShell(command: String): String {
    val wrapped = Regex("""^(?:/bin/|/usr/bin/)?(?:zsh|bash|sh) -l?c '(.*)'$""", RegexOption.DOT_MATCHES_ALL).find(command.trim())
    return wrapped?.groupValues?.get(1)?.replace("'\\''", "'") ?: command.trim()
}

/** A working app-server: the player's own Codex, unrestricted, answering server requests through [onRequest]. */
internal fun workCodex(runtime: CodexRuntime, receive: (String, JsonObject) -> Unit, onRequest: (String, JsonObject, (JsonObject?) -> Unit) -> Unit) =
    StdioCodexAppServer(runtime, allowedMethods = WORK_CODEX_METHODS, onNotification = receive, timeoutSeconds = 30, onRequest = onRequest)

/** What a working app-server may be asked: the account and its conversation. */
internal val WORK_CODEX_METHODS = setOf("account/read", "thread/start", "thread/resume", "turn/start", "turn/interrupt")

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
