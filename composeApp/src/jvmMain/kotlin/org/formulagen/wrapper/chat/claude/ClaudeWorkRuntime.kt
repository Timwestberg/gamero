package org.formulagen.wrapper.chat.claude

import java.io.File
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalBroker
import org.formulagen.wrapper.approvals.ApprovalDecision
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.bridge.ActivityKind
import org.formulagen.wrapper.bridge.ActivityStatus
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.ChatWaiting
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.bridge.WaitingKind
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.ChatRuntime
import org.formulagen.wrapper.chat.ClaudeEvent
import org.formulagen.wrapper.chat.ClaudeFailures
import org.formulagen.wrapper.chat.ClaudeStream
import org.formulagen.wrapper.chat.Persona
import org.formulagen.wrapper.chat.Readiness
import org.formulagen.wrapper.chat.SummaryRequest
import org.formulagen.wrapper.chat.TurnEvents
import org.formulagen.wrapper.chat.TurnHandle
import org.formulagen.wrapper.chat.TurnRequest
import org.formulagen.wrapper.chat.claudeModel
import org.formulagen.wrapper.chat.loggingDaemonThreads
import org.formulagen.wrapper.chat.userMessage
import org.formulagen.wrapper.companion.terminateProcessTree
import org.formulagen.wrapper.companion.terminateProcessTreeInBackground
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.process.readBoundedLine
import org.formulagen.wrapper.process.runBounded
import org.formulagen.wrapper.providers.CLAUDE_STATUS_UNKNOWN
import org.formulagen.wrapper.providers.ClaudeConnection
import org.formulagen.wrapper.providers.ClaudeRuntime

/**
 * Claude Code working in a companion's project folder, with exactly the player's own setup: one
 * long-lived `claude -p … --permission-prompt-tool stdio --append-system-prompt <working persona>`
 * per (chat lease, member), in the folder, with no `--safe-mode`, `--tools`, `--strict-mcp-config`
 * or `--permission-mode`, so the player's settings, `CLAUDE.md`, skills, plugins and MCP servers
 * apply as in their terminal. Everything Claude Code would ask the player goes to [broker] (the
 * native dialog); the page only sees `awaiting-you`. A grant for the conversation lives in the
 * process, which is why the process lives as long as the conversation.
 *
 * Stop is `control_request interrupt` (the process and its grants stay); release, close and a
 * broken process end the process tree. Talk-only Claude members are [org.formulagen.wrapper.chat.ClaudeChatRuntime],
 * unchanged. [environment] is the working child's whole environment ([claudeWorkEnvironment]).
 */
