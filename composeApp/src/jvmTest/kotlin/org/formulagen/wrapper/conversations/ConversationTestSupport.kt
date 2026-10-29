package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.formulagen.wrapper.approvals.DialogPresenter
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.hovel.HovelEventLines
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.hovel.HovelStatusException

/** Shared fixtures for the conversation tests: ids, brains, claims, a fake server and fake runtimes. */

internal fun id(): String = UUID.randomUUID().toString()

internal const val AT = "2026-10-29T12:00:00.000Z"

/**
 * A brain whose coordinator runs on Codex, with [specialists] (each `name to mode`, on [runtime]) and,
 * when [workflow], one workflow step per specialist in order, each after the first returning to the one before.
 */
internal fun brain(vararg specialists: Pair<String, MemberMode>, workflow: Boolean = false, askBeforeStarting: Boolean = false,
    runtime: RuntimeId = RuntimeId.CODEX): BrainDocument {
    val roles = specialists.mapIndexed { index, (name, mode) ->
        SpecialistRole(id(), name, "Be the $name.", mode, BrainBinding(runtime, SharedRules.RUNTIME_DEFAULT_MODEL), index)
    }
    val steps = roles.mapIndexed { index, role ->
        WorkflowStep(id(), "Step ${index + 1}", role.id, "Do step ${index + 1}.", "", "Step ${index + 1} result", askBeforeStarting, null, null)
    }.let { steps -> steps.mapIndexed { index, step -> if (index > 0) step.copy(returnTo = BrainReturn(steps[index - 1].id, "Needs changes")) else step } }
    val flow = if (workflow) BrainWorkflow(id(), "Ship", true, steps, emptyList()) else null
    val coordinator = CoordinatorRole(id(), BrainBinding(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL), "Be kind.")
    return BrainDocument(4, BrainConfiguration(1, coordinator, roles, flow))
}

/** One conversation's fixed parts: its project, companion and account records as a claim carries them. */
internal class TestConversation(val id: String = id(), val projectId: String = id(), val companionId: String = id(), val computerId: String = id()) {
    fun claim(claimId: String, message: ConversationMessage, document: BrainDocument, history: List<ConversationMessage> = emptyList(),
        thread: ConversationThread? = null, recalledMemory: String? = null, projectRevision: Long = 3) = ClaimResult(
        document, claimId, AccountCompanion(companionId, buildJsonObject { put("body", "round") }, "Ember", 1),
        Conversation(false, id, AT, projectId, 2, null, false, "Add sign-in"), history, message,
        CompanionProject(false, companionId, computerId, emptyList(), projectId, "Hovel notes", projectRevision), recalledMemory, thread,
    )

    fun message(text: String, threadId: String? = null, messageId: String = id(), revision: Long = 2) =
        ConversationMessage(MessageAuthor.USER, null, id, AT, null, null, messageId, emptyList(), null, revision, MessageState.CLAIMED, text, threadId)
}

/** A queued message waiting on the fake server. */
internal class Queued(val message: ConversationMessage, val build: (claimId: String, message: ConversationMessage) -> ClaimResult)

/**
 * hovel-service as the Mac sees it, in memory: queued messages per conversation, claims fenced by claim
 * id (a repeated claim id answers the same claim, and progress needs the message's current claim),
 * revisions that refuse a stale progress, checkpoints, deltas, approvals whose first answer wins (the
 * Mac's through `approval.closed`), projects and their folder saves, input texts, and a host stream fed
 * by the test.
 */
