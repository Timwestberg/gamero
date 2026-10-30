package org.formulagen.wrapper.conversations

import java.util.UUID
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.approvals.startOfWorkDialog
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.brain.*
import org.formulagen.wrapper.runtimes.*
import org.formulagen.wrapper.conversation.voice.*
import org.formulagen.wrapper.util.*
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.storage.Artifacts
import org.formulagen.wrapper.storage.sha256
import org.formulagen.wrapper.conversation.Activity
import org.formulagen.wrapper.conversation.MemberMode

/**
 * Runs one claimed message of a conversation through the companion's brain: the coordinator, its specialists
 * in threads, and the workflow, with the pure transitions of `BrainTransitions.kt`. The session is the
 * conversation: nothing pauses because a page closed, and a run continues from its record whichever message
 * claims it next.
 *
 * - **No specialists:** the coordinator is a plain talk turn that streams, with no decision step.
 * - **A main message:** the coordinator decides; an answer's body streams once its header line validates.
 *   Each delegated task is a thread, announced in the conversation, whose specialist reply streams into it.
 * - **A thread reply** (`THREAD_REPLY`): the coordinator forwards it to the thread's specialist, answers in
 *   the thread, or delegates a new task, and says which with a coordinator note.
 *
 * Every visible step is a checkpoint, kept in the record's outbox until the server stores it; [run] answers
 * the state that settles the message. A coordinator reply in the main conversation also reaches the claim's
 * listener as it streams and once written ([ClaimOutput.wrote]), so this Mac can speak it. Work dialogs
 * (start of work, stages, the resume review, provider permissions) race the phone through [approvals].
 * Provider turns go through [turns].
 */
