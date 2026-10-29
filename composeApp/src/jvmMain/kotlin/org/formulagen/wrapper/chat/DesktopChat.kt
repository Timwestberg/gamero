package org.formulagen.wrapper.chat

import java.nio.file.Path
import org.formulagen.wrapper.voice.HEARING_ENDPOINT_SILENCE_MS
import org.formulagen.wrapper.voice.HEARING_MIN_SPEECH_MS
import org.formulagen.wrapper.voice.HEARING_RESUME_GUARD_MS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.approvals.memberLabel
import org.formulagen.wrapper.approvals.startOfWorkSummary
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.closeInReverse
import org.formulagen.wrapper.providers.copy
import org.formulagen.wrapper.util.shortened
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.FILLER_LINES
import org.formulagen.wrapper.voice.FillerRotation
import org.formulagen.wrapper.voice.FillerSettings
import org.formulagen.wrapper.voice.FillerTurn
import org.formulagen.wrapper.voice.HearingResult
import org.formulagen.wrapper.workspace.ProjectFolder

/**
 * Owns one view's conversation lease: its history, request dedupe, the reply watchdog, speech and
 * handovers between team members. Each reply runs on the [ChatRuntime] of the member that sends it;
 * workers never mutate a newer view or replay a send. [runtimes] are the runtimes found on this
 * Mac; the chat closes them. [offered] are the runtimes this build offers, found or not.
 *
 * A message in `work` mode ([work]) runs on the member's working runtime in the companion's project
 * folder, after the person confirms the start of work natively; while a native dialog is up the
 * chat is `awaiting-you` and refuses sends, retries and handovers. Without a folder, or when the
 * person chooses Not now, the message is answered as talk with a notice.
 */