internal class FakeServer(val hostId: String = id()) : ConversationApi {
    private val queues = ConcurrentHashMap<String, ArrayDeque<Queued>>()
    private val claims = HashMap<String, ClaimResult>()
    /** Each claimed message's current claim id. */
    private val holders = HashMap<String, String>()
    /** Claim ids a takeover replaced: asked again, they answer 409, moved on. */
    private val superseded = HashSet<String>()
    /**
     * The contract the server answers this Mac's claims at. From 2, a claim answers `stop-requested` with an in-flight
     * message the person stopped, and a fresh claim takes over one whose claim was lost; before it, the replay of a
     * stopped message's claim answers 409 `stop-requested`, and a fresh claim never takes one over.
     */
    @Volatile var contract = HovelContract.CURRENT
    /** A body the server answers claims with, once it has answered them, as the contract's JSON: one this build may not read. */
    @Volatile var claimBody: JsonObject? = null
    /** Claim ids answered `stop-requested`, in order. */
    val stopAnswers = CopyOnWriteArrayList<String>()
    val claimRequests = CopyOnWriteArrayList<ClaimRequest>()
    val checkpoints = CopyOnWriteArrayList<MessageCheckpoint>()
    val deltas = CopyOnWriteArrayList<ReplyDelta>()
    val progress = CopyOnWriteArrayList<MessageProgress>()
    val revisions = ConcurrentHashMap<String, Long>()
    val heartbeats = CopyOnWriteArrayList<HostHeartbeat>()
    val approvals = ConcurrentHashMap<String, AgentApproval>()
    /** The Mac's answers, as `approval.closed` (answered) checkpoints the server took. */
    val answers = CopyOnWriteArrayList<Pair<String, ApprovalOption>>()
    val folderSaves = CopyOnWriteArrayList<Pair<String, ProjectFoldersSave>>()
    val projects = ConcurrentHashMap<String, CompanionProject>()
    val texts = ConcurrentHashMap<String, String>()
    @Volatile var textsReadable = true
    /** A claim's or progress's status the server answers instead, such as 404 for a message gone for good. */
    @Volatile var claimStatus: Int? = null
    /** A 409 claim refusal's code. */
    @Volatile var claimCode: String? = null
    /** Runs as a claim arrives, before it is answered: a test holds the claim on its way. */
    @Volatile var onClaim: () -> Unit = {}
    /** Streams opened so far. */
    val streamsOpened = java.util.concurrent.atomic.AtomicInteger()
    /**
     * Messages the person asked to stop. As in hovel-service, a claim answers `stop-requested` (by [contract]), and a
     * new checkpoint, a delta and `running` progress answer 409 `stop-requested`; `approval.closed` and the settling
     * progress still land.
     */
    val stopRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile var progressStatus: Int? = null
    /** Refuses every checkpoint, as a server that took the claim back. */
    @Volatile var refuseCheckpoints = false
    @Volatile var approvalsReadable = true
    @Volatile var macUpdateRequired = emptySet<String>()
    @Volatile var offline = false
    @Volatile var refuseRunning = false
    /** Refuses every `stopped` settle with 409, as a server whose claim moved on in between. */
    @Volatile var refuseStops = false
    /**
     * A service older than this Mac's contract: every companion route answers 400 `unsupported-contract`, counted in
     * [olderRefusals]; hovel-service's own heartbeat still answers.
     */
    @Volatile var olderService = false
    val olderRefusals = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var foldersStatus: Int? = null
    @Volatile var streamFailures = 0
    private val stream = LinkedBlockingQueue<String>()

    fun queue(conversationId: String, queued: Queued) = synchronized(this) { queues.getOrPut(conversationId) { ArrayDeque() }.addLast(queued) }

    /** Ends a queued message before it is claimed, as expiry or a cancel does. */
    fun expire(conversationId: String) = synchronized(this) { queues[conversationId]?.clear() }

    /** Send a host event on the stream. */
    fun emit(event: HostStreamEvent) { stream.put("data: " + wire(HostStreamEvent.serializer(), event)); stream.put("") }

    /** The phone answers first, if it can. */
    @Synchronized fun phoneAnswers(approvalId: String, option: ApprovalOption): Boolean {
        val approval = approvals[approvalId]?.takeIf { it.state == ApprovalState.OPEN } ?: return false
        approvals[approvalId] = approval.copy(state = ApprovalState.ANSWERED, revision = approval.revision + 1,
            answer = ApprovalAnswerRecord(ApprovalSurface.PHONE, id(), option))
        return true
    }

    fun settled(messageId: String): MessageState? = progress.lastOrNull { it.claimedMessageId == messageId && it.state.settles }?.state