internal class ClaudeWorkRuntime(
    private val claude: ClaudeRuntime,
    private val connection: ClaudeConnection,
    private val broker: ApprovalBroker,
    private val environment: () -> Map<String, String> = { claudeWorkEnvironment(System.getenv(), loginShellPath.value, claude.executable) },
    /** How long a stopped turn may take to report its end before its process is replaced. */
    private val settle: kotlin.time.Duration = 10.seconds,
) : ChatRuntime {
    override val id = RuntimeId.CLAUDE_CODE
    private val sessions = ConcurrentHashMap<Pair<String, TeamMember>, WorkProcess>()
    /** Approvals block on the native dialog, so each one waits on its own thread. */
    private val approvals = Executors.newCachedThreadPool(loggingDaemonThreads("hovel-claude-approval"))
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
        val key = work.lease to work.member
        var process = sessions[key]?.takeIf { it.fits(work.folder, model, turn.persona, saved?.id) && it.ready(settle) }
        if (process == null) {
            sessions.remove(key)?.close()
            val session = saved?.id ?: UUID.randomUUID().toString()
            process = WorkProcess(work.folder, model, turn.persona, session, opens = saved == null, work.companion, work.member)
            sessions[key] = process
            if (closed) { process.close(); error("Chat closed") }
            try { process.start() } catch (error: Exception) { sessions.remove(key, process); process.close(); throw error }
        }
        return process.turn(turn.input(), events)
    }

    /** Summaries come from the member's talk-only runtime; a working process never writes one. */
    override fun summarize(request: SummaryRequest): String = throw UnsupportedOperationException("Working members summarize through their talk runtime")

    /** End every working process: their pending approvals are denied and their trees ended. */
    override fun release() { sessions.keys.toList().forEach { key -> sessions.remove(key)?.close() } }

    override fun close() {
        closed = true
        release()
        approvals.shutdownNow()
    }

    /** One working `claude -p` process: a session in a folder with a model and a persona. */
    private inner class WorkProcess(
        val folder: Path,
        val model: String,
        val persona: Persona,
        val session: String,
        private var opens: Boolean,
        private val companion: String,
        private val member: TeamMember,
    ) {
        private lateinit var process: Process
        private lateinit var writer: java.io.BufferedWriter
        private val initialized = CompletableFuture<Unit>()
        private val sequence = AtomicInteger()
        private val ended = AtomicBoolean()
        /** Pending permission requests by request id, with what the dialog shows. */
        private val pending = ConcurrentHashMap<String, Pair<CancelSignal, ChatWaiting>>()
        /** Whether Claude Code has asked Hovel anything yet: proof the permission prompt reaches the host. */
        @Volatile private var asked = false
        @Volatile private var current: WorkTurn? = null

        fun fits(folder: Path, model: String, persona: Persona, session: String?) =
            !ended.get() && process.isAlive && this.folder == folder && this.model == model && this.persona == persona && (session == null || session == this.session)

        /** No earlier turn still running: a stopped one gets [wait] to report its end. */
        fun ready(wait: kotlin.time.Duration): Boolean {
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
            pendingInitialize = id
            send(ClaudeControl.initialize(id))
            // The answer names the player's account; it is only waited for, never kept or shown.
            try { initialized.get(INITIALIZE_SECONDS, TimeUnit.SECONDS) }
            catch (error: Exception) { close(); throw IllegalStateException("Claude Code did not start working", error) }
        }

        @Volatile private var pendingInitialize: String? = null

        fun turn(input: String, events: TurnEvents): TurnHandle {
            val turn = WorkTurn(events, ClaudeActivity(folder))
            current = turn
            // A working reply carries its activity from the start.
            events.activity(turn.activity.activity())
            try { send(userMessage(input)) } catch (_: IOException) { turn.fail(ChatFailure.Temporary); close() }
            return TurnHandle { stop(turn) }
        }

        /** Deny what the turn waits on, then ask Claude Code to stop the turn; the process and its grants stay. */
        private fun stop(turn: WorkTurn) {
            if (!turn.stop()) return
            pending.values.forEach { it.first.cancel() }
            approvals.execute { runCatching { send(ClaudeControl.interrupt("hovel-${sequence.incrementAndGet()}")) } }
        }

        private fun read() {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = readBoundedLine(reader) ?: break
                        val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                        when (val control = ClaudeControl.decode(message)) {
                            null -> current?.receive(line, message)
                            is ClaudeControlMessage.Response -> if (control.requestId == pendingInitialize) {
                                if (control.success) initialized.complete(Unit) else initialized.completeExceptionally(IllegalStateException("initialize refused"))
                            }
                            is ClaudeControlMessage.CanUseTool -> {
                                asked = true
                                // Registered before the next line is read, so a withdrawal right after it finds it.
                                val request = ClaudeControl.approvalRequest(control.permission, companion, member, folder)
                                val cancel = CancelSignal()
                                pending[control.permission.requestId] = cancel to ChatWaiting(WaitingKind.APPROVAL, request.summary())
                                approvals.execute { approve(control.permission, request, cancel) }
                            }
                            is ClaudeControlMessage.Unreadable -> {
                                asked = true
                                send(ClaudeControl.deny(control.requestId, control.toolUseId, ClaudeControl.UNREADABLE))
                                current?.note("A request Hovel could not show", ClaudeControl.UNREADABLE)
                            }
                            is ClaudeControlMessage.Unsupported -> send(ClaudeControl.error(control.requestId))
                            is ClaudeControlMessage.Cancel -> pending[control.requestId]?.first?.cancel()
                        }
                    }
                }
            } catch (_: IOException) { /* The process ended; reported below. */ }
            finally {
                initialized.completeExceptionally(IllegalStateException("Claude Code stopped"))
                if (ended.compareAndSet(false, true)) pending.values.forEach { it.first.cancel() }
                // A turn still open when the process goes stopped unexpectedly: its finished steps stay, and it can be retried.
                current?.fail(ChatFailure.Temporary)
            }
        }

        /** Ask the player natively; the dialog's answer, a stop, a cancel or the timeout decides. */
        private fun approve(permission: ToolPermission, request: org.formulagen.wrapper.approvals.ApprovalRequest, cancel: CancelSignal) {
            val turn = current
            if (turn == null || turn.stopped || ended.get()) cancel.cancel()
            if (!cancel.isCancelled) turn?.events?.waiting(ChatWaiting(WaitingKind.APPROVAL, request.summary()))
            val asked = try { broker.ask(request, cancel) } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                ApprovalDecision.DENY
            }
            // A stopped turn, a closed chat or a withdrawn request is never allowed, whatever the dialog said last.
            val decision = if (cancel.isCancelled) ApprovalDecision.DENY else asked
            pending.remove(permission.requestId)
            // Another request may still be waiting on its own dialog; the page shows that one next.
            turn?.events?.waiting(pending.values.firstOrNull()?.second)
            runCatching { send(ClaudeControl.answer(permission, decision)) }
        }

        @Synchronized private fun send(line: String) {
            writer.write(line); writer.newLine(); writer.flush()
        }

        fun close() {
            if (ended.compareAndSet(false, true)) pending.values.forEach { it.first.cancel() }
            current?.fail(ChatFailure.Temporary)
            if (::process.isInitialized && process.isAlive) terminateProcessTreeInBackground(process, "hovel-claude-work-end")
        }

        /** One turn on this process, from the person's message to its `result`. */
        private inner class WorkTurn(val events: TurnEvents, val activity: ClaudeActivity) {
            val finished = CompletableFuture<Unit>()
            private val done = AtomicBoolean()
            @Volatile var stopped = false
                private set
            private var verified = false
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
                        if (event.sessionId != session) return fail(ChatFailure.Unexpected)
                        if (opens) { opens = false; report { events.opened(session) } }
                        report { events.accepted() }
                    }
                    is ClaudeEvent.TextDelta -> if (text.length < ChatLimits.REPLY) {
                        text.append(event.text); report { events.partial(text.take(ChatLimits.REPLY).toString()) }
                    }
                    is ClaudeEvent.RateLimit -> rateLimit = event
                    is ClaudeEvent.AssistantError -> assistantError = event.error
                    is ClaudeEvent.Result -> finish(event)
                    else -> Unit
                }
            }

            private fun finish(result: ClaudeEvent.Result) {
                if (!done.compareAndSet(false, true)) return
                finished.complete(Unit)
                if (stopped) return
                val succeeded = !result.isError && result.terminalReason in setOf(null, "completed")
                when {
                    // Claude Code denied a tool without asking: the permission prompt never reached Hovel.
                    activity.deniedWithoutAsking && !asked -> {
                        log(LogLevel.Warn, "claude", "Working turn refused; a permission was denied without reaching Hovel")
                        events.failed(ChatFailure.Unexpected)
                    }
                    succeeded -> events.completed(text.toString().ifBlank { result.result.orEmpty() }.take(ChatLimits.REPLY), session)
                    else -> events.failed(ClaudeFailures.classify(result, assistantError, rateLimit))
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
        /** The player's login-shell `PATH`, read once: a Mac app started from Finder has only the system folders. */
        val loginShellPath = lazy { readLoginShellPath(System.getenv()) }
    }
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun CompletableFuture<Unit>.awaitQuietly(wait: kotlin.time.Duration): Boolean =
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

/**
 * A working child's whole environment: the player's own ([parent]), with their login shell's `PATH`
 * ([loginPath]) so their tools are found, and the Claude executable's folder on it. Removed:
 * `ANTHROPIC_*` (an API key or another endpoint instead of the subscription) and the variables
 * another Claude Code session sets for its children (`CLAUDECODE`, `CLAUDE_CODE_*`, every other
 * `CLAUDE_*`), except `CLAUDE_CONFIG_DIR`, which is where the player keeps their own setup.
 */
internal fun claudeWorkEnvironment(parent: Map<String, String>, loginPath: String?, executable: Path): Map<String, String> {
    val kept = parent.filterKeys { key ->
        !key.startsWith("ANTHROPIC_") && key != "CLAUDECODE" && (!key.startsWith("CLAUDE_") || key == "CLAUDE_CONFIG_DIR")
    }
    val path = (loginPath ?: parent["PATH"]).orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
    val withClaude = (listOfNotNull(executable.parent?.toString()).filter { it !in path } + path +
        listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin").filter { it !in path })
    return kept + ("PATH" to withClaude.joinToString(File.pathSeparator)) + ("HOME" to (parent["HOME"] ?: System.getProperty("user.home")))
}

/** The `PATH` the player's login shell sets up, or null when it cannot be read in a few seconds. */
internal fun readLoginShellPath(parent: Map<String, String>): String? {
    val shell = parent["SHELL"]?.takeIf { Path.of(it).isAbsolute && Files.isExecutable(Path.of(it)) } ?: "/bin/zsh"
    val marker = "__HOVEL_PATH__"
    val output = runBounded(ProcessBuilder(shell, "-l", "-c", "printf '$marker%s' \"\$PATH\"").directory(File(System.getProperty("user.home"))), 5.seconds)
        ?.takeIf { it.exitCode == 0 }?.output ?: return null
    return output.substringAfterLast(marker, "").trim().takeIf { value ->
        value.isNotEmpty() && value.split(File.pathSeparator).all { it.isEmpty() || it.startsWith("/") }
    }
}