internal class DesktopChat(
    private val providerRoot: Path,
    runtimes: List<ChatRuntime>,
    private val speech: ChatSpeech,
    private val offered: Set<RuntimeId> = RuntimeId.entries.toSet(),
    private val work: ChatWork? = null,
    private val voices: VoicePickerModel? = null,
    private val fillers: FillerSettings = FillerSettings(),
    private val fillerFile: Path? = null,
    private val silenceMs: Int = HEARING_ENDPOINT_SILENCE_MS,
    private val guardMs: Int = HEARING_RESUME_GUARD_MS,
    private val hearing: DesktopHearing? = null,
) : ChatCapability, AutoCloseable {
    private val runtimes = runtimes.associateBy { it.id }
    private val workers: Map<RuntimeId, WorkRuntime> = work?.runtimes.orEmpty()
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-chat"))
    private val timer = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-chat-timeout"))
    private val fillerRotation = FillerRotation()
    private var fillerTurn = FillerTurn(fillers, fillerRotation)
    private var liveFillers = fillers
    private var talkOn = false
    private var talkPhase = TalkPhase.OFF
    private var guardGeneration = 0
    private var resumePending = false
    private var hearingPartial = ""
    private var hearingNotice: String? = null
    private var active: Session? = null
    /** The last view the host ended on its own, so that view's polls keep showing why. */
    private var ended: Pair<ChatScope, ChatSnapshot>? = null
    private var closed = false

    init {
        speech.onVoice { synchronized(gate) { if (!closed) publishPicker() } }
    }

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
        /** The entries before the turn in progress added its own notes; they name the member it took over from. */
        var beforeTurn: List<ChatEntry> = emptyList()
        /** The turn in progress, from its send or retry until it ends. */
        var inFlight: InFlight? = null
        /** Why the last turn failed, until a turn succeeds. */
        var failure: ChatFailureReport? = null
        /** Runtimes this view has run turns or summaries on; closing the view ends their work. */
        val used = mutableSetOf<RuntimeId>()
        /** What the native dialog asks while `awaiting-you`. */
        var waiting: ChatWaiting? = null
        /** Cancels a start-of-work confirmation still on screen. */
        var startCancel: CancelSignal? = null
        /** A refusal's notice, shown while the chat stays in the state it was refused in. */
        var refusal: Pair<ChatState, String>? = null
    }

    private class PendingHandover(val requestId: String, val from: RuntimeId?, val state: ChatState, val notice: String?)

    /**
     * A turn answering the user entry [userEntryId] with [member], whose reply is the entry [replyId].
     * [mode] is what was asked; [work] is the folder once a working turn is confirmed, and [notice]
     * stays after the reply when a working message was answered as talk.
     */
    private class InFlight(val member: TeamMember, val userEntryId: String, val replyId: String, val text: String,
        val mode: MemberMode = MemberMode.TALK, val priorSteps: List<ActivityStep> = emptyList(), val effort: ModelEffort? = null) {
        var work: ProjectFolder? = null
        var notice: String? = null
    }

    /** Opening is the only operation allowed to replace an active conversation. */
    override suspend fun open(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        check(!closed)
        val old = active
        if (old?.scope == scope && old.state !in setOf(ChatState.ERROR, ChatState.DISCONNECTED)) {
            publishPicker()
            return@synchronized view(old)
        }
        invalidateLocked(keepEnded = false)
        ended = null
        val history = companionChatHistory(providerRoot, scope.profile, scope.companionId)
        val snapshot = view(Session(scope, Persona.companion(scope.name), history, history.read()).also { active = it })
        publishPicker()
        if (liveFillers.enabled) speech.warmFillers(FILLER_LINES)
        snapshot
    }

    /** Polling cannot reopen an expired lease, but a view the host ended keeps reading its final state. */
    override suspend fun snapshot(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        publishPicker()
        ended?.takeIf { !closed && it.first == scope }?.second?.copy(voice = speech.status()) ?: view(current(scope))
    }

    /**
     * Persist the explicit user message, as an entry with the request's id, before starting any
     * runtime work. A chat that is disconnected or in error takes a new send, which checks the
     * member's runtime again. A message whose turn fails stays, unanswered, for `chat.retry`. A
     * member on a runtime this build does not offer is refused with a notice and no entry.
     */
    override suspend fun send(args: ChatSend): ChatSnapshot = synchronized(gate) { submitLocked(args) }

    private fun submitLocked(args: ChatSend): ChatSnapshot {
        val session = current(args.scope())
        if (session.record.entries.any { it.id == args.requestId }) return view(session)
        if (session.state == ChatState.AWAITING_YOU) return refuse(session, ANSWER_FIRST)
        if (session.state == ChatState.RESPONDING || session.state == ChatState.HANDING_OVER) throw BridgeBusyException()
        val kind = args.member.runtimeId
        if (kind !in offered) return view(session).copy(notice = kind.copy.notOffered)
        fillerTurn.cancel()
        speech.stop()
        val text = args.text.trimEcma()
        val record = session.record
        // A runtime's first turn, a runtime catching up, or a new model is noted just before the message it answers.
        val noted = Handovers.beforeTurn(record, args.member)
        val turn = InFlight(args.member, args.requestId, UUID.randomUUID().toString(), text, args.mode ?: MemberMode.TALK, effort = args.effort)
        val next = noted.copy(entries = (noted.entries + ChatEntry.User(args.requestId, text) +
            ChatEntry.Assistant(turn.replyId, args.member, "")).takeLast(ChatLimits.HISTORY))
        session.history.write(next)
        session.record = next
        begin(session, turn, record.entries, args.speak)
        return view(session)
    }

    /**
     * Run the turn of an unanswered message again with [ChatRetry.member], adding no user entry. A
     * different member than the conversation's current one is noted just before the message, by the
     * same rules as a handover (without a summary). Its reply takes the request's id, so a repeated
     * retry runs one turn. Refused, with a notice and no change, unless the message is unanswered and
     * no reply or switch is in progress.
     */
    override suspend fun retry(args: ChatRetry): ChatSnapshot = synchronized(gate) {
        val session = current(args.scope())
        val record = session.record
        if (record.entries.any { it.id == args.requestId }) return@synchronized view(session)
        if (session.state == ChatState.AWAITING_YOU) return@synchronized refuse(session, ANSWER_FIRST)
        if (session.state == ChatState.RESPONDING || session.state == ChatState.HANDING_OVER) {
            return@synchronized refuse(session, "Wait for the reply to finish, or stop it, before trying again.")
        }
        val at = record.entries.indexOfFirst { it is ChatEntry.User && it.id == args.entryId && it.status == UserStatus.UNANSWERED }
        if (at < 0) return@synchronized refuse(session, "Only a message without a reply can be sent again.")
        if (args.member.runtimeId !in offered) return@synchronized view(session).copy(notice = args.member.runtimeId.copy.notOffered)
        val message = record.entries[at] as ChatEntry.User
        speech.stop()
        val before = record.copy(entries = record.entries.take(at))
        val switched = Handovers.switchTo(before, args.member)
        val notes = switched?.entries?.drop(before.entries.size).orEmpty()
        // The reply the failed turn left, if any, is replaced.
        val after = record.entries.drop(at + 1).let { rest -> if (rest.firstOrNull() is ChatEntry.Assistant) rest.drop(1) else rest }
        // A failed working reply's finished steps stay with the message's next try, if it works too.
        val failed = (record.entries.drop(at + 1).firstOrNull() as? ChatEntry.Assistant)?.activity?.steps.orEmpty()
        val turn = InFlight(args.member, message.id, args.requestId, message.text, args.mode ?: MemberMode.TALK, failed, args.effort)
        val next = record.copy(entries = (before.entries + notes + message + ChatEntry.Assistant(turn.replyId, args.member, "") + after)
            .takeLast(ChatLimits.HISTORY), pendingContext = switched?.pendingContext ?: record.pendingContext)
        session.history.write(next)
        session.record = next
        begin(session, turn, before.entries, args.speak)
        view(session)
    }

    /** Start [turn] on its member's runtime, with the watchdog; a runtime not on this Mac fails it at once. */
    private fun begin(session: Session, turn: InFlight, before: List<ChatEntry>, speak: Boolean) {
        val kind = turn.member.runtimeId
        session.inFlight = turn; session.beforeTurn = before; session.turn = null
        if (kind !in runtimes) return fail(session, ChatFailureKind.UNAVAILABLE, null, ChatState.DISCONNECTED, missing(kind), release = false)
        session.state = ChatState.RESPONDING; session.notice = null; session.speak = speak
        val generation = ++session.generation
        worker.execute { attempt(session, generation, kind) { if (decide(session, generation, turn)) startTurn(session, generation, turn, retried = false) } }
        // Work waits for the person and runs longer; its dialogs bound each wait, and this bounds the whole reply.
        val working = turn.mode == MemberMode.WORK
        timer.schedule({ synchronized(gate) {
            if (live(session, generation) && (session.state == ChatState.RESPONDING || session.state == ChatState.AWAITING_YOU)) {
                fail(session, ChatFailureKind.TEMPORARY, null, ChatState.ERROR,
                    if (working) "The work took too long. Your message is saved; retry when you are ready."
                    else "The reply took too long. Your message is saved; retry when you are ready.")
            }
        } }, if (working) WORK_TIMEOUT_MINUTES else TALK_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    }

    /**
     * Whether [turn] works or talks, decided on the worker before it starts: a working message needs a
     * runtime that can work here, the companion's project folder, and the person's Start working for
     * this conversation, asked natively while the chat is `awaiting-you`. Anything else answers as
     * talk with a notice. False when the view moved on meanwhile.
     */
    private fun decide(session: Session, generation: Int, turn: InFlight): Boolean {
        if (turn.mode != MemberMode.WORK) return true
        val member = turn.member
        val scope = session.scope
        fun talk(notice: String) = synchronized(gate) { turn.notice = notice.shortened(ChatLimits.NOTICE); live(session, generation) }
        val work = work ?: return talk(member.runtimeId.copy.cannotWork)
        val folder = when (val availability = work.availability(scope.profile, scope.companionId, member)) {
            is WorkAvailability.Talk -> return talk(availability.notice)
            is WorkAvailability.Work -> availability.folder
        }
        if (!work.startOfWork.isConfirmed(scope.sessionId, scope.companionId, folder.path, member)) {
            val cancel = CancelSignal()
            synchronized(gate) {
                if (!live(session, generation)) return false
                session.state = ChatState.AWAITING_YOU
                session.waiting = ChatWaiting(WaitingKind.START, startOfWorkSummary(scope.name, folder.path, member))
                session.startCancel = cancel
            }
            val started = work.startOfWork.ask(scope.name, folder.path, member, cancel)
            synchronized(gate) {
                // An answer that comes back after the view moved on is never remembered.
                if (!live(session, generation)) return false
                session.state = ChatState.RESPONDING; session.waiting = null; session.startCancel = null
                if (started) work.startOfWork.remember(scope.sessionId, scope.companionId, folder.path, member)
            }
            if (!started) return talk("${memberLabel(member)} answered without working. Choose Start working to let them work in ${folder.name}.")
        }
        return synchronized(gate) {
            if (!live(session, generation)) return false
            // The page labels a reply as work by its activity, so a working reply carries one from its start
            // (with the steps a failed try already finished, when this is its retry).
            turn.work = folder
            updateActivity(session, turn.replyId, merged(turn, Activity(emptyList(), truncated = false)))
            true
        }
    }

    /** [activity] after the steps an earlier, failed try of the same message finished, within the contract's bounds. */
    private fun merged(turn: InFlight, activity: Activity): Activity {
        if (turn.priorSteps.isEmpty()) return activity
        val steps = turn.priorSteps + activity.steps
        return Activity(steps.take(ActivityLimits.STEPS), activity.truncated || steps.size > ActivityLimits.STEPS)
    }

    /**
     * Make `to` the active member. The same runtime records it at once; another runtime is checked
     * and, when there is something it missed, handed a summary (or the recent messages) on the worker.
     */
    override suspend fun handover(args: ChatHandover): ChatSnapshot = synchronized(gate) {
        val session = current(args.scope())
        if (session.record.entries.any { it.id == args.requestId } || session.handover?.requestId == args.requestId) return@synchronized view(session)
        // A refusal is a snapshot with a notice and no new entry; the page keeps the current member.
        if (session.state == ChatState.AWAITING_YOU) return@synchronized refuse(session, ANSWER_FIRST)
        if (session.state == ChatState.RESPONDING) return@synchronized refuse(session, "Wait for the reply to finish, or stop it, before switching.")
        if (session.state == ChatState.HANDING_OVER) return@synchronized refuse(session, "Another switch is still in progress.")
        val to = args.to.runtimeId
        if (to !in runtimes) { session.notice = missing(to); return@synchronized view(session) }
        val from = args.from
        // A member that only worked here has spoken too: its summary comes from its talk-only runtime, from the transcript.
        val canSummarize = from != null && from.runtimeId in runtimes &&
            (session.record.conversations[from.runtimeId] != null || session.record.workConversations[from.runtimeId] != null)
        val plan = Handovers.plan(session.record, from, args.to, canSummarize)
        if (plan is HandoverPlan.Record && (to in session.ready || plan.method == HandoverMethod.SAME_CONVERSATION)) {
            record(session, args, plan.method, null, plan.context)
            return@synchronized view(session)
        }
        from?.runtimeId?.let { session.used += it }
        session.handover = PendingHandover(args.requestId, from?.runtimeId, session.state, session.notice)
        session.state = ChatState.HANDING_OVER; session.notice = null
        val generation = ++session.generation
        worker.execute { attempt(session, generation, to, onFailure = { restore(session, couldNotComplete(to)) }) { handOver(session, generation, args, plan) } }
        view(session)
    }

    /**
     * Stop ends the reply gracefully (or, before its runtime started it, ends that runtime's work),
     * or cancels a handover being prepared. With neither in progress it changes nothing.
     */
    override suspend fun stop(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        val session = current(scope)
        if (talkOn) {
            endConversationLocked()
            return@synchronized view(session)
        }
        val handover = session.handover
        if (session.state == ChatState.HANDING_OVER && handover != null) {
            // The outgoing member stays active and nothing is recorded; its summary is abandoned.
            restore(session, handover.notice)
            handover.from?.let { runtimes[it]?.release() }
            return@synchronized view(session)
        }
        if (session.state != ChatState.RESPONDING && session.state != ChatState.AWAITING_YOU) return@synchronized view(session)
        // A pending dialog is denied: the start of work by its cancel, an approval by the runtime's stop.
        val turn = session.turn
        end(session, ChatState.CANCELLED, "Reply stopped.", release = turn == null)
        turn?.stop()
        publishPicker()
        view(session)
    }

    /** Audio can stop independently while text generation continues. */
    override suspend fun stopSpeaking(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        val session = current(scope); session.speak = false; speech.stop(); publishPicker(); view(session)
    }

    /** An old view cannot close the newly opened companion. */
    override suspend fun closeSession(scope: ChatScope) = synchronized(gate) {
        if (active?.scope == scope) invalidateLocked(keepEnded = false)
        if (ended?.first == scope) ended = null
        // The conversation's start-of-work confirmations end with its lease.
        work?.startOfWork?.forget(scope.sessionId)
        publishPicker()
    }

    /** The voice row calls this. The next spoken sentence uses [id], and a short line plays now. */
    internal fun chooseSpokenVoice(id: String) {
        speech.previewVoice(id)
        synchronized(gate) { publishPicker() }
    }

    /** Called when the document or provider login changes. A still-open view reads why and can reconnect. */
    fun invalidate() = synchronized(gate) { invalidateLocked(keepEnded = true) }

    /** Why a member's runtime cannot answer here: this build does not offer it, or Hovel did not find it. */
    private fun missing(runtime: RuntimeId) = if (runtime in offered) runtime.copy.notInstalled else runtime.copy.notOffered

    private fun current(scope: ChatScope): Session = checkNotNull(active?.takeIf { !closed && it.scope == scope }) { "Chat view expired" }
    private fun live(session: Session, generation: Int = session.generation) = !closed && active === session && generation == session.generation
    private fun view(session: Session) = ChatSnapshot(session.state, session.record.entries,
        session.refusal?.takeIf { it.first == session.state }?.second ?: session.notice, speech.status(), session.failure,
        session.waiting?.takeIf { session.state == ChatState.AWAITING_YOU })

    /** A refusal: no change, and its notice stays in every snapshot until the chat's state changes. */
    private fun refuse(session: Session, notice: String): ChatSnapshot {
        session.refusal = session.state to notice
        return view(session)
    }

    /** Check the member's runtime once per view, then start the reply. A turn that outlived its view is ended at once. */
    private fun startTurn(session: Session, generation: Int, turn: InFlight, retried: Boolean) {
        val member = turn.member
        val kind = member.runtimeId
        val folder = synchronized(gate) { turn.work }
        val worker = folder?.let { workers.getValue(kind) }
        val runtime = runtimes.getValue(kind)
        synchronized(gate) { if (live(session, generation)) session.used += kind }
        // Nothing reached the runtime, but the message stays, unanswered, for a retry.
        if (!ready(session, generation, kind, worker?.let { it::readiness } ?: runtime::readiness) { readiness ->
            fail(session, if (readiness == Readiness.SignedOut) ChatFailureKind.SIGNED_OUT else ChatFailureKind.UNAVAILABLE, null,
                notReadyState(readiness), notReadyNotice(kind, readiness), release = false)
        }) return
        val request = synchronized(gate) {
            check(live(session, generation))
            val record = session.record
            val scope = session.scope
            if (folder == null) TurnRequest(scope.profile, scope.companionId, session.persona, member.modelId,
                conversation = record.conversations[kind], context = Handovers.owedTo(record, kind), text = turn.text, effort = turn.effort)
            else TurnRequest(scope.profile, scope.companionId, Persona.working(scope.name), member.modelId,
                conversation = record.workConversations[kind], context = Handovers.workContext(record, kind, session.beforeTurn), text = turn.text,
                work = WorkScope(folder.path, scope.sessionId, scope.name, member), effort = turn.effort)
        }
        val reply = Reply(session, generation, turn, request, retried)
        val handle = worker?.startTurn(request, reply) ?: runtime.startTurn(request, reply)
        val stale = synchronized(gate) {
            val current = live(session, generation)
            // A working turn may already wait on an approval; Stop must still reach it.
            if (current && (session.state == ChatState.RESPONDING || session.state == ChatState.AWAITING_YOU)) session.turn = handle
            !current
        }
        if (stale) worker?.release() ?: runtime.release()
    }

    /** True once runtime [id] is ready for this view; otherwise [notReady] runs with the gate held, while the work is still current. */
    private fun ready(session: Session, generation: Int, id: RuntimeId, readiness: (String) -> Readiness, notReady: (Readiness) -> Unit): Boolean {
        if (synchronized(gate) { id in session.ready }) return true
        val answer = readiness(session.scope.profile)
        return synchronized(gate) {
            if (!live(session, generation)) return@synchronized false
            if (answer == Readiness.Ready) { session.ready += id; true } else { notReady(answer); false }
        }
    }

    /** Check the incoming runtime, run the planned summary, and record the handover, unless it was cancelled. */
    private fun handOver(session: Session, generation: Int, args: ChatHandover, plan: HandoverPlan) {
        val to = args.to.runtimeId
        if (!ready(session, generation, to, runtimes.getValue(to)::readiness) { restore(session, notReadyNotice(to, it)) }) return
        val (method, summary, context) = when (plan) {
            is HandoverPlan.Record -> Triple(plan.method, null, plan.context)
            is HandoverPlan.Summarize -> {
                val from = checkNotNull(args.from)
                // A handover cancelled meanwhile starts no summary, so no runtime work is begun after the cancel.
                if (!synchronized(gate) { live(session, generation) }) return
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
        // A summary from a member that worked carries its work log; a recent-messages handover carries the last one there was.
        val workLog = args.from?.takeIf { method == HandoverMethod.SUMMARY }?.let { Handovers.workLog(session.record.entries, it) }
        val entry = Handovers.entry(args.from, args.to, method, args.reason, summary, id = args.requestId, workLog = workLog)
        session.record = Handovers.handover(session.record, entry, context)
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
    private inner class Reply(val session: Session, val generation: Int, val turn: InFlight, val request: TurnRequest, val retried: Boolean) : TurnEvents {
        private val member = turn.member
        private val kind = member.runtimeId
        private val working = request.work != null
        private fun open() = live(session, generation) && (session.state == ChatState.RESPONDING || session.state == ChatState.AWAITING_YOU)
        /** The record with this turn's conversation (its talk or its working one), with the persona it ran with. */
        private fun keep(record: ChatRecord, conversation: String): ChatRecord {
            val kept = NativeConversation(conversation, request.persona.hash, playerLogin = true)
            return if (working) record.copy(workConversations = record.workConversations + (kind to kept))
                else record.copy(conversations = record.conversations + (kind to kept))
        }

        /** Saved before the reply ends, so a stopped turn keeps the conversation it started. */
        override fun opened(conversation: String) = synchronized(gate) {
            if (!open()) return@synchronized
            session.record = keep(session.record, conversation)
            runCatching { session.history.write(session.record) }.onFailure { end(session, ChatState.ERROR, couldNotComplete(kind)) }
        }

        /**
         * What this runtime was owed reached it with the turn, so it is owed nothing now, whatever the
         * reply does next. A working turn's context is worked out each time and owes nothing.
         */
        override fun accepted() = synchronized(gate) {
            if (!open() || request.context == null || working) return@synchronized
            session.record = Handovers.consumed(session.record, kind)
            runCatching { session.history.write(session.record) }
            Unit
        }

        override fun partial(text: String) = synchronized(gate) { if (open()) updateReply(session, turn.replyId, text) }

        /** Live while the work runs; the reply's entry carries what was done so far. */
        override fun activity(activity: Activity) = synchronized(gate) { if (open() && working) updateActivity(session, turn.replyId, merged(turn, activity)) }

        /** The page shows what the native dialog asks, and cannot answer it. */
        override fun waiting(waiting: ChatWaiting?) = synchronized(gate) {
            if (!open() || !working) return@synchronized
            session.waiting = waiting?.let { it.copy(summary = it.summary.shortened(ChatLimits.NOTICE)) }
            session.state = if (waiting != null) ChatState.AWAITING_YOU else ChatState.RESPONDING
        }

        override fun completed(text: String, conversation: String) = synchronized(gate) {
            if (!open()) return@synchronized
            updateReply(session, turn.replyId, text)
            // Answered now, so the message is no longer unanswered and the last turn did not fail.
            session.record = keep(session.record, conversation).let { record -> record.copy(entries = record.entries.map {
                if (it is ChatEntry.User && it.id == turn.userEntryId) it.copy(status = null) else it
            }) }
            session.state = ChatState.READY; session.notice = turn.notice; session.turn = null; session.inFlight = null; session.failure = null
            session.waiting = null
            runCatching { session.history.write(session.record) }.onFailure {
                session.notice = "This reply could not be saved. Keep this chat open and try again later."
            }
            if (session.speak) (session.record.entries.firstOrNull { it.id == turn.replyId } as? ChatEntry.Assistant)?.let {
                fillerTurn.replyArrived()
                speech.speak(it.text)
            }
            publishPicker()
        }

        /** Errors keep the person's text and never send it again, except once into a new conversation when the runtime lost its own. */
        override fun failed(failure: ChatFailure) = synchronized(gate) {
            if (!open()) return@synchronized
            if (failure == ChatFailure.ConversationLost && !retried) return@synchronized if (working) startOverWorking() else startOver()
            if (failure == ChatFailure.SignedOut) session.ready -= kind
            fail(session, failureKind(failure), (failure as? ChatFailure.UsageLimit)?.resetsAt, failureState(failure), failureNotice(kind, failure), release = false)
        }

        /** The working conversation is gone: the next try starts a new one, which hears everything before the message. */
        private fun startOverWorking() {
            session.record = session.record.copy(workConversations = session.record.workConversations - kind)
            session.turn = null
            session.state = ChatState.RESPONDING; session.waiting = null
            updateReply(session, turn.replyId, "")
            updateActivity(session, turn.replyId, merged(turn, Activity(emptyList(), truncated = false)))
            runCatching { session.history.write(session.record) }
            worker.execute { attempt(session, generation, kind) { startTurn(session, generation, turn, retried = true) } }
        }

        /** The runtime starts a new conversation and hears the recent messages, noted just before the person's message. */
        private fun startOver() {
            session.record = Handovers.startOver(session.record, member, turn.userEntryId, session.beforeTurn)
            session.turn = null
            updateReply(session, turn.replyId, "")
            runCatching { session.history.write(session.record) }
            worker.execute { attempt(session, generation, kind) { startTurn(session, generation, turn, retried = true) } }
        }
    }

    private fun updateActivity(session: Session, replyId: String, activity: Activity) {
        session.record = session.record.copy(entries = session.record.entries.map {
            if (it is ChatEntry.Assistant && it.id == replyId) it.copy(activity = activity) else it
        })
    }

    private fun updateReply(session: Session, replyId: String, text: String) {
        session.record = session.record.copy(entries = session.record.entries.map {
            if (it is ChatEntry.Assistant && it.id == replyId) it.copy(text = text.take(ChatLimits.REPLY)) else it
        })
    }

    /**
     * The turn in progress failed after its message was saved: the message stays, unanswered, an
     * empty reply goes, and [kind] (when the turn failed rather than was ended) is why, until a turn succeeds.
     */
    private fun fail(session: Session, kind: ChatFailureKind?, resetsAt: Instant?, state: ChatState, notice: String, release: Boolean = true) {
        session.inFlight?.let { turn ->
            session.record = session.record.copy(entries = session.record.entries.mapNotNull {
                when {
                    it is ChatEntry.User && it.id == turn.userEntryId -> it.copy(status = UserStatus.UNANSWERED)
                    // A reply that said nothing goes, unless it already did some work: finished steps stay.
                    it is ChatEntry.Assistant && it.id == turn.replyId && it.text.isEmpty() && it.activity?.steps.isNullOrEmpty() -> null
                    else -> it
                }
            })
            if (kind != null) session.failure = ChatFailureReport(kind, turn.member, resetsAt?.truncatedTo(ChronoUnit.MILLIS)?.toString())
        }
        end(session, state, notice, release)
    }

    /**
     * Any failure, including a native linkage error, ends the request instead of killing the
     * worker; a VM error is then rethrown.
     */
    private fun attempt(session: Session, generation: Int, runtime: RuntimeId,
        onFailure: () -> Unit = { fail(session, ChatFailureKind.UNEXPECTED, null, ChatState.ERROR, couldNotComplete(runtime)) }, work: () -> Unit) {
        try { work() } catch (error: Throwable) {
            synchronized(gate) { if (live(session, generation) && session.state != ChatState.DISCONNECTED) onFailure() }
            if (error is VirtualMachineError) throw error
        }
    }

    /** [release] ends the work of the runtimes this view used; a failure a runtime reported, or a graceful stop, has already ended its own. */
    private fun end(session: Session, state: ChatState, notice: String, release: Boolean = true) {
        session.generation++; session.state = state; session.notice = notice
        session.turn = null; session.speak = false; session.handover = null; session.inFlight = null
        // A start-of-work dialog still up is denied; an approval is denied by the runtime's stop or release.
        session.waiting = null; session.startCancel?.cancel(); session.startCancel = null
        fillerTurn.cancel()
        if (release) session.used.forEach { runtimes[it]?.release(); workers[it]?.release() }
        speech.stop()
        runCatching { session.history.write(session.record) }
        publishPicker()
    }

    private fun publishPicker() {
        if (talkOn) notePlaybackLocked()
        val model = voices ?: return
        model.update(
            voicePickerState(
                chatOpen = !closed && active != null,
                kokoro = speech.kokoroActive(),
                voices = speech.voiceChoices(),
                selectedId = speech.currentVoiceId(),
                phase = talkPhase,
                fillersOn = liveFillers.enabled,
                partial = hearingPartial,
                hearingNotice = hearingNotice,
            ),
        )
    }

    /**
     * Click on the chat icon. The first click listens until a second of quiet, then keeps going.
     * The next click, Escape, or stop ends the session. The icon stays clickable while Ember speaks.
     */
    internal fun converseClick() {
        val ear = synchronized(gate) {
            if (closed || active == null) return
            if (talkOn) {
                endConversationLocked()
                return
            }
            talkOn = true
            hearingPartial = ""
            hearingNotice = null
            setPhase(TalkPhase.LISTENING)
            publishPicker()
            hearing
        }
        if (ear == null) {
            synchronized(gate) {
                talkOn = false
                setPhase(TalkPhase.OFF)
                hearingNotice = "Talk needs the hearing models. Run scripts/fetch-voice.sh, then rebuild."
                publishPicker()
            }
            return
        }
        val started = ear.startSession(
            silenceMs,
            onPartial = { text ->
                synchronized(gate) {
                    if (talkOn && talkPhase == TalkPhase.LISTENING && text != hearingPartial) {
                        hearingPartial = text
                        publishPicker()
                    }
                }
            },
            onUtterance = { result -> synchronized(gate) { onUtteranceLocked(result) } },
            onFailed = { message ->
                synchronized(gate) {
                    hearingNotice = message
                    if (talkOn) endConversationLocked() else publishPicker()
                }
            },
        )
        if (!started) synchronized(gate) {
            talkOn = false
            setPhase(TalkPhase.OFF)
            log(LogLevel.Info, "hearing", "button busy")
            publishPicker()
        }
    }

    /** Escape. Stop uses the same path so a session cannot outlive the reply. */
    internal fun endConversation() = synchronized(gate) {
        if (talkOn) endConversationLocked()
    }

    /** The Voice row toggle. The env var wins at the next launch; this choice is what the file remembers. */
    internal fun toggleFillers() = synchronized(gate) {
        val enabled = !liveFillers.enabled
        liveFillers = liveFillers.copy(enabled = enabled)
        fillerTurn.cancel()
        fillerTurn = FillerTurn(liveFillers, fillerRotation)
        fillerFile?.let { writeSavedFillers(it, enabled) }
        speech.setFillersEnabled(enabled)
        log(LogLevel.Info, "hearing", "fillers enabled=$enabled")
        publishPicker()
    }

    /** A test session with no microphone. Real clicks go through [converseClick]. */
    internal fun testingSession() = synchronized(gate) {
        check(active != null && !closed)
        talkOn = true
        hearingPartial = ""
        hearingNotice = null
        setPhase(TalkPhase.LISTENING)
        publishPicker()
    }

    /** A finished hands-free turn, as the microphone thread would deliver it. */
    internal fun testingUtterance(result: HearingResult) = synchronized(gate) { onUtteranceLocked(result) }

    private fun endConversationLocked() {
        val session = active
        talkOn = false
        resumePending = false
        guardGeneration++
        hearingPartial = ""
        setPhase(TalkPhase.OFF)
        hearing?.endSession()
        if (session != null && (session.state == ChatState.RESPONDING || session.state == ChatState.AWAITING_YOU)) {
            val turn = session.turn
            end(session, ChatState.CANCELLED, "Reply stopped.", release = turn == null)
            turn?.stop()
        } else {
            speech.stop()
            publishPicker()
        }
    }

    private fun onUtteranceLocked(result: HearingResult) {
        if (!talkOn || talkPhase != TalkPhase.LISTENING) return
        log(LogLevel.Info, "hearing", "asr_final decodeMs=${result.decodeMs.toInt()} speechMs=${result.speechMs} chars=${result.text.length}")
        val text = result.text.trim()
        if (text.isEmpty() || result.speechMs < HEARING_MIN_SPEECH_MS) {
            log(LogLevel.Info, "hearing", "drop chars=${text.length} speechMs=${result.speechMs}")
            hearingPartial = ""
            publishPicker()
            return
        }
        hearingPartial = ""
        commitHearingLocked(result)
        if (talkOn && active?.state == ChatState.RESPONDING) holdMicLocked(TalkPhase.THINKING)
        publishPicker()
    }

    /** Half-duplex: the mic stays closed while Ember is speaking or a reply is still in flight. */
    private fun notePlaybackLocked() {
        if (!talkOn) return
        val voice = speech.status().state
        val chat = active?.state
        val emberBusy = voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING
        val chatBusy = chat == ChatState.RESPONDING || chat == ChatState.HANDING_OVER || chat == ChatState.AWAITING_YOU
        when {
            emberBusy -> if (talkPhase != TalkPhase.SPEAKING) holdMicLocked(TalkPhase.SPEAKING)
            chatBusy -> if (talkPhase != TalkPhase.THINKING && talkPhase != TalkPhase.SPEAKING) holdMicLocked(TalkPhase.THINKING)
                else if (talkPhase == TalkPhase.SPEAKING) {
                    resumePending = false
                    guardGeneration++
                    setPhase(TalkPhase.THINKING)
                    hearing?.setPaused(true)
                }
            talkPhase != TalkPhase.LISTENING && talkPhase != TalkPhase.OFF -> scheduleResumeLocked()
        }
    }

    private fun holdMicLocked(phase: TalkPhase) {
        resumePending = false
        guardGeneration++
        setPhase(phase)
        hearing?.setPaused(true)
    }

    private fun scheduleResumeLocked() {
        if (resumePending || !talkOn) return
        resumePending = true
        val ticket = ++guardGeneration
        val wait = guardMs.coerceAtLeast(0).toLong()
        if (wait == 0L) {
            resumeListeningLocked(ticket, publish = false)
            return
        }
        timer.schedule({ synchronized(gate) { resumeListeningLocked(ticket, publish = true) } }, wait, TimeUnit.MILLISECONDS)
    }

    private fun resumeListeningLocked(ticket: Int, publish: Boolean) {
        if (ticket != guardGeneration) return
        resumePending = false
        if (!talkOn || closed) return
        val voice = speech.status().state
        val chat = active?.state
        if (voice == VoiceState.PREPARING || voice == VoiceState.SPEAKING) return
        if (chat == ChatState.RESPONDING || chat == ChatState.HANDING_OVER || chat == ChatState.AWAITING_YOU) return
        setPhase(TalkPhase.LISTENING)
        hearing?.setPaused(false)
        if (publish) publishPicker()
    }

    private fun setPhase(next: TalkPhase) {
        if (talkPhase == next) return
        val leavingSpeech = talkPhase == TalkPhase.SPEAKING && next != TalkPhase.SPEAKING
        val enteringSpeech = next == TalkPhase.SPEAKING
        talkPhase = next
        log(LogLevel.Info, "hearing", "session=${next.name.lowercase()} chat=${active?.state ?: "none"} voice=${speech.status().state}")
        val line = emberLine()
        if (enteringSpeech) hearing?.noteEmberSpeaking(line)
        if (leavingSpeech) hearing?.noteEmberSilent(line)
    }

    private fun emberLine(): String {
        val assistant = active?.record?.entries?.lastOrNull { it is ChatEntry.Assistant } as? ChatEntry.Assistant
        return assistant?.text.orEmpty()
    }

    /** A test, or a finished hold, handing the recognizer result to the chat. */
    internal fun commitHeard(result: HearingResult) = synchronized(gate) {
        commitHearingLocked(result)
        publishPicker()
    }

    private fun commitHearingLocked(result: HearingResult) {
        val session = active ?: return
        val member = Handovers.currentMember(session.record.entries)
        when (val decision = decideHearing(result, member, session.state)) {
            HearingCommit.Ignore -> Unit
            is HearingCommit.Notice -> {
                session.notice = decision.text
                hearingNotice = decision.text
            }
            is HearingCommit.Send -> {
                if (member == null) return
                hearingNotice = null
                submitLocked(ChatSend(
                    session.scope.profile, session.scope.companionId, session.scope.name, session.scope.sessionId,
                    member, UUID.randomUUID().toString(), decision.text, speak = true, mode = MemberMode.TALK,
                ))
                fillerTurn.begin()
                if (session.state == ChatState.RESPONDING && session.speak && fillerTurn.arm(System.nanoTime())) {
                    log(LogLevel.Info, "hearing", "send chars=${decision.text.length}")
                    timer.schedule({ fillerDue() }, liveFillers.thresholdMs.toLong(), TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun fillerDue() = synchronized(gate) {
        if (closed || active == null) return@synchronized
        val cue = fillerTurn.poll(System.nanoTime()) ?: return@synchronized
        val voiceId = speech.currentVoiceId().ifEmpty { "unknown" }
        val pcm = speech.fillerPcm(voiceId, cue.line)
        if (pcm == null) {
            fillerTurn.clipFinished()
            log(LogLevel.Info, "hearing", "filler skipped cache-cold voice=$voiceId")
            return@synchronized
        }
        log(LogLevel.Info, "hearing", "filler line=\"${cue.line}\" voice=$voiceId waitedMs=${cue.waitedMs}")
        speech.playClip(pcm.pcm, pcm.sampleRate) { synchronized(gate) { fillerTurn.clipFinished() } }
    }

    private fun invalidateLocked(keepEnded: Boolean) {
        val session = active; active = null
        session?.let {
            // The conversation's start-of-work confirmations end with its view (a closed chat, another companion).
            work?.startOfWork?.forget(it.scope.sessionId)
            talkOn = false
            resumePending = false
            guardGeneration++
            setPhase(TalkPhase.OFF)
            hearing?.endSession()
            // A reply cut off by the closed view leaves its message unanswered, for a retry after reconnecting.
            fail(it, null, null, ChatState.ERROR, "This chat was closed. Reconnect to continue.")
            if (keepEnded) ended = it.scope to view(it)
        }
        speech.stop()
        publishPicker()
    }

    /** App exit: stop work, then close the runtimes, which wait briefly for their processes to end. */
    override fun close() {
        synchronized(gate) {
            if (closed) return
            invalidateLocked(keepEnded = false); closed = true; ended = null; publishPicker(); timer.shutdownNow(); worker.shutdownNow()
        }
        hearing?.close()
        try { closeInReverse(runtimes.values.toList() + workers.values.toList()) } finally { speech.close() }
    }
}

/** Every send, retry and handover while a native dialog is up is refused with this. */
internal const val ANSWER_FIRST = "Answer the approval first."

/** How long a talk-only reply may take. */
private const val TALK_TIMEOUT_MINUTES = 3L

/** How long a working reply may take in all, waits for the person included. */
private const val WORK_TIMEOUT_MINUTES = 30L

/** The kind the page reads for a failed turn. A conversation lost twice is unexpected. */
private fun failureKind(failure: ChatFailure) = when (failure) {
    ChatFailure.SignedOut -> ChatFailureKind.SIGNED_OUT
    is ChatFailure.UsageLimit -> ChatFailureKind.USAGE_LIMIT
    ChatFailure.ModelUnavailable -> ChatFailureKind.MODEL_UNAVAILABLE
    ChatFailure.Temporary -> ChatFailureKind.TEMPORARY
    ChatFailure.Lockdown -> ChatFailureKind.LOCKDOWN
    ChatFailure.ConversationLost, ChatFailure.Unexpected -> ChatFailureKind.UNEXPECTED
}

/** A lost sign-in leaves the chat "disconnected"; every other failure is an error the person can retry. */
private fun failureState(failure: ChatFailure) = if (failure == ChatFailure.SignedOut) ChatState.DISCONNECTED else ChatState.ERROR

private fun notReadyState(readiness: Readiness) = if (readiness == Readiness.SignedOut) ChatState.DISCONNECTED else ChatState.ERROR

private fun notReadyNotice(runtime: RuntimeId, readiness: Readiness) = when (readiness) {
    is Readiness.Unavailable -> readiness.reason
    else -> signedOutNotice(runtime)
}

private fun signedOutNotice(runtime: RuntimeId) = runtime.copy.signedOut

private fun couldNotComplete(runtime: RuntimeId) = "${runtime.copy.program} could not complete the request. Reconnect or retry when ready."

/** The person-facing reason a turn failed. Raw runtime output never reaches it. */
internal fun failureNotice(runtime: RuntimeId, failure: ChatFailure, zone: ZoneId = ZoneId.systemDefault()): String = when (failure) {
    ChatFailure.SignedOut -> signedOutNotice(runtime)
    is ChatFailure.UsageLimit -> "You've reached your ${runtime.copy.product} usage limit. " +
        (failure.resetsAt?.let { "It resets at ${resetTime(it, zone)}." } ?: "Try again later.")
    ChatFailure.ModelUnavailable -> "${runtime.copy.product} can't use this model. Choose another model and try again."
    ChatFailure.Temporary -> "The reply stopped. Try again when ${runtime.copy.product} is ready."
    ChatFailure.ConversationLost -> "${runtime.copy.product} could not continue this conversation. Send your message again to start fresh."
    ChatFailure.Lockdown -> "${runtime.copy.program} started with tools or plugins that companion chat does not allow, so the reply was stopped."
    ChatFailure.Unexpected -> "${runtime.copy.product} could not finish this reply. Check Providers and try again."
}

/** "3:00 PM" today, otherwise the date too, in the Mac's time zone. */
private fun resetTime(at: Instant, zone: ZoneId): String {
    val local = at.atZone(zone)
    return if (local.toLocalDate() == LocalDate.now(zone)) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local)
    else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(local)
}
