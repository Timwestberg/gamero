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
 * id (a repeated claim id answers the same claim), revisions that refuse a stale progress, checkpoints,
 * deltas, approvals whose first answer wins, folder saves, and a host stream fed by the test.
 */
internal class FakeServer(val hostId: String = id()) : ConversationApi {
    private val queues = ConcurrentHashMap<String, ArrayDeque<Queued>>()
    private val claims = HashMap<String, ClaimResult>()
    val claimRequests = CopyOnWriteArrayList<ClaimRequest>()
    val checkpoints = CopyOnWriteArrayList<MessageCheckpoint>()
    val deltas = CopyOnWriteArrayList<ReplyDelta>()
    val progress = CopyOnWriteArrayList<MessageProgress>()
    val revisions = ConcurrentHashMap<String, Long>()
    val heartbeats = CopyOnWriteArrayList<HostHeartbeat>()
    val approvals = ConcurrentHashMap<String, AgentApproval>()
    val answers = CopyOnWriteArrayList<Pair<String, ApprovalAnswer>>()
    val folderSaves = CopyOnWriteArrayList<Pair<String, ProjectFoldersSave>>()
    val heard = CopyOnWriteArrayList<Pair<String, MessageSend>>()
    @Volatile var macUpdateRequired = emptySet<String>()
    @Volatile var offline = false
    @Volatile var refuseRunning = false
    @Volatile var refuseAnswers = false
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

    override fun openStream(): HovelEventLines {
        if (offline) throw java.io.IOException("offline")
        synchronized(this) { if (streamFailures > 0) { streamFailures--; throw java.io.IOException("refused") } }
        return object : HovelEventLines {
            @Volatile var open = true
            override fun lines(): Sequence<String> = generateSequence {
                while (open) { stream.poll(20, TimeUnit.MILLISECONDS)?.let { return@generateSequence it } }
                null
            }
            override fun close() { open = false }
        }
    }

    @Synchronized override fun claim(request: ClaimRequest): ClaimResponse {
        if (offline) throw java.io.IOException("offline")
        claimRequests += request
        claims[request.claimId]?.let { return ClaimResponse.Claimed(it) }
        if (request.conversationId in macUpdateRequired) return ClaimResponse.MacUpdateRequired(HovelContract.CURRENT + 1)
        val next = queues[request.conversationId]?.removeFirstOrNull() ?: return ClaimResponse.Empty
        val claim = next.build(request.claimId, next.message)
        claims[request.claimId] = claim
        revisions[claim.message.id] = claim.message.revision
        return ClaimResponse.Claimed(claim)
    }

    @Synchronized override fun checkpoint(messageId: String, request: MessageCheckpointRequest) {
        if (offline) throw java.io.IOException("offline")
        check(claims[request.claimId]?.message?.id == messageId && request.claimedMessageId == messageId) { "Not this claim" }
        when (val checkpoint = request.checkpoint) {
            is MessageCheckpoint.ApprovalOpened -> approvals[checkpoint.approval.id] = checkpoint.approval
            is MessageCheckpoint.ApprovalClosed -> {
                val approval = approvals.getValue(checkpoint.approvalId)
                if (approval.state == ApprovalState.ANSWERED) throw HovelRejectedException()
                approvals[checkpoint.approvalId] = approval.copy(state = ApprovalState.WITHDRAWN, revision = approval.revision + 1)
            }
            else -> Unit
        }
        checkpoints += request.checkpoint
    }

    override fun delta(messageId: String, request: ReplyDeltaRequest) { deltas += request.delta }

    @Synchronized override fun progress(messageId: String, progress: MessageProgress): Long? {
        if (offline) throw java.io.IOException("offline")
        if (progress.state == MessageState.RUNNING && refuseRunning) throw HovelRejectedException()
        if (revisions[messageId] != progress.expectedRevision) throw HovelRejectedException()
        this.progress += progress
        return (progress.expectedRevision + 1).also { revisions[messageId] = it }
    }

    @Synchronized override fun saveFolders(projectId: String, save: ProjectFoldersSave): CompanionProject? {
        foldersStatus?.let { if (it == 409) throw HovelRejectedException() else throw HovelStatusException(it) }
        folderSaves += projectId to save
        return CompanionProject(false, id(), hostId, save.folders, projectId, "Project", save.expectedRevision + 1)
    }

    @Synchronized override fun answer(approvalId: String, answer: ApprovalAnswer): AgentApproval {
        if (offline) throw java.io.IOException("offline")
        answers += approvalId to answer
        val approval = approvals[approvalId] ?: throw HovelStatusException(404)
        if (refuseAnswers || approval.state != ApprovalState.OPEN || approval.revision != answer.expectedRevision) throw HovelRejectedException()
        return approval.copy(state = ApprovalState.ANSWERED, revision = approval.revision + 1,
            answer = ApprovalAnswerRecord(ApprovalSurface.MAC, hostId, answer.option)).also { approvals[approvalId] = it }
    }

    override fun approval(approvalId: String): AgentApproval = approvals[approvalId] ?: throw HovelStatusException(404)

    override fun sendHeard(conversationId: String, send: MessageSend): ConversationMessage {
        heard += conversationId to send
        return ConversationMessage(MessageAuthor.USER, null, conversationId, AT, AT, null, send.id, emptyList(), null, 1, MessageState.QUEUED, send.text, send.threadId)
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
