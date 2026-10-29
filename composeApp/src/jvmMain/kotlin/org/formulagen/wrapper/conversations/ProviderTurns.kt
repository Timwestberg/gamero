package org.formulagen.wrapper.conversations

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.brain.ProviderFailure
import org.formulagen.wrapper.brain.model
import org.formulagen.wrapper.brain.roleIssues
import org.formulagen.wrapper.chat.*

/** The person stopped the message (or Hovel is quitting), or a stage dialog's Stop ended the run. */
internal class StoppedException : RuntimeException("Stopped")

/**
 * Who one claimed message's provider turns run for: the account ([profile]), the companion, the project's
 * [folders], and the message's [cancel], which ends any turn or wait.
 */
internal class TurnCall(
    val profile: String,
    val companionId: String,
    val companion: String,
    val folders: ProjectFolders,
    val cancel: CancelSignal,
) {
    /** Throws once the message was stopped. */
    fun live() { if (cancel.isCancelled) throw StoppedException() }
}

/**
 * The provider turns of a conversation's roles: the coordinator's and talk specialists' turns on [talk] runtimes,
 * and working specialists' on [work] runtimes, each on its thread's lease. A talk runtime takes [talkPermits]
 * turns at a time, across every conversation (Codex holds one turn per process). Readiness and model checks use
 * the brain's own rules ([roleIssues], `BrainBinding.model`), and a problem fails as the provider's refusal would.
 */