    fun appended(): List<MessageAppend> = checkpoints.filterIsInstance<MessageCheckpoint.MessageAppended>().map { it.message }

    fun threads(): List<ConversationThread> = checkpoints.filterIsInstance<MessageCheckpoint.ThreadUpserted>().map { it.thread }

    override fun heartbeat(beat: HostHeartbeat): JsonObject {
        if (offline) throw java.io.IOException("offline")
        heartbeats += beat
        return buildJsonObject { put("ok", true) }
    }

    /** As an older service answers a companion route. */
    private fun refuseIfOlder() {
        if (!olderService) return
        olderRefusals.incrementAndGet()
        throw HovelStatusException(400, HovelStatusException.UNSUPPORTED_CONTRACT)
    }

    override fun openStream(): HovelEventLines {
        if (offline) throw java.io.IOException("offline")
        refuseIfOlder()
        synchronized(this) { if (streamFailures > 0) { streamFailures--; throw java.io.IOException("refused") } }
        streamsOpened.incrementAndGet()
        return object : HovelEventLines {
            @Volatile var open = true
            override fun lines(): Sequence<String> = generateSequence {
                while (open) { stream.poll(20, TimeUnit.MILLISECONDS)?.let { return@generateSequence it } }
                null
            }
            override fun close() { open = false }
        }
    }

    override fun claim(request: ClaimRequest): ClaimResponse {
        if (offline) throw java.io.IOException("offline")
        claimRequests += request
        refuseIfOlder()
        onClaim()
        return answer(request)
    }

    @Synchronized private fun answer(request: ClaimRequest): ClaimResponse {
        claimStatus?.let { if (it == 409) throw HovelRejectedException(claimCode) else throw HovelStatusException(it) }
        val response = respond(request)
        claimBody?.let { return fromWire(ClaimResponse.serializer(), it) }
        if (response is ClaimResponse.StopRequested) stopAnswers += request.claimId
        return response
    }

    private fun respond(request: ClaimRequest): ClaimResponse {
        if (request.claimId in superseded) throw HovelRejectedException()
        claims[request.claimId]?.let { claim ->
            if (!stopping(claim.message.id)) return ClaimResponse.Claimed(claim)
            if (contract < 2) throw HovelRejectedException(ClaimOutput.STOP_REQUESTED)
            return stopAnswer(claim)
        }
        // A stopped message still in flight holds its conversation. From contract 2 a fresh claim takes it over, whose
        // claim was lost; before it, the fresh claim finds nothing.
        val lost = claims.entries.firstOrNull { (_, claim) -> claim.conversation.id == request.conversationId && stopping(claim.message.id) }
        if (lost != null) {
            if (contract < 2) return ClaimResponse.Empty
            val (lostId, claim) = lost.toPair()
            claims.remove(lostId)
            superseded += lostId
            return stopAnswer(claim.copy(claimId = request.claimId).also { claims[request.claimId] = it; holders[it.message.id] = request.claimId })
        }
        if (request.conversationId in macUpdateRequired) return ClaimResponse.MacUpdateRequired(HovelContract.CURRENT + 1)
        val next = queues[request.conversationId]?.removeFirstOrNull() ?: return ClaimResponse.Empty
        val claim = next.build(request.claimId, next.message)
        claims[request.claimId] = claim
        holders[claim.message.id] = request.claimId
        revisions[claim.message.id] = claim.message.revision
        return ClaimResponse.Claimed(claim)
    }

    /** The person asked to stop [messageId], and it has not settled. */
    private fun stopping(messageId: String) = messageId in stopRequested && settled(messageId) == null

    /** `stop-requested` with [claim]'s message as it stands now: claimed, or running once `running` landed. */
    private fun stopAnswer(claim: ClaimResult): ClaimResponse.StopRequested {
        val id = claim.message.id
        val running = progress.any { it.claimedMessageId == id && it.state == MessageState.RUNNING }
        return ClaimResponse.StopRequested(claim.message.copy(revision = revisions.getValue(id),
            state = if (running) MessageState.RUNNING else MessageState.CLAIMED))
    }

