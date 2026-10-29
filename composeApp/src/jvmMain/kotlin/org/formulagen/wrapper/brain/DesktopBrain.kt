package org.formulagen.wrapper.brain

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.workspace.FilePicker

/**
 * A single leased, durable controller. Provider work and dialogs run outside the state lock. The run's state changes
 * are the pure functions in `BrainTransitions.kt`; this class owns the lease, the store, the providers and the dialogs.
 */
internal class DesktopBrain(
    private val root: Path,
    private val store: BrainStore,
    runtimes: List<ChatRuntime>,
    private val work: ChatWork,
    private val providers: ProviderCapability,
    private val dialogs: NativeDialogs,
    /** Chooses a file to attach as an input; the page never names a path. */
    private val files: FilePicker,
    private val speech: ChatSpeech,
    private val closeLegacy: () -> Unit,
    private val saveWhileLegacyIdle: (String, String, () -> BrainSaveResult) -> BrainSaveResult = { _, _, save -> save() },
    private val ownershipGate: Any = Any(),
) : BrainCapability, AutoCloseable {
    private val runtimes = runtimes.associateBy { it.id }
    private val gate = Any()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "companion-brain").apply { isDaemon = true } }
    private var active: Session? = null

    /** One chat lease. [understood] is the runtimes its page decodes; [coordinator] is the model resolved for the turn in flight. */
    private class Session(val lease: BrainLease, var record: BrainRecord, val understood: Set<RuntimeId>) {
        var cancel = CancelSignal()
        var handle: TurnHandle? = null
        var busy = false
        var speak = false
        var generation = 0L
        var coordinator: BrainRoleSnapshot? = null
    }

    private fun uuid() = UUID.randomUUID().toString()
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    /** Request receipts compare the arguments in their contract form. */
    private inline fun <reified T> digest(args: T) = BrainStore.digest(brainWire(args).toString().toByteArray())
    private fun live(session: Session) = active === session && !session.cancel.isCancelled
    private fun requireSession(lease: BrainLease) = active?.takeIf { it.lease == lease } ?: error("Expired brain lease")

    /** A cancelled execution stays cancelled even when the same chat starts another run. */
    private fun renewedSession(lease: BrainLease): Session {
        val previous = requireSession(lease)
        return if (!previous.cancel.isCancelled) previous else Session(previous.lease, previous.record, previous.understood).also {
            it.speak = previous.speak
            active = it
        }
    }

    private fun persist(session: Session, value: BrainRecord) {
        store.writeRecord(session.lease.profile, session.lease.companionId, value)
        session.record = value
    }

    /** Apply one transition to a live session's record as one run revision. */
    private fun change(session: Session, transform: (BrainRecord) -> BrainRecord) = synchronized(gate) {
        check(live(session)) { "Expired brain execution" }
        persist(session, transform(session.record).nextRevision())
    }

    private fun currentRun(session: Session) = synchronized(gate) { check(live(session)); checkNotNull(session.record.run) }

    /**
     * Nothing is in flight. A worker that has just paused or finished only winds down: it checks the run's state and
     * generation before doing anything more, so a new request may start beside it.
     */
    private fun idle(session: Session) = !session.busy || session.record.run?.summary?.state?.active != true

    /**
     * What [session]'s page would read, and what work it starts would run on: the saved [document], whose coordinator
     * the next decision uses, the run's pinned brain, and every role the conversation shows.
     */
    private fun requireReadable(session: Session, document: BrainDocument?) =
        requireDecodable(session.understood, document?.configuration?.runtimeIds().orEmpty() + session.record.runtimeIds())

    override suspend fun read(args: BrainRead): BrainReadResult = io {
        val understood = SharedRules.understood(args.supportedRuntimes)
        val document = store.read(args.profile, args.companionId) ?: return@io BrainReadResult.Absent
        requireDecodable(understood, document.configuration.runtimeIds())
        BrainReadResult.Found(document, readiness(args.profile, args.companionId, document.configuration))
    }

    override suspend fun save(args: BrainSave): BrainSaveResult = io {
        val result = saveWhileLegacyIdle(args.profile, args.companionId) {
            store.save(args.profile, args.companionId, args.expectedRevision, args.mutationId, args.configuration)
        }
        if (result !is BrainSaveResult.Saved) return@io result
        result.copy(issues = readiness(args.profile, args.companionId, result.document.configuration))
    }

    override suspend fun open(args: BrainOpen): BrainSnapshot = io { open(args.lease(), SharedRules.understood(args.supportedRuntimes)) }

    override suspend fun send(args: BrainSend): BrainSnapshot = io { sendNow(args) }

    override suspend fun close(lease: BrainLease) = io {
        synchronized(gate) { active?.takeIf { it.lease == lease }?.let { end(it, false); active = null } }
        Unit
    }

    override suspend fun snapshot(args: BrainSnapshotRequest): BrainSnapshot =
        io { synchronized(gate) { snapshot(requireSession(args.lease())) } }

    override suspend fun stop(lease: BrainLease): BrainSnapshot = io {
        synchronized(gate) {
            val session = requireSession(lease)
            end(session, true)
            snapshot(session)
        }
    }

    override suspend fun stopSpeaking(lease: BrainLease): BrainSnapshot = io {
        synchronized(gate) {
            speech.stop()
            snapshot(requireSession(lease))
        }
    }

    override suspend fun retry(args: BrainRetry): BrainSnapshot = io {
        val command = RunCommand(args.runId, args.expectedRunRevision, args.requestId, digest(args))
        resume(args.lease(), command, target = null, retry = args.attemptId)
    }

    override suspend fun resume(args: BrainResume): BrainSnapshot = io {
        val command = RunCommand(args.runId, args.expectedRunRevision, args.requestId, digest(args))
        resume(args.lease(), command, args.targetRevision, retry = null)
    }

    override suspend fun showApproval(args: BrainShowApproval) = io {
        synchronized(gate) {
            val session = requireSession(args.lease())
            require(session.record.run?.summary?.id == args.runId && session.record.waiting?.approvalId == args.approvalId)
        }
    }

    override suspend fun addInput(args: BrainAddInput): BrainArtifact = io {
        addInput(args.lease(), args.messageId, args.text.toByteArray(), args.title, "text/plain")
    }

    override suspend fun chooseInput(args: BrainChooseInput): BrainArtifact? = io {
        synchronized(gate) { requireSession(args.lease()) }
        files.choose("Attach input to companion")?.let { path ->
            require(Files.isRegularFile(path) && Files.size(path) <= BrainStore.ARTIFACT_BYTES)
            val bytes = Files.readAllBytes(path)
            addInput(args.lease(), args.messageId, bytes, path.fileName.toString(), inputMediaType(path.fileName.toString(), bytes))
        }
    }

    override suspend fun readOutput(args: BrainReadOutput): BrainOutputPage = io {
        val artifact = synchronized(gate) {
            val session = requireSession(args.lease())
            val run = session.record.run?.takeIf { it.summary.id == args.runId }
                ?: store.archived(args.profile, args.companionId, args.runId) ?: error("Unknown run")
            run.artifacts.find { it.id == args.artifactId } ?: error("Unknown artifact")
        }
        val text = if (artifact.mediaType == BrainStore.BINARY) "Attached file: ${artifact.title}"
        else store.bytes(args.profile, args.companionId, artifact).toString(Charsets.UTF_8)
        require(args.cursor <= text.length)
        val end = minOf(text.length, args.cursor + BrainLimits.OUTPUT)
        BrainOutputPage(text.substring(args.cursor, end), if (end < text.length) end else null)
    }

    private fun open(lease: BrainLease, understood: Set<RuntimeId>): BrainSnapshot = synchronized(ownershipGate) {
        synchronized(gate) state@{
            active?.takeIf { it.lease == lease }?.let { current ->
                // A lease keeps the vocabulary it opened with.
                if (current.understood != understood) throw BridgeUpdateRequiredException()
                return@state snapshot(current)
            }
            // Before taking over, so a page that cannot read this brain never ends another page's lease or chat.
            val saved = runCatching { store.read(lease.profile, lease.companionId) }.getOrNull()?.configuration?.runtimeIds().orEmpty()
            val shown = runCatching { store.readRecord(lease.profile, lease.companionId) }.getOrNull()?.runtimeIds().orEmpty()
            requireDecodable(understood, saved + shown)
            active?.let { end(it, false) }
            closeLegacy()
            var record = store.readRecord(lease.profile, lease.companionId)
            if (record.messages.isEmpty()) record = record.copy(messages = legacyConversation(lease))
            val session = Session(lease, record.reopened(), understood)
            active = session
            persist(session, session.record)
            snapshot(session)
        }
    }

    /** The team conversation, from either of its locations. An unreadable record never blocks the coordinator; it stays on disk. */
    private fun legacyConversation(lease: BrainLease): List<BrainEntry> {
        val legacy = runCatching { companionChatHistory(root, lease.profile, lease.companionId).read() }.getOrNull()
        return legacy?.entries.orEmpty().mapNotNull { entry ->
            when (entry) {
                is ChatEntry.User -> BrainEntry(entry.id, BrainAuthor.USER, entry.text)
                is ChatEntry.Assistant -> {
                    val text = "Previous conversation (${entry.author.modelId}): ${entry.text}"
                    BrainEntry(entry.id, BrainAuthor.SYSTEM, text.take(BrainLimits.OUTPUT))
                }
                else -> null
            }
        }.takeLast(BrainLimits.HISTORY)
    }

    private fun snapshot(session: Session): BrainSnapshot {
        val document = store.read(session.lease.profile, session.lease.companionId)
        val coordinator = session.coordinator?.takeIf { session.busy } ?: document?.configuration?.coordinator?.snapshot()
        val record = session.record
        val run = record.run
        val snapshot = BrainSnapshot(document?.revision ?: 0, coordinator, record.messages, run?.summary, run?.attempts ?: emptyList(),
            run?.artifacts ?: emptyList(), record.waiting, speech.status(), (run?.summary?.revision ?: 0).toString(), record.notice)
        requireDecodable(session.understood, snapshot.runtimeIds() + document?.configuration?.runtimeIds().orEmpty() + record.runtimeIds())
        return snapshot
    }

    private fun sendNow(args: BrainSend): BrainSnapshot = synchronized(gate) {
        val session = renewedSession(args.lease())
        val digest = digest(args)
        if (session.record.replays(args.requestId, digest)) return@synchronized snapshot(session)
        require(idle(session))
        val document = store.read(session.lease.profile, session.lease.companionId) ?: error("Configure a coordinator first")
        // A page must never start work it cannot then show.
        requireReadable(session, document)
        if (document.revision != args.expectedRevision) {
            persist(session, session.record.staleSend())
            return@synchronized snapshot(session)
        }
        val available = session.record.staged + (session.record.run?.artifacts ?: emptyList())
        require(args.inputIds.all { id -> available.any { it.id == id } })
        val text = args.text.trimEcma()
        val attached = available.filter { it.id in args.inputIds }
        val existing = session.record.run
        // A message to a run awaiting input answers it; otherwise it starts the next run.
        val answering = existing?.takeIf { it.summary.state == BrainRunState.AWAITING_INPUT }
        val next = answering?.answered(text, args.inputIds, attached) ?: run {
            require(existing == null || existing.summary.state.terminal) { "Resume or stop the current run first" }
            existing?.let { store.archive(session.lease.profile, session.lease.companionId, it) }
            val workspace = work.workspaces.folder(session.lease.profile, session.lease.companionId)?.path?.toString()
            val summary = BrainRun(uuid(), 1, document.revision, null, BrainRunState.PLANNING, null)
            RunRecord(summary, document.configuration, workspace, text, args.inputIds, artifacts = attached)
        }
        session.cancel = CancelSignal()
        session.speak = args.speak
        persist(session, session.record.sent(args.requestId, digest, text, args.inputIds, next))
        launch(session)
        snapshot(session)
    }

    private fun addInput(lease: BrainLease, messageId: String, bytes: ByteArray, title: String, type: String): BrainArtifact {
        val session = synchronized(gate) { requireSession(lease) }
        val source = BrainSource(BrainSourceKind.USER, messageId = messageId)
        val artifact = store.artifact(lease.profile, lease.companionId, title, source, bytes, type)
        synchronized(gate) {
            require(active === session)
            persist(session, session.record.copy(staged = (session.record.staged + artifact).takeLast(BrainLimits.INPUTS)))
        }
        return artifact
    }

    private fun launch(session: Session) {
        session.busy = true
        val generation = ++session.generation
        executor.execute {
            try {
                fun working() = live(session) && session.generation == generation && session.record.run?.summary?.state?.working == true
                while (synchronized(gate) { working() }) {
                    if (currentRun(session).phase == BrainPhase.EXECUTE) execute(session) else decide(session)
                }
            } catch (_: InterruptedException) {
            } catch (error: Throwable) {
                synchronized(gate) { if (live(session) && session.generation == generation) failed(session, error) }
            } finally {
                synchronized(gate) {
                    if (active === session && session.generation == generation) {
                        session.busy = false
                        session.handle = null
                    }
                }
            }
        }
    }

    /** Pause after [error] with what is known: the provider's own reason when it gave one, and whether the attempt could have done work. */
    private fun failed(session: Session, error: Throwable) {
        val failure = error as? ProviderFailure
        val message = failurePause(failure, session.record.failingRole)
        change(session) { it.afterFailure(failure, message) }
    }

    private fun end(session: Session, cancelled: Boolean) {
        session.generation++
        session.cancel.cancel()
        session.handle?.stop()
        session.handle = null
        session.busy = false
        runtimes.values.forEach { it.release() }
        work.runtimes.values.forEach { it.release() }
        speech.stop()
        session.record.ended(cancelled)?.let { persist(session, it) }
    }

    fun invalidate() = synchronized(gate) {
        active?.let { end(it, false) }
        active = null
    }

    override fun close() {
        invalidate()
        executor.shutdownNow()
        speech.close()
    }

    /** Catalog checks never silently substitute a model or effort. */
    private suspend fun readiness(profile: String, companionId: String, configuration: BrainConfiguration): List<BrainIssue> {
        val issues = mutableListOf<BrainIssue>()
        val coordinator = Triple(configuration.coordinator.id, configuration.coordinator.binding, MemberMode.TALK)
        val roles = listOf(coordinator) + configuration.specialists.map { Triple(it.id, it.binding, it.mode) }
        // A capacity-sized brain still needs only one status/catalog read per subscription.
        val catalogs = roles.mapNotNull { it.second?.runtimeId }.distinct().associateWith { runtime ->
            val scope = ProviderScope(profile, runtime)
            val status = runCatching { providers.perform(ProviderAction.STATUS, scope).state }.getOrNull()
            val connected = status == ProviderState.CONNECTED
            connected to if (connected) runCatching { providers.models(scope).models }.getOrNull() else null
        }
        val projectExists = work.workspaces.folder(profile, companionId)?.exists == true
        for ((id, binding, mode) in roles) {
            if (binding == null) {
                issues += BrainIssue(BrainIssueReason.MISSING_BINDING, id)
                continue
            }
            val (connected, models) = catalogs.getValue(binding.runtimeId)
            if (!connected) {
                issues += BrainIssue(BrainIssueReason.UNAVAILABLE_PROVIDER, id)
                continue
            }
            val model = models?.let { binding.model(it) }
            if (model == null) issues += BrainIssue(BrainIssueReason.UNAVAILABLE_MODEL, id)
            if (binding.effort != null && model?.effortLevels?.contains(binding.effort) != true) {
                issues += BrainIssue(BrainIssueReason.UNSUPPORTED_EFFORT, id)
            }
            if (mode == MemberMode.WORK) {
                if (binding.runtimeId !in work.runtimes) issues += BrainIssue(BrainIssueReason.UNSUPPORTED_PROJECT_WORK, id)
                if (!projectExists) issues += BrainIssue(BrainIssueReason.MISSING_PROJECT, id)
            }
        }
        configuration.workflow?.takeIf { it.enabled }?.let { workflow ->
            if (workflow.steps.isEmpty()) issues += BrainIssue(BrainIssueReason.EMPTY_WORKFLOW)
            issues += workflow.steps.filter { it.specialistId == null }.map { BrainIssue(BrainIssueReason.MISSING_ROLE, stepId = it.id) }
        }
        return issues
    }

    /** [role] with the model its runtime actually runs for its binding; an unavailable model or effort fails. */
    private fun resolved(session: Session, role: BrainRoleSnapshot): BrainRoleSnapshot {
        val models = runBlocking { providers.models(ProviderScope(session.lease.profile, role.binding.runtimeId)).models }
        val model = role.binding.model(models) ?: error("Model unavailable")
        require(role.binding.effort == null || model.effortLevels?.contains(role.binding.effort) == true)
        return role.copy(binding = role.binding.copy(modelId = model.modelId))
    }

    /** Ask the coordinator, which reads the current saved coordinator settings, and apply its validated decision. */
    private fun decide(session: Session) {
        val current = currentRun(session)
        val document = store.read(session.lease.profile, session.lease.companionId) ?: error("Missing brain")
        val coordinator = resolved(session, checkNotNull(document.configuration.coordinator.snapshot()))
        synchronized(gate) { check(live(session)); session.coordinator = coordinator }
        val allowed = allowedDecisions(current)
        val request = coordinatorRequest(
            decisionId = uuid(),
            run = current,
            allowed = allowed,
            input = handoff(session, current.artifacts.filter { it.id in current.inputs }.map { it.id }, files = false),
            latestOutput = current.attempts.lastOrNull()?.outputIds?.let { handoff(session, it, files = false) } ?: "",
            conversation = synchronized(gate) { session.record.messages.takeLast(10) },
        )
        val persona = coordinator.copy(instructions = coordinatorInstructions(coordinator.instructions))
        var decision: CoordinatorDecision? = null
        repeat(2) { index ->
            if (decision == null) {
                val text = turn(session, persona, request + if (index == 1) DECISION_REPAIR else "", coordinator = true).first
                decision = runCatching { parseDecision(text, allowed) }.getOrNull()
            }
        }
        val value = decision ?: run {
            val message = "The coordinator returned an invalid decision, so no specialist was started. Reply to try again."
            change(session) { it.paused(message, BrainWaitingKind.INPUT) }
            return
        }
        change(session) { it.decided(value, coordinator, ::uuid) }
        if (value is CoordinatorDecision.Answer && session.speak) speech.speak(value.text)
    }

    /** Run the queued attempt: allowance, readiness, stage approval and start of work first, then the provider turn. */
    private fun execute(session: Session) {
        val current = currentRun(session)
        var attempt = current.attempts.last()
        if (current.overAllowance) {
            val message = "The run reached its revision or dispatch allowance. Continue adds another bounded allowance."
            change(session) { it.paused(message, BrainWaitingKind.LIMIT) }
            return
        }
        val issues = runBlocking { readiness(session.lease.profile, session.lease.companionId, current.configuration) }
            .filter { it.roleId == attempt.role.roleId }
        if (issues.isNotEmpty()) {
            change(session) { it.paused(notReadyPause(attempt.role, issues)) }
            return
        }
        val role = resolved(session, attempt.role)
        attempt = attempt.copy(role = role)
        change(session) { it.copy(run = checkNotNull(it.run).withLast(attempt)) }
        val step = current.configuration.workflow?.steps?.find { it.id == attempt.stepId }
        // Ad hoc work for a role the enabled workflow uses honors the strictest of that role's stage approvals.
        val stages = step?.let(::listOf)
            ?: current.configuration.workflow?.takeIf { it.enabled }?.steps?.filter { it.specialistId == role.roleId }.orEmpty()
        if (stages.any { it.askBeforeStarting } && !approveStage(session, current, attempt, step)) return
        if (role.mode == MemberMode.WORK && !startWork(session, current, attempt)) return
        change(session) { it.dispatched(attempt) }
        val text = specialistTurn(role, current, step, handoff(session, attempt.inputIds, files = role.mode == MemberMode.WORK))
        val (output, activity) = turn(session, role, text, coordinator = false)
        val source = BrainSource(BrainSourceKind.ATTEMPT, attemptId = attempt.id)
        val (profile, companionId) = session.lease.profile to session.lease.companionId
        val artifact = store.artifact(profile, companionId, step?.name ?: role.name, source, output.toByteArray(), "text/markdown")
        change(session) { it.completed(attempt, artifact, output, activity, uuid()) }
    }

    /** The native stage dialog for [attempt]. True to go on; otherwise the run waits for changes, stays paused or stops. */
    private fun approveStage(session: Session, run: RunRecord, attempt: BrainAttempt, step: WorkflowStep?): Boolean {
        val role = attempt.role
        val subject = stageSubject(role, step)
        val waiting = BrainWaiting(BrainWaitingKind.STAGE, stageSummary(subject, role).take(BrainLimits.DESCRIPTION), uuid(), attempt.id)
        change(session) { it.awaitingStage(attempt, waiting) }
        when (dialogs.ask(stageDialog(role, run, step, handoff(session, attempt.inputIds, files = false)), session.cancel)) {
            StageChoice.CONTINUE -> return true
            StageChoice.CHANGES -> change(session) { it.declined(attempt, "Describe the changes before this step starts.") }
            StageChoice.STOP -> synchronized(gate) { if (live(session)) end(session, true) }
            StageChoice.PAUSE, null -> change(session) { it.stagePostponed(attempt, subject) }
        }
        return false
    }

    /** Confirm the start of project work, once per run, role and instructions. False when the person declined. */
    private fun startWork(session: Session, run: RunRecord, attempt: BrainAttempt): Boolean {
        val role = attempt.role
        val member = role.binding.member()
        val folder = run.workspace?.let(Path::of) ?: error("Choose a project folder")
        val chosen = work.workspaces.folder(session.lease.profile, session.lease.companionId)?.path
        require(Files.isDirectory(folder) && chosen?.toRealPath() == folder.toRealPath())
        val identity = "${session.lease.sessionId}:${run.summary.id}:${role.roleId}:${BrainStore.digest(role.instructions.toByteArray())}"
        if (work.startOfWork.isConfirmed(identity, session.lease.companionId, folder, member)) return true
        change(session) { it.copy(waiting = BrainWaiting(BrainWaitingKind.START_OF_WORK, "${role.name} wants project access.")) }
        if (!work.startOfWork.ask(session.lease.name, folder, member, session.cancel)) {
            change(session) { it.declined(attempt, "Project access was not approved, so ${role.name} has not started.") }
            return false
        }
        synchronized(gate) {
            check(live(session))
            work.startOfWork.remember(identity, session.lease.companionId, folder, member)
        }
        return true
    }

    /** The run's input artifacts [ids] as handoff text; only a project role ([files]) is also given their native files. */
    private fun handoff(session: Session, ids: List<String>, files: Boolean): String {
        val run = currentRun(session)
        val (profile, companionId) = session.lease.profile to session.lease.companionId
        return handoffText(ids.distinct().map { id ->
            val artifact = run.artifacts.find { it.id == id } ?: error("Unknown input artifact")
            val bytes = store.bytes(profile, companionId, artifact)
            HandoffInput(artifact, bytes, if (files) store.artifactPath(profile, companionId, artifact) else null)
        })
    }

    /** Provider callbacks are bound to this exact lease and invocation. */
    private fun turn(session: Session, role: BrainRoleSnapshot, input: String, coordinator: Boolean): Pair<String, Activity?> {
        val current = currentRun(session)
        val lease = session.lease
        val persona = Persona.role(lease.name, role.name, roleInstructions(role), role.mode == MemberMode.WORK)
        val key = "${role.roleId}:${role.binding.runtimeId}"
        val before = synchronized(gate) { if (coordinator) session.record.coordinatorContexts[key] else current.contexts[key] }
        val conversation = before?.takeIf { it.personaHash == persona.hash }
        val future = CompletableFuture<Pair<String, Activity?>>()
        var latestActivity: Activity? = null
        val generation = synchronized(gate) { check(live(session)); session.generation }
        val events = object : TurnEvents {
            private fun valid() = live(session) && session.generation == generation && !future.isDone
            override fun opened(conversation: String) = synchronized(gate) {
                if (!valid()) return@synchronized
                val native = NativeConversation(conversation, persona.hash, playerLogin = true)
                try {
                    change(session) {
                        if (coordinator) it.copy(coordinatorContexts = it.coordinatorContexts + (key to native))
                        else it.copy(run = it.run?.let { run -> run.copy(contexts = run.contexts + (key to native)) })
                    }
                } catch (failure: Exception) {
                    future.completeExceptionally(failure)
                }
                Unit
            }
            override fun accepted() {}
            override fun partial(text: String) { /* Control responses remain private until validated. */ }
            override fun completed(text: String, conversation: String) {
                synchronized(gate) { if (valid()) { opened(conversation); future.complete(text to latestActivity) } }
            }
            override fun failed(failure: ChatFailure) {
                val error = ProviderFailure(failure, role.binding.runtimeId, key, coordinator)
                synchronized(gate) { if (valid()) future.completeExceptionally(error) }
            }
            override fun activity(activity: Activity) { synchronized(gate) { if (valid()) latestActivity = activity } }
            override fun waiting(waiting: ChatWaiting?) {
                synchronized(gate) {
                    val asked = waiting?.let { BrainWaiting(BrainWaitingKind.PROVIDER, it.summary) }
                    if (valid()) change(session) { it.copy(waiting = asked) }
                }
            }
        }
        val executionId = "${lease.sessionId}:${current.summary.id}:${role.roleId}"
        val workScope = if (role.mode != MemberMode.WORK) null
        else WorkScope(Path.of(checkNotNull(current.workspace)), executionId, lease.name, role.binding.member())
        val request = TurnRequest(lease.profile, lease.companionId, persona, role.binding.modelId, conversation, null, input, workScope,
            role.binding.effort)
        val cancellation = session.cancel.onCancel { future.completeExceptionally(InterruptedException()) }
        try {
            val handle = if (role.mode == MemberMode.WORK) {
                val worker = work.runtimes[role.binding.runtimeId] ?: error("Project execution unavailable")
                require(worker.readiness(lease.profile) == Readiness.Ready)
                synchronized(gate) { check(live(session) && session.generation == generation) }
                worker.startTurn(request, events)
            } else {
                val runtime = runtimes[role.binding.runtimeId] ?: error("Runtime unavailable")
                require(runtime.readiness(lease.profile) == Readiness.Ready)
                synchronized(gate) { check(live(session) && session.generation == generation) }
                runtime.startTurn(request, events)
            }
            synchronized(gate) { if (live(session) && session.generation == generation) session.handle = handle else handle.stop() }
            return try { future.get(2, TimeUnit.HOURS) } catch (wrapped: ExecutionException) { throw wrapped.cause ?: wrapped }
        } finally {
            cancellation.close()
            synchronized(gate) { if (active === session && session.generation == generation) session.handle?.stop() }
            if (role.mode == MemberMode.WORK) work.runtimes[role.binding.runtimeId]?.release()
            synchronized(gate) { if (active === session && session.generation == generation) session.handle = null }
        }
    }

    /** A run command's identity: the run, the run revision the page saw, and its request receipt. */
    private class RunCommand(val runId: String, val expectedRunRevision: Long, val requestId: String, val digest: String)

    /** Resume/retry always reviews uncertain effects and changed configuration natively. [retry] names the attempt to run again. */
    private fun resume(lease: BrainLease, command: RunCommand, target: Long?, retry: String?): BrainSnapshot = synchronized(gate) {
        val session = renewedSession(lease)
        val run = checkNotNull(session.record.run)
        if (session.record.replays(command.requestId, command.digest)) return@synchronized snapshot(session)
        require(idle(session))
        require(run.summary.id == command.runId && run.summary.revision == command.expectedRunRevision)
        require(run.summary.state.resumable)
        if (retry != null) require(run.attempts.lastOrNull()?.id == retry)
        val document = store.read(lease.profile, lease.companionId)
        // The resumed work, whose next decision runs on the saved coordinator, must stay something this page can show.
        requireReadable(session, document)
        val adopted = target?.let { revision -> document?.also { require(it.revision == revision) } }
        if (adopted != null && !adoptable(run.configuration, adopted.configuration)) {
            persist(session, session.record.adoptionRefused())
            return@synchronized snapshot(session)
        }
        session.cancel = CancelSignal()
        session.busy = true
        persist(session, session.record.withReceipt(command.requestId, command.digest))
        val previousWaiting = session.record.waiting
        change(session) { it.reviewing() }
        val generation = ++session.generation
        executor.execute { review(session, generation, run, retry != null, adopted, previousWaiting) }
        snapshot(session)
    }

    /**
     * The native resume review, off the lock. Continue goes on from [run], the run as it was when the person asked,
     * adopting [target]; Keep paused restores [previousWaiting].
     */
    private fun review(
        session: Session,
        generation: Long,
        run: RunRecord,
        retry: Boolean,
        target: BrainDocument?,
        previousWaiting: BrainWaiting?,
    ) {
        try {
            val last = run.attempts.lastOrNull()
            // A dispatched attempt without a result runs again; only a project role's can have left effects behind.
            val rerun = last != null && (retry || last.state.dispatchedWithoutResult)
            val uncertain = last != null && (last.state == BrainAttemptState.RUNNING || last.state == BrainAttemptState.INTERRUPTED) &&
                last.role.mode == MemberMode.WORK
            val dialog = resumeDialog(run, rerun, uncertain, target, limit = previousWaiting?.kind == BrainWaitingKind.LIMIT)
            val approved = dialogs.ask(dialog, session.cancel) == true
            synchronized(gate) {
                if (!live(session) || session.generation != generation) return@synchronized
                session.busy = false
                if (approved) {
                    change(session) { it.reviewApproved(run, rerun, target, ::uuid) }
                    launch(session)
                } else change(session) { it.reviewDeclined(previousWaiting, run.summary.state) }
            }
        } catch (_: Throwable) {
            synchronized(gate) {
                if (active === session && session.generation == generation) {
                    session.busy = false
                    if (live(session)) change(session) { it.paused("Resume could not complete. The run remains paused.") }
                }
            }
        }
    }
}

/** The catalog model this binding runs on: the runtime's default for [SharedRules.RUNTIME_DEFAULT_MODEL], else the one named. */
private fun BrainBinding.model(models: List<ProviderModel>) =
    models.find { if (modelId == SharedRules.RUNTIME_DEFAULT_MODEL) it.isDefault else it.modelId == modelId }

/** UTF-8 text of any file name is text, Markdown by its extension in any case; anything else is a binary file. */
internal fun inputMediaType(fileName: String, bytes: ByteArray): String {
    val text = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
    }.getOrNull()
    return when {
        // A NUL is valid UTF-8 but marks binary data, such as UTF-16 text or an image.
        text == null || text.contains('\u0000') -> BrainStore.BINARY
        fileName.substringAfterLast('.', "").lowercase() in setOf("md", "markdown") -> "text/markdown"
        else -> "text/plain"
    }
}
