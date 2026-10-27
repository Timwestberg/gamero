package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.providers.*

/** Owns one view's conversation lease. Workers never mutate a newer view or replay a send. */
internal class DesktopChat(
    private val providerRoot: Path,
    private val executable: Path?,
    private val speech: ChatSpeech,
    private val createRpc: (CodexRuntime, (String, JsonObject) -> Unit) -> CodexAppServer = ::chatCodex,
) : ChatCapability, AutoCloseable {
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-chat"))
    private val timer = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-chat-timeout"))
    /** Closing an app-server can wait seconds for its process tree, so it never runs under [gate] or on a caller's thread. */
    private val releaser = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-chat-release"))
    private var active: Session? = null
    /** The last view the host ended on its own (or could not open), so that view's polls keep showing why. */
    private var ended: Pair<ChatScope, ChatSnapshot>? = null
    private var closed = false
    private class Session(val scope: ChatScope, val runtime: CodexRuntime, val history: ChatHistory, var record: ChatRecord) {
        var state = ChatState.CHECKING
        var notice: String? = "Checking Codex connection…"
        var rpc: CodexAppServer? = null
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
        if (old?.scope == scope && old.state !in setOf(ChatState.ERROR, ChatState.DISCONNECTED)) return@synchronized view(old)
        invalidateLocked(keepEnded = false)
        ended = null
        if (executable == null) {
            return@synchronized ChatSnapshot(ChatState.ERROR, notice = "Install Codex on this Mac and restart Hovel.", voice = speech.status())
                .also { ended = scope to it }
        }
        val home = codexProfileHome(providerRoot, scope.profile)
        val runtime = CodexRuntime(executable, home)
        val id = UUID.fromString(scope.companionId).toString()
        val history = ChatHistory(home.resolve("companion-chats").resolve("$id.json"))
        val session = Session(scope, runtime, history, history.read()).also { active = it }
        val generation = session.generation
        worker.execute { attempt(session, generation) { ensureAccount(session, generation) } }
        view(session)
    }

    /** Polling cannot reopen an expired lease, but a view the host ended keeps reading its final state. */
    override suspend fun snapshot(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        ended?.takeIf { !closed && it.first == scope }?.second?.copy(voice = speech.status()) ?: view(current(scope))
    }

    /** Persist the explicit user message before starting any provider work. */
    override suspend fun send(args: ChatSend): ChatSnapshot = synchronized(gate) {
        val session = current(args.scope())
        if (session.record.messages.any { it.id == args.requestId }) return@synchronized view(session)
        if (session.checking || session.state == ChatState.RESPONDING) throw BridgeBusyException()
        check(session.state in setOf(ChatState.READY, ChatState.CANCELLED))
        speech.stop()
        val messages = (session.record.messages + ChatMessage(args.requestId, ChatRole.USER, args.text.trimEcma()) +
            ChatMessage(UUID.randomUUID().toString(), ChatRole.ASSISTANT, "")).takeLast(ChatLimits.HISTORY)
        val next = session.record.copy(messages = messages)
        session.history.write(next)
        session.record = next; session.state = ChatState.RESPONDING; session.notice = null
        session.speak = args.speak; session.itemId = null; session.turnId = null; session.awaitingTurn.clear()
        val generation = ++session.generation
        worker.execute { attempt(session, generation) { startTurn(session, generation, args.text.trimEcma()) } }
        timer.schedule({ synchronized(gate) {
            if (live(session, generation) && session.state == ChatState.RESPONDING) {
                end(session, ChatState.ERROR, "The reply took too long. Your message is saved; retry when you are ready.")
            }
        } }, 3, TimeUnit.MINUTES)
        view(session)
    }

    /** Stop closes the owned provider process, including any still-starting turn. */
    override suspend fun stop(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        val session = current(scope)
        end(session, ChatState.CANCELLED, "Reply stopped.")
        view(session)
    }

    /** Audio can stop independently while text generation continues. */
    override suspend fun stopSpeaking(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        val session = current(scope); session.speak = false; speech.stop(); view(session)
    }

    /** An old view cannot close the newly opened companion. */
    override suspend fun closeSession(scope: ChatScope) = synchronized(gate) {
        if (active?.scope == scope) invalidateLocked(keepEnded = false)
        if (ended?.first == scope) ended = null
    }

    /** Called when the document or provider login changes. A still-open view reads why and can reconnect. */
    fun invalidate() = synchronized(gate) { invalidateLocked(keepEnded = true) }

    private fun current(scope: ChatScope): Session = checkNotNull(active?.takeIf { !closed && it.scope == scope }) { "Chat view expired" }
    private fun live(session: Session, generation: Int = session.generation) = !closed && active === session && generation == session.generation
    private fun view(session: Session) = ChatSnapshot(session.state, session.record.messages, session.notice, speech.status())

    /** Keep account verification off the UI thread and never substitute an API key. */
    private fun ensureAccount(session: Session, generation: Int): CodexAppServer {
        val rpc = synchronized(gate) { check(live(session, generation)); session.rpc } ?: run {
            session.runtime.prepare()
            val epoch = synchronized(gate) { check(live(session, generation)); ++session.rpcEpoch }
            val opened = createRpc(session.runtime) { method, params -> receive(session, epoch, method, params) }
            val kept = synchronized(gate) { live(session, generation).also { if (it) session.rpc = opened } }
            if (!kept) { opened.close(); error("Chat closed") }
            opened
        }
        // Readiness only: a forced refresh costs a round trip and can race another app-server sharing the keyring login.
        val result = rpc.call("account/read", buildJsonObject { put("refreshToken", false) })
        synchronized(gate) {
            check(live(session, generation))
            val account = result["account"] as? JsonObject
            if (account?.get("type") != JsonPrimitive("chatgpt")) {
                session.state = ChatState.DISCONNECTED; session.notice = "Connect Codex in Profile → Providers to message your companion."
                session.checking = false
                dropRpc(session)
                error("ChatGPT account required")
            }
            session.checking = false
            if (session.state != ChatState.RESPONDING) { session.state = ChatState.READY; session.notice = null }
        }
        return rpc
    }

    /** Resume only the native-owned thread belonging to this companion. */
    private fun startTurn(session: Session, generation: Int, text: String) {
        val rpc = ensureAccount(session, generation)
        if (!session.resumed) {
            val cwd = session.runtime.home.resolve("conversation-workspace")
            Files.createDirectories(cwd)
            val id = openThread(rpc, session, cwd.toString())
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
            if (live(session, generation) && session.state == ChatState.RESPONDING) {
                session.turnId = started.getValue("turn").jsonObject.getValue("id").jsonPrimitive.content
                val buffered = session.awaitingTurn.toList(); session.awaitingTurn.clear()
                buffered.forEach { (method, params) -> receive(session, session.rpcEpoch, method, params) }
            }
        }
    }

    /** Resume this companion's thread; if Codex no longer has it, start a fresh one, which the caller saves. */
    private fun openThread(rpc: CodexAppServer, session: Session, cwd: String): String {
        val saved = session.record.threadId
        val resumed = saved?.let {
            try { rpc.call("thread/resume", chatThreadParams(session.scope.name, cwd, it)) }
            catch (_: CodexRequestRejected) { null }
        }
        val result = resumed ?: rpc.call("thread/start", chatThreadParams(session.scope.name, cwd, null))
        return result.getValue("thread").jsonObject.getValue("id").jsonPrimitive.content
    }

    /** Consume only text and terminal state. Tool requests are rejected by the transport. */
    private fun receive(session: Session, epoch: Int, method: String, params: JsonObject) = synchronized(gate) {
        if (!live(session) || epoch != session.rpcEpoch) return@synchronized
        if (method in setOf("request/refused", "transport/closed")) {
            if (session.state == ChatState.RESPONDING) end(session, ChatState.ERROR, "The reply stopped. Try again when Codex is ready.")
            // A dead app-server is never reused: the next send starts a new one and resumes the thread again.
            else if (method == "transport/closed") dropRpc(session)
            return@synchronized
        }
        if (session.state != ChatState.RESPONDING || params["threadId"] != JsonPrimitive(session.record.threadId)) return@synchronized
        if (session.turnId == null) {
            if (session.awaitingTurn.size >= 512) end(session, ChatState.ERROR, "The reply could not be synchronized. Reopen this chat to try again.")
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
                session.state = when (status) { "completed" -> ChatState.READY; "interrupted" -> ChatState.CANCELLED; else -> ChatState.ERROR }
                session.notice = if (session.state == ChatState.ERROR) "Codex could not finish this reply. Check your connection or plan allowance." else null
                session.turnId = null
                runCatching { session.history.write(session.record) }.onFailure {
                    session.notice = "This reply could not be saved. Keep this chat open and try again later."
                }
                if (session.state == ChatState.READY && session.speak) speech.speak(session.record.messages.last().text)
            }
            "error" -> if (params["willRetry"] != JsonPrimitive(true)) end(session, ChatState.ERROR, "Codex could not finish this reply. Check Providers and try again.")
        }
    }

    private fun updateReply(session: Session, text: String) {
        val messages = session.record.messages
        if (messages.lastOrNull()?.role != ChatRole.ASSISTANT) return
        session.record = session.record.copy(messages = messages.dropLast(1) + messages.last().copy(text = text.take(ChatLimits.REPLY)))
    }

    /**
     * Errors keep the user's text and never automatically send it again. Any failure, including a
     * native linkage error, ends the request instead of killing the worker; a VM error is then rethrown.
     */
    private fun attempt(session: Session, generation: Int, work: () -> Unit) {
        try { work() } catch (error: Throwable) {
            synchronized(gate) {
                if (live(session, generation) && session.state != ChatState.DISCONNECTED) end(session, ChatState.ERROR, "Codex could not complete the request. Reconnect or retry when ready.")
            }
            if (error is VirtualMachineError) throw error
        }
    }

    private fun end(session: Session, state: ChatState, notice: String) {
        session.generation++; session.state = state; session.notice = notice; session.checking = false
        session.turnId = null; session.awaitingTurn.clear(); session.speak = false
        dropRpc(session); speech.stop()
        runCatching { session.history.write(session.record) }
    }

    /** Detach the session's app-server so later events are ignored, and close it off the lock. */
    private fun dropRpc(session: Session) {
        session.rpcEpoch++; session.resumed = false
        val rpc = session.rpc ?: return
        session.rpc = null
        try { releaser.execute { rpc.close() } } catch (_: RejectedExecutionException) { rpc.close() }
    }

    private fun invalidateLocked(keepEnded: Boolean) {
        val session = active; active = null
        session?.let {
            end(it, ChatState.ERROR, "This chat was closed. Reconnect to continue.")
            if (keepEnded) ended = it.scope to view(it)
        }
        speech.stop()
    }

    /** App exit: stop work, then wait briefly so owned app-servers finish terminating. */
    override fun close() {
        synchronized(gate) {
            if (closed) return
            invalidateLocked(keepEnded = false); closed = true; ended = null; timer.shutdownNow(); worker.shutdownNow()
        }
        releaser.shutdown()
        releaser.awaitTermination(5, TimeUnit.SECONDS)
        speech.close()
    }
}