    @Synchronized override fun checkpoint(messageId: String, request: MessageCheckpointRequest) {
        if (offline) throw java.io.IOException("offline")
        refuseIfOlder()
        check(claims[request.claimId]?.message?.id == messageId && request.claimedMessageId == messageId) { "Not this claim" }
        if (refuseCheckpoints) throw HovelRejectedException()
        if (messageId in stopRequested && request.checkpoint !is MessageCheckpoint.ApprovalClosed) {
            throw HovelRejectedException(ClaimOutput.STOP_REQUESTED)
        }
        when (val checkpoint = request.checkpoint) {
            is MessageCheckpoint.ApprovalOpened -> approvals[checkpoint.approval.id] = checkpoint.approval
            is MessageCheckpoint.ApprovalClosed -> {
                val approval = approvals[checkpoint.approvalId] ?: throw HovelStatusException(404)
                // The first answer wins: a close for an approval that already ended is refused.
                if (approval.state != ApprovalState.OPEN) throw HovelRejectedException()
                approvals[checkpoint.approvalId] = when (val outcome = checkpoint.outcome) {
                    is ApprovalOutcome.Answered -> {
                        answers += checkpoint.approvalId to outcome.option
                        approval.copy(state = ApprovalState.ANSWERED, revision = approval.revision + 1,
                            answer = ApprovalAnswerRecord(ApprovalSurface.MAC, hostId, outcome.option))
                    }
                    ApprovalOutcome.Withdrawn -> approval.copy(state = ApprovalState.WITHDRAWN, revision = approval.revision + 1)
                }
            }
            else -> Unit
        }
        checkpoints += request.checkpoint
    }

    override fun delta(messageId: String, request: ReplyDeltaRequest) {
        refuseIfOlder()
        if (messageId in stopRequested) throw HovelRejectedException(ClaimOutput.STOP_REQUESTED)
        deltas += request.delta
    }

    @Synchronized override fun progress(messageId: String, progress: MessageProgress): Long? {
        if (offline) throw java.io.IOException("offline")
        refuseIfOlder()
        if (progress.state == MessageState.RUNNING && refuseRunning) throw HovelRejectedException()
        if (progress.state == MessageState.STOPPED && refuseStops) throw HovelRejectedException()
        if (progress.state == MessageState.RUNNING && messageId in stopRequested) throw HovelRejectedException(ClaimOutput.STOP_REQUESTED)
        progressStatus?.let { throw HovelStatusException(it) }
        // Not the message's current claim: 409, as a claim a takeover replaced gets.
        if (holders[messageId] != progress.claimId) throw HovelRejectedException()
        if (revisions[messageId] != progress.expectedRevision) throw HovelRejectedException()
        this.progress += progress
        return (progress.expectedRevision + 1).also { revisions[messageId] = it }
    }

    @Synchronized override fun saveFolders(projectId: String, save: ProjectFoldersSave): CompanionProject? {
        refuseIfOlder()
        foldersStatus?.let { if (it == 409) throw HovelRejectedException() else throw HovelStatusException(it) }
        projects[projectId]?.let { if (it.revision != save.expectedRevision) throw HovelRejectedException() }
        folderSaves += projectId to save
        return CompanionProject(false, id(), hostId, save.folders, projectId, "Project", save.expectedRevision + 1).also { projects[projectId] = it }
    }

    override fun approval(approvalId: String): AgentApproval {
        refuseIfOlder()
        if (!approvalsReadable) throw java.io.IOException("unreadable")
        return approvals[approvalId] ?: throw HovelStatusException(404)
    }

    override fun project(projectId: String): CompanionProject {
        refuseIfOlder()
        return projects[projectId] ?: throw HovelStatusException(404)
    }

    override fun artifactText(artifactId: String, cursor: Int): ArtifactTextPage {
        refuseIfOlder()
        if (!textsReadable) throw java.io.IOException("unreadable")
        val text = texts[artifactId] ?: throw HovelStatusException(404)
        val end = minOf(text.length, cursor + TEXT_PAGE)
        return ArtifactTextPage(if (end < text.length) end else null, text.substring(cursor, end))
    }

