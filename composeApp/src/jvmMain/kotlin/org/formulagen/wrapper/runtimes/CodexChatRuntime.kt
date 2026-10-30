package org.formulagen.wrapper.runtimes

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.formulagen.wrapper.conversation.ChatLimits
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.providers.CodexAppServer
import org.formulagen.wrapper.providers.CodexRequestRejected
import org.formulagen.wrapper.providers.CodexRuntime
import org.formulagen.wrapper.providers.StdioCodexAppServer
import org.formulagen.wrapper.providers.codexAccountStatus
import org.formulagen.wrapper.providers.codexModelList
import org.formulagen.wrapper.util.loggingDaemonThreads

/**
 * Codex behind [ChatRuntime], on the player's own Codex sign-in and default home. Talk-only: the
 * player's configuration loads, so every thread turns off the MCP servers and features it enables
 * ([talkOnlyOverrides]) on top of the process lockdown ([chatProcessArguments]); an MCP server
 * that starts anyway ends the turn as [ChatFailure.Lockdown]. A thread opened on Hovel's old
 * Codex login (before turns ran on the player's own) cannot be resumed and is a lost conversation.
 *
 * One app-server serves the active account and is kept between turns;
 * it is dropped on [release], on a closed transport, on a refused tool request and when the account
 * is not ChatGPT, and the next turn starts a new one and resumes the thread again. A thread Codex no
 * longer has is a lost conversation, which the next turn starts fresh. Stopping
 * a turn closes the app-server, including a turn that is still starting.
 */