internal class ProviderTurns(
    talk: List<ChatRuntime>,
    work: List<WorkRuntime>,
    private val providers: ProviderCapability,
    private val approvals: RemoteApprovals,
    talkPermits: (RuntimeId) -> Int = { if (it == RuntimeId.CODEX) 1 else 4 },
    private val turnTimeout: Duration = 2.hours,
) {
    private val talk = talk.associateBy { it.id }
    private val work = work.associateBy { it.id }
    private val gates = RuntimeId.entries.associateWith { Semaphore(talkPermits(it), true) }

    /** The runtimes this Mac lets read context folders: a project lists them as `readableBy`. */
    fun readableBy(): List<RuntimeId> = RuntimeId.entries.filter { work[it]?.contextFolders?.readable == true }

    /**
     * A talk turn of [role] on [persona]: the coordinator's (in its conversation context [context]) or a talk
     * specialist's (in its thread). [conversation] is the provider conversation to continue, [opened] keeps a new
     * one, and [stream] gets the reply as it is written.
     */
    fun talk(call: TurnCall, role: BrainRoleSnapshot, persona: Persona, input: String, conversation: NativeConversation?, context: String,
        coordinator: Boolean, stream: ReplyStream, opened: (NativeConversation) -> Unit): Pair<String, Activity?> {
        val runtime = ready(call, talk[role.binding.runtimeId], role, context, coordinator)
        val request = TurnRequest(call.profile, call.companionId, persona, role.binding.modelId, conversation, null, input, null,
            role.binding.effort)
        return gated(call, runtime.id) { turn(call, request, role, coordinator, context, stream, runtime::startTurn, opened) }
    }

    /**
     * A working specialist's turn in [threadId], on that thread's lease: the project's folders as its runtime may
     * be given them, its permission requests relayed through [scope], and only its own processes ended after it.
     */
    fun work(call: TurnCall, threadId: String, role: BrainRoleSnapshot, persona: Persona, input: String, conversation: NativeConversation?,
        scope: ApprovalScope, stream: ReplyStream, opened: (NativeConversation) -> Unit): Pair<String, Activity?> {
        val worker = ready(call, work[role.binding.runtimeId], role, threadId, coordinator = false)
        val folders = WorkScope(call.folders.forRuntime(worker.contextFolders), threadId, call.companion, role.binding.member())
        val request = TurnRequest(call.profile, call.companionId, persona, role.binding.modelId, conversation, null, input, folders,
            role.binding.effort)
        val relay = approvals.register(threadId, scope)
        try {
            return turn(call, request, role, coordinator = false, context = threadId, stream = stream, start = worker::startTurn,
                opened = opened)
        } finally {
            relay.close()
            worker.release(threadId)
        }
    }

    /**
     * [role] with the model its runtime actually runs for its binding; an unavailable model or effort fails as the
     * provider's refusal.
     */
    fun resolved(call: TurnCall, role: BrainRoleSnapshot): BrainRoleSnapshot {
        val unavailable = ProviderFailure(ChatFailure.ModelUnavailable, role.binding.runtimeId, role.roleId, false)
        val scope = ProviderScope(call.profile, role.binding.runtimeId)
        val models = runCatching { runBlocking { providers.models(scope).models } }.getOrNull()
            ?: throw unavailable
        val model = role.binding.model(models) ?: throw unavailable
        if (role.binding.effort != null && model.effortLevels?.contains(role.binding.effort) != true) throw unavailable
        return role.copy(binding = role.binding.copy(modelId = model.modelId))
    }

    /** Why [role] cannot run on this Mac now ([roleIssues]): a talk role also needs its talk runtime here. */
    fun issues(call: TurnCall, role: BrainRoleSnapshot): List<BrainIssue> {
        val binding = role.binding
        val scope = ProviderScope(call.profile, binding.runtimeId)
        val status = runCatching { runBlocking { providers.perform(ProviderAction.STATUS, scope) }.state }.getOrNull()
        val signedIn = status == ProviderState.CONNECTED
        val offered = role.mode == MemberMode.WORK || binding.runtimeId in talk
        val models = runCatching { runBlocking { providers.models(scope).models } }.getOrNull()
        val canWork = binding.runtimeId in work
        return roleIssues(role.roleId, binding, role.mode, signedIn && offered, models, canWork, call.folders.working != null)
    }

    /** [action] holding one of [runtime]'s talk turns; Stop ends the wait. */
    private fun <T> gated(call: TurnCall, runtime: RuntimeId, action: () -> T): T {
        val gate = gates.getValue(runtime)
        while (!gate.tryAcquire(50, TimeUnit.MILLISECONDS)) call.live()
        try { return action() } finally { gate.release() }
    }

    /** [runtime] when it can take [role]'s turn now; otherwise the failure its readiness names (signed out, or unavailable). */
    private fun <R : Any> ready(call: TurnCall, runtime: R?, role: BrainRoleSnapshot, context: String, coordinator: Boolean): R {
        val readiness = when (runtime) {
            is ChatRuntime -> runtime.readiness(call.profile)
            is WorkRuntime -> runtime.readiness(call.profile)
            else -> Readiness.Unavailable("Not offered on this Mac")
        }
        if (runtime == null || readiness != Readiness.Ready) {
            val failure = if (readiness == Readiness.SignedOut) ChatFailure.SignedOut else ChatFailure.Temporary
            throw ProviderFailure(failure, role.binding.runtimeId, context, coordinator)
        }
        return runtime
    }

    /** One provider turn, bound to its message: Stop ends it, and nothing it reports after that is used. */
    private fun turn(call: TurnCall, request: TurnRequest, role: BrainRoleSnapshot, coordinator: Boolean, context: String,
        stream: ReplyStream, start: (TurnRequest, TurnEvents) -> TurnHandle, opened: (NativeConversation) -> Unit,
    ): Pair<String, Activity?> {
        call.live()
        val future = CompletableFuture<Pair<String, Activity?>>()
        val latest = AtomicReference<Activity?>()
        val events = object : TurnEvents {
            override fun opened(conversation: String) {
                if (!future.isDone) runCatching { opened(NativeConversation(conversation, request.persona.hash, playerLogin = true)) }
                    .onFailure { future.completeExceptionally(it) }
            }
            override fun accepted() {}
            override fun partial(text: String) { if (!future.isDone) runCatching { stream.partial(text) } }
            override fun completed(text: String, conversation: String) {
                if (future.isDone) return
                opened(conversation)
                future.complete(text to latest.get())
            }
            override fun failed(failure: ChatFailure) {
                future.completeExceptionally(ProviderFailure(failure, role.binding.runtimeId, context, coordinator))
            }
            override fun activity(activity: Activity) { latest.set(activity) }
        }
        val stopping = call.cancel.onCancel { future.completeExceptionally(InterruptedException()) }
        var handle: TurnHandle? = null
        try {
            handle = start(request, events)
            return try { future.get(turnTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
            catch (wrapped: ExecutionException) { throw wrapped.cause ?: wrapped }
        } finally {
            stopping.close()
            if (!future.isDone || future.isCompletedExceptionally) handle?.stop()
        }
    }
}