    companion object {
        /** The fake's page size, small enough that a test input spans pages. */
        const val TEXT_PAGE = 1_000
    }
}

/**
 * A runtime the test scripts: [reply] answers each turn (null holds the turn until [release]), streamed
 * as partials in [chunk]-character steps. Every request is kept; a new conversation is opened with a
 * fresh id, and an existing one continues.
 */
internal open class ScriptedRuntime(override val id: RuntimeId, val chunk: Int = 7, var reply: (TurnRequest) -> String? = { "Done." }) : ChatRuntime, WorkRuntime {
    val requests = CopyOnWriteArrayList<TurnRequest>()
    val released = CopyOnWriteArrayList<String>()
    val held = CopyOnWriteArrayList<TurnEvents>()
    val stops = CopyOnWriteArrayList<TurnRequest>()
    @Volatile var failure: ChatFailure? = null
    @Volatile var activity: Activity? = null
    @Volatile var support = ContextFolderSupport.NONE
    override val contextFolders: ContextFolderSupport get() = support
    val started = CountDownLatch(1)

    override fun readiness(profile: String) = Readiness.Ready

    override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
        requests += turn
        started.countDown()
        val conversation = turn.conversation?.id ?: id().also(events::opened)
        events.accepted()
        failure?.let { failure = null; events.failed(it); return TurnHandle {} }
        val text = reply(turn) ?: run { held += events; return TurnHandle { stops += turn } }
        activity?.let(events::activity)
        var at = 0
        while (at < text.length) { at = minOf(text.length, at + chunk); events.partial(text.take(at)) }
        events.completed(text, conversation)
        return TurnHandle { stops += turn }
    }

    override fun summarize(request: SummaryRequest) = "Summary"
    override fun release(lease: String) { released += lease }
    override fun release() = Unit
    override fun close() = Unit
}

/** Providers that are all connected, each with one default model that takes high effort. */
internal class ConnectedProviders(private val offered: Set<RuntimeId> = RuntimeId.entries.toSet()) : ProviderCapability {
    @Volatile var connected = true
    override suspend fun perform(action: ProviderAction, scope: ProviderScope) =
        ProviderStatus(scope.runtimeId, if (connected && scope.runtimeId in offered) ProviderState.CONNECTED else ProviderState.DISCONNECTED)
    override suspend fun list(profile: String) = ProviderList(offered.toList(), emptyList())
    override suspend fun listForClient(args: ProviderListForClientArgs) = list(args.profile)
    override suspend fun models(scope: ProviderScope) =
        ProviderModels(listOf(ProviderModel("Test model", true, "test-model", listOf(ModelEffort.HIGH))), scope.runtimeId)
    override suspend fun usage(scope: ProviderScope) = ProviderUsage(scope.runtimeId, 1_000, ProviderUsageWindow(12.5, 1_800_000_000))
    override suspend fun setup(scope: ProviderScope) = ProviderSetup(scope.runtimeId, ProviderSetupState.READY)
    override suspend fun startLogin(scope: ProviderScope) = ProviderLogin(scope.runtimeId, false)
}

/** The Mac's screen as the test drives it: every dialog shown, answered by [answer] (null leaves it open), and every dismissal. */
internal class Screen(@Volatile var answer: (NativeDialog<*>) -> Int? = { 0 }) : DialogPresenter {
    val shown = CopyOnWriteArrayList<NativeDialog<*>>()
    val open = CopyOnWriteArrayList<(Int) -> Unit>()
    val dismissed = CopyOnWriteArrayList<NativeDialog<*>>()
    override fun present(dialog: NativeDialog<*>, answer: (Int) -> Unit): () -> Unit {
        shown += dialog
        open += answer
        this.answer(dialog)?.let(answer)
        return { dismissed += dialog }
    }
}

/** A temporary folder. */
internal fun folder(prefix: String = "conversation"): Path = Files.createTempDirectory(prefix).toRealPath()

/** Wait for [condition], failing after five seconds. */
internal fun eventually(message: String = "condition not met in time", condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
        check(System.nanoTime() < deadline) { message }
        Thread.sleep(5)
    }
}
