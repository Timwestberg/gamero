package org.formulagen.wrapper.chat.claude

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalBroker
import org.formulagen.wrapper.approvals.ApprovalRelay
import org.formulagen.wrapper.bridge.ActivityKind
import org.formulagen.wrapper.bridge.ActivityStatus
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.ClaudeEvent
import org.formulagen.wrapper.chat.ClaudeFailures
import org.formulagen.wrapper.chat.ClaudeStream
import org.formulagen.wrapper.chat.Persona
import org.formulagen.wrapper.chat.Readiness
import org.formulagen.wrapper.chat.TurnEvents
import org.formulagen.wrapper.chat.TurnHandle
import org.formulagen.wrapper.chat.TurnRequest
import org.formulagen.wrapper.chat.WorkPaths
import org.formulagen.wrapper.chat.WorkRuntime
import org.formulagen.wrapper.chat.claudeModel
import org.formulagen.wrapper.chat.loggingDaemonThreads
import org.formulagen.wrapper.chat.userMessage
import org.formulagen.wrapper.companion.terminateProcessTree
import org.formulagen.wrapper.companion.terminateProcessTreeInBackground
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.process.ProtocolLine
import org.formulagen.wrapper.process.readLoginShellPath
import org.formulagen.wrapper.process.readProtocolLine
import org.formulagen.wrapper.providers.CLAUDE_STATUS_UNKNOWN
import org.formulagen.wrapper.providers.ClaudeConnection
import org.formulagen.wrapper.providers.ClaudeRuntime
import org.formulagen.wrapper.providers.claudeWorkEnvironment
import org.formulagen.wrapper.util.text

/**
 * Claude Code working in a companion's project folder, with exactly the player's own setup: one
 * long-lived `claude -p … --permission-prompt-tool stdio --append-system-prompt <working persona>`
 * per chat lease (a lease's working conversation is the runtime's, whichever Claude member sends),
 * in the folder, with no `--safe-mode`, `--tools`, `--strict-mcp-config` or `--permission-mode`, so
 * the player's settings, `CLAUDE.md`, skills, plugins and MCP servers apply as in their terminal.
 * Everything Claude Code would ask the player goes through one [ApprovalRelay] per process to
 * [broker] (the native dialog). A grant for the conversation lives in the process, which is why the
 * process lives as long as the conversation; another model or folder replaces it, resuming the
 * session once the old process has exited.
 *
 * Stop is `control_request interrupt` (the process and its grants stay); release, close and a
 * broken process end the process tree. A session Claude Code cannot resume is a lost conversation.
 * Talk-only Claude members are [org.formulagen.wrapper.chat.ClaudeChatRuntime], unchanged.
 */
