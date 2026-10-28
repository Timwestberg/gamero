package org.formulagen.wrapper.chat

import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.companion.terminateProcessTree
import org.formulagen.wrapper.companion.terminateProcessTreeInBackground
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.providers.CLAUDE_FALLBACK_MODEL
import org.formulagen.wrapper.providers.CLAUDE_MODELS
import org.formulagen.wrapper.providers.ClaudeConnection
import org.formulagen.wrapper.providers.ClaudeRuntime
import org.formulagen.wrapper.providers.readBoundedLine

/**
 * Claude Code behind [ChatRuntime]: one locked-down `claude -p` process per turn, on the player's
 * own Claude Code sign-in. A new conversation gets `--session-id`, an existing one `--resume`.
 * Every turn's `system/init` must pass [ClaudeLockdown] before any of its text is used; otherwise the
 * process tree is ended and the turn fails closed. Stop is SIGINT, which keeps the text so far in
 * Claude Code's session; teardown ends the process tree.
 */
internal class ClaudeChatRuntime(
    private val claude: ClaudeRuntime,
    private val connection: ClaudeConnection = ClaudeConnection(claude),
) : ChatRuntime {
    override val id = RuntimeId.CLAUDE_CODE
    private val running = ConcurrentHashMap.newKeySet<Turn>()
    /** Sends SIGINT and, if a stopped turn lingers, ends it, off the caller's thread. */
    private val stopper = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-claude-stop"))
    @Volatile private var closed = false

    override fun readiness(profile: String): Readiness {
        val status = connection.status()
        return when (status.state) {
            ProviderState.CONNECTED -> Readiness.Ready
            ProviderState.DISCONNECTED -> Readiness.SignedOut
            else -> Readiness.Unavailable(status.message ?: "Claude status could not be checked.")
        }
    }

    override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
        val model = claudeModel(turn.model) ?: run { events.failed(ChatFailure.ModelUnavailable); return TurnHandle {} }
        val saved = turn.conversation
        // A Claude session keeps the first system prompt it was given, so another persona needs a new session.
        if (saved != null && saved.personaHash != turn.persona.hash) { events.failed(ChatFailure.ConversationLost); return TurnHandle {} }
        val session = saved?.id ?: UUID.randomUUID().toString()
        val conversation = if (saved == null) listOf("--session-id", session) else listOf("--resume", session)
        val started = Turn(events, session, opens = saved == null)
        started.start(claudeTurnArguments(model, turn.persona) + conversation, turn.profile, turn.input())
        return TurnHandle { started.stop() }
    }

    /** A turn that Claude Code never saves, so the companion's session is untouched. */
    override fun summarize(request: SummaryRequest): String {
        val model = claudeModel(request.model) ?: error("Unknown model")
        val summary = CompletableFuture<String>()
        val turn = Turn(object : TurnEvents {
            override fun opened(conversation: String) = Unit
            override fun partial(text: String) = Unit
            override fun completed(text: String, conversation: String) { summary.complete(text) }
            override fun failed(failure: ChatFailure) { summary.completeExceptionally(IllegalStateException("No summary: $failure")) }
        }, session = null, opens = false)
        turn.start(claudeTurnArguments(model, request.persona) + "--no-session-persistence", request.profile, summaryInstruction(request.transcript))
        return try { boundSummary(summary.get(request.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) }
        catch (error: Exception) { turn.abandon(); throw error }
    }

    override fun release() = running.forEach { it.abandon() }

    override fun close() {
        closed = true
        release()
        stopper.shutdown()
    }

    /** One `claude -p` process. [session] is the session it must report, or null for a summary. */
    private inner class Turn(private val events: TurnEvents, private val session: String?, private val opens: Boolean) {
        private lateinit var process: Process
        /** Set once the turn is stopped, abandoned or has reported its end; nothing is reported after. */
        private val ended = AtomicBoolean()

        fun start(args: List<String>, profile: String, input: String) {
            process = claude.builder(args, claude.workspace(profile)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            running += this
            if (closed) abandon()
            Thread.ofPlatform().daemon().name("hovel-claude-turn").start { runCatching { read() }.onFailure { abandon(); report(ChatFailure.Unexpected) } }
            // One user message, then end of input: the process answers it and exits.
            try { process.outputStream.bufferedWriter().use { it.write(userMessage(input)); it.newLine() } } catch (_: IOException) { /* It exited; the reader reports why. */ }
        }

        fun stop() {
            if (!ended.compareAndSet(false, true)) return
            stopper.execute { signalInterrupt(process) }
            stopper.schedule({ if (process.isAlive) terminateProcessTree(process) }, 5, TimeUnit.SECONDS)
        }

        fun abandon() {
            ended.set(true)
            if (process.isAlive) terminateProcessTreeInBackground(process, "hovel-claude-end")
        }

        private fun read() {
            var verified = false
            var assistantError: String? = null
            var rateLimit: ClaudeEvent.RateLimit? = null
            var result: ClaudeEvent.Result? = null
            val text = StringBuilder()
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = readBoundedLine(reader) ?: break
                        if (ended.get()) continue
                        when (val event = ClaudeStream.parse(line)) {
                            is ClaudeEvent.Init -> {
                                val broken = ClaudeLockdown.verify(event) + (if (session != null && event.sessionId != session) setOf("session_id") else emptySet())
                                if (broken.isNotEmpty()) return refuse(broken)
                                if (!verified && opens) deliver { events.opened(session!!) }
                                verified = true
                            }
                            is ClaudeEvent.TextDelta -> {
                                if (!verified) return refuse(setOf("system/init"))
                                if (text.length < ChatLimits.REPLY) { text.append(event.text); deliver { events.partial(text.take(ChatLimits.REPLY).toString()) } }
                            }
                            is ClaudeEvent.RateLimit -> rateLimit = event
                            is ClaudeEvent.AssistantError -> assistantError = event.error
                            is ClaudeEvent.Result -> result = event
                            else -> Unit
                        }
                    }
                }
            } catch (_: IOException) { /* The process ended; its exit decides. */ }
            val exit = if (process.waitFor(5, TimeUnit.SECONDS)) process.exitValue() else { terminateProcessTree(process); null }
            running -= this
            val outcome = result
            when {
                !ClaudeFailures.succeeded(exit, outcome) -> report(ClaudeFailures.classify(outcome, assistantError, rateLimit))
                // A reply without a verified init is never used.
                !verified -> report(ChatFailure.Lockdown)
                else -> if (ended.compareAndSet(false, true)) {
                    events.completed((outcome?.result ?: text.toString()).take(ChatLimits.REPLY), session.orEmpty())
                }
            }
        }

        /** Fail closed: end the process tree first, then report only which fields broke the lockdown. */
        private fun refuse(broken: Set<String>) {
            log(LogLevel.Warn, "claude", "Claude Code turn refused; unexpected ${broken.sorted().joinToString()}")
            val first = ended.compareAndSet(false, true)
            terminateProcessTree(process)
            running -= this
            if (first) events.failed(ChatFailure.Lockdown)
        }

        private fun report(failure: ChatFailure) { if (ended.compareAndSet(false, true)) events.failed(failure) }

        private inline fun deliver(event: () -> Unit) { if (!ended.get()) event() }
    }
}

