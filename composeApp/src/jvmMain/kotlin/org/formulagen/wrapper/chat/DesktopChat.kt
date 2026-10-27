package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.providers.*

/** Owns one view's conversation lease. Workers never mutate a newer view or replay a send. */
internal class DesktopChat(
    private val providerRoot: Path,
    private val executable: Path?,
    private val speech: ChatSpeech,
    private val createRpc: (CodexRuntime, (String, JsonObject) -> Unit) -> CodexAccountRpc = ::chatCodex,
) : ChatCapability, AutoCloseable {
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "hovel-chat").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "hovel-chat-timeout").apply { isDaemon = true } }
    private var active: Session? = null
    private var closed = false
    private class Session(val scope: ChatScope, val runtime: CodexRuntime, val history: ChatHistory, var record: ChatRecord) {
        var state = "checking"
        var notice: String? = "Checking Codex connection…"
        var rpc: CodexAccountRpc? = null
        var resumed = false
        var generation = 0
        var turnId: String? = null
        var itemId: String? = null
        var speak = false
        var checking = true
        var rpcEpoch = 0
        val awaitingTurn = mutableListOf<Pair<String, JsonObject>>()
    }

    /** Opening is the only operation allowed to replace an active conversation. */
    override suspend fun open(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        check(!closed)
        val old = active
        if (old?.scope == scope && old.state !in setOf("error", "disconnected")) return@synchronized view(old)
        invalidateLocked()
        if (executable == null) return@synchronized ChatSnapshot("error", notice = "Install Codex on this Mac and restart Hovel.", voice = speech.status())
        val home = codexProfileHome(providerRoot, scope.profile)
        val runtime = CodexRuntime(executable, home)
        val id = UUID.fromString(scope.companionId).toString()
        val history = ChatHistory(home.resolve("companion-chats").resolve("$id.json"))
        val session = Session(scope, runtime, history, history.read()).also { active = it }
        val generation = session.generation
        worker.execute { attempt(session, generation) { ensureAccount(session, generation) } }
        view(session)
    }

    /** Polling cannot reopen an expired lease. */
    override suspend fun snapshot(scope: ChatScope): ChatSnapshot = synchronized(gate) { view(current(scope)) }

    /** Persist the explicit user message before starting any provider work. */
    override suspend fun send(args: ChatSend): ChatSnapshot = synchronized(gate) {
        val session = current(args.scope())
        if (session.record.messages.any { it.id == args.requestId }) return@synchronized view(session)
        if (session.checking || session.state == "responding") throw BridgeBusyException()
        check(session.state in setOf("ready", "cancelled"))
        speech.stop()
        val messages = (session.record.messages + ChatMessage(args.requestId, "user", args.text.trim()) +
            ChatMessage(UUID.randomUUID().toString(), "assistant", "")).takeLast(40)
        val next = session.record.copy(messages = messages)
        session.history.write(next)
        session.record = next; session.state = "responding"; session.notice = null
        session.speak = args.speak; session.itemId = null; session.turnId = null; session.awaitingTurn.clear()
        val generation = ++session.generation
        worker.execute { attempt(session, generation) { startTurn(session, generation, args.text.trim()) } }
        timer.schedule({ synchronized(gate) {
            if (live(session, generation) && session.state == "responding") {
                end(session, "error", "The reply took too long. Your message is saved; retry when you are ready.")
            }
        } }, 3, TimeUnit.MINUTES)
        view(session)
    }

    /** Stop closes the owned provider process, including any still-starting turn. */
    override suspend fun stop(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        val session = current(scope)
        end(session, "cancelled", "Reply stopped.")
        view(session)
    }

    /** Audio can stop independently while text generation continues. */
    override suspend fun stopSpeaking(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        val session = current(scope); session.speak = false; speech.stop(); view(session)
    }

    /** An old view cannot close the newly opened companion. */
    override suspend fun closeSession(scope: ChatScope) = synchronized(gate) {
        if (active?.scope == scope) invalidateLocked()
    }

    /** Called when the document or provider login changes. */
    fun invalidate() = synchronized(gate) { invalidateLocked() }

    private fun current(scope: ChatScope): Session = checkNotNull(active?.takeIf { !closed && it.scope == scope }) { "Chat view expired" }
    private fun live(session: Session, generation: Int = session.generation) = !closed && active === session && generation == session.generation
    private fun view(session: Session) = ChatSnapshot(session.state, session.record.messages, session.notice, speech.status())

    /** Keep account verification off the UI thread and never substitute an API key. */
    private fun ensureAccount(session: Session, generation: Int): CodexAccountRpc {
        val rpc = synchronized(gate) { check(live(session, generation)); session.rpc } ?: run {
            session.runtime.prepare()
            val epoch = synchronized(gate) { check(live(session, generation)); ++session.rpcEpoch }
            val opened = createRpc(session.runtime) { method, params -> receive(session, epoch, method, params) }
            synchronized(gate) { if (!live(session, generation)) { opened.close(); error("Chat closed") }; session.rpc = opened }
            opened
        }
        val result = rpc.call("account/read", buildJsonObject { put("refreshToken", true) })
        synchronized(gate) {
            check(live(session, generation))
            val account = result["account"] as? JsonObject
            if (account?.get("type") != JsonPrimitive("chatgpt")) {
                session.state = "disconnected"; session.notice = "Connect Codex in Profile → Providers to message your companion."
                session.checking = false
                error("ChatGPT account required")
            }
            session.checking = false
            if (session.state != "responding") { session.state = "ready"; session.notice = null }
        }
        return rpc
    }

    /** Resume only the native-owned thread belonging to this companion. */
    private fun startTurn(session: Session, generation: Int, text: String) {
        val rpc = ensureAccount(session, generation)
        if (!session.resumed) {
            val cwd = session.runtime.home.resolve("conversation-workspace")
            Files.createDirectories(cwd)
            val thread = session.record.threadId
            val result = rpc.call(if (thread == null) "thread/start" else "thread/resume", chatThreadParams(session.scope.name, cwd.toString(), thread))
            val id = result.getValue("thread").jsonObject.getValue("id").jsonPrimitive.content
            require(UUID.fromString(id).toString() == id)
            synchronized(gate) {
                check(live(session, generation))
                session.record = session.record.copy(threadId = id); session.history.write(session.record); session.resumed = true
            }
        }
        synchronized(gate) { check(live(session, generation)) }
        val started = rpc.call("turn/start", buildJsonObject {
            put("threadId", session.record.threadId)
            putJsonArray("input") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        })
        synchronized(gate) {
            if (live(session, generation) && session.state == "responding") {
                session.turnId = started.getValue("turn").jsonObject.getValue("id").jsonPrimitive.content
                val buffered = session.awaitingTurn.toList(); session.awaitingTurn.clear()
                buffered.forEach { (method, params) -> receive(session, session.rpcEpoch, method, params) }
            }
        }
    }

    /** Consume only text and terminal state. Tool requests are rejected by the transport. */
    private fun receive(session: Session, epoch: Int, method: String, params: JsonObject) = synchronized(gate) {
        if (!live(session) || epoch != session.rpcEpoch) return@synchronized
        if (method in setOf("request/refused", "transport/closed")) {
            if (session.state == "responding") end(session, "error", "The reply stopped. Try again when Codex is ready.")
            return@synchronized
        }
        if (session.state != "responding" || params["threadId"] != JsonPrimitive(session.record.threadId)) return@synchronized
        if (session.turnId == null) {
            if (session.awaitingTurn.size >= 512) end(session, "error", "The reply could not be synchronized. Reopen this chat to try again.")
            else session.awaitingTurn += method to params
            return@synchronized
        }
        val turn = params["turn"] as? JsonObject
        val eventTurn = (params["turnId"] as? JsonPrimitive)?.contentOrNull ?: (turn?.get("id") as? JsonPrimitive)?.contentOrNull
        if (session.turnId != null && eventTurn != session.turnId) return@synchronized
        when (method) {
            "item/agentMessage/delta" -> {
                val id = params["itemId"]?.jsonPrimitive?.content ?: return@synchronized
                val prefix = if (session.itemId == id) session.record.messages.last().text else ""
                session.itemId = id
                updateReply(session, prefix + params.getValue("delta").jsonPrimitive.content)
            }
            "item/completed" -> {
                val item = params["item"] as? JsonObject
                if (item?.get("type") == JsonPrimitive("agentMessage")) updateReply(session, item["text"]?.jsonPrimitive?.content.orEmpty())
            }
            "turn/completed" -> {
                val status = turn?.get("status")?.jsonPrimitive?.content
                session.state = when (status) { "completed" -> "ready"; "interrupted" -> "cancelled"; else -> "error" }
                session.notice = if (session.state == "error") "Codex could not finish this reply. Check your connection or plan allowance." else null
                session.turnId = null
                runCatching { session.history.write(session.record) }.onFailure {
                    session.notice = "This reply could not be saved. Keep this chat open and try again later."
                }
                if (session.state == "ready" && session.speak) speech.speak(session.record.messages.last().text)
            }
            "error" -> if (params["willRetry"] != JsonPrimitive(true)) end(session, "error", "Codex could not finish this reply. Check Providers and try again.")
        }
    }

    private fun updateReply(session: Session, text: String) {
        val messages = session.record.messages
        if (messages.lastOrNull()?.role != "assistant") return
        session.record = session.record.copy(messages = messages.dropLast(1) + messages.last().copy(text = text.take(16000)))
    }

    /** Errors keep the user's text and never automatically send it again. */
    private fun attempt(session: Session, generation: Int, work: () -> Unit) {
        try { work() } catch (_: Exception) { synchronized(gate) {
            if (live(session, generation) && session.state != "disconnected") end(session, "error", "Codex could not complete the request. Reconnect or retry when ready.")
        } }
    }

    private fun end(session: Session, state: String, notice: String) {
        session.generation++; session.rpcEpoch++; session.state = state; session.notice = notice; session.checking = false
        session.turnId = null; session.awaitingTurn.clear(); session.resumed = false; session.speak = false
        val rpc = session.rpc; session.rpc = null; rpc?.close(); speech.stop()
        runCatching { session.history.write(session.record) }
    }

    private fun invalidateLocked() {
        val session = active; active = null
        session?.let { end(it, "cancelled", "Chat closed.") }
        speech.stop()
    }

    override fun close() = synchronized(gate) {
        invalidateLocked(); closed = true; timer.shutdownNow(); worker.shutdownNow(); speech.close()
    }
}