internal class ClaudeWorkRuntime(
    private val claude: ClaudeRuntime,
    private val connection: ClaudeConnection,
    private val broker: ApprovalBroker,
    /** The working child's whole environment ([claudeWorkEnvironment]). */
    private val environment: () -> Map<String, String> = { claudeWorkEnvironment(System.getenv(), loginShellPath.value, claude.executable) },
    /** How long a stopped turn or a replaced process may take to end before it is ended for it. */
    private val settle: Duration = 10.seconds,
    /** Longest protocol line kept from this trusted local child; a longer one is answered from its prefix. */
    private val lineLimit: Int = WORK_LINE_LIMIT,
) : WorkRuntime {
    override val id = RuntimeId.CLAUDE_CODE
    private val processes = ConcurrentHashMap<String, WorkProcess>()
    /** Approvals block on the native dialog, so each one waits on its own thread. */
    private val approvals = Executors.newCachedThreadPool(loggingDaemonThreads("hovel-claude-approval"))
    /** Changes on every release; a process started across one is ended at once. */
    private val epoch = AtomicInteger()
    @Volatile private var closed = false

    override fun readiness(profile: String): Readiness {
        val status = connection.status()
        return when (status.state) {
            ProviderState.CONNECTED -> Readiness.Ready
            ProviderState.DISCONNECTED -> Readiness.SignedOut
            else -> Readiness.Unavailable(status.message ?: CLAUDE_STATUS_UNKNOWN)
        }
    }

    override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
        val work = checkNotNull(turn.work) { "A working turn needs a project folder" }
        val model = claudeModel(turn.model) ?: run { events.failed(ChatFailure.ModelUnavailable); return TurnHandle {} }
        val saved = turn.conversation
        // A Claude session keeps the first system prompt it was given, so another persona needs a new session.
        if (saved != null && saved.personaHash != turn.persona.hash) { events.failed(ChatFailure.ConversationLost); return TurnHandle {} }
        if (!Files.isDirectory(work.folder)) { events.failed(ChatFailure.Unexpected); return TurnHandle {} }
        check(!closed) { "Chat closed" }
        val started = epoch.get()
        val current = processes[work.lease]
        val process = current?.takeIf { it.fits(work.folder, model, turn.persona, saved?.id) && it.ready(settle) } ?: run {
            // Another model, folder or session, or a turn that would not end: the old process exits first.
            current?.let { processes.remove(work.lease, it); it.closeAndWait() }
            val session = saved?.id ?: UUID.randomUUID().toString()
            WorkProcess(work.folder, model, turn.persona, session, opens = saved == null, work.companion, work.member).also { fresh ->
                processes[work.lease] = fresh
                try { fresh.start() } catch (error: Exception) {
                    processes.remove(work.lease, fresh); fresh.close()
                    if (error is LostSession) { events.failed(ChatFailure.ConversationLost); return TurnHandle {} }
                    throw error
                }
            }
        }
        // A release or close while it started: it ends now, and the chat has ended the turn already.
        if (closed || epoch.get() != started) { processes.remove(work.lease, process); process.close(); events.failed(ChatFailure.Temporary); return TurnHandle {} }
        return process.turn(turn.input(), events)
    }

    /** End every working process: their pending approvals are denied and their trees ended. */
    override fun release() {
        epoch.incrementAndGet()
        processes.keys.toList().forEach { lease -> processes.remove(lease)?.close() }
    }

    override fun close() {
        closed = true
        release()
        approvals.shutdownNow()
    }

    /** Claude Code could not resume the session it was given. */
    private class LostSession : IllegalStateException("Claude Code could not resume the session")

    /** One working `claude -p` process: a session in a folder with a model and a persona. */
    private inner class WorkProcess(
        val folder: Path,
        val model: String,
        val persona: Persona,
        val session: String,
        private var opens: Boolean,
        private val companionName: String,
        private val member: TeamMember,
    ) {
        private val paths = WorkPaths(folder)
        private val relay = ApprovalRelay(broker)
        private lateinit var process: Process
        private lateinit var writer: java.io.BufferedWriter
        private val initialized = CompletableFuture<Unit>()
        private val sequence = AtomicInteger()
        private val ended = AtomicBoolean()
        @Volatile private var initializeId: String? = null
        /** Claude Code reported, before any turn, that it has no such session. */
        @Volatile private var sessionMissing = false
        /** Whether Claude Code has asked Hovel anything yet: proof the permission prompt reaches the host. */
        @Volatile private var asked = false
        @Volatile private var current: WorkTurn? = null

        fun fits(folder: Path, model: String, persona: Persona, session: String?) =
            !ended.get() && process.isAlive && this.folder == folder && this.model == model && this.persona == persona && session == this.session

        /** No earlier turn still running: a stopped one gets [wait] to report its end. */
        fun ready(wait: Duration): Boolean {
            val previous = current ?: return true
            return previous.finished.awaitQuietly(wait) && !ended.get()
        }

        fun start() {
            val conversation = if (opens) listOf("--session-id", session) else listOf("--resume", session)
            process = ProcessBuilder(listOf(claude.executable.toString()) + claudeWorkArguments(model, persona) + conversation)
                .directory(folder.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment().clear(); environment().putAll(this@ClaudeWorkRuntime.environment()) }
                .start()
            writer = process.outputStream.bufferedWriter()
            Thread.ofPlatform().daemon().name("hovel-claude-work").start { read() }
            val id = "hovel-${sequence.incrementAndGet()}"
            initializeId = id
            runCatching { send(ClaudeControl.initialize(id)) }
            // The answer names the player's account; it is only waited for, never kept or shown.
            try { initialized.get(INITIALIZE_SECONDS, TimeUnit.SECONDS) }
            catch (error: Exception) {
                val exited = !process.isAlive
                close()
                // A session that cannot be resumed ends the process before it starts working.
                if (!opens && (sessionMissing || exited)) throw LostSession()
                throw IllegalStateException("Claude Code did not start working", error)
            }
        }

        fun turn(input: String, events: TurnEvents): TurnHandle {
            val turn = WorkTurn(events, ClaudeActivity(folder))
            current = turn
            if (ended.get()) { turn.fail(ChatFailure.Temporary); return TurnHandle {} }
            try { send(userMessage(input)) } catch (_: IOException) { turn.fail(ChatFailure.Temporary); close() }
            return TurnHandle { stop(turn) }
        }

        /** Deny what the turn waits on, then ask Claude Code to stop the turn; the process and its grants stay. */
        private fun stop(turn: WorkTurn) {
            if (!turn.stop()) return
            relay.cancelAll()
            approvals.execute { runCatching { send(ClaudeControl.interrupt("hovel-${sequence.incrementAndGet()}")) } }
        }

        private fun read() {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        when (val line = readProtocolLine(reader, lineLimit) ?: break) {
                            is ProtocolLine.Whole -> receive(line.text)
                            is ProtocolLine.Dropped -> dropped(line.prefix)
                        }
                    }
                }
            } catch (_: IOException) { /* The process ended; reported below. */ }
            finally {
                initialized.completeExceptionally(IllegalStateException("Claude Code stopped"))
                if (ended.compareAndSet(false, true)) relay.cancelAll()
                // A turn still open when the process goes stopped unexpectedly: its finished steps stay, and it can be retried.
                current?.fail(ChatFailure.Temporary)
            }
        }

        private fun receive(line: String) {
            val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return
            when (val control = ClaudeControl.decode(message)) {
                null -> {
                    val turn = current
                    if (turn != null) turn.receive(line, message)
                    // Before any turn, a result can only be Claude Code refusing the session it was given.
                    else if (message.text("type") == "result") sessionMissing = true
                }
                is ClaudeControlMessage.Response -> if (control.requestId == initializeId) {
                    if (control.success) initialized.complete(Unit) else initialized.completeExceptionally(IllegalStateException("initialize refused"))
                }
                is ClaudeControlMessage.CanUseTool -> {
                    asked = true
                    val turn = current
                    val permission = control.permission
                    // Registered before the next line is read, so a withdrawal right after it finds it; then checked against a
                    // stop, so a stop either finds it or is seen here. Nothing to show it for (no turn, or one that ended) denies.
                    val pending = relay.register(permission.requestId, ClaudeControl.approvalRequest(permission, companionName, member, folder), turn?.events)
                    if (turn == null || turn.isDone || turn.stopped || ended.get()) pending.cancel.cancel()
                    approvals.execute { runCatching { send(ClaudeControl.answer(permission, relay.ask(permission.requestId, pending))) } }
                }
                is ClaudeControlMessage.Unreadable -> {
                    asked = true
                    send(ClaudeControl.deny(control.requestId, control.toolUseId, ClaudeControl.UNREADABLE))
                    current?.note("A request Hovel could not show", ClaudeControl.UNREADABLE)
                }
                is ClaudeControlMessage.Unsupported -> send(ClaudeControl.error(control.requestId))
                is ClaudeControlMessage.Cancel -> relay.cancel(control.requestId)
            }
        }

        /**
         * A line too long to keep: it is still answered from its opening. A permission request is
         * denied, another request refused, and a turn's `result` ends the turn as unexpected.
         */
        private fun dropped(prefix: String) {
            val type = TYPE.find(prefix)?.groupValues?.get(1)
            val requestId = REQUEST_ID.find(prefix)?.groupValues?.get(1)
            when {
                type == "control_request" && requestId != null && "\"can_use_tool\"" in prefix -> {
                    asked = true
                    send(ClaudeControl.deny(requestId, TOOL_USE_ID.find(prefix)?.groupValues?.get(1), ClaudeControl.UNREADABLE))
                    current?.note("A request too long to show", ClaudeControl.UNREADABLE)
                }
                type == "control_request" && requestId != null -> send(ClaudeControl.error(requestId))
                type == "result" -> current?.fail(ChatFailure.Unexpected)
            }
        }

        @Synchronized private fun send(line: String) {
            writer.write(line); writer.newLine(); writer.flush()
        }

        fun close() {
            if (ended.compareAndSet(false, true)) relay.cancelAll()
            current?.fail(ChatFailure.Temporary)
            if (::process.isInitialized && process.isAlive) terminateProcessTreeInBackground(process, "hovel-claude-work-end")
        }

        /** End this process and wait for it, so a replacement never shares its session with it. */
        fun closeAndWait() {
            if (ended.compareAndSet(false, true)) relay.cancelAll()
            current?.fail(ChatFailure.Temporary)
            if (::process.isInitialized && process.isAlive) {
                runCatching { writer.close() }
                if (!process.waitFor(settle.inWholeMilliseconds, TimeUnit.MILLISECONDS)) terminateProcessTree(process)
            }
        }

        /** One turn on this process, from the person's message to its `result`. */
        private inner class WorkTurn(val events: TurnEvents, val activity: ClaudeActivity) {
            val finished = CompletableFuture<Unit>()
            private val done = AtomicBoolean()
            val isDone: Boolean get() = done.get()
            @Volatile var stopped = false
                private set
            private var verified = false
            /** The player's own permission mode, from this turn's `system/init`. */
            private var permissionMode: String? = null
            private val text = StringBuilder()
            private var assistantError: String? = null
            private var rateLimit: ClaudeEvent.RateLimit? = null

            fun receive(line: String, message: JsonObject) {
                if (done.get()) return
                if (message.text("type") == "stream_event" && message["parent_tool_use_id"].let { it == null || it == JsonNull } &&
                    (message["event"] as? JsonObject)?.text("type") == "message_start" && text.isNotBlank() && !text.endsWith("\n\n")) text.append("\n\n")
                if (activity.receive(message)) report { events.activity(activity.activity()) }
                when (val event = ClaudeStream.parse(line)) {
                    is ClaudeEvent.Init -> if (!verified) {
                        verified = true
                        permissionMode = event.permissionMode
                        if (event.sessionId != session) return fail(ChatFailure.Unexpected)
                        if (opens) { opens = false; report { events.opened(session) } }
                        report { events.accepted() }
                    }
                    is ClaudeEvent.TextDelta -> if (text.length < ChatLimits.REPLY) {
                        text.append(event.text); report { events.partial(reply()) }
                    }
                    is ClaudeEvent.RateLimit -> rateLimit = event
                    is ClaudeEvent.AssistantError -> assistantError = event.error
                    is ClaudeEvent.Result -> finish(event)
                    else -> Unit
                }
            }

            /** The reply as the page sees it: the project folder as `.`, the home folder as `~`. */
            private fun reply(fallback: String = "") = paths.scrub(text.toString().ifBlank { fallback }).take(ChatLimits.REPLY)

            private fun finish(result: ClaudeEvent.Result) {
                if (!done.compareAndSet(false, true)) return
                finished.complete(Unit)
                if (stopped) return
                val succeeded = !result.isError && result.terminalReason in setOf(null, "completed")
                val failure = if (succeeded) null else ClaudeFailures.classify(result, assistantError, rateLimit)
                when {
                    // Claude Code needed an approval and never asked Hovel: the permission prompt does not reach the host.
                    // A mode that denies on its own (the player's choice) is not that.
                    activity.deniedWithoutAsking && !asked && permissionMode !in SILENT_MODES -> {
                        log(LogLevel.Warn, "claude", "Working turn refused; a permission needing approval was denied without reaching Hovel")
                        events.failed(ChatFailure.Unexpected)
                    }
                    failure == null -> events.completed(reply(result.result.orEmpty()), session)
                    else -> {
                        events.failed(failure)
                        // The session is gone: the next try starts a new one in a new process.
                        if (failure == ChatFailure.ConversationLost) approvals.execute { processes.values.remove(this@WorkProcess); close() }
                    }
                }
            }

            /** A step Hovel adds itself, such as a request it could not show. */
            fun note(label: String, detail: String) {
                if (done.get()) return
                activity.note(ActivityKind.OTHER, label, ActivityStatus.DENIED, detail)
                report { events.activity(activity.activity()) }
            }

            /** True the first time; nothing is reported after a stop. */
            fun stop(): Boolean { if (stopped || done.get()) return false; stopped = true; return true }

            fun fail(failure: ChatFailure) {
                if (!done.compareAndSet(false, true)) return
                finished.complete(Unit)
                if (!stopped) events.failed(failure)
            }

            private inline fun report(event: () -> Unit) { if (!stopped && !done.get()) event() }
        }
    }

    private companion object {
        const val INITIALIZE_SECONDS = 60L
        /** Permission modes in which Claude Code denies without asking, by the player's own choice. */
        val SILENT_MODES = setOf("dontAsk", "bypassPermissions", "auto")
        val TYPE = Regex("\"type\"\\s*:\\s*\"([a-z_]+)\"")
        val REQUEST_ID = Regex("\"request_id\"\\s*:\\s*\"([^\"]+)\"")
        val TOOL_USE_ID = Regex("\"tool_use_id\"\\s*:\\s*\"([^\"]+)\"")
        /** The player's login-shell `PATH`, read once: a Mac app started from Finder has only the system folders. */
        val loginShellPath = lazy { readLoginShellPath(System.getenv()) }
    }
}

/** Longest protocol line kept from a trusted local working child: tool results can be large. */
internal const val WORK_LINE_LIMIT = 16 * 1024 * 1024

private fun CompletableFuture<Unit>.awaitQuietly(wait: Duration): Boolean =
    try { get(wait.inWholeMilliseconds, TimeUnit.MILLISECONDS); true } catch (_: Exception) { false }

/**
 * A working turn's arguments: the player's own Claude Code, streaming, with every permission prompt
 * routed to Hovel (`--permission-prompt-tool stdio`; without it Claude Code denies silently) and the
 * working persona added to its own system prompt. Nothing narrows the player's setup. The caller adds
 * how the session is chosen.
 */
internal fun claudeWorkArguments(model: String, persona: Persona): List<String> = listOf(
    "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
    "--permission-prompt-tool", "stdio", "--append-system-prompt", persona.instructions, "--model", model,
)
