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
 * Owns one view's conversation lease: its history, request dedupe, the reply watchdog, speech and
 * handovers between team members. Each reply runs on the [ChatRuntime] of the member that sends it;
 * workers never mutate a newer view or replay a send. [runtimes] are the runtimes found on this
 * Mac; the chat closes them.
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
    /** The last view the host ended on its own, so that view's polls keep showing why. */
    private var ended: Pair<ChatScope, ChatSnapshot>? = null
    private var closed = false

    private class Session(val scope: ChatScope, val persona: Persona, val history: ChatHistory, var record: ChatRecord) {
        var state = ChatState.READY
        var notice: String? = null
        var generation = 0
        /** The reply in progress, once its runtime has started it. */
        var turn: TurnHandle? = null
        var speak = false
        /** Runtimes this view has found ready; a lost sign-in removes one, so the next send checks again. */
        val ready = mutableSetOf<RuntimeId>()
        /** The handover being prepared, and what a cancel returns to. */
        var handover: PendingHandover? = null
        /** The record before the send in progress, restored when its runtime turns out not to be ready, since nothing reached it. */
        var beforeSend: ChatRecord? = null
    }

    private class PendingHandover(val requestId: String, val from: RuntimeId?, val state: ChatState, val notice: String?)

    /** Opening is the only operation allowed to replace an active conversation. */
    override suspend fun open(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        check(!closed)
        val old = active
        if (old?.scope == scope && old.state !in setOf(ChatState.ERROR, ChatState.DISCONNECTED)) return@synchronized view(old)
        invalidateLocked(keepEnded = false)
        ended = null
        val history = companionChatHistory(providerRoot, scope.profile, scope.companionId)
        view(Session(scope, Persona.companion(scope.name), history, history.read()).also { active = it })
    }

    /** Polling cannot reopen an expired lease, but a view the host ended keeps reading its final state. */
    override suspend fun snapshot(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        ended?.takeIf { !closed && it.first == scope }?.second?.copy(voice = speech.status()) ?: view(current(scope))
    }

    /**
     * Persist the explicit user message before starting any runtime work. A disconnected chat
     * takes a resend, which checks the member's runtime again.
     */
    override suspend fun send(args: ChatSend): ChatSnapshot = synchronized(gate) {
        val session = current(args.scope())
        if (session.record.entries.any { it.id == args.requestId }) return@synchronized view(session)
        if (session.state == ChatState.RESPONDING || session.state == ChatState.HANDING_OVER) throw BridgeBusyException()
        check(session.state in setOf(ChatState.READY, ChatState.CANCELLED, ChatState.DISCONNECTED))
        val kind = args.member.runtimeId
        if (kind !in runtimes) {
            session.state = ChatState.DISCONNECTED; session.notice = notInstalled(kind)
            return@synchronized view(session)
        }
        speech.stop()
        val text = args.text.trimEcma()
        val record = session.record
        val joining = joining(record, args.member)
        // A new model for the current member is noted just before the message it answers.
        val switched = if (joining == null) Handovers.modelChange(record.entries, args.member, UUID.randomUUID().toString()) else null
        val entries = (record.entries + listOfNotNull(joining?.first, switched) + ChatEntry.User(args.requestId, text) +
            ChatEntry.Assistant(UUID.randomUUID().toString(), args.member, "")).takeLast(ChatLimits.HISTORY)
        val next = record.copy(entries = entries, pendingContext = joining?.second ?: record.pendingContext)
        session.history.write(next)
        session.beforeSend = record
        session.record = next; session.state = ChatState.RESPONDING; session.notice = null
        session.speak = args.speak; session.turn = null
        val generation = ++session.generation
        worker.execute { attempt(session, generation, kind) { startTurn(session, generation, args.member, text, retried = false) } }
        timer.schedule({ synchronized(gate) {
            if (live(session, generation) && session.state == ChatState.RESPONDING) {
                end(session, ChatState.ERROR, "The reply took too long. Your message is saved; retry when you are ready.")
            }
        } }, 3, TimeUnit.MINUTES)
        view(session)
    }

    /**
     * Make `to` the active member. The same runtime records it at once; another runtime is checked
     * and, when there is something it missed, handed a summary (or the recent messages) on the worker.
     */
    override suspend fun handover(args: ChatHandover): ChatSnapshot = synchronized(gate) {
        val session = current(args.scope())
        if (session.record.entries.any { it.id == args.requestId } || session.handover?.requestId == args.requestId) return@synchronized view(session)
        // A refusal is a snapshot with a notice and no new entry; the page keeps the current member.
        if (session.state == ChatState.RESPONDING) return@synchronized view(session).copy(notice = "Wait for the reply to finish, or stop it, before switching.")
        if (session.state == ChatState.HANDING_OVER) return@synchronized view(session).copy(notice = "Another switch is still in progress.")
        val to = args.to.runtimeId
        if (to !in runtimes) { session.notice = notInstalled(to); return@synchronized view(session) }
        val from = args.from
        val canSummarize = from != null && from.runtimeId in runtimes && session.record.conversations[from.runtimeId] != null
        val plan = Handovers.plan(session.record, from, args.to, canSummarize)
        if (plan is HandoverPlan.Record && (to in session.ready || plan.method == HandoverMethod.SAME_CONVERSATION)) {
            record(session, args, plan.method, null, plan.context)
            return@synchronized view(session)
        }
        session.handover = PendingHandover(args.requestId, from?.runtimeId, session.state, session.notice)
        session.state = ChatState.HANDING_OVER; session.notice = null
        val generation = ++session.generation
        worker.execute { attempt(session, generation, to, onFailure = { restore(session, couldNotComplete(to)) }) { handOver(session, generation, args, plan) } }
        view(session)
    }

    /** Stop ends the reply gracefully, cancels a handover being prepared, or ends the runtimes' work when nothing has started. */
    override suspend fun stop(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        val session = current(scope)
        val handover = session.handover
        if (session.state == ChatState.HANDING_OVER && handover != null) {
            // The outgoing member stays active and nothing is recorded; its summary is abandoned.
            restore(session, handover.notice)
            handover.from?.let { runtimes[it]?.release() }
            return@synchronized view(session)
        }
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
    private fun view(session: Session) = ChatSnapshot(session.state, session.record.entries, session.notice, speech.status())

    /**
     * A runtime's first turn in this conversation is noted: `fresh` when nothing came before, or
     * `recent-messages`, which it then hears, as when an old conversation is re-joined. A runtime
     * with a conversation of its own, owed a handover, or already noted as taking over is not noted again.
     */
    private fun joining(record: ChatRecord, member: TeamMember): Pair<ChatEntry.Handover, PendingContext?>? {
        val kind = member.runtimeId
        if (record.conversations[kind] != null || record.pendingContext?.runtimeId == kind) return null
        if (record.entries.filterIsInstance<ChatEntry.Handover>().lastOrNull()?.to?.runtimeId == kind) return null
        val context = Handovers.recentMessages(Handovers.unseen(record, kind))
        val method = if (context == null) HandoverMethod.FRESH else HandoverMethod.RECENT_MESSAGES
        return Handovers.entry(UUID.randomUUID().toString(), Handovers.lastAuthor(record.entries), member, method) to
            context?.let { PendingContext(kind, it) }
    }

    /** Check the member's runtime once per view, then start the reply. A turn that outlived its view is ended at once. */
    private fun startTurn(session: Session, generation: Int, member: TeamMember, text: String, retried: Boolean) {
        val kind = member.runtimeId
        val runtime = runtimes.getValue(kind)
        if (!ready(session, generation, runtime) { readiness ->
            session.beforeSend?.let { session.record = it }
            end(session, notReadyState(readiness), notReadyNotice(kind, readiness), release = false)
        }) return
        val request = synchronized(gate) {
            check(live(session, generation))
            val record = session.record
            TurnRequest(session.scope.profile, session.scope.companionId, session.persona, member.modelId,
                conversation = record.conversations[kind], context = record.pendingContext?.takeIf { it.runtimeId == kind }?.text, text = text)
        }
        val handle = runtime.startTurn(request, Reply(session, generation, member, request, retried))
        val stale = synchronized(gate) {
            val current = live(session, generation)
            if (current && session.state == ChatState.RESPONDING) {
                session.turn = handle
                // The turn has started, so its model counts even if the reply is then stopped.
                if (member.modelId != SharedRules.RUNTIME_DEFAULT_MODEL) namedModel(session, kind)
            }
            !current
        }
        if (stale) runtime.release()
    }

    /** Remember that [kind]'s conversation was given an explicit model. */
    private fun namedModel(session: Session, kind: RuntimeId) {
        val conversation = session.record.conversations[kind]?.takeUnless { it.explicitModel } ?: return
        session.record = session.record.copy(conversations = session.record.conversations + (kind to conversation.copy(explicitModel = true)))
        runCatching { session.history.write(session.record) }
    }

    /** True once [runtime] is ready for this view; otherwise [notReady] runs with the gate held, while the work is still current. */
    private fun ready(session: Session, generation: Int, runtime: ChatRuntime, notReady: (Readiness) -> Unit): Boolean {
        if (synchronized(gate) { runtime.id in session.ready }) return true
        val readiness = runtime.readiness(session.scope.profile)
        return synchronized(gate) {
            if (!live(session, generation)) return@synchronized false
            if (readiness == Readiness.Ready) { session.ready += runtime.id; true } else { notReady(readiness); false }
        }
    }

    /** Check the incoming runtime, run the planned summary, and record the handover, unless it was cancelled. */
    private fun handOver(session: Session, generation: Int, args: ChatHandover, plan: HandoverPlan) {
        val to = args.to.runtimeId
        if (!ready(session, generation, runtimes.getValue(to)) { restore(session, notReadyNotice(to, it)) }) return
        val (method, summary, context) = when (plan) {
            is HandoverPlan.Record -> Triple(plan.method, null, plan.context)
            is HandoverPlan.Summarize -> {
                val from = checkNotNull(args.from)
                val written = try {
                    runtimes.getValue(from.runtimeId).summarize(SummaryRequest(session.scope.profile, session.persona, from.modelId, plan.transcript))
                } catch (error: Exception) {
                    if (error is InterruptedException) throw error
                    null
                }
                if (written != null) Triple(HandoverMethod.SUMMARY, written, Handovers.summaryContext(from, written))
                else Triple(HandoverMethod.RECENT_MESSAGES, null, plan.fallback)
            }
        }
        synchronized(gate) { if (live(session, generation)) record(session, args, method, summary, context) }
    }

    /** Write the handover entry and what the incoming runtime is owed; the chat is then ready for `to`. */
    private fun record(session: Session, args: ChatHandover, method: HandoverMethod, summary: String?, context: String?) {
        val to = args.to.runtimeId
        val record = session.record
        val owed = context?.let { PendingContext(to, it) } ?: record.pendingContext?.takeIf { it.runtimeId == to }
        val entry = Handovers.entry(args.requestId, args.from, args.to, method, summary)
        session.record = record.copy(entries = (record.entries + entry).takeLast(ChatLimits.HISTORY), pendingContext = owed)
        session.handover = null; session.state = ChatState.READY; session.notice = null
        runCatching { session.history.write(session.record) }.onFailure {
            session.notice = "This switch could not be saved. Keep this chat open and try again later."
        }
    }

    /** Back to where the chat was before a handover that did not happen. */
    private fun restore(session: Session, notice: String?) {
        val handover = session.handover ?: return
        session.generation++; session.handover = null
        session.state = handover.state; session.notice = notice
    }

    /** One turn's events, applied only while its view and generation are current and the reply is open. */
    private inner class Reply(val session: Session, val generation: Int, val member: TeamMember, val request: TurnRequest, val retried: Boolean) : TurnEvents {
        private val kind = member.runtimeId
        private fun open() = live(session, generation) && session.state == ChatState.RESPONDING
        private val named = member.modelId != SharedRules.RUNTIME_DEFAULT_MODEL
        /** This turn's conversation, with the persona it ran with; a model named in it stays remembered. */
        private fun keep(conversation: String): Map<RuntimeId, NativeConversation> {
            val previous = session.record.conversations[kind]?.takeIf { it.id == conversation }
            return session.record.conversations + (kind to NativeConversation(conversation, request.persona.hash, named || previous?.explicitModel == true))
        }

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
            session.state = ChatState.READY; session.notice = null; session.turn = null; session.beforeSend = null
            runCatching { session.history.write(session.record) }.onFailure {
                session.notice = "This reply could not be saved. Keep this chat open and try again later."
            }
            if (session.speak) (session.record.entries.lastOrNull() as? ChatEntry.Assistant)?.let { speech.speak(it.text) }
        }

        /** Errors keep the person's text and never send it again, except once into a new conversation when the runtime lost its own. */
        override fun failed(failure: ChatFailure) = synchronized(gate) {
            if (!open()) return@synchronized
            if (failure == ChatFailure.ConversationLost && !retried) return@synchronized startOver()
            if (failure == ChatFailure.SignedOut) session.ready -= kind
            end(session, failureState(failure), failureNotice(kind, failure), release = false)
        }

        /** The runtime starts a new conversation and hears the recent messages, noted just before the person's message. */
        private fun startOver() {
            val record = session.record
            val forgotten = record.copy(conversations = record.conversations - kind)
            val at = forgotten.entries.indexOfLast { it is ChatEntry.User }.coerceAtLeast(0)
            val earlier = forgotten.copy(entries = forgotten.entries.take(at))
            val owed = record.pendingContext?.takeIf { it.runtimeId == kind }
            val context = if (owed == null) Handovers.recentMessages(Handovers.unseen(earlier, kind)) else null
            // From the member that was current before this send's own notes.
            val from = Handovers.currentMember((session.beforeSend ?: earlier).entries)
            val note = context?.let { Handovers.entry(UUID.randomUUID().toString(), from, member, HandoverMethod.RECENT_MESSAGES) }
            val entries = (earlier.entries + listOfNotNull(note) + forgotten.entries.drop(at)).takeLast(ChatLimits.HISTORY)
            session.record = forgotten.copy(entries = entries, pendingContext = owed ?: context?.let { PendingContext(kind, it) } ?: record.pendingContext)
            session.turn = null
            updateReply(session, "")
            runCatching { session.history.write(session.record) }
            worker.execute { attempt(session, generation, kind) { startTurn(session, generation, member, request.text, retried = true) } }
        }
    }

    private fun updateReply(session: Session, text: String) {
        val entries = session.record.entries
        val reply = entries.lastOrNull() as? ChatEntry.Assistant ?: return
        session.record = session.record.copy(entries = entries.dropLast(1) + reply.copy(text = text.take(ChatLimits.REPLY)))
    }

    /**
     * Any failure, including a native linkage error, ends the request instead of killing the
     * worker; a VM error is then rethrown.
     */
    private fun attempt(session: Session, generation: Int, runtime: RuntimeId,
        onFailure: () -> Unit = { end(session, ChatState.ERROR, couldNotComplete(runtime)) }, work: () -> Unit) {
        try { work() } catch (error: Throwable) {
            synchronized(gate) { if (live(session, generation) && session.state != ChatState.DISCONNECTED) onFailure() }
            if (error is VirtualMachineError) throw error
        }
    }

    /** [release] ends every runtime's work too; a failure a runtime reported, or a graceful stop, has already ended its own. */
    private fun end(session: Session, state: ChatState, notice: String, release: Boolean = true) {
        session.generation++; session.state = state; session.notice = notice
        session.turn = null; session.speak = false; session.handover = null; session.beforeSend = null
        if (release) runtimes.values.forEach { it.release() }
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

private fun notReadyState(readiness: Readiness) = if (readiness == Readiness.SignedOut) ChatState.DISCONNECTED else ChatState.ERROR

private fun notReadyNotice(runtime: RuntimeId, readiness: Readiness) = when (readiness) {
    is Readiness.Unavailable -> readiness.reason
    else -> signedOutNotice(runtime)
}

private fun notInstalled(runtime: RuntimeId) = "Install ${runtime.program} on this Mac and restart Hovel."

private fun signedOutNotice(runtime: RuntimeId) = when (runtime) {
    RuntimeId.CODEX -> "Connect Codex in Profile → Providers to message your companion."
    RuntimeId.CLAUDE_CODE -> "Sign in to Claude Code again."
}

private fun couldNotComplete(runtime: RuntimeId) = "${runtime.program} could not complete the request. Reconnect or retry when ready."

/** The person-facing reason a turn failed. Raw runtime output never reaches it. */
internal fun failureNotice(runtime: RuntimeId, failure: ChatFailure, zone: ZoneId = ZoneId.systemDefault()): String = when (failure) {
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
