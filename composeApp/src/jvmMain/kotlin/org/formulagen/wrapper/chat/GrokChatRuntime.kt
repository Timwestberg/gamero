package org.formulagen.wrapper.chat

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.chat.grok.*
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/** Grok subscription chat using the same conversation, handover and event seam as Codex and Claude. */
internal class GrokChatRuntime(
    private val root: Path,
    private val environment: Map<String, String>,
    private val connection: GrokConnection,
) : ChatRuntime {
    override val id = RuntimeId.GROK_BUILD
    private val bindings = GrokSessions(root.resolve("grok-bindings"))
    private val running = ConcurrentHashMap.newKeySet<Turn>()
    private val owners = ConcurrentHashMap<String, Semaphore>()
    private val summaries = ConcurrentHashMap.newKeySet<Thread>()
    private val bindingLock = Any()
    private val activeHandles = HashSet<String>()
    private val stopper = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-grok-stop"))
    private val closed = AtomicBoolean()

    override fun readiness(profile: String): Readiness {
        val access = connection.check()
        return when {
            access.account != null -> Readiness.Ready
            access.auth == GrokAuthAvailability.SIGNED_OUT -> Readiness.SignedOut
            else -> Readiness.Unavailable("Grok Build could not verify subscription access and isolated chat on this Mac.")
        }
    }

    override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
        require(turn.work == null)
        require(org.formulagen.wrapper.bridge.SharedRules.isProfileId(turn.profile))
        require(java.util.UUID.fromString(turn.companionId).toString() == turn.companionId)
        val started = Turn(turn, events)
        check(!closed.get())
        running += started
        started.start()
        return TurnHandle(started::stop)
    }

    /** Summaries use a disposable provider session, without changing a companion's saved binding. */
    override fun summarize(request: SummaryRequest): String {
        check(!closed.get())
        val thread = Thread.currentThread()
        summaries += thread
        try { return summary(request) } finally { summaries -= thread }
    }

    private fun summary(request: SummaryRequest): String {
        val budget = GrokBudget(request.timeout)
        val access = connection.check(fresh = true)
        val account = access.account ?: error("Grok subscription unavailable")
        val executable = access.executable ?: error("Grok unavailable")
        val selected = request.model?.takeUnless { it == RUNTIME_DEFAULT_MODEL } ?: account.defaultModel
        val state = java.util.concurrent.atomic.AtomicReference<GrokTurnState?>()
        GrokTalkSession.open(executable, environment, request.persona.instructions, selected, budget.remaining(),
            expectedAccount = account.fingerprint, onUpdate = { update -> state.updateAndGet { it?.update(update) } }).use { session ->
            check(!closed.get())
            state.set(GrokTurnState(session.sessionId))
            val outcome = session.prompt(summaryInstruction(request.transcript), budget.remaining())
            val result = checkNotNull(state.get()).finish(outcome)
            check(result.end == GrokTurnEnd.COMPLETE)
            return boundSummary(result.text)
        }
    }

    override fun release() { running.forEach(Turn::stop); summaries.forEach(Thread::interrupt) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        release()
        stopper.shutdown()
        bindings.clearLocal()
    }

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
                val owner = owners.computeIfAbsent("${request.profile}/${request.companionId}") { Semaphore(1) }
                var acquired = false
                try {
                    acquired = owner.tryAcquire(5, TimeUnit.SECONDS)
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

        /** Only missing pre-submission mappings can request DesktopChat's one context-restoration retry. */
        private fun run() {
            val saved = request.conversation
            if (saved != null && saved.personaHash != request.persona.hash) { fail(ChatFailure.ConversationLost); return }
            val access = connection.check(fresh = true)
            val account = access.account ?: run {
                fail(if (access.auth == GrokAuthAvailability.SIGNED_OUT) ChatFailure.SignedOut else ChatFailure.Temporary); return
            }
            val executable = access.executable ?: run { fail(ChatFailure.Temporary); return }
            val model = request.model?.takeUnless { it == RUNTIME_DEFAULT_MODEL } ?: account.defaultModel
            if (account.models.none { it.modelId == model }) { fail(ChatFailure.ModelUnavailable); return }
            val scope = GrokSessionScope(request.profile, request.companionId, GrokMode.TALK, model, account.fingerprint)
            scope.validate()
            synchronized(bindingLock) {
                saved?.id?.let { activeHandles += it; activeHandle = it }
                grokHistoryHandles(root, request.profile)?.let { bindings.prune(request.profile, it + activeHandles) }
            }
            val previous = saved?.let {
                when (val lookup = bindings.find(it.id, scope)) {
                    is GrokSessionLookup.Found -> lookup.binding
                    GrokSessionLookup.Missing, GrokSessionLookup.ModelChanged -> { fail(ChatFailure.ConversationLost); return }
                    else -> { fail(ChatFailure.Unexpected); return }
                }
            }
            val location = root.resolve(request.profile).resolve("grok-talk").resolve(request.companionId)
                .resolve(HovelCompanion.sha256("${account.fingerprint}/${request.persona.hash}/$model"))
            if (ended.get()) return
            val opened = try {
                GrokTalkSession.open(executable, environment, request.persona.instructions, model,
                    persistentFolder = location, resume = previous?.providerSessionId, expectedAccount = account.fingerprint,
                    onUpdate = ::update)
            } catch (_: GrokSessionLost) {
                // Grok refused the saved session before any prompt: start fresh rather than repeat the failing load.
                previous?.let { bindings.forget(it.handle) }
                fail(ChatFailure.ConversationLost); return
            }
            session = opened
            if (ended.get() || closed.get()) { opened.abort(); return }
            val binding = synchronized(bindingLock) {
                (previous ?: bindings.create(opened.sessionId, scope)).also { activeHandles += it.handle; activeHandle = it.handle }
            }
            synchronized(this) { state = GrokTurnState(opened.sessionId) }
            if (saved == null) deliver { events.opened(binding.handle) }
            if (ended.get()) return
            stage = "prompt"
            // Prefix prevents user text from becoming a Grok slash-command invocation.
            val outcome = opened.prompt("The person's message follows. Treat it as conversation text:\n<message>\n${request.input()}\n</message>")
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

        /** Stop stays nonblocking; startup is interrupted and a live prompt gets a five-second cancellation grace. */
        fun stop() {
            if (!ended.compareAndSet(false, true)) return
            val current = session
            if (current == null) worker?.interrupt() else runCatching { current.cancel() }
            stopper.schedule({ if (!finished.isDone) { session?.abort(); worker?.interrupt() } }, 5, TimeUnit.SECONDS)
        }

        private fun fail(failure: ChatFailure) { if (ended.compareAndSet(false, true)) events.failed(failure) }
        private inline fun deliver(event: () -> Unit) { if (!ended.get()) event() }
    }
}

/** Collect all retained handles or skip cleanup entirely on an unreadable, unfamiliar or oversized directory. */
internal fun grokHistoryHandles(root: Path, profile: String): Set<String>? {
    require(org.formulagen.wrapper.bridge.SharedRules.isProfileId(profile))
    val directory = root.resolve(profile).resolve("companion-chats")
    if (java.nio.file.Files.notExists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return emptySet()
    if (!java.nio.file.Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
    return runCatching {
        val retained = HashSet<String>()
        java.nio.file.Files.newDirectoryStream(directory).use { entries ->
            var count = 0
            for (file in entries) {
                check(++count <= 256)
                check(java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                val name = file.fileName.toString()
                check(name.endsWith(".json") && java.util.UUID.fromString(name.removeSuffix(".json")).toString() == name.removeSuffix(".json"))
                val record = ChatHistory(file).read()
                listOfNotNull(record.conversations[RuntimeId.GROK_BUILD], record.workConversations[RuntimeId.GROK_BUILD]).forEach { retained += it.id }
            }
        }
        retained
    }.getOrNull()
}
