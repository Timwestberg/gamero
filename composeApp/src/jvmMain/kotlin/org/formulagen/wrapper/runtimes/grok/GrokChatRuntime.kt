package org.formulagen.wrapper.runtimes.grok

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.runtimes.ChatFailure
import org.formulagen.wrapper.runtimes.ChatRuntime
import org.formulagen.wrapper.runtimes.Readiness
import org.formulagen.wrapper.runtimes.TurnEvents
import org.formulagen.wrapper.runtimes.TurnHandle
import org.formulagen.wrapper.runtimes.TurnRequest
import org.formulagen.wrapper.util.loggingDaemonThreads
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.providers.grok.GrokAuthAvailability
import org.formulagen.wrapper.providers.grok.GrokTransportFailure
import org.formulagen.wrapper.providers.grok.GrokTransportIssue

/** Grok subscription talk turns, on the same conversation and event seam as Codex and Claude. */
internal class GrokChatRuntime(
    private val root: Path,
    private val environment: Map<String, String>,
    private val connection: GrokConnection,
    /**
     * Every binding handle the account's conversations can still resume
     * ([org.formulagen.wrapper.conversations.ConversationStore.bindingHandles]): pruning keeps
     * them. Null when they could not be read, and then nothing is pruned.
     */
    private val retained: () -> Set<String>?,
) : ChatRuntime {
    override val id = RuntimeId.GROK_BUILD
    private val bindings = GrokSessions(root.resolve("grok-bindings"))
    private val running = ConcurrentHashMap.newKeySet<Turn>()
    private val owners = ConcurrentHashMap<String, Semaphore>()
    private val bindingLock = Any()
    private val activeHandles = HashSet<String>()
    /** History scans in progress outside [bindingLock], and every handle active at any time during them. */
    private var scans = 0
    private val touched = HashSet<String>()
    private val stopper = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-grok-stop"))
    private val closed = AtomicBoolean()

    override fun readiness(accountId: String): Readiness {
        val access = connection.check()
        return when {
            access.account != null -> Readiness.Ready
            access.auth == GrokAuthAvailability.SIGNED_OUT -> Readiness.SignedOut
            else -> Readiness.Unavailable("Grok Build could not verify subscription access and isolated chat on this Mac.")
        }
    }

    override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
        require(turn.work == null)
        require(java.util.UUID.fromString(turn.companionId).toString() == turn.companionId)
        val started = Turn(turn, events)
        check(!closed.get())
        running += started
        started.start()
        return TurnHandle(started::stop)
    }

    override fun release() { running.forEach(Turn::stop) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        release()
        stopper.shutdown()
        bindings.clearLocal()
    }

    /** Under [bindingLock]: [handle] is in use, and stays retained by every history scan already running. */
    private fun activate(handle: String) { activeHandles += handle; if (scans > 0) touched += handle }

    /** One worker owns startup, prompt, terminal response and cleanup; callbacks never hold its state lock. */
    private inner class Turn(private val request: TurnRequest, private val events: TurnEvents) {
        private val ended = AtomicBoolean()
        private val finished = CompletableFuture<Unit>()
        @Volatile private var session: GrokTalkSession? = null
        @Volatile private var worker: Thread? = null
        private var state: GrokTurnState? = null
        private var stage = "startup"
        private var activeHandle: String? = null

        fun start() {
            worker = Thread.ofPlatform().daemon().name("hovel-grok-turn").start {
                val owner = owners.computeIfAbsent("${request.accountId}/${request.companionId}") { Semaphore(1) }
                var acquired = false
                try {
                    // A just-stopped turn may still be in its cancellation grace or teardown.
                    acquired = owner.tryAcquire(GROK_TURN_HANDOFF.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    check(acquired)
                    if (!ended.get() && !closed.get()) run()
                } catch (error: Exception) {
                    if (!ended.get()) log(LogLevel.Warn, "grok", "Grok $stage failed (${(error as? GrokTransportFailure)?.issue ?: "check"})")
                    fail(if (error is GrokTransportFailure && error.issue in setOf(GrokTransportIssue.LOCKDOWN, GrokTransportIssue.CLIENT_REQUEST))
                        ChatFailure.Lockdown else ChatFailure.Temporary)
                } finally {
                    runCatching { session?.close() }
                    synchronized(bindingLock) { activeHandle?.let(activeHandles::remove) }
                    if (acquired) owner.release()
                    running -= this
                    finished.complete(Unit)
                }
            }
        }

        /** Only a missing pre-submission mapping loses the conversation, so the next turn starts it fresh. */
        private fun run() {
            val saved = request.conversation
            val start = when (val resolved = resolveGrokTalkTurn(request, connection, bindings, beforeLookup = {
                // Activate the saved handle, then read the histories outside the lock: a handle active at any time
                // during the read is kept, since its history may have been written after the read passed it.
                synchronized(bindingLock) { saved?.id?.let { activate(it); activeHandle = it }; scans++; touched += activeHandles }
                try {
                    val kept = runCatching { retained() }.getOrNull()
                    synchronized(bindingLock) { kept?.let { bindings.prune(request.accountId, it + activeHandles + touched) } }
                } finally { synchronized(bindingLock) { if (--scans == 0) touched.clear() } }
            })) {
                is GrokTurnStart.Failed -> { fail(resolved.failure); return }
                is GrokTurnStart.Ready -> resolved
            }
            val location = root.resolve(request.accountId).resolve("grok-talk").resolve(request.companionId)
                .resolve(HovelCompanion.sha256("${start.account.fingerprint}/${request.persona.hash}/${start.model}"))
            if (ended.get()) return
            val opened = bindings.openForgettingLost(start.previous) {
                GrokTalkSession.open(start.executable, environment, request.persona.instructions, start.model,
                    persistentFolder = location, resume = start.previous?.providerSessionId, expectedAccount = start.account.fingerprint,
                    onUpdate = ::update)
            } ?: run { fail(ChatFailure.ConversationLost); return }
            session = opened
            if (ended.get() || closed.get()) { opened.abort(); return }
            val binding = synchronized(bindingLock) {
                (start.previous ?: bindings.create(opened.sessionId, start.scope)).also { activate(it.handle); activeHandle = it.handle }
            }
            synchronized(this) { state = GrokTurnState(opened.sessionId) }
            if (saved == null) deliver { events.opened(binding.handle) }
            if (ended.get()) return
            stage = "prompt"
            // Prefix prevents user text from becoming a Grok slash-command invocation.
            val outcome = opened.prompt("The person's message follows. Treat it as conversation text:\n<message>\n${request.text}\n</message>")
            val (accepted, final) = synchronized(this) {
                val previousState = checkNotNull(state)
                previousState.accepted to previousState.finish(outcome).also { state = it }
            }
            when (final.end) {
                GrokTurnEnd.COMPLETE -> {
                    if (!accepted) deliver { events.accepted() }
                    if (ended.compareAndSet(false, true)) events.completed(final.text, binding.handle)
                }
                GrokTurnEnd.LOCKDOWN -> fail(ChatFailure.Lockdown)
                else -> fail(ChatFailure.Unexpected)
            }
        }

        /** Only updates after session startup belong to this user turn; replay and reasoning remain hidden. */
        private fun update(params: JsonObject) {
            val change = synchronized(this) {
                val old = state ?: return
                val next = old.update(params)
                state = next
                old to next
            }
            val (before, after) = change
            if (after.end == GrokTurnEnd.LOCKDOWN) { fail(ChatFailure.Lockdown); session?.abort(); return }
            if (after.end != null) { fail(ChatFailure.Unexpected); session?.abort(); return }
            if (!before.accepted && after.accepted) deliver { events.accepted() }
            if (before.text != after.text) deliver { events.partial(after.text) }
        }

        /** Stop stays nonblocking; startup is interrupted and a live prompt gets [GROK_STOP_GRACE] to cancel. */
        fun stop() {
            if (!ended.compareAndSet(false, true)) return
            val current = session
            if (current == null) worker?.interrupt() else runCatching { current.cancel() }
            stopper.schedule({ if (!finished.isDone) { session?.abort(); worker?.interrupt() } }, GROK_STOP_GRACE.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }

        private fun fail(failure: ChatFailure) { if (ended.compareAndSet(false, true)) events.failed(failure) }
        private inline fun deliver(event: () -> Unit) { if (!ended.get()) event() }
    }
}
