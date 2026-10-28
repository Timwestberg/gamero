package org.formulagen.wrapper.chat

import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.closeInReverse

/**
 * Owns one view's conversation lease: its history, request dedupe, the reply watchdog and speech.
 * Replies run through a [ChatRuntime]; workers never mutate a newer view or replay a send.
 * [runtimes] are the runtimes found on this Mac; the chat closes them.
 */
internal class DesktopChat(
    private val providerRoot: Path,
    runtimes: List<ChatRuntime>,
    private val speech: ChatSpeech,
) : ChatCapability, AutoCloseable {
    private val runtimes = runtimes.associateBy { it.id }
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-chat"))
    private val timer = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-chat-timeout"))
    private var active: Session? = null
    /** The last view the host ended on its own (or could not open), so that view's polls keep showing why. */
    private var ended: Pair<ChatScope, ChatSnapshot>? = null
    private var closed = false
    private class Session(val scope: ChatScope, val runtime: ChatRuntime, val persona: Persona, val history: ChatHistory, var record: ChatRecord) {
        var state = ChatState.CHECKING
        var notice: String? = "Checking ${runtime.id.product} connection…"
        var generation = 0
        /** The reply in progress, once its runtime has started it. */
        var turn: TurnHandle? = null
        var speak = false
        var checking = true
    }

    /** Opening is the only operation allowed to replace an active conversation. */
    override suspend fun open(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        check(!closed)
        val old = active
        if (old?.scope == scope && old.state !in setOf(ChatState.ERROR, ChatState.DISCONNECTED)) return@synchronized view(old)
        invalidateLocked(keepEnded = false)
        ended = null
        // The chat contract does not name a team member yet, so every companion talks to Codex.
        val kind = RuntimeKind.CODEX
        val runtime = runtimes[kind] ?: return@synchronized ChatSnapshot(ChatState.ERROR,
            notice = "Install ${kind.program} on this Mac and restart Hovel.", voice = speech.status()).also { ended = scope to it }
        val history = companionChatHistory(providerRoot, scope.profile, scope.companionId)
        val session = Session(scope, runtime, Persona.companion(scope.name), history, history.read()).also { active = it }
        val generation = session.generation
        worker.execute { attempt(session, generation) { checkReadiness(session, generation) } }
        view(session)
    }

    /** Polling cannot reopen an expired lease, but a view the host ended keeps reading its final state. */
    override suspend fun snapshot(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        ended?.takeIf { !closed && it.first == scope }?.second?.copy(voice = speech.status()) ?: view(current(scope))
    }

    /** Persist the explicit user message before starting any provider work. */
    override suspend fun send(args: ChatSend): ChatSnapshot = synchronized(gate) {
        val session = current(args.scope())
        if (session.record.entries.any { it.id == args.requestId }) return@synchronized view(session)
        if (session.checking || session.state == ChatState.RESPONDING) throw BridgeBusyException()
        check(session.state in setOf(ChatState.READY, ChatState.CANCELLED))
        speech.stop()
        val text = args.text.trimEcma()
        val author = MemberRef(session.runtime.id, RUNTIME_DEFAULT_MODEL)
        val entries = (session.record.entries + HistoryEntry.User(args.requestId, text) +
            HistoryEntry.Assistant(UUID.randomUUID().toString(), author, "")).takeLast(ChatLimits.HISTORY)
        val next = session.record.copy(entries = entries)
        session.history.write(next)
        session.record = next; session.state = ChatState.RESPONDING; session.notice = null
        session.speak = args.speak; session.turn = null
        val generation = ++session.generation
        worker.execute { attempt(session, generation) { startTurn(session, generation, text, retried = false) } }
        timer.schedule({ synchronized(gate) {
            if (live(session, generation) && session.state == ChatState.RESPONDING) {
                end(session, ChatState.ERROR, "The reply took too long. Your message is saved; retry when you are ready.")
            }
        } }, 3, TimeUnit.MINUTES)
        view(session)
    }

    /** Stop ends the reply gracefully, or ends the runtime's work when no reply has started yet (an account check, a starting turn). */
    override suspend fun stop(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        val session = current(scope)
        val turn = session.turn
        end(session, ChatState.CANCELLED, "Reply stopped.", release = turn == null)
        turn?.stop()
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
    /** The page sees user and assistant text only; handover entries reach it once the contract carries them. */
    private fun view(session: Session) = ChatSnapshot(session.state, session.record.entries.mapNotNull {
        when (it) {
            is HistoryEntry.User -> ChatMessage(it.id, ChatRole.USER, it.text)
            is HistoryEntry.Assistant -> ChatMessage(it.id, ChatRole.ASSISTANT, it.text)
            is HistoryEntry.Handover -> null
        }
    }, session.notice, speech.status())

    /** Keep the account check off the bridge thread; a runtime never substitutes an API key. */
    private fun checkReadiness(session: Session, generation: Int) {
        val readiness = session.runtime.readiness(session.scope.profile)
        synchronized(gate) {
            if (!live(session, generation)) return
            session.checking = false
            when (readiness) {
                Readiness.Ready -> if (session.state != ChatState.RESPONDING) { session.state = ChatState.READY; session.notice = null }
                Readiness.SignedOut -> { session.state = ChatState.DISCONNECTED; session.notice = signedOutNotice(session.runtime.id) }
                is Readiness.Unavailable -> { session.state = ChatState.ERROR; session.notice = readiness.reason }
            }
        }
    }

    /** Start the reply on the session's runtime. A turn that outlived its view is ended at once. */
    private fun startTurn(session: Session, generation: Int, text: String, retried: Boolean) {
        val kind = session.runtime.id
        val request = synchronized(gate) {
            check(live(session, generation))
            val record = session.record
            TurnRequest(session.scope.profile, session.scope.companionId, session.persona, model = null,
                conversation = record.conversations[kind], context = record.pendingContext?.takeIf { it.runtimeId == kind }?.text, text = text)
        }
        val handle = session.runtime.startTurn(request, Reply(session, generation, request, retried))
        val stale = synchronized(gate) {
            val current = live(session, generation)
            if (current && session.state == ChatState.RESPONDING) session.turn = handle
            !current
        }
        if (stale) session.runtime.release()
    }

    /** One turn's events, applied only while its view and generation are current and the reply is open. */
    private inner class Reply(val session: Session, val generation: Int, val request: TurnRequest, val retried: Boolean) : TurnEvents {
        private val kind = session.runtime.id
        private fun open() = live(session, generation) && session.state == ChatState.RESPONDING
        private fun keep(conversation: String) = session.record.conversations + (kind to NativeConversation(conversation, request.persona.hash))

        /** Saved before the reply ends, so a stopped turn keeps the conversation it started. */
        override fun opened(conversation: String) = synchronized(gate) {
            if (!open()) return@synchronized
            session.record = session.record.copy(conversations = keep(conversation))
            runCatching { session.history.write(session.record) }.onFailure { end(session, ChatState.ERROR, couldNotComplete(kind)) }
        }

        override fun partial(text: String) = synchronized(gate) { if (open()) updateReply(session, text) }

        override fun completed(text: String, conversation: String) = synchronized(gate) {
            if (!open()) return@synchronized
            updateReply(session, text)
            // The handover text reached this runtime with the turn, so it is no longer owed.
            val owed = session.record.pendingContext?.takeUnless { request.context != null && it.runtimeId == kind }
            session.record = session.record.copy(conversations = keep(conversation), pendingContext = owed)
            session.state = ChatState.READY; session.notice = null; session.turn = null
            runCatching { session.history.write(session.record) }.onFailure {
                session.notice = "This reply could not be saved. Keep this chat open and try again later."
            }
            if (session.speak) (session.record.entries.lastOrNull() as? HistoryEntry.Assistant)?.let { speech.speak(it.text) }
        }

        /** Errors keep the user's text and never send it again, except once into a new conversation when the runtime lost its own. */
        override fun failed(failure: ChatFailure) = synchronized(gate) {
            if (!open()) return@synchronized
            if (failure == ChatFailure.ConversationLost && request.conversation != null && !retried) {
                session.record = session.record.copy(conversations = session.record.conversations - kind); session.turn = null
                updateReply(session, "")
                runCatching { session.history.write(session.record) }
                worker.execute { attempt(session, generation) { startTurn(session, generation, request.text, retried = true) } }
                return@synchronized
            }
            end(session, failureState(failure), failureNotice(kind, failure), release = false)
        }
    }

    private fun updateReply(session: Session, text: String) {
        val entries = session.record.entries
        val reply = entries.lastOrNull() as? HistoryEntry.Assistant ?: return
        session.record = session.record.copy(entries = entries.dropLast(1) + reply.copy(text = text.take(ChatLimits.REPLY)))
    }

    /**
     * Any failure, including a native linkage error, ends the request instead of killing the
     * worker; a VM error is then rethrown.
     */
    private fun attempt(session: Session, generation: Int, work: () -> Unit) {
        try { work() } catch (error: Throwable) {
            synchronized(gate) {
                if (live(session, generation) && session.state != ChatState.DISCONNECTED) end(session, ChatState.ERROR, couldNotComplete(session.runtime.id))
            }
            if (error is VirtualMachineError) throw error
        }
    }

    /** [release] ends the runtime's work too; a failure the runtime reported, or a graceful stop, has already ended it. */
    private fun end(session: Session, state: ChatState, notice: String, release: Boolean = true) {
        session.generation++; session.state = state; session.notice = notice; session.checking = false
        session.turn = null; session.speak = false
        if (release) session.runtime.release()
        speech.stop()
        runCatching { session.history.write(session.record) }
    }

    private fun invalidateLocked(keepEnded: Boolean) {
        val session = active; active = null
        session?.let {
            end(it, ChatState.ERROR, "This chat was closed. Reconnect to continue.")
            if (keepEnded) ended = it.scope to view(it)
        }
        speech.stop()
    }

    /** App exit: stop work, then close the runtimes, which wait briefly for their processes to end. */
    override fun close() {
        synchronized(gate) {
            if (closed) return
            invalidateLocked(keepEnded = false); closed = true; ended = null; timer.shutdownNow(); worker.shutdownNow()
        }
        try { closeInReverse(runtimes.values.toList()) } finally { speech.close() }
    }
}

/** A lost sign-in leaves the chat "disconnected"; every other failure is an error the person can retry. */
private fun failureState(failure: ChatFailure) = if (failure == ChatFailure.SignedOut) ChatState.DISCONNECTED else ChatState.ERROR

private fun signedOutNotice(runtime: RuntimeKind) = when (runtime) {
    RuntimeKind.CODEX -> "Connect Codex in Profile → Providers to message your companion."
    RuntimeKind.CLAUDE_CODE -> "Sign in to Claude Code again."
}

private fun couldNotComplete(runtime: RuntimeKind) = "${runtime.program} could not complete the request. Reconnect or retry when ready."

/** The person-facing reason a turn failed. Raw runtime output never reaches it. */
internal fun failureNotice(runtime: RuntimeKind, failure: ChatFailure, zone: ZoneId = ZoneId.systemDefault()): String = when (failure) {
    ChatFailure.SignedOut -> signedOutNotice(runtime)
    is ChatFailure.UsageLimit -> "You've reached your ${runtime.product} usage limit. " +
        (failure.resetsAt?.let { "It resets at ${resetTime(it, zone)}." } ?: "Try again later.")
    ChatFailure.ModelUnavailable -> "${runtime.product} can't use this model. Choose another model and try again."
    ChatFailure.Temporary -> "The reply stopped. Try again when ${runtime.product} is ready."
    ChatFailure.ConversationLost -> "${runtime.product} could not continue this conversation. Send your message again to start fresh."
    ChatFailure.Lockdown -> "${runtime.program} started with tools or plugins that companion chat does not allow, so the reply was stopped."
    ChatFailure.Unexpected -> "${runtime.product} could not finish this reply. Check Providers and try again."
}

/** "3:00 PM" today, otherwise the date too, in the Mac's time zone. */
private fun resetTime(at: Instant, zone: ZoneId): String {
    val local = at.atZone(zone)
    return if (local.toLocalDate() == LocalDate.now(zone)) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local)
    else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(local)
}