internal class CoordinatorEngine(
    private val store: ConversationStore,
    private val folders: ProjectFolderStore,
    private val turns: ProviderTurns,
    private val approvals: RemoteApprovals,
    private val startOfWork: StartOfWork,
    /** Reads a message input's whole text; the claim carries only its preview. */
    private val api: ConversationApi,
) : MessageEngine {
    /** The runtimes this Mac lets read context folders: a project lists them as `readableBy`. */
    fun readableBy(): List<RuntimeId> = turns.readableBy()

    override fun run(claim: ClaimResult, output: ClaimOutput, cancel: CancelSignal, closing: () -> Boolean): MessageState =
        MessageRun(claim, output, cancel, closing).run()

    /** One claimed message's run: its record, what it sends, and its Stop. */
    private inner class MessageRun(val claim: ClaimResult, val out: ClaimOutput, val cancel: CancelSignal, val closing: () -> Boolean) {
        val conversationId = claim.conversation.id
        val projectId = claim.project.id
        val companion = claim.companion.name
        val message = claim.message
        val local = folders.folders(store.accountId, projectId).filter { it.exists }
        val call = TurnCall(store.accountId, claim.companion.id, companion, folders.projectFolders(store.accountId, projectId), cancel)

        /** The conversation's record. Provider callbacks change it too ([update]), so every change holds this run's lock. */
        @Volatile var record = store.read(conversationId)

        fun run(): MessageState {
            flush(strict = false)
            return try {
                recover()
                when {
                    claim.brain.configuration.specialists.isEmpty() -> direct()
                    message.threadId != null -> threadReply(message.threadId)
                    else -> mainMessage()
                }
            } catch (_: StoppedException) { stopped() }
            catch (_: InterruptedException) { stopped() }
            catch (refused: HovelRejectedException) {
                // `stop-requested` cancelled the message as it was refused: the run stops as the person's Stop does.
                if (cancel.isCancelled) stopped() else throw refused
            }
            catch (failure: ProviderFailure) {
                // The next message starts the coordinator's conversation fresh.
                if (failure.coordinator && failure.failure == ChatFailure.ConversationLost) {
                    update { it.copy(brain = it.brain.copy(coordinatorContexts = it.brain.coordinatorContexts - failure.context)) }
                }
                system(failurePause(failure, "the coordinator"), message.threadId)
                MessageState.FAILED
            } catch (configuration: BrainConfigurationException) {
                // The brain cannot run as configured: say what to change, since sending it again would fail the same way.
                system(checkNotNull(configuration.message), message.threadId)
                MessageState.FAILED
            } catch (error: Exception) {
                log(LogLevel.Warn, "conversations", "A message could not run", error)
                system("Hovel could not finish this message on this Mac. Try sending it again.", message.threadId)
                MessageState.FAILED
            } finally {
                // Before the message settles; what still fails waits for the conversation's next run.
                runCatching { flush(strict = false) }
            }
        }

        // ---- The three kinds of message ----------------------------------------------------------

        /** A brain with no specialists: the coordinator answers as a plain, streaming talk turn. */
        fun direct(): MessageState {
            val role = turns.resolved(call, coordinatorRole())
            val persona = Persona.role(companion, role.name, roleInstructions(role), false)
            val key = contextKey(role)
            val conversation = record.brain.coordinatorContexts[key]?.takeIf { it.personaHash == persona.hash }
            val replyId = uuid()
            val threadId = message.threadId
            val recent = if (conversation != null) emptyList() else claim.history.filter { it.id != message.id && it.threadId == null }
                .takeLast(10).map { it.author.name.lowercase() to it.text }
            val stream = ReplyStream(headed = false) { out.delta(replyId, threadId, it) }
            val text = turns.talk(call, role, persona, directInput(message.text, claim.recalledMemory, recent), conversation, key,
                coordinator = true, stream = stream, opened = rememberCoordinator(key)).first.trim().take(CompanionLimits.MESSAGE)
            check(text.isNotEmpty()) { "The coordinator answered nothing" }
            append(MessageAuthor.COORDINATOR, replyId, text, threadId, threadId?.let { CoordinatorNote(NoteKind.ANSWERED, it) })
            return MessageState.ANSWERED
        }

        /**
         * A message in the main conversation: it answers a run awaiting input, or starts a run. The person's retry of
         * the message that last drove an unfinished run reviews that run natively and resumes it.
         */
        fun mainMessage(): MessageState {
            seedHistory()
            val retry = record.brain.receipts.containsKey(message.id)
            val existing = record.brain.run
            if (retry && existing != null && existing.summary.state.resumable && record.runMessageId == message.id) {
                if (!resume(existing)) return MessageState.FAILED
                return loop()
            }
            val inputs = inputs()
            val text = message.text.trimEcma()
            val answering = existing?.takeIf { it.summary.state == BrainRunState.AWAITING_INPUT }
            if (answering == null && existing != null && !existing.summary.state.terminal) {
                // A run nobody finished ends here, and its threads say so: a new request starts the next run.
                change { it.copy(run = existing.halted(BrainRunState.CANCELLED), waiting = null) }
            }
            val next = answering?.answered(text, inputs.map { it.id }, inputs)
                ?: RunRecord(BrainRun(uuid(), 1, claim.brain.revision, null, BrainRunState.PLANNING, null), claim.brain.configuration,
                    call.folders.working?.toString(), text, inputs.map { it.id }, artifacts = inputs)
            change(adjust = { it.copy(runMessageId = message.id) }) {
                if (retry) it.copy(run = next, waiting = null, notice = null)
                else it.sent(message.id, sha256(text.toByteArray()), text, inputs.map { input -> input.id }, next)
            }
            return loop()
        }

        /** A reply in [threadId]: the coordinator forwards it, answers it there, or delegates a new task. */
        fun threadReply(threadId: String): MessageState {
            seedHistory()
            // The newer of this Mac's thread and the server's: another Mac may have moved it on.
            val thread = listOfNotNull(record.threads[threadId], claim.thread?.takeIf { it.id == threadId }?.let(::localThread))
                .maxByOrNull { it.revision } ?: error("Unknown thread")
            val coordinator = turns.resolved(call, coordinatorRole())
            val (decision, replyId) = decide(coordinator, THREAD_REPLY_DECISIONS, threadId) { allowed ->
                threadReplyRequest(uuid(), allowed, threadId, thread, message.text, claim.brain.configuration.specialists,
                    record.brain.messages.takeLast(10), claim.recalledMemory)
            } ?: run {
                system("The coordinator could not decide what to do with this reply. Send it again.", threadId)
                return MessageState.FAILED
            }
            return when (decision) {
                is CoordinatorDecision.Answer -> {
                    append(MessageAuthor.COORDINATOR, replyId, decision.text, threadId, CoordinatorNote(NoteKind.ANSWERED, threadId))
                    MessageState.ANSWERED
                }
                is CoordinatorDecision.Forward -> {
                    val note = decision.note?.trimEcma()?.takeIf { it.isNotEmpty() }
                    val said = "Forwarded to ${thread.role.name}" + (note?.let { ": $it" } ?: ".")
                    append(MessageAuthor.COORDINATOR, uuid(), said, threadId, CoordinatorNote(NoteKind.FORWARDED, threadId))
                    threadTurn(threadId, thread, forwardedTurn(message.text, note))
                }
                is CoordinatorDecision.Delegate -> {
                    val role = specialistSnapshot(decision.specialistId)
                    val newThread = uuid()
                    val title = threadTitle(role.name, decision.task)
                    val announcementId = uuid()
                    append(MessageAuthor.COORDINATOR, announcementId, announcement(role, null, decision.task), null)
                    val opened = ThreadState(title, announcementId, 0, role, null, BrainAttemptState.QUEUED)
                    putThread(newThread, opened)
                    val started = CoordinatorNote(NoteKind.STARTED, newThread)
                    append(MessageAuthor.COORDINATOR, uuid(), "Started a new task: $title", threadId, started)
                    threadTurn(newThread, opened, delegatedTurn(role, decision.task, message.text))
                }
                else -> error("Not a thread reply decision")
            }
        }

        // ---- The run loop over the brain's transitions ------------------------------------------

        /** Work the run until it waits or ends, then the state that settles this message. */
        fun loop(): MessageState {
            while (record.brain.run?.summary?.state?.working == true) {
                call.live()
                try {
                    if (record.brain.run?.phase == BrainPhase.EXECUTE) execute() else decideRun()
                } catch (failure: ProviderFailure) {
                    failed(failure)
                }
            }
            return when (record.brain.run?.summary?.state) {
                BrainRunState.COMPLETED, BrainRunState.AWAITING_INPUT -> MessageState.ANSWERED
                BrainRunState.CANCELLED -> MessageState.STOPPED
                // Paused or failed: the person retries the message, which reviews the run and resumes it.
                else -> MessageState.FAILED
            }
        }

        /** Ask the coordinator what the run does next, and apply its validated decision. */
        fun decideRun() {
            val current = currentRun()
            val coordinator = turns.resolved(call, coordinatorRole())
            val allowed = allowedDecisions(current)
            val (decision, replyId) = decide(coordinator, allowed, null) { kinds ->
                val input = handoff(current.artifacts.filter { it.id in current.inputs }.map { it.id }, files = false)
                val latest = current.attempts.lastOrNull()?.outputIds?.let { handoff(it, files = false) } ?: ""
                val request = coordinatorRequest(uuid(), current, kinds, input, latest, record.brain.messages.takeLast(10))
                conversationRequest(request, claim.recalledMemory)
            } ?: run {
                change { it.paused(INVALID_DECISION, BrainWaitingKind.INPUT) }
                return
            }
            var first = true
            change { brain ->
                // A decision that does not fit the run (a step that is not next, an unknown role) applies nothing.
                runCatching { brain.decided(decision, coordinator) { if (first) { first = false; replyId } else uuid() } }.getOrElse {
                    brain.paused("The coordinator's decision did not fit this run, so nothing was started. Reply to try again.",
                        BrainWaitingKind.INPUT)
                }
            }
        }

        /** Run the queued attempt: allowance, readiness, stage approval and start of work first, then its specialist in its thread. */
        fun execute() {
            val current = currentRun()
            var attempt = current.attempts.last()
            if (current.overAllowance) {
                change { it.paused(OVER_ALLOWANCE, BrainWaitingKind.LIMIT) }
                return
            }
            val issues = turns.issues(call, attempt.role)
            if (issues.isNotEmpty()) { change { it.paused(notReadyPause(attempt.role, issues)) }; return }
            val role = turns.resolved(call, attempt.role)
            attempt = attempt.copy(role = role)
            change { it.copy(run = checkNotNull(it.run).withLast(attempt)) }
            val step = current.configuration.workflow?.steps?.find { it.id == attempt.stepId }
            val threadId = checkNotNull(record.attemptThreads[attempt.id])
            if (asksBeforeStarting(current.configuration, role, step) && !approveStage(current, attempt, step, threadId)) return
            if (role.mode == MemberMode.WORK && !startWork(role, threadId) { change { it.declined(attempt, notApproved(role)) } }) return
            change { it.dispatched(attempt) }
            val entryId = uuid()
            val input = specialistTurn(role, current, step, handoff(attempt.inputIds, files = role.mode == MemberMode.WORK))
            val (output, activity) = specialist(threadId, role, input, entryId)
            val source = BrainSource(BrainSourceKind.ATTEMPT, attemptId = attempt.id)
            val artifact = store.artifact(conversationId, step?.name ?: role.name, source, output.toByteArray(), "text/markdown")
            emit(MessageCheckpoint.ArtifactStored(ref(artifact), output.take(BrainLimits.OUTPUT)))
            change(entries = mapOf(entryId to threadId), adjust = { record ->
                val thread = record.threads.getValue(threadId).withOutput(artifact, shareableActivity(activity, local))
                record.copy(threads = record.threads + (threadId to thread))
            }) { it.completed(attempt, artifact, output, activity, entryId) }
        }

        /** The stage dialog for [attempt]. True to go on; otherwise the run waits for changes, stays paused, or stops. */
        fun approveStage(run: RunRecord, attempt: BrainAttempt, step: WorkflowStep?, threadId: String): Boolean {
            val role = attempt.role
            val subject = stageSubject(role, step)
            val summary = stageSummary(subject, role).take(BrainLimits.DESCRIPTION)
            val waiting = BrainWaiting(BrainWaitingKind.STAGE, summary, uuid(), attempt.id)
            change { it.awaitingStage(attempt, waiting) }
            val options = listOf(ApprovalOption.CONTINUE to StageChoice.CONTINUE, ApprovalOption.REQUEST_CHANGES to StageChoice.CHANGES,
                ApprovalOption.NOT_NOW to StageChoice.PAUSE, ApprovalOption.STOP to StageChoice.STOP)
            val dialog = stageDialog(role, run, step, handoff(attempt.inputIds, false))
            when (approvals.ask(scope(threadId), ApprovalKind.STAGE, dialog, options, cancel)) {
                StageChoice.CONTINUE -> return true
                StageChoice.CHANGES -> change { it.declined(attempt, "Describe the changes before this step starts.") }
                StageChoice.STOP -> throw StoppedException()
                StageChoice.PAUSE, null -> change { it.stagePostponed(attempt, subject) }
            }
            return false
        }

        /**
         * Confirm [role]'s start of work, once per conversation, project, folder and member. [declined] records a no.
         * False when the person declined or there is no working folder.
         */
        fun startWork(role: BrainRoleSnapshot, threadId: String, declined: () -> Unit): Boolean {
            val member = role.binding.member()
            val folder = call.folders.working ?: run { declined(); return false }
            if (startOfWork.isConfirmed(conversationId, projectId, folder, member)) return true
            val approved = approvals.ask(scope(threadId), ApprovalKind.START_OF_WORK, startOfWorkDialog(companion, folder, member),
                listOf(ApprovalOption.CONTINUE to true, ApprovalOption.NOT_NOW to false), cancel) == true
            if (!approved) { declined(); return false }
            call.live()
            startOfWork.remember(conversationId, projectId, folder, member)
            return true
        }

        /** The native resume review of a retried message's run. True when the person continued it. */
        fun resume(run: RunRecord): Boolean {
            val last = run.attempts.lastOrNull()
            val rerun = last != null && last.state.dispatchedWithoutResult
            val uncertain = last != null && (last.state == BrainAttemptState.RUNNING || last.state == BrainAttemptState.INTERRUPTED) &&
                last.role.mode == MemberMode.WORK
            val target = claim.brain.takeIf {
                it.revision != run.summary.configurationRevision && adoptable(run.configuration, it.configuration)
            }
            val waiting = record.brain.waiting
            val dialog = resumeDialog(run, rerun, uncertain, target, limit = waiting?.kind == BrainWaitingKind.LIMIT)
            change { it.reviewing() }
            val approved = approvals.ask(scope(null), ApprovalKind.RESUME_REVIEW, dialog,
                listOf(ApprovalOption.CONTINUE to true, ApprovalOption.NOT_NOW to false), cancel) == true
            if (!approved) {
                change { it.reviewDeclined(waiting, run.summary.state) }
                return false
            }
            change(rerunOf = last?.takeIf { rerun }?.id) { it.reviewApproved(run, rerun, target, ::uuid) }
            return true
        }

        /** A provider turn failed: the run pauses with the provider's own reason, and a lost conversation is forgotten. */
        fun failed(failure: ProviderFailure) {
            change(adjust = { record ->
                if (failure.failure != ChatFailure.ConversationLost) record
                else if (failure.coordinator) {
                    record.copy(brain = record.brain.copy(coordinatorContexts = record.brain.coordinatorContexts - failure.context))
                }
                else record.copy(threadContexts = record.threadContexts - failure.context)
            }) { it.afterFailure(failure, failurePause(failure, it.failingRole)) }
        }

        /**
         * The person stopped: the run, if any is unfinished, is cancelled. Hovel quitting is not a Stop: the run pauses
         * for review and says so (`run.changed`), so the person's retry resumes it, and the message settles uncertain.
         */
        fun stopped(): MessageState {
            val run = record.brain.run?.takeIf { !it.summary.state.terminal }
            if (closing()) {
                if (run != null) {
                    runCatching { change(live = false) { it.copy(run = run.halted(BrainRunState.PAUSED), waiting = INTERRUPTED) } }
                }
                return MessageState.RECONCILIATION_REQUIRED
            }
            if (run != null) runCatching { change(live = false) { it.copy(run = run.halted(BrainRunState.CANCELLED), waiting = null) } }
            return MessageState.STOPPED
        }

        // ---- Threads outside a run: forwarded replies and delegated thread tasks ---------------------

        /** Run [thread]'s specialist on [input] in its own thread, outside any run. */
        fun threadTurn(threadId: String, thread: ThreadState, input: String): MessageState {
            val issues = turns.issues(call, thread.role)
            if (issues.isNotEmpty()) {
                putThread(threadId, thread.copy(state = BrainAttemptState.BLOCKED, revision = thread.revision + 1))
                system(notReadyPause(thread.role, issues), threadId)
                return MessageState.FAILED
            }
            val role = turns.resolved(call, thread.role)
            if (role.mode == MemberMode.WORK && !startWork(role, threadId) {
                    putThread(threadId, thread.copy(state = BrainAttemptState.BLOCKED, revision = thread.revision + 1))
                    system(notApproved(role), threadId)
                }) return MessageState.FAILED
            val running = thread.copy(role = role, state = BrainAttemptState.RUNNING, revision = thread.revision + 1)
            putThread(threadId, running)
            val entryId = uuid()
            val ended = { state: BrainAttemptState -> putThread(threadId, running.copy(state = state, revision = running.revision + 1)) }
            val (output, activity) = try { specialist(threadId, role, input, entryId) } catch (failure: ProviderFailure) {
                if (failure.failure == ChatFailure.ConversationLost) update { it.copy(threadContexts = it.threadContexts - threadId) }
                ended(if (uncertain(role, failure.failure)) BrainAttemptState.INTERRUPTED else BrainAttemptState.FAILED)
                system(failurePause(failure, role.name), threadId)
                return MessageState.FAILED
            } catch (stop: Exception) {
                // Stopped, or Hovel quitting: the thread never stays running.
                if (stop is StoppedException || stop is InterruptedException) runCatching { ended(BrainAttemptState.INTERRUPTED) }
                throw stop
            }
            val source = BrainSource(BrainSourceKind.ATTEMPT, attemptId = threadId)
            val artifact = store.artifact(conversationId, role.name, source, output.toByteArray(), "text/markdown")
            emit(MessageCheckpoint.ArtifactStored(ref(artifact), output.take(BrainLimits.OUTPUT)))
            putThread(threadId, running.withOutput(artifact, shareableActivity(activity, local))
                .copy(state = BrainAttemptState.COMPLETED, revision = running.revision + 1))
            append(MessageAuthor.SPECIALIST, entryId, output.ifEmpty { "(no reply)" }, threadId)
            return MessageState.ANSWERED
        }

        // ---- Provider turns ----------------------------------------------------------------------

        /**
         * The coordinator's decision among [allowed], with one repair attempt, and the reply id its answer
         * streamed as; null when both replies were invalid. Each attempt streams as a reply of its own in
         * [threadId], once its answer header validates.
         */
        fun decide(coordinator: BrainRoleSnapshot, allowed: Set<DecisionKind>, threadId: String?,
            request: (Set<DecisionKind>) -> String): Pair<CoordinatorDecision, String>? {
            val instructions = coordinator.copy(instructions = conversationCoordinatorInstructions(coordinator.instructions))
            val persona = Persona.role(companion, coordinator.name, roleInstructions(instructions), false)
            val key = contextKey(coordinator)
            val input = request(allowed)
            repeat(2) { attempt ->
                val replyId = uuid()
                val stream = ReplyStream(headed = true, allowAnswer = DecisionKind.ANSWER in allowed) { out.delta(replyId, threadId, it) }
                val conversation = record.brain.coordinatorContexts[key]?.takeIf { it.personaHash == persona.hash }
                val text = turns.talk(call, coordinator, persona, input + if (attempt == 1) REPLY_REPAIR else "", conversation, key,
                    coordinator = true, stream = stream, opened = rememberCoordinator(key)).first
                val decision = runCatching { parseCoordinatorReply(text, allowed) }.getOrNull()?.takeIf { fits(it, threadId) }
                if (decision != null) return decision to replyId
            }
            return null
        }

        /** Whether [decision] names what exists: this thread for a forward, a bound specialist for a delegation. */
        fun fits(decision: CoordinatorDecision, threadId: String?): Boolean = when (decision) {
            is CoordinatorDecision.Forward -> decision.threadId == threadId
            is CoordinatorDecision.Delegate -> threadId == null ||
                claim.brain.configuration.specialists.any { it.id == decision.specialistId && it.binding != null }
            else -> true
        }

        /** [role]'s specialist turn in [threadId], streamed as reply [entryId] into the thread, with its activity. */
        fun specialist(threadId: String, role: BrainRoleSnapshot, input: String, entryId: String): Pair<String, Activity?> {
            val persona = Persona.role(companion, role.name, roleInstructions(role), role.mode == MemberMode.WORK)
            val conversation = record.threadContexts[threadId]?.takeIf { it.personaHash == persona.hash }
            val stream = ReplyStream(headed = false) { out.delta(entryId, threadId, it) }
            val remember = { native: NativeConversation -> update { it.copy(threadContexts = it.threadContexts + (threadId to native)) } }
            return if (role.mode == MemberMode.WORK) {
                turns.work(call, threadId, role, persona, input, conversation, scope(threadId), stream, remember)
            } else turns.talk(call, role, persona, input, conversation, threadId, coordinator = false, stream = stream, opened = remember)
        }

        fun rememberCoordinator(key: String) = { native: NativeConversation ->
            update { it.copy(brain = it.brain.copy(coordinatorContexts = it.brain.coordinatorContexts + (key to native))) }
        }

        // ---- Records and checkpoints ------------------------------------------------------------

        /**
         * Apply one transition to the run as one revision, give each new attempt its thread, save, and send what
         * changed: new main-conversation entries, then threads, then thread entries ([entries] names an entry's
         * thread), the run, and a new waiting notice. [adjust] changes the record before threads are refreshed.
         */
        fun change(rerunOf: String? = null, entries: Map<String, String> = emptyMap(), live: Boolean = true,
            adjust: (ConversationRecord) -> ConversationRecord = { it }, transform: (BrainRecord) -> BrainRecord) {
            if (live) call.live()
            val fresh = synchronized(this) {
                val before = record
                val revised = before.copy(brain = transform(before.brain).nextRevision()).withThreads(rerunOf, ::uuid)
                val next = adjust(revised).withThreadStates()
                checkpoints(before, next, entries).also { save(next.copy(outbox = next.outbox + it)) }
            }
            fresh.forEach(out::wrote)
            flush(strict = true)
        }

        /** What changed between [before] and [after], in the order the server needs it. */
        fun checkpoints(before: ConversationRecord, after: ConversationRecord, entries: Map<String, String>): List<MessageCheckpoint> {
            val known = before.brain.messages.mapTo(HashSet()) { it.id }
            val added = after.brain.messages.filter { it.id !in known && it.author != BrainAuthor.USER }
            fun appended(entry: BrainEntry): MessageCheckpoint {
                val author = when (entry.author) {
                    BrainAuthor.COORDINATOR -> MessageAuthor.COORDINATOR
                    BrainAuthor.SPECIALIST -> MessageAuthor.SPECIALIST
                    else -> MessageAuthor.SYSTEM
                }
                val threadId = if (author == MessageAuthor.SYSTEM) null else entries[entry.id]
                val text = entry.text.take(CompanionLimits.MESSAGE)
                return MessageCheckpoint.MessageAppended(MessageAppend(author, entry.id, null, text, threadId))
            }
            val (inThreads, main) = added.partition { entries[it.id] != null && it.author != BrainAuthor.SYSTEM }
            val threads = after.threads.filter { (id, thread) -> thread != before.threads[id] }
                .map { (id, thread) -> MessageCheckpoint.ThreadUpserted(thread.wire(conversationId, id)) }
            val run = after.conversationRun()?.takeIf { it != before.conversationRun() }?.let { MessageCheckpoint.RunChanged(it) }
            val waiting = after.brain.waiting?.takeIf { waiting ->
                val said = added.any { it.text.take(BrainLimits.DESCRIPTION) == waiting.summary }
                waiting != before.brain.waiting && waiting.kind in NOTICES && !said
            }
            val notice = waiting?.let {
                MessageCheckpoint.MessageAppended(MessageAppend(MessageAuthor.SYSTEM, uuid(), null, it.summary, null))
            }
            return main.map(::appended) + threads + inThreads.map(::appended) + listOfNotNull(run, notice)
        }

        fun append(author: MessageAuthor, id: String, text: String, threadId: String?, note: CoordinatorNote? = null) {
            out.drain()
            emit(MessageCheckpoint.MessageAppended(MessageAppend(author, id, note, text.take(CompanionLimits.MESSAGE), threadId)))
        }

        fun system(text: String, threadId: String?) = append(MessageAuthor.SYSTEM, uuid(), text, threadId)

        fun putThread(id: String, thread: ThreadState) {
            update { it.copy(threads = it.threads + (id to thread)) }
            emit(MessageCheckpoint.ThreadUpserted(thread.wire(conversationId, id)))
        }

        /** Keep [checkpoint] in the outbox and send what the outbox holds. */
        fun emit(checkpoint: MessageCheckpoint) {
            update { it.copy(outbox = it.outbox + checkpoint) }
            out.wrote(checkpoint)
            flush(strict = true)
        }

        /** Keep [checkpoint] for the next flush without sending now: an approval's close the server could not take. */
        fun later(checkpoint: MessageCheckpoint) = update { it.copy(outbox = it.outbox + checkpoint) }

        /**
         * Send the outbox in order, under this message's claim, until it is empty or the server cannot be reached
         * (the rest waits for the next flush). A refused checkpoint is dropped; [strict] then throws the refusal,
         * since the claim is gone, while a flush of earlier work goes on with the rest.
         */
        fun flush(strict: Boolean) {
            while (true) {
                val next = record.outbox.firstOrNull() ?: return
                try {
                    out.checkpoint(next)
                } catch (refused: HovelRejectedException) {
                    log(LogLevel.Warn, "conversations", "The server refused a ${next::class.simpleName} checkpoint; it is dropped")
                    sent(next)
                    // a close queued while offline lost to another answer: say which one this Mac ran
                    (next as? MessageCheckpoint.ApprovalClosed)?.let(approvals::conflict)?.let { conflict ->
                        later(MessageCheckpoint.MessageAppended(MessageAppend(MessageAuthor.SYSTEM, uuid(), null, conflict.text, conflict.threadId)))
                    }
                    if (strict) throw refused else continue
                } catch (_: Exception) {
                    return
                }
                sent(next)
            }
        }

        @Synchronized fun sent(checkpoint: MessageCheckpoint) {
            if (record.outbox.firstOrNull() === checkpoint) save(record.copy(outbox = record.outbox.drop(1)))
        }

        @Synchronized fun save(next: ConversationRecord) { record = next; store.write(conversationId, next) }

        /** Change the record from its latest state, from any thread. */
        @Synchronized fun update(transform: (ConversationRecord) -> ConversationRecord) = save(transform(record))

        /**
         * After a crash nothing of this conversation is running: a run left working or awaiting approval pauses for
         * review, and a thread outside the run left running is interrupted. Both are published.
         */
        fun recover() {
            val run = record.brain.run?.takeIf { it.summary.state.active }
            if (run != null) change(live = false) { it.copy(run = run.halted(BrainRunState.PAUSED), waiting = INTERRUPTED) }
            val inRun = record.attemptThreads.values.toSet().takeIf { record.brain.run?.summary?.state?.active == true }.orEmpty()
            for ((id, thread) in record.threads) {
                if (id !in inRun && thread.state == BrainAttemptState.RUNNING) {
                    putThread(id, thread.copy(state = BrainAttemptState.INTERRUPTED, revision = thread.revision + 1))
                }
            }
        }

        /** The main conversation's history as the coordinator reads it, when this Mac has none of its own yet. */
        fun seedHistory() {
            if (record.brain.messages.isNotEmpty()) return
            val entries = claim.history.filter { it.id != message.id && it.threadId == null }.mapNotNull { entry ->
                val author = when (entry.author) {
                    MessageAuthor.USER -> BrainAuthor.USER
                    MessageAuthor.COORDINATOR -> BrainAuthor.COORDINATOR
                    MessageAuthor.SYSTEM -> BrainAuthor.SYSTEM
                    MessageAuthor.SPECIALIST -> return@mapNotNull null
                }
                BrainEntry(entry.id, author, entry.text.take(BrainLimits.OUTPUT))
            }.takeLast(BrainLimits.HISTORY)
            update { it.copy(brain = it.brain.copy(messages = entries)) }
        }

        fun currentRun(): RunRecord = checkNotNull(record.brain.run)

        fun scope(threadId: String?) = ApprovalScope(out, conversationId, threadId, local, companion, ::later)

        // ---- Roles and inputs ------------------------------------------------------------------

        fun coordinatorRole(): BrainRoleSnapshot = claim.brain.configuration.coordinator.snapshot()
            ?: throw BrainConfigurationException.unassigned(BrainConfigurationException.COORDINATOR)

        fun specialistSnapshot(id: String): BrainRoleSnapshot {
            val role = claim.brain.configuration.specialists.first { it.id == id }
            val binding = role.binding ?: throw BrainConfigurationException.unassigned(role.name)
            return BrainRoleSnapshot(role.id, role.name, binding, role.instructions, role.mode)
        }

        fun contextKey(role: BrainRoleSnapshot) = "${role.roleId}:${role.binding.runtimeId.wireName}"

        /**
         * The message's inputs as run artifacts: one this Mac already holds keeps its bytes, a text input brings its
         * whole text from the server, and when that read fails the turn says it works from the preview.
         */
        fun inputs(): List<BrainArtifact> = message.inputs.map { ref ->
            record.brain.run?.artifacts?.find { it.id == ref.id } ?: run {
                val text = if (ref.mediaType in Artifacts.TEXT) wholeText(ref.id) ?: previewOnly(ref.preview) else elsewhereOnly(ref.title)
                val type = if (ref.mediaType in Artifacts.TEXT) ref.mediaType else "text/plain"
                val source = BrainSource(BrainSourceKind.USER, messageId = message.id)
                store.artifact(conversationId, ref.title, source, text.toByteArray(), type, id = ref.id)
            }
        }

        /** An input's whole text, page by page, within the brain's input bound; null when it could not be read. */
        fun wholeText(artifactId: String): String? = runCatching {
            val text = StringBuilder()
            var cursor: Int? = 0
            var pages = 0
            while (cursor != null && text.length < BrainLimits.INPUT && pages++ < MAX_INPUT_PAGES) {
                val page = api.artifactText(artifactId, cursor)
                text.append(page.text)
                cursor = page.nextCursor
            }
            text.take(BrainLimits.INPUT).toString()
        }.onFailure { log(LogLevel.Warn, "conversations", "An input's whole text could not be read", it) }.getOrNull()

        /** The run's input artifacts [ids] as handoff text; only a working role ([files]) is also given their files. */
        fun handoff(ids: List<String>, files: Boolean): String {
            val path = if (files) { artifact: BrainArtifact -> store.artifactPath(conversationId, artifact) } else null
            return handoffText(currentRun().handoffInputs(ids, { store.bytes(conversationId, it) }, path))
        }

        fun localThread(thread: ConversationThread) = ThreadState(thread.title, thread.parentMessageId, thread.revision, thread.role,
            thread.stepId, thread.state, thread.outcome, thread.activity, thread.outputs)

        fun ref(artifact: BrainArtifact) = ArtifactRef(artifact.id, artifact.mediaType, artifact.preview, artifact.title)

        fun notApproved(role: BrainRoleSnapshot) = "Project access was not approved, so ${role.name} has not started."

        /** Whether a failed working turn may have changed something, so its thread is interrupted rather than failed. */
        fun uncertain(role: BrainRoleSnapshot, failure: ChatFailure) =
            role.mode == MemberMode.WORK && (failure == ChatFailure.Temporary || failure == ChatFailure.Unexpected)
    }

    private companion object {
        /** Waiting reasons the conversation shows as a notice; the others are approvals with dialogs of their own. */
        val NOTICES = setOf(BrainWaitingKind.INPUT, BrainWaitingKind.RECOVERY, BrainWaitingKind.LIMIT)

        /** Why a run that was working when its message ended (a crash, or Hovel quitting) waits for review. */
        val INTERRUPTED = BrainWaiting(BrainWaitingKind.RECOVERY, "Work was interrupted. Review completed outputs before resuming.")

        /** Why a run waits for the person: the coordinator's reply was invalid twice, or the run used its allowance. */
        const val INVALID_DECISION = "The coordinator returned an invalid decision, so no specialist was started. Reply to try again."
        const val OVER_ALLOWANCE = "The run reached its revision or dispatch allowance. Retry the message to allow more."

        /** Pages of an input's text read at most: its bound, at the smallest page the server sends. */
        const val MAX_INPUT_PAGES = 64

        fun uuid() = UUID.randomUUID().toString()
    }
}
