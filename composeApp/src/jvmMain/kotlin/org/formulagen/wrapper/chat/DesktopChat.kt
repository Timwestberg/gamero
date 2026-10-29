package org.formulagen.wrapper.chat

import java.nio.file.Path
import org.formulagen.wrapper.GAMERO_PRODUCT_NAME
import org.formulagen.wrapper.voice.DuplexChoice
import org.formulagen.wrapper.voice.HEARING_BARGE_SUSTAIN_MS
import org.formulagen.wrapper.voice.HEARING_ENDPOINT_SILENCE_MS
import org.formulagen.wrapper.voice.HEARING_RESUME_GUARD_MS
import org.formulagen.wrapper.voice.HearingDuplex
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
import org.formulagen.wrapper.voice.FillerSettings
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
 *
 * Voice runs in a [TalkLoop], which this chat hosts for its lease: heard turns are sent as talk turns
 * to the member the page named, and replies are spoken while the send asked for it.
 */
internal class DesktopChat(
    private val providerRoot: Path,
    runtimes: List<ChatRuntime>,
    private val speech: ChatSpeech,
    private val offered: Set<RuntimeId> = RuntimeId.entries.toSet(),
    private val work: ChatWork? = null,
    private val voices: VoicePickerModel? = null,
    fillers: FillerSettings = FillerSettings(),
    fillerFile: Path? = null,
    silenceMs: Int = HEARING_ENDPOINT_SILENCE_MS,
    guardMs: Int = HEARING_RESUME_GUARD_MS,
    hearing: Hearing? = null,
    route: HearingRoute? = null,
    duplexChoice: DuplexChoice = DuplexChoice(HearingDuplex.HALF, "default"),
    bargeMs: Int = HEARING_BARGE_SUSTAIN_MS,
    private val product: String = GAMERO_PRODUCT_NAME,
    private val isConverted: (String, String) -> Boolean = { _, _ -> false },
    private val beforeOpen: () -> Unit = {},
    private val ownershipGate: Any = Any(),
) : ChatCapability, AutoCloseable {
    private val runtimes = runtimes.associateBy { it.id }
    private val workers: Map<RuntimeId, WorkRuntime> = work?.runtimes.orEmpty()
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-chat"))
    private val timer = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-chat-timeout"))
    /** The member the page named on press. The native row leaves this null and uses the active member. */
    private var hearingMember: TeamMember? = null
    /** The page's effort for a heard turn. Null, as when the native row armed hearing, is the runtime default. */
    private var hearingEffort: ModelEffort? = null
    private var active: Session? = null
    /** The last view the host ended on its own, so that view's polls keep showing why. */
    private var ended: Pair<ChatScope, ChatSnapshot>? = null
    private var closed = false

    /** This lease as the voice loop's conversation. */
    private val talkHost = object : TalkHost {
        override val open: Boolean get() = !closed && active != null

        override val reply: TalkReply get() = when (active?.state) {
            ChatState.RESPONDING -> TalkReply.RESPONDING
            ChatState.HANDING_OVER -> TalkReply.HANDING_OVER
            ChatState.AWAITING_YOU -> TalkReply.AWAITING_YOU
            else -> TalkReply.IDLE
        }

        /** A heard turn is a talk turn to the member the page named, or the active member, spoken. */
        override fun sendHeard(result: HearingResult): HearingCommit {
            val session = active ?: return HearingCommit.Ignore
            val member = hearingMember ?: Handovers.currentMember(session.record.entries)
            val decision = decideHearing(result, member, session.state)
            when (decision) {
                HearingCommit.Ignore -> Unit
                is HearingCommit.Notice -> session.notice = decision.text
                is HearingCommit.Send -> submitLocked(ChatSend(
                    session.scope.profile, session.scope.companionId, session.scope.name, session.scope.sessionId,
                    checkNotNull(member), UUID.randomUUID().toString(), decision.text, speak = true, mode = MemberMode.TALK, effort = hearingEffort,
                ))
            }
            return decision
        }

        /** The turn ends with no notice and its partial reply kept; heard turns are spoken from here on. */
        override fun interrupt() {
            val session = active ?: return
            val turn = session.turn
            session.generation++
            session.state = ChatState.READY
            session.notice = null
            session.speak = true
            session.turn = null
            session.inFlight = null
            session.waiting = null
            session.startCancel?.cancel()
            session.startCancel = null
            turn?.stop()
        }

        override fun endReply(): Boolean {
            val session = active ?: return false
            if (session.state != ChatState.RESPONDING && session.state != ChatState.AWAITING_YOU) return false
            val turn = session.turn
            end(session, ChatState.CANCELLED, "Reply stopped.", release = turn == null)
            turn?.stop()
            return true
        }

        override fun lastReply(): String {
            val assistant = active?.record?.entries?.lastOrNull { it is ChatEntry.Assistant } as? ChatEntry.Assistant
            return assistant?.text.orEmpty()
        }

        override fun changed() = publishPicker()
    }

    private val talk = TalkLoop(gate, talkHost, speech, hearing, route, duplexChoice, timer, fillers, fillerFile, silenceMs, guardMs,
        bargeMs, ChatLimits.MESSAGE)

    /** Stage times of the last heard turn. */
    internal val stageLatency: StageLatency? get() = talk.stageLatency

    init {
        speech.onVoice { synchronized(gate) { if (!closed) publishPicker() } }
    }

    private class Session(val scope: ChatScope, val persona: Persona, val history: ChatHistory, var record: ChatRecord, val understood: Set<RuntimeId>) {
        /** This lease's page asked for [ChatSnapshot.hearing]. Until then its results stay host-bridge v2. */
        var pageHears = false
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

    private sealed interface HearingArm {
        class Ready(val snapshot: ChatSnapshot) : HearingArm
        data object Start : HearingArm
    }

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
    override suspend fun open(scope: ChatScope): ChatSnapshot = open(scope, SharedRules.LEGACY_RUNTIMES)

    override suspend fun openForClient(args: ChatOpenForClient): ChatSnapshot = open(args.scope(), SharedRules.understood(args.supportedRuntimes))

    private fun open(scope: ChatScope, understood: Set<RuntimeId>): ChatSnapshot = synchronized(ownershipGate) {
        require(!isConverted(scope.profile, scope.companionId)) { "Use the coordinator conversation for this companion" }
        beforeOpen()
        synchronized(gate) state@{
            require(!isConverted(scope.profile, scope.companionId)) { "Use the coordinator conversation for this companion" }
            check(!closed)
            val old = active
            if (old != null && old.scope.sameChat(scope) && old.understood != understood) throw BridgeUpdateRequiredException()
            if (old != null && old.scope.sameChat(scope) && old.state !in setOf(ChatState.ERROR, ChatState.DISCONNECTED)) {
                notePage(old, scope)
                publishPicker()
                return@state view(old)
            }
            invalidateLocked(keepEnded = false)
            ended = null
            val history = companionChatHistory(providerRoot, scope.profile, scope.companionId)
            val record = history.read()
            requireDecodable(understood, record.runtimeIds())
            val snapshot = view(Session(scope, Persona.companion(scope.name), history, record, understood).also { notePage(it, scope); active = it })
            publishPicker()
            talk.warm()
            snapshot
        }
    }

    /** A conversion cannot cancel a legacy turn or race a new send. */
    fun <T> whileIdle(profile: String, companionId: String, action: () -> T): T = synchronized(gate) {
        val session = active?.takeIf { it.scope.profile == profile && it.scope.companionId == companionId }
        if (session?.state in setOf(ChatState.RESPONDING, ChatState.HANDING_OVER, ChatState.AWAITING_YOU)) throw BridgeBusyException()
        action()
    }

    /** Polling cannot reopen an expired lease, but a view the host ended keeps reading its final state. */
    override suspend fun snapshot(scope: ChatScope): ChatSnapshot = synchronized(gate) {
        publishPicker()
        ended?.takeIf { !closed && it.first.sameChat(scope) }?.second?.copy(voice = speech.status())
            ?: current(scope).let { notePage(it, scope); view(it) }
    }

    /**
     * Persist the explicit user message, as an entry with the request's id, before starting any
     * runtime work. A chat that is disconnected or in error takes a new send, which checks the
     * member's runtime again. A message whose turn fails stays, unanswered, for `chat.retry`. A
     * member on a runtime this build does not offer is refused with a notice and no entry.
     */
    override suspend fun send(args: ChatSend): ChatSnapshot = synchronized(gate) { submitLocked(args) }

    private fun submitLocked(args: ChatSend): ChatSnapshot {
        require(!isConverted(args.profile, args.companionId)) { "Use Configure brain and reopen coordinator chat" }
        val session = current(args.scope())
        requireDecodable(session.understood, listOf(args.member.runtimeId))
        if (session.record.entries.any { it.id == args.requestId }) return view(session)
        if (session.state == ChatState.AWAITING_YOU) return refuse(session, ANSWER_FIRST)
        if (session.state == ChatState.RESPONDING || session.state == ChatState.HANDING_OVER) throw BridgeBusyException()
        val kind = args.member.runtimeId
        if (kind !in offered) return view(session).copy(notice = kind.copy.notOffered)
        talk.cancelFiller()
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
        require(!isConverted(args.profile, args.companionId)) { "Use Configure brain and reopen coordinator chat" }
        val session = current(args.scope())
        requireDecodable(session.understood, listOf(args.member.runtimeId))
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
        require(!isConverted(args.profile, args.companionId)) { "Use Configure brain and reopen coordinator chat" }
        val session = current(args.scope())
        requireDecodable(session.understood, listOfNotNull(args.to.runtimeId, args.from?.runtimeId))
        if (session.record.entries.any { it.id == args.requestId } || session.handover?.requestId == args.requestId) return@synchronized view(session)
        // A refusal is a snapshot with a notice and no new entry; the page keeps the current member.
        if (session.state == ChatState.AWAITING_YOU) return@synchronized refuse(session, ANSWER_FIRST)
        if (session.state == ChatState.RESPONDING) return@synchronized refuse(session, "Wait for the reply to finish, or stop it, before switching.")
        if (session.state == ChatState.HANDING_OVER) return@synchronized refuse(session, "Another switch is still in progress.")
        val to = args.to.runtimeId
        if (to !in runtimes) { session.notice = missing(to); return@synchronized view(session) }
        // Heard turns follow the switch: the current member answers them, at its default effort, until the next press.
        hearingMember = null
        hearingEffort = null
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
        notePage(session, scope)
        if (talk.on) {
            talk.end()
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
        val session = current(scope); notePage(session, scope)
        session.speak = false; speech.stop(); publishPicker(); view(session)
    }

    /** An old view cannot close the newly opened companion. */
    override suspend fun closeSession(scope: ChatScope) = synchronized(gate) {
        if (active?.scope?.sameChat(scope) == true) invalidateLocked(keepEnded = false)
        if (ended?.first?.sameChat(scope) == true) ended = null
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

    private fun current(scope: ChatScope): Session = checkNotNull(active?.takeIf { !closed && it.scope.sameChat(scope) }) { "Chat view expired" }

    /**
     * A v2 page leaves [ChatScope.hearing] unset. A later page sets it, or calls [hearing]. Called only
     * once [scope] holds [session]'s lease, so a stale view cannot opt in another page's lease.
     */
    private fun notePage(session: Session, scope: ChatScope) {
        if (scope.hearing == true) session.pageHears = true
    }
    private fun live(session: Session, generation: Int = session.generation) = !closed && active === session && generation == session.generation
    private fun view(session: Session): ChatSnapshot {
        requireDecodable(session.understood, session.record.runtimeIds())
        return ChatSnapshot(session.state, session.record.entries,
            session.refusal?.takeIf { it.first == session.state }?.second ?: session.notice, speech.status(), session.failure,
            session.waiting?.takeIf { session.state == ChatState.AWAITING_YOU },
            if (session.pageHears) talk.hearing().let { ChatHearing(it.state, it.partial, it.notice) } else null,
            talk.dictation()?.let { ChatDictation(it.state, partial = it.partial, notice = it.notice) })
    }

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
        if (stale) {
            handle.stop()
            worker?.release() ?: runtime.release()
        }
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
        private val spoken = talk.spokenReply()
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

        override fun partial(text: String) = synchronized(gate) {
            if (!open()) return@synchronized
            updateReply(session, turn.replyId, text)
            if (session.speak && !working) {
                spoken.grow(text)
                publishPicker()
            }
        }

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
            if (session.speak) {
                talk.replyArrived()
                spoken.finish(text)
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
        talk.cancelFiller()
        if (release) session.used.forEach { runtimes[it]?.release(); workers[it]?.release() }
        speech.stop()
        runCatching { session.history.write(session.record) }
        publishPicker()
    }

    private fun publishPicker() {
        talk.observe()
        voices?.update(talk.picker(open = !closed && active != null, product = product))
    }

    /**
     * Click on the chat icon. The first click listens until a second of quiet, then keeps going.
     * The next click, Escape, or stop ends the session. The icon stays clickable while Ember speaks.
     */
    internal fun converseClick() = talk.click()

    /**
     * The catalog page. Press arms the microphone this host owns and hides the native icon.
     * A second press does not open a second capture. Release and off end the session.
     */
    override suspend fun hearing(args: ChatHearingRequest): ChatSnapshot {
        val armed = synchronized(gate) {
            val arming = args.action == HearingAction.PRESS || args.action == HearingAction.HANDS_FREE
            // Arming checks what a chat.send of the heard text would, so a turn is not lost after capture.
            if (arming) require(!isConverted(args.profile, args.companionId)) { "Use Configure brain and reopen coordinator chat" }
            val session = current(args.scope())
            // The handshake is not a change, so even a refused call opts the lease in.
            session.pageHears = true
            if (arming) requireDecodable(session.understood, listOf(args.member.runtimeId))
            if (session.state == ChatState.AWAITING_YOU && args.action != HearingAction.OFF && args.action != HearingAction.RELEASE) {
                return@synchronized HearingArm.Ready(refuse(session, ANSWER_FIRST))
            }
            if (arming && args.member.runtimeId !in offered) {
                return@synchronized HearingArm.Ready(view(session).copy(notice = args.member.runtimeId.copy.notOffered))
            }
            talk.pageOwns()
            when (args.action) {
                HearingAction.PRESS, HearingAction.HANDS_FREE -> if (talk.isDictating) {
                    HearingArm.Ready(refuse(session, TALK_WHILE_DICTATING))
                } else {
                    hearingMember = args.member
                    hearingEffort = args.effort
                    log(LogLevel.Info, "hearing", "page action=${args.action.name.lowercase()} effort=${hearingEffort?.value ?: "default"}")
                    if (talk.press()) HearingArm.Start else HearingArm.Ready(view(session))
                }
                HearingAction.RELEASE, HearingAction.OFF -> {
                    talk.end()
                    HearingArm.Ready(view(session))
                }
                HearingAction.MUTE -> {
                    talk.mute()
                    HearingArm.Ready(view(session))
                }
                HearingAction.UNMUTE -> {
                    talk.unmute()
                    HearingArm.Ready(view(session))
                }
            }
        }
        return when (armed) {
            is HearingArm.Ready -> armed.snapshot
            HearingArm.Start -> {
                talk.startCapture()
                synchronized(gate) { view(current(args.scope())) }
            }
        }
    }

    /** Dictation into the page's draft ([TalkLoop.dictate]): nothing is sent or spoken. It works while a dialog waits. */
    override suspend fun dictation(args: ChatDictationRequest): ChatSnapshot {
        val step = synchronized(gate) {
            val session = current(args.scope())
            talk.pageOwns()
            when (val step = talk.dictate(args.action)) {
                DictationStep.Ready -> return view(session)
                else -> step
            }
        }
        return when (step) {
            is DictationStep.Finish -> {
                val heard = talk.awaitDictation()
                synchronized(gate) {
                    val text = talk.dictationEnded(heard, step.keep)
                    val snapshot = view(current(args.scope()))
                    if (step.keep) snapshot.copy(dictation = ChatDictation(DictationState.DONE, text = text)) else snapshot
                }
            }
            else -> {
                talk.startDictation()
                synchronized(gate) { view(current(args.scope())) }
            }
        }
    }

    /**
     * Escape. While Ember is answering, this interrupts her and keeps listening.
     * While the session is only listening, this ends it. Stop always ends it.
     * False when no voice session is up, so the catalog page can keep the key.
     */
    internal fun endConversation(): Boolean = talk.escape()

    /** The Voice row toggle. The env var wins at the next launch; this choice is what the file remembers. */
    internal fun toggleFillers() = talk.toggleFillers()

    /** A test session with no microphone. Real clicks go through [converseClick]. */
    internal fun testingSession() = talk.testingSession()

    /** A finished hands-free turn, as the microphone thread would deliver it. */
    internal fun testingUtterance(result: HearingResult) = talk.testingUtterance(result)

    /** A test, or a finished hold, handing the recognizer result to the chat. */
    internal fun commitHeard(result: HearingResult) = talk.commit(result)

    private fun invalidateLocked(keepEnded: Boolean) {
        val session = active; active = null
        session?.let {
            // The conversation's start-of-work confirmations end with its view (a closed chat, another companion).
            work?.startOfWork?.forget(it.scope.sessionId)
            hearingMember = null
            hearingEffort = null
            talk.reset()
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
        talk.close()
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
    ChatFailure.ConversationLost, ChatFailure.Unexpected, ChatFailure.ProjectTrustRequired, ChatFailure.ProjectTrustTooBroad -> ChatFailureKind.UNEXPECTED
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
    ChatFailure.ProjectTrustRequired -> "Work did not start. ${runtime.copy.product} needs your approval to trust this project and load its instructions."
    ChatFailure.ProjectTrustTooBroad -> "Work did not start. ${runtime.copy.program} treats your home folder, or a folder above it, " +
        "as this project, and Hovel will not ask you to trust all of that. Choose a project folder that is its own repository."
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
