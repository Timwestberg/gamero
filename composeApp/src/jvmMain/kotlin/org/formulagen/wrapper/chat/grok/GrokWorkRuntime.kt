package org.formulagen.wrapper.chat.grok

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
import org.formulagen.wrapper.approvals.displayPath
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.Readiness
import org.formulagen.wrapper.chat.TurnEvents
import org.formulagen.wrapper.chat.TurnHandle
import org.formulagen.wrapper.chat.TurnRequest
import org.formulagen.wrapper.chat.WorkRuntime
import org.formulagen.wrapper.chat.companionConversationIds
import org.formulagen.wrapper.chat.loggingDaemonThreads
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.process.readLoginShellPath
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.providers.grok.GrokAuthAvailability
import org.formulagen.wrapper.providers.grok.GrokCleanupUnconfirmed
import org.formulagen.wrapper.providers.grok.GrokTransportFailure
import org.formulagen.wrapper.providers.grok.GrokWorkAuthUnpinned
import org.formulagen.wrapper.providers.grok.GrokWorkLaunch
import org.formulagen.wrapper.providers.grok.grokWorkConfig
import org.formulagen.wrapper.providers.grok.grokWorkEnvironment
import org.formulagen.wrapper.util.shortened

/** Grok project work through the existing native folder, lease, history, activity and approval seams. */
internal class GrokWorkRuntime(
    private val root: Path,
    private val environment: Map<String, String>,
    private val connection: GrokConnection,
    private val broker: ApprovalBroker,
    private val trust: GrokWorkTrust = GrokWorkTrust { _, _, _ -> false },
    /** The player's login-shell `PATH` for the working child ([grokWorkEnvironment]); null keeps [environment]'s. */
    private val loginPath: () -> String? = { loginShellPath.value },
    /**
     * Handles of a profile's work bindings that live outside team chat's history, such as a conversation
     * thread's ([org.formulagen.wrapper.conversations.ConversationStore.bindingHandles]); null when they
     * could not be read, and then nothing is pruned.
     */
    private val retained: (profile: String) -> Set<String>? = { emptySet() },
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
        } catch (error: Exception) {
            // Hovel never writes the player's Grok configuration: the notice says exactly what to add, and what else it changes.
            Readiness.Unavailable(if (error is GrokWorkAuthUnpinned || error is java.nio.file.NoSuchFileException)
                grokWorkAuthNotice(grokWorkConfig(environment)) else GROK_WORK_CONFIG_UNSUPPORTED)
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

    /** Stop [lease]'s turns; other leases keep running. */
    override fun release(lease: String) {
        running.filter { it.lease == lease }.forEach(Turn::stop)
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
        val lease: String get() = work.lease
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
                    // A just-stopped turn may still be in its cancellation grace or teardown.
                    acquired = owner.tryAcquire(GROK_TURN_HANDOFF.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    check(acquired)
                    check(!cleanupFailed.get())
                    if (active()) run()
                } catch (error: Exception) {
                    // A failed startup that could not reap its process quarantines this runtime like a failed close.
                    if (error is GrokCleanupUnconfirmed) quarantine()
                    if (active()) log(LogLevel.Warn, "grok", "Grok work $stage failed (${(error as? GrokTransportFailure)?.issue ?: "check"})")
                    fail(when (error) {
                        is GrokWorkTrustDeclined -> ChatFailure.ProjectTrustRequired
                        is GrokWorkTrustTooBroad -> ChatFailure.ProjectTrustTooBroad
                        else -> ChatFailure.Temporary
                    })
                } finally {
                    approvals?.close()
                    try { session?.close() } catch (_: Exception) { quarantine() }
                    if (acquired) owner.release()
                    running -= this
                    finished.complete(Unit)
                }
            }
        }

        /** Resume only the same account, persona, model and canonical project; never replay an ambiguous prompt. */
        private fun run() {
            val saved = request.conversation
            // Retain the saved handle while pruning; it is looked up next.
            val start = when (val resolved = resolveGrokWorkTurn(request, connection, bindings, beforeLookup = {
                retainedGrokHandles(root, request.profile, retained)
                    ?.let { bindings.prune(request.profile, it + listOfNotNull(saved?.id)) }
            })) {
                is GrokTurnStart.Failed -> { fail(resolved.failure); return }
                is GrokTurnStart.Ready -> resolved
            }
            if (!active()) return
            val opened = bindings.openForgettingLost(start.previous) {
                GrokWorkSession.open(start.executable, environment, start.folder, request.persona.instructions, start.model,
                    start.account.fingerprint, start.previous?.providerSessionId, ::update, confirmTrust = { selected, root ->
                        active() && trust.ask(selected, root, startupCancel) && active()
                    }, loginPath = loginPath()) { params, respond ->
                    val relay = approvals
                    if (active() && relay != null) relay.receive(params, respond)
                    else respond(cancelledGrokPermission())
                }
            } ?: run { fail(ChatFailure.ConversationLost); return }
            session = opened
            if (!active()) { opened.abort(); return }
            val binding = start.previous ?: bindings.create(opened.sessionId, start.scope)
            synchronized(this) { activity = GrokWorkActivity(opened.sessionId, start.folder) }
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

        /** Invalidate approvals first; allow [GROK_STOP_GRACE] for ACP cancellation before forcing local cleanup. */
        fun stop() {
            if (!ended.compareAndSet(false, true)) return
            startupCancel.cancel()
            approvals?.close()
            val current = session
            if (current == null) worker?.interrupt() else runCatching { current.cancel() }
            runCatching { stopper.schedule({ if (!finished.isDone) { session?.abort(); worker?.interrupt() } },
                GROK_STOP_GRACE.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
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

    /** Disable further work after an unconfirmed cleanup: a process from this runtime may still be running. */
    private fun quarantine() {
        if (cleanupFailed.compareAndSet(false, true)) log(LogLevel.Warn, "grok", "Grok work cleanup was not confirmed; further work is disabled")
    }

    private companion object {
        /** The player's login-shell `PATH`, read once: a Mac app started from Finder has only the system folders. */
        val loginShellPath = lazy { readLoginShellPath(System.getenv()) }
    }
}

/** The manual setting Grok work needs, with its effect outside Hovel; the file's path goes last, so only it can be cut. */
internal fun grokWorkAuthNotice(config: Path): String = ("Grok work needs Grok Build to use only your account sign-in. " +
    "Add [auth] with preferred_method = \"oidc\" to your Grok config yourself. " +
    "This also changes how Grok Build signs in outside Hovel. File: ${displayPath(config)}").shortened(ChatLimits.NOTICE)

internal const val GROK_WORK_CONFIG_UNSUPPORTED = "Grok work can't use your Grok config: it has settings Hovel has not " +
    "accepted for project work, such as custom models, endpoints, relays or command prefixes."

/**
 * Every Grok handle [profile] still uses: team chat's saved conversations and the [retained] ones kept
 * elsewhere, such as conversation threads. Null when either could not be read, so nothing is pruned
 * on a partial view.
 */
internal fun retainedGrokHandles(root: Path, profile: String, retained: (String) -> Set<String>?): Set<String>? {
    val chat = companionConversationIds(root, profile, RuntimeId.GROK_BUILD) ?: return null
    val elsewhere = runCatching { retained(profile) }.getOrNull() ?: return null
    return chat + elsewhere
}