/** A Claude Code alias for [model]: the runtime default becomes [CLAUDE_FALLBACK_MODEL]; anything not in [CLAUDE_MODELS] is null. */
internal fun claudeModel(model: String?): String? = when (model) {
    null, RUNTIME_DEFAULT_MODEL -> CLAUDE_FALLBACK_MODEL
    in CLAUDE_MODELS.map { it.modelId } -> model
    else -> null
}

/**
 * The locked-down turn: no tools, no MCP servers, no skills, plugins, hooks or CLAUDE.md (safe
 * mode), every permission prompt denied, one model turn, and the companion persona as the system
 * prompt. The caller adds how the session is chosen.
 */
internal fun claudeTurnArguments(model: String, persona: Persona): List<String> = listOf(
    "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
    "--safe-mode", "--disable-slash-commands", "--tools", "", "--disallowedTools", "mcp__*", "--strict-mcp-config",
    "--permission-mode", "dontAsk", "--permission-prompts", "none", "--max-turns", "1",
    "--model", model, "--system-prompt", persona.instructions,
)

/** The one stdin line of a turn. */
internal fun userMessage(input: String): String = buildJsonObject {
    put("type", "user")
    putJsonObject("message") { put("role", "user"); put("content", input) }
    put("parent_tool_use_id", JsonNull)
}.toString()

/** SIGINT, which the JVM cannot send itself: Claude Code then ends the turn and keeps the text so far. */
private fun signalInterrupt(process: Process) {
    if (!process.isAlive) return
    runCatching { ProcessBuilder("/bin/kill", "-INT", process.pid().toString()).redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS) }
}