internal class CodexChatRuntime(
    private val providerRoot: Path,
    private val executable: Path,
    /** How often Codex is asked for its default model. */
    private val defaultModelRefresh: kotlin.time.Duration = kotlin.time.Duration.parse("1m"),
    private val createRpc: (CodexRuntime, (String, JsonObject) -> Unit) -> CodexAppServer = ::chatCodex,
) : ChatRuntime {
    override val id = RuntimeId.CODEX
    /** Guards [connection], [epoch] and each connection's turn. Never held while calling Codex or delivering events. */
    private val lock = Any()
    /** Serializes event delivery, so events buffered before a turn's acknowledgement keep their order with later ones. */
    private val delivery = Any()
    /** Closing an app-server can wait seconds for its process tree, so it never runs on a caller's thread. */
    private val releaser = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-chat-release"))
    private var connection: Connection? = null
    /** Codex's default model by account, from `model/list`: the last one known, and when it was last asked for (System.nanoTime). */
    private val defaults = ConcurrentHashMap<String, Pair<Long, String?>>()
    /** Changes on every connect and release; events from an older app-server are ignored. */
    private var epoch = 0
    private var closed = false

    /** [folder] is the account's empty conversation folder, where its threads start. */
    private class Connection(val accountId: String, val folder: Path, val rpc: CodexAppServer, val epoch: Int) {
        /** The thread this app-server has resumed or started; any other thread is resumed first. */
        var thread: String? = null
        var turn: Turn? = null
    }

    private class Turn(val thread: String, val events: TurnEvents) {
        /** Set once `turn/start` answers; events before then are held in [early]. */
        var id: String? = null
        val reply = ReplyText()
        val early = mutableListOf<Pair<String, JsonObject>>()
    }

    override fun readiness(accountId: String): Readiness = account(connect(accountId))

    /** Resume only the native-owned thread belonging to this companion, then start the turn. */
    override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
        val model = explicitModel(turn.model) ?: run { events.failed(ChatFailure.ModelUnavailable); return TurnHandle {} }
        // Opened on Hovel's old Codex login, which the player's own login does not have.
        if (turn.conversation?.playerLogin == false) { events.failed(ChatFailure.ConversationLost); return TurnHandle {} }
        val connection = connect(turn.accountId)
        if (account(connection) != Readiness.Ready) { events.failed(ChatFailure.SignedOut); return TurnHandle {} }
        val overrides = overrides(connection) ?: run { events.failed(ChatFailure.Lockdown); return TurnHandle {} }
        val thread = thread(connection, turn, events, overrides) ?: run { events.failed(ChatFailure.ConversationLost); return TurnHandle {} }
        // Codex keeps a named model for the later turns of a thread, so "runtime-default" names Codex's own
        // default whenever it is known; only when it never was is the model left out.
        val sent = model.ifEmpty { defaultModel(connection).orEmpty() }
        val active = Turn(thread, events)
        synchronized(lock) { current(connection); connection.turn = active }
        val started = connection.rpc.call("turn/start", buildJsonObject {
            put("threadId", thread)
            putJsonArray("input") { add(buildJsonObject { put("type", "text"); put("text", turn.text) }) }
            // Codex keeps a turn's model for later turns, so an explicit model is sent with every turn.
            sent.takeIf { it.isNotEmpty() }?.let { put("model", it) }
            turn.effort?.let { put("effort", it.value) }
        })
        val turnId = started.getValue("turn").jsonObject.getValue("id").jsonPrimitive.content
        synchronized(delivery) {
            val early = synchronized(lock) {
                if (this.connection !== connection || connection.turn !== active) null
                else { active.id = turnId; active.early.toList().also { active.early.clear() } }
            }
            // Codex answered turn/start, so the turn and its context reached it.
            if (early != null) events.accepted()
            early?.forEach { (method, params) -> handle(connection, active, method, params) }
        }
        return TurnHandle { detach(connection) }
    }

    override fun release() = synchronized(lock) { detachLocked() }

    /** App exit: drop the app-server, then wait briefly so it finishes terminating. */
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; detachLocked() }
        releaser.shutdown()
        releaser.awaitTermination(5, TimeUnit.SECONDS)
    }

    /** The account's app-server, started when needed. A release while it starts closes it again. */
    private fun connect(accountId: String): Connection {
        synchronized(lock) {
            check(!closed) { "Chat closed" }
            connection?.let { if (it.accountId == accountId) return it; detachLocked() }
        }
        val runtime = CodexRuntime(executable, providerRoot)
        val folder = runtime.conversationFolder(accountId)
        val mine = synchronized(lock) { ++epoch }
        val rpc = createRpc(runtime) { method, params -> receive(mine, method, params) }
        val opened = synchronized(lock) {
            if (closed || epoch != mine || connection != null) null else Connection(accountId, folder, rpc, mine).also { connection = it }
        }
        if (opened == null) { rpc.close(); error("Chat closed") }
        return opened
    }

    /**
     * Ready only on a ChatGPT login; an API-key login is never used. Readiness only: a forced refresh
     * costs a round trip and can race the player's other Codex sessions sharing the login.
     */
    private fun account(connection: Connection): Readiness {
        val account = connection.rpc.call("account/read", buildJsonObject { put("refreshToken", false) })["account"]
        current(connection)
        val status = runCatching { codexAccountStatus(account) }.getOrNull()
        if (status?.state == ProviderState.CONNECTED) return Readiness.Ready
        detach(connection)
        return if (status?.state == ProviderState.ERROR) Readiness.Unavailable(checkNotNull(status.message)) else Readiness.SignedOut
    }

    /**
     * What turns off the player's MCP servers and features for this conversation, from Codex's own
     * reading of the configuration as a thread in [Connection.folder] sees it. Null when Codex cannot
     * say, and then no thread starts.
     */
    private fun overrides(connection: Connection): JsonObject? {
        val read = try { connection.rpc.call("config/read", buildJsonObject { put("cwd", connection.folder.toString()) }) }
            catch (_: CodexRequestRejected) { return null }
        current(connection)
        return (read["config"] as? JsonObject)?.let(::talkOnlyOverrides)
    }

    /** The companion's thread on this app-server: resumed, or new when there is none. Null when Codex no longer has it. */
    private fun thread(connection: Connection, turn: TurnRequest, events: TurnEvents, overrides: JsonObject): String? {
        val saved = turn.conversation?.id
        if (saved != null && synchronized(lock) { connection.thread } == saved) return saved
        val cwd = connection.folder.toString()
        // Codex applies the persona on every resume, so a thread survives a renamed companion.
        val result = if (saved == null) connection.rpc.call("thread/start", chatThreadParams(turn.persona, cwd, null, overrides = overrides))
            else try { connection.rpc.call("thread/resume", chatThreadParams(turn.persona, cwd, saved, overrides = overrides)) } catch (_: CodexRequestRejected) { return null }
        val id = result.getValue("thread").jsonObject.getValue("id").jsonPrimitive.content
        require(UUID.fromString(id).toString() == id)
        synchronized(lock) { current(connection); connection.thread = id }
        if (id != saved) events.opened(id)
        return id
    }

    /**
     * Codex's own default model for the account, asked for at most once a minute. A failed or empty
     * answer keeps the last one known; null only when none ever was.
     */
    private fun defaultModel(connection: Connection): String? {
        val known = defaults[connection.accountId]
        if (known != null && System.nanoTime() - known.first < defaultModelRefresh.inWholeNanoseconds) return known.second
        val fresh = try { codexModelList(connection.rpc).firstOrNull { it.isDefault }?.modelId } catch (_: Exception) { null }
        current(connection)
        return (fresh ?: known?.second).also { defaults[connection.accountId] = System.nanoTime() to it }
    }

    /** Consume only text and terminal state from the current app-server. Tool requests are refused by the transport. */
    private fun receive(epoch: Int, method: String, params: JsonObject) = synchronized(delivery) {
        val connection = synchronized(lock) { connection?.takeIf { it.epoch == epoch } } ?: return@synchronized
        if (method == MCP_STARTUP) {
            // A talk-only conversation never runs an MCP server; one starting means the overrides did not hold.
            log(LogLevel.Warn, "codex", "Codex chat refused; an MCP server started")
            val turn = synchronized(lock) { connection.turn.also { connection.turn = null } }
            detach(connection)
            turn?.events?.failed(ChatFailure.Lockdown)
            return@synchronized
        }
        if (method == "transport/closed" || method == "request/refused") {
            val turn = synchronized(lock) { connection.turn.also { connection.turn = null } }
            // A dead app-server is never reused: the next turn starts a new one and resumes the thread again.
            if (turn != null || method == "transport/closed") detach(connection)
            turn?.events?.failed(ChatFailure.Temporary)
            return@synchronized
        }
        val thread = (params["threadId"] as? JsonPrimitive)?.contentOrNull
        var overflow: Turn? = null
        val turn = synchronized(lock) {
            val turn = connection.turn?.takeIf { it.thread == thread } ?: return@synchronized null
            when {
                turn.id != null -> turn
                turn.early.size < 512 -> { turn.early += method to params; null }
                else -> { connection.turn = null; overflow = turn; null }
            }
        }
        overflow?.let { detach(connection); it.events.failed(ChatFailure.Unexpected) }
        turn?.let { handle(connection, it, method, params) }
    }

    /** One event of an acknowledged turn. Called with [delivery] held. */
    private fun handle(connection: Connection, turn: Turn, method: String, params: JsonObject) {
        if (synchronized(lock) { connection.turn !== turn }) return
        val eventTurn = (params["turnId"] as? JsonPrimitive)?.contentOrNull ?: ((params["turn"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
        if (eventTurn != turn.id) return
        if (turn.reply.receive(method, params)) { turn.events.partial(turn.reply.text); return }
        when (method) {
            "turn/completed" -> {
                if (!finish(connection, turn)) return
                val ended = params["turn"] as? JsonObject
                if ((ended?.get("status") as? JsonPrimitive)?.contentOrNull == "completed") turn.events.completed(turn.reply.text, turn.thread)
                else turn.events.failed(codexFailure(ended?.get("error") as? JsonObject))
            }
            "error" -> if (params["willRetry"] != JsonPrimitive(true)) {
                if (!finish(connection, turn)) return
                detach(connection)
                turn.events.failed(codexFailure(params["error"] as? JsonObject))
            }
        }
    }

    /** Claim the end of [turn] once; a later terminal event for it is ignored. */
    private fun finish(connection: Connection, turn: Turn): Boolean =
        synchronized(lock) { (connection.turn === turn).also { if (it) connection.turn = null } }

    private fun current(connection: Connection) = synchronized(lock) { check(this.connection === connection) { "Chat closed" } }

    private fun detach(connection: Connection) = synchronized(lock) { if (this.connection === connection) detachLocked() }

    /** Detach now, so later events are ignored and the next turn starts a new app-server; close it off the caller's thread. */
    private fun detachLocked() {
        epoch++
        val dropped = connection ?: return
        connection = null
        try { releaser.execute { dropped.rpc.close() } } catch (_: RejectedExecutionException) { dropped.rpc.close() }
    }
}

/**
 * What a chat app-server may be asked: the account, the configuration it must turn off, its
 * conversation, and the model list for naming Codex's default.
 */
internal val CHAT_CODEX_METHODS = setOf("account/read", "config/read", "thread/start", "thread/resume", "turn/start", "turn/interrupt", "model/list")

/** Codex reports each MCP server it starts for a thread with this notification. */
private const val MCP_STARTUP = "mcpServer/startupStatus/updated"

/**
 * The talk-only lockdown, once: features that give a companion a tool, a hook, memory or another
 * agent off; no web search; no `notify` program (the player's, run after every turn like a hook);
 * no bundled skills or their instructions; no agents; no project docs; a read-only sandbox and no
 * approvals. The process gets it as `-c` arguments ([chatProcessArguments]) and every thread
 * again as its own `config` ([talkOnlyOverrides]).
 */
internal val TALK_ONLY_LOCKDOWN: JsonObject = buildJsonObject {
    putJsonObject("features") {
        listOf("shell_tool", "apps", "plugins", "remote_plugin", "hooks", "multi_agent", "multi_agent_v2",
            "computer_use", "browser_use", "browser_use_external", "in_app_browser", "image_generation", "view_image",
            "unified_exec", "code_mode_host", "workspace_dependencies", "worktrees", "shell_snapshot",
            "chronicle", "goals", "sleep_tool", "tool_suggest", "realtime_conversation",
            "memories", "skill_search", "skill_mcp_dependency_install", "request_permissions_tool").forEach { put(it, false) }
    }
    put("web_search", "disabled")
    putJsonArray("notify") {}
    putJsonObject("skills") { putJsonObject("bundled") { put("enabled", false) }; put("include_instructions", false) }
    putJsonObject("agents") { put("enabled", false) }
    put("project_doc_max_bytes", 0)
    put("sandbox_mode", "read-only")
    put("approval_policy", "never")
}

/**
 * A thread's `config` overrides: [TALK_ONLY_LOCKDOWN] applied to the thread itself, because the
 * process-level `-c` settings do not reach everything a thread loads (the player's plugins' MCP
 * servers and Codex's Apps server start despite `-c features.plugins=false`), while the thread's own
 * `config` does. On top of it, everything the player's own configuration ([config], from
 * `config/read`) turns on is turned off: every MCP server and plugin it lists and every feature it
 * enables. Codex merges these into the player's tables (an empty `mcp_servers={}` leaves them all
 * running), so each one is named, as a nested table rather than a dotted key so that a name with a
 * dot is turned off too.
 */
internal fun talkOnlyOverrides(config: JsonObject): JsonObject = buildJsonObject {
    fun names(key: String) = (config[key] as? JsonObject)?.keys.orEmpty().sorted()
    TALK_ONLY_LOCKDOWN.forEach { (key, value) -> if (key != "features") put(key, value) }
    val servers = names("mcp_servers")
    if (servers.isNotEmpty()) putJsonObject("mcp_servers") { servers.forEach { putJsonObject(it) { put("enabled", false) } } }
    val plugins = names("plugins")
    if (plugins.isNotEmpty()) putJsonObject("plugins") { plugins.forEach { putJsonObject(it) { put("enabled", false) } } }
    val enabled = (config["features"] as? JsonObject)?.filterValues { it == JsonPrimitive(true) }?.keys.orEmpty()
    putJsonObject("features") { (TALK_ONLY_LOCKDOWN.getValue("features").jsonObject.keys + enabled).toSortedSet().forEach { put(it, false) } }
}

/**
 * The text of one Codex reply as its notifications build it: a delta extends the current agent
 * message, a new message item starts the text over, and a completed agent message replaces it.
 */
internal class ReplyText {
    var text = ""
        private set
    private var item: String? = null

    /** Apply [method] if it is reply text; false for any other notification. */
    fun receive(method: String, params: JsonObject): Boolean {
        when (method) {
            "item/agentMessage/delta" -> {
                val item = (params["itemId"] as? JsonPrimitive)?.contentOrNull ?: return false
                val delta = (params["delta"] as? JsonPrimitive)?.contentOrNull ?: return false
                text = ((if (this.item == item) text else "") + delta).take(ChatLimits.REPLY)
                this.item = item
            }
            "item/completed" -> {
                val item = (params["item"] as? JsonObject)?.takeIf { it["type"] == JsonPrimitive("agentMessage") } ?: return false
                text = (item["text"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(ChatLimits.REPLY)
            }
            else -> return false
        }
        return true
    }
}

/** An explicit Codex model, "" for Codex's own default, or null for an id that is not a model id. */
private fun explicitModel(model: String?): String? = when {
    model == null || model == RUNTIME_DEFAULT_MODEL -> ""
    SharedRules.isModelId(model) -> model
    else -> null
}

/** Codex's error code for a failed turn, as a runtime-neutral [ChatFailure]. */
internal fun codexFailure(error: JsonObject?): ChatFailure {
    val info = error?.get("codexErrorInfo")
    return when ((info as? JsonPrimitive)?.contentOrNull ?: (info as? JsonObject)?.keys?.singleOrNull()) {
        "usageLimitExceeded", "rateLimitExceeded" -> ChatFailure.UsageLimit(null)
        "unauthorized" -> ChatFailure.SignedOut
        "serverOverloaded", "internalServerError", "httpConnectionFailed", "responseStreamConnectionFailed",
        "responseStreamDisconnected", "responseTooManyFailedAttempts" -> ChatFailure.Temporary
        else -> ChatFailure.Unexpected
    }
}

/** Keep conversation capabilities separate from the approved workspace-task runtime. */
internal fun chatCodex(runtime: CodexRuntime, receive: (String, JsonObject) -> Unit) =
    StdioCodexAppServer(runtime,
        allowedMethods = CHAT_CODEX_METHODS,
        arguments = chatProcessArguments(), onNotification = receive, timeoutSeconds = 15)

/** [TALK_ONLY_LOCKDOWN] as `-c` arguments, applied at process startup before any thread can run; each thread repeats it ([talkOnlyOverrides]). */
internal fun chatProcessArguments(): List<String> = flattened(TALK_ONLY_LOCKDOWN).flatMap { (key, value) -> listOf("-c", "$key=$value") }

/** Dotted keys and TOML values for every leaf of [table]. */
private fun flattened(table: JsonObject, prefix: String = ""): List<Pair<String, String>> = table.flatMap { (key, value) ->
    when (value) {
        is JsonObject -> flattened(value, "$prefix$key.")
        is JsonArray -> listOf("$prefix$key" to "[${value.joinToString(",")}]")
        else -> listOf("$prefix$key" to value.toString())
    }
}

/**
 * Thread settings for the companion [persona], with the player's configuration turned off by
 * [overrides] ([talkOnlyOverrides]); tool access remains controlled by native configuration.
 * A resume asks for thread metadata only: the full turn history of a long conversation would not
 * fit in one bounded protocol line, and the native record already holds the visible messages.
 * An [ephemeral] thread is never written to Codex's history.
 */
internal fun chatThreadParams(persona: Persona, cwd: String, threadId: String?, ephemeral: Boolean = false,
    overrides: JsonObject): JsonObject = buildJsonObject {
    threadId?.let { put("threadId", it); put("excludeTurns", true) }
    if (ephemeral) put("ephemeral", true)
    put("cwd", cwd); put("sandbox", "read-only"); put("approvalPolicy", "never")
    put("baseInstructions", persona.instructions)
    put("config", overrides)
}
