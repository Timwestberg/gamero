package org.formulagen.wrapper.chat

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalBroker
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.chat.grok.*
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/** Grok project work through the existing native folder, lease, history, activity and approval seams. */
internal class GrokWorkRuntime(
    private val root: Path,
    private val environment: Map<String, String>,
    private val connection: GrokConnection,
    private val broker: ApprovalBroker,
    private val trust: GrokWorkTrust = GrokWorkTrust { _, _, _ -> false },
) : WorkRuntime {
    override val id = RuntimeId.GROK_BUILD
    private val bindings = GrokSessions(root.resolve("grok-work-bindings"))
    private val running = ConcurrentHashMap.newKeySet<Turn>()
    private val owner = Semaphore(1)
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val cleanupFailed = AtomicBoolean()
    private val lifecycle = Any()
    private val stopper = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-grok-work-stop"))

    /** Account access is shared with chat; project-specific configuration is checked before every turn. */
    override fun readiness(profile: String): Readiness {
        if (cleanupFailed.get()) return Readiness.Unavailable("Grok work cleanup could not be confirmed. Restart Hovel before continuing.")
        val access = connection.check()
        if (access.auth == GrokAuthAvailability.SIGNED_OUT) return Readiness.SignedOut
        val executable = access.executable
        if (access.account == null || executable == null) return Readiness.Unavailable("Grok could not verify subscription access.")
        return try {
            val home = Path.of(environment["HOME"] ?: System.getProperty("user.home"))
            GrokWorkLaunch.create(executable, environment, home, "Readiness check.").use { }
            Readiness.Ready
        } catch (_: Exception) {
            Readiness.Unavailable("Grok work requires an OIDC auth preference and supported local configuration. See Manage subscriptions.")
        }
    }

    /** Register before starting the worker so release cannot miss a racing startup. */
    override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
        require(turn.work != null && turn.work.member.runtimeId == id && turn.work.folder.isAbsolute)
        require(SharedRules.isProfileId(turn.profile))
        require(java.util.UUID.fromString(turn.companionId).toString() == turn.companionId)
        val started = synchronized(lifecycle) {
            check(!closed.get() && !cleanupFailed.get())
            Turn(turn, events, generation.get()).also { running += it; it.start() }
        }
        return TurnHandle(started::stop)
    }

    /** Stop every current lease immediately, including workers that are still authenticating. */
    override fun release() {
        val turns = synchronized(lifecycle) { generation.incrementAndGet(); running.toList() }
        turns.forEach(Turn::stop)
    }

    /** UI teardown begins bounded asynchronous cleanup without waiting on Grok or a dialog. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        release(); stopper.shutdown(); bindings.clearLocal()
    }

    /** One worker owns startup, prompt, native approvals and reaping; no callback runs under its lock. */
    private inner class Turn(private val request: TurnRequest, private val events: TurnEvents, private val epoch: Long) {
        private val ended = AtomicBoolean()
        private val finished = CompletableFuture<Unit>()
        private val work = checkNotNull(request.work)
        private val startupCancel = CancelSignal()
        @Volatile private var worker: Thread? = null
        @Volatile private var session: GrokWorkSession? = null
        @Volatile private var approvals: GrokApprovals? = null
        private var activity: GrokWorkActivity? = null
        private var stage = "startup"

        /** Serialize cleanup as well as prompts, preventing a replacement turn from overlapping stale tools. */
        fun start() {
            worker = Thread.ofPlatform().daemon().name("hovel-grok-work").start {
                var acquired = false
                try {
                    acquired = owner.tryAcquire(15, TimeUnit.SECONDS)
                    check(acquired)
                    check(!cleanupFailed.get())
                    if (active()) run()
                } catch (error: Exception) {
                    if (active()) log(LogLevel.Warn, "grok", "Grok work $stage failed (${(error as? GrokTransportFailure)?.issue ?: "check"})")
                    fail(when (error) {
                        is GrokWorkTrustDeclined -> ChatFailure.ProjectTrustRequired
                        is GrokWorkTrustTooBroad -> ChatFailure.ProjectTrustTooBroad
                        else -> ChatFailure.Temporary
                    })
                } finally {
                    approvals?.close()
                    try { session?.close() } catch (_: Exception) {
                        cleanupFailed.set(true)
                        log(LogLevel.Warn, "grok", "Grok work cleanup was not confirmed; further work is disabled")
                    }
                    if (acquired) owner.release()
                    running -= this
                    finished.complete(Unit)
                }
            }
        }

        /** Resume only the same account, persona, model and canonical project; never replay an ambiguous prompt. */
        private fun run() {
            val saved = request.conversation
            if (saved != null && saved.personaHash != request.persona.hash) { fail(ChatFailure.ConversationLost); return }
            val access = connection.check(fresh = true)
            val account = access.account ?: run {
                fail(if (access.auth == GrokAuthAvailability.SIGNED_OUT) ChatFailure.SignedOut else ChatFailure.Temporary); return
            }
            val executable = access.executable ?: run { fail(ChatFailure.Temporary); return }
            val model = request.model?.takeUnless { it == SharedRules.RUNTIME_DEFAULT_MODEL } ?: account.defaultModel
            if (account.models.none { it.modelId == model }) { fail(ChatFailure.ModelUnavailable); return }
            val folder = work.folder.toRealPath()
            val scope = GrokSessionScope(request.profile, request.companionId, GrokMode.WORK, model,
                account.fingerprint, HovelCompanion.sha256(folder.toString()))
            scope.validate()
            grokHistoryHandles(root, request.profile)?.let { bindings.prune(request.profile, it + listOfNotNull(saved?.id)) }
            val previous = saved?.let {
                when (val lookup = bindings.find(it.id, scope)) {
                    is GrokSessionLookup.Found -> lookup.binding
                    GrokSessionLookup.Missing, GrokSessionLookup.ModelChanged -> { fail(ChatFailure.ConversationLost); return }
                    else -> {
                        log(LogLevel.Warn, "grok", "Grok work binding could not be resumed (${lookup::class.simpleName})")
                        fail(ChatFailure.Unexpected); return
                    }
                }
            }
            if (!active()) return
            val opened = GrokWorkSession.open(executable, environment, folder, request.persona.instructions, model,
                account.fingerprint, previous?.providerSessionId, ::update, confirmTrust = { selected, root ->
                    active() && trust.ask(selected, root, startupCancel) && active()
                }) { params, respond ->
                val relay = approvals
                if (active() && relay != null) relay.receive(params, respond)
                else respond(cancelledGrokPermission())
            }
            session = opened
            if (!active()) { opened.abort(); return }
            val binding = previous ?: bindings.create(opened.sessionId, scope)
            synchronized(this) { activity = GrokWorkActivity(opened.sessionId, folder) }
            approvals = GrokApprovals(opened.sessionId, work, events, broker)
            if (!active()) { approvals?.close(); opened.abort(); return }
            if (saved == null) deliver { events.opened(binding.handle) }
            if (!active()) return
            stage = "prompt"
            val result = opened.prompt("The person's project request follows:\n<message>\n${request.input()}\n</message>")
            approvals?.close()
            val (accepted, final) = synchronized(this) {
                val current = checkNotNull(activity)
                current.state.accepted to current.finish(result)
            }
            opened.close()
            session = null
            val denied = final.end == GrokTurnEnd.CANCELLED && approvals?.wasDenied == true
            if (final.end == GrokTurnEnd.COMPLETE || denied) {
                if (!accepted) deliver { events.accepted() }
                if (active() && ended.compareAndSet(false, true)) events.completed(
                    if (denied) final.text + "\n\nWork stopped because an operation was not approved." else final.text, binding.handle)
            } else {
                log(LogLevel.Warn, "grok", "Grok work ended without completion (${final.end})")
                fail(ChatFailure.Unexpected)
            }
        }

        /** Session replay is ignored until startup finishes; generation checks drop late callbacks. */
        private fun update(params: JsonObject) {
            if (!active()) return
            val (before, after, steps) = synchronized(this) {
                val current = activity ?: return
                val before = current.state
                val steps = current.update(params)
                Triple(before, current.state, steps)
            }
            if (after.end != null) {
                log(LogLevel.Warn, "grok", "Grok work received an invalid update (${after.end})")
                fail(ChatFailure.Unexpected); session?.abort(); return
            }
            if (!before.accepted && after.accepted) deliver { events.accepted() }
            if (before.text != after.text) deliver { events.partial(after.text) }
            if (steps != null) deliver { events.activity(steps) }
        }

        /** Invalidate approvals first; allow five seconds for ACP cancellation before forcing local cleanup. */
        fun stop() {
            if (!ended.compareAndSet(false, true)) return
            startupCancel.cancel()
            approvals?.close()
            val current = session
            if (current == null) worker?.interrupt() else runCatching { current.cancel() }
            runCatching { stopper.schedule({ if (!finished.isDone) { session?.abort(); worker?.interrupt() } }, 5, TimeUnit.SECONDS) }
        }

        /** A lease invalidation is final even if a worker was between registration and startup. */
        private fun active() = !ended.get() && !closed.get() && generation.get() == epoch

        /** Failure is emitted once; pending approval requests cannot outlive it. */
        private fun fail(failure: ChatFailure) {
            if (active() && ended.compareAndSet(false, true)) { approvals?.close(); events.failed(failure) }
        }

        /** DesktopChat independently rejects callbacks that were already in flight when Stop was pressed. */
        private inline fun deliver(event: () -> Unit) { if (active()) event() }
    }
}
