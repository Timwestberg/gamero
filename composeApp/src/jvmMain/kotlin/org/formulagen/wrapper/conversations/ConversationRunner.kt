package org.formulagen.wrapper.conversations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.chat.loggingDaemonThreads
import org.formulagen.wrapper.hovel.HovelEventLines
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.hovel.HovelStatusException
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/** Runs one claimed message to the state that settles it. [CoordinatorEngine] is the companion brain's. */
internal fun interface MessageEngine {
    /**
     * Run [claim], sending through [output]. [cancel] ends the run: the person's Stop, or Hovel quitting when
     * [closing] says so, and then unfinished work pauses for review instead of ending. Throws
     * `HovelRejectedException` when the server refuses the claim's work.
     */
    fun run(claim: ClaimResult, output: ClaimOutput, cancel: CancelSignal, closing: () -> Boolean): MessageState
}

/**
 * This Mac as the worker for its projects' conversations. Once the account is signed in and the Mac
 * registered, it runs from launch with no page involved:
 *
 * - **Connection:** it holds the host event stream, reconnecting with backoff, and after [idle] with no line
 *   (the server's own heartbeats included); and sends a heartbeat every [heartbeatEvery] (readiness, the
 *   provider directory and usage), whose answer goes to [onHeartbeat] (tasks and memory jobs ride it).
 * - **Claiming:** `work.available` wakes that conversation's [Session], which claims its next message with a
 *   claim id journaled first ([ConversationStore.journal]), runs it through the [engine], and settles it. One
 *   message runs at a time per conversation; at most [maxRunning] conversations run at once, and the others
 *   wait for a slot before they claim.
 * - **Crash safety:** a claim journaled but not launched is claimed again with the same id; one that may have
 *   launched settles `reconciliation-required` and never runs again; a decided outcome is delivered until the
 *   server takes it. A refusal mid-run settles the message rather than leaving it claimed; a 400, 403 or 404 on
 *   a claim or its progress is final, and drops the journal entry.
 * - **Control:** `stop.requested` and `message.cancelled` stop the running message, and a Stop that arrives
 *   while a claim is on its way applies once it lands; `approval.answered` closes the Mac's dialog when the phone
 *   answered first; `project.changed` publishes that project's folders. `mac-update-required` leaves the
 *   message queued on the server and says so in [status].
 * - **Voice:** [wake] claims a conversation's next message at once, for a heard turn this Mac just sent, by the
 *   same claim, journal and fencing as `work.available`; [replies] names who hears each claimed message's replies.
 */
internal class ConversationRunner(
    private val api: ConversationApi,
    private val store: ConversationStore,
    private val engine: MessageEngine,
    private val approvals: RemoteApprovals,
    private val folders: ProjectFolderStore,
    private val publisher: ProjectFolderPublisher,
    private val heartbeat: () -> HostHeartbeat,
    private val onHeartbeat: (JsonObject) -> Unit = {},
    private val status: (String) -> Unit = {},
    private val maxRunning: Int = MAX_RUNNING,
    private val heartbeatEvery: Duration = 10.seconds,
    private val firstBackoff: Duration = 1.seconds,
    private val maxBackoff: Duration = 30.seconds,
    private val idle: Duration = 75.seconds,
    /** Runs the heartbeat and the stream watchdog. */
    private val timer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-conversation-timer")),
    /** Waits between stream reconnects. */
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = System::nanoTime,
    /** Who hears each claimed message's replies on this Mac: voice, when it speaks them. */
    private val replies: ReplyListeners = ReplyListeners.NONE,
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val slots = Semaphore(maxRunning, true)
    private val closed = AtomicBoolean()
    private val control: ExecutorService = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-conversation-control"))
    private val lastLine = AtomicLong()
    @Volatile private var stream: HovelEventLines? = null
    @Volatile private var streamThread: Thread? = null

    /** Whether the host stream is connected now. */
    @Volatile var connected = false
        private set

    /** Lanes waiting for a slot to claim in. */
    internal val waitingForSlot: Int get() = slots.queueLength

    /** The conversations with a lane now; an idle lane is let go. */
    internal val lanes: Set<String> get() = sessions.keys.toSet()

    /** Recover journaled claims, then start the heartbeat, the stream and its watchdog. */
    fun start() {
        pendingInOrder().forEach { session(it.conversationId).recover(it) }
        timer.scheduleWithFixedDelay(::beat, 0, heartbeatEvery.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay(::watch, idle.inWholeMilliseconds / 3, idle.inWholeMilliseconds / 3, TimeUnit.MILLISECONDS)
        streamThread = Thread.ofPlatform().daemon().name("hovel-conversation-stream").start(::listen)
    }

    /**
     * One heartbeat. Whether it gets through or not, journaled work that waited for the server is tried again and
     * project folders are published; nothing here ever stops the schedule.
     */
    internal fun beat() {
        if (closed.get()) return
        try {
            val answer = runCatching { api.heartbeat(heartbeat()) }
            answer.onSuccess { response ->
                status(if (connected) CONNECTED else CONNECTING)
                runCatching { onHeartbeat(response) }
                    .onFailure { log(LogLevel.Warn, "conversations", "A heartbeat's tasks could not run", it) }
            }.onFailure { status(OFFLINE) }
            pendingInOrder().forEach { session(it.conversationId).retry(it) }
            publisher.publishPending()
        } catch (error: Exception) {
            log(LogLevel.Warn, "conversations", "A heartbeat round failed", error)
        }
    }

    /** Outcomes before claims, so a lane's next claim follows its last message's settle. */
    private fun pendingInOrder() = store.pendingClaims().sortedBy { it.phase.unrun }

    /** Hold the host stream, reconnecting with doubling backoff, until closed. */
    private fun listen() {
        var wait = firstBackoff.inWholeMilliseconds
        while (!closed.get()) {
            try {
                val lines = api.openStream()
                stream = lines
                if (closed.get()) { lines.close(); return }
                connected = true
                lastLine.set(now())
                wait = firstBackoff.inWholeMilliseconds
                status(CONNECTED)
                lines.use { open -> HostEvents.read(open.lines().onEach { lastLine.set(now()) }, ::dispatch) }
            } catch (_: Exception) {
            } finally {
                connected = false
                stream = null
            }
            if (closed.get()) return
            try { sleep(wait) } catch (_: InterruptedException) { return }
            wait = minOf(wait * 2, maxBackoff.inWholeMilliseconds)
        }
    }

    /** A stream that has said nothing for [idle] is dropped, so it reconnects. */
    internal fun watch() {
        val open = stream ?: return
        if (now() - lastLine.get() > idle.inWholeNanoseconds) runCatching { open.close() }
    }

    /** Act on one host event. Nothing here blocks the stream: work goes to the lanes, reads to [control]. */
    internal fun dispatch(event: HostStreamEvent) {
        if (closed.get()) return
        when (event) {
            is HostStreamEvent.WorkAvailable -> session(event.conversationId).poke()
            is HostStreamEvent.StopRequested -> session(event.conversationId).stop()
            is HostStreamEvent.MessageCancelled -> sessions.values.forEach { it.cancel(event.messageId) }
            is HostStreamEvent.ApprovalAnswered -> later { approvals.answered(event.approvalId) }
            is HostStreamEvent.ProjectChanged -> later { publisher.refresh(event.projectId) }
        }
    }

    private fun later(task: () -> Unit) = runCatching { control.execute { runCatching(task) } }

    /**
     * Claim [conversationId]'s next message now, as `work.available` would, without waiting for the stream: a heard
     * turn this Mac just sent runs at once. A lane already at work looks again when it is done.
     */
    fun wake(conversationId: String) {
        if (!closed.get()) session(conversationId).poke()
    }

    private fun session(conversationId: String): Session = sessions.computeIfAbsent(conversationId, ::Session)

    /**
     * Stop everything. A running message is not the person's Stop: its run pauses for review and the message
     * settles `reconciliation-required` (now, or at the next launch from the journal), so the person may retry it.
     * A claim that lands after this stays journaled for the next launch. The lanes get up to 5 seconds to wind
     * down before they are interrupted.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessions.values.forEach { it.stop() }
        runCatching { stream?.close() }
        streamThread?.interrupt()
        val executors = listOf(timer, control) + sessions.values.map { it.executor }
        executors.forEach { it.shutdown() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        executors.forEach { it.awaitTermination(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
        executors.forEach { it.shutdownNow() }
    }

    /** One message running in a conversation: its id and its Stop. */
    private class Running(val messageId: String, val cancel: CancelSignal)

    /** One conversation's serial lane: its claims, its running message, and its journal. */
    private inner class Session(val conversationId: String) {
        /** One thread at most, which ends after a minute idle. */
        val executor: ExecutorService = ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS, LinkedBlockingQueue(),
            loggingDaemonThreads("hovel-conversation-$conversationId"))
        private val woken = AtomicBoolean()
        @Volatile private var current: Running? = null
        @Volatile private var claiming = false
        /** A Stop that came while a claim was on its way, or journaled and not yet run: it applies to that claim. */
        @Volatile private var stopPending = false

        /**
         * Claim and run messages until none is queued. A poke while it works makes it look again after. Pokes and
         * eviction share one lock, so a lane already let go hands the poke to the conversation's current lane, and
         * no conversation ever has two lanes at work.
         */
        fun poke() {
            synchronized(sessions) {
                if (sessions[conversationId] !== this) return session(conversationId).poke()
                if (woken.compareAndSet(false, true)) submit { woken.set(false); drain() }
            }
        }

        fun stop() {
            current?.let { it.cancel.cancel(); return }
            if (claiming || store.claims(conversationId).any { it.phase.unrun }) stopPending = true
        }

        fun cancel(messageId: String) { current?.takeIf { it.messageId == messageId }?.cancel?.cancel() }

        /** A claim journaled before a restart: run on, settle as uncertain, or deliver its outcome; then look for more. */
        fun recover(entry: ClaimEntry) = when (entry.phase) {
            ClaimPhase.CLAIMING, ClaimPhase.CLAIMED -> poke()
            ClaimPhase.LAUNCHING -> submit { if (settle(entry, MessageState.RECONCILIATION_REQUIRED)) poke() }
            ClaimPhase.SETTLING -> submit { if (deliver(entry)) poke() }
        }

        /** After a heartbeat: a claim that could not reach the server, or an undelivered outcome, is tried again. */
        fun retry(entry: ClaimEntry) {
            if (current != null) return
            when (entry.phase) {
                ClaimPhase.CLAIMING, ClaimPhase.CLAIMED -> poke()
                // The server holds the conversation's next message until this one settles.
                ClaimPhase.SETTLING -> submit { if (deliver(entry)) poke() }
                ClaimPhase.LAUNCHING -> Unit
            }
        }

        /** Run [task] on this lane; a lane already let go hands it to the conversation's current one. */
        private fun submit(task: () -> Unit) {
            try { executor.execute(task) } catch (_: RejectedExecutionException) {
                if (!closed.get()) session(conversationId).takeIf { it !== this }?.submit(task)
            }
        }

        private fun drain() {
            while (!closed.get()) {
                try { slots.acquire() } catch (_: InterruptedException) { return }
                val ran = try { claimOnce() } finally { slots.release() }
                if (!ran) break
            }
            evict()
        }

        /** Nothing to run and nothing journaled: let the lane go, so conversations seen once hold nothing. */
        private fun evict() {
            synchronized(sessions) {
                if (woken.get() || current != null || store.claims(conversationId).isNotEmpty()) return
                if (sessions.remove(conversationId, this)) executor.shutdown()
            }
        }

        /** Claim this conversation's next message and run it. False when there was nothing to run. */
        private fun claimOnce(): Boolean {
            val entry = store.claims(conversationId).firstOrNull { it.phase.unrun }
                ?: store.journal(ClaimEntry(UUID.randomUUID().toString(), conversationId, ClaimPhase.CLAIMING))
            claiming = true
            val response = try {
                api.claim(ClaimRequest(entry.claimId, conversationId))
            } catch (refused: HovelRejectedException) {
                stopPending = false
                if (refused.code == ClaimOutput.STOP_REQUESTED) return stopReplayed(entry)
                store.forget(entry)
                if (refused.code == UPDATE_REQUIRED_CODE) status(UPDATE_REQUIRED)
                return false
            } catch (final: HovelStatusException) {
                if (!final.final) return false
                log(LogLevel.Warn, "conversations", "The server refused a claim for good (HTTP ${final.status}); it is dropped")
                store.forget(entry)
                stopPending = false
                return false
            } catch (_: SerializationException) {
                // A claim this build cannot read names something newer than it: the same claim id is asked again after an update.
                status(UPDATE_REQUIRED)
                return false
            } catch (error: Exception) {
                // Unreachable: the journaled claim id is tried again after the next heartbeat.
                log(LogLevel.Warn, "conversations", "Claiming a message failed", error)
                return false
            } finally {
                claiming = false
            }
            return when (response) {
                ClaimResponse.Empty -> { store.forget(entry); stopPending = false; false }
                is ClaimResponse.MacUpdateRequired -> { store.forget(entry); stopPending = false; status(UPDATE_REQUIRED); false }
                is ClaimResponse.Claimed -> { run(entry, response.claim); true }
            }
        }

        /**
         * The replay of a journaled claim was refused because the person asked to stop its message: a claim that
         * landed before (journaled `claimed`) settles `stopped`, and the lane looks for more once it is delivered.
         * A claim journaled only as `claiming` never learned its message, so it cannot be settled from here.
         */
        private fun stopReplayed(entry: ClaimEntry): Boolean {
            if (entry.phase == ClaimPhase.CLAIMED && entry.messageId != null && entry.revision != null) {
                return settle(entry, MessageState.STOPPED)
            }
            log(LogLevel.Warn, "conversations", "A stopped message's claim never reached this Mac's journal; it is dropped")
            store.forget(entry)
            return false
        }

        /** Journal the claim, cross the launch boundary, run it, and settle it. */
        private fun run(claiming: ClaimEntry, claim: ClaimResult) {
            val ours = claim.claimId == claiming.claimId && claim.conversation.id == conversationId
            check(ours && claim.message.state == MessageState.CLAIMED) {
                "The server answered a claim that is not this one"
            }
            val messageId = claim.message.id
            // Running at once, so a Stop from here on is never lost.
            val cancel = CancelSignal()
            current = Running(messageId, cancel)
            val stoppedOnTheWay = stopPending.also { stopPending = false }
            val listener = runCatching { replies.forClaim(claim, cancel::cancel) }
                .onFailure { log(LogLevel.Warn, "conversations", "A claim's reply listener failed", it) }.getOrNull()
            try {
                val claimed = claiming.copy(phase = ClaimPhase.CLAIMED, messageId = messageId, revision = claim.message.revision)
                var entry = store.journal(claimed)
                // Quitting: the claim waits, journaled, for the next launch; no provider turn starts.
                if (closed.get()) return
                folders.claimed(store.accountId, claim.project)
                later { publisher.publish(claim.project.id) }
                // A Stop that came while the claim was on its way: the message stops before any provider turn.
                if (stoppedOnTheWay || cancel.isCancelled) {
                    if (!closed.get()) settle(entry, MessageState.STOPPED)
                    return
                }
                entry = store.journal(entry.copy(phase = ClaimPhase.LAUNCHING))
                ClaimOutput(api, claim.claimId, messageId, onStopRequested = cancel::cancel, listener = listener).use { output ->
                    launch(entry, claim, output, cancel)
                }
            } finally {
                current = null
                listener?.let { runCatching(it::ended) }
            }
        }

        private fun launch(launching: ClaimEntry, claim: ClaimResult, output: ClaimOutput, cancel: CancelSignal) {
            var entry = launching
            val messageId = claim.message.id
            try {
                val claimed = claim.message.revision
                val revision = api.progress(messageId, MessageProgress(claim.claimId, messageId, claimed, MessageState.RUNNING))
                entry = store.journal(entry.copy(revision = revision ?: (claimed + 1)))
                val state = engine.run(claim, output, cancel) { closed.get() }
                output.drain()
                settle(entry, state)
            } catch (refused: HovelRejectedException) {
                // The server refused the claim's work: it never stays claimed, and is settled as what it is. After the
                // person's Stop (or the server's `stop-requested`) that is `stopped`, which the server still takes.
                val stopped = refused.code == ClaimOutput.STOP_REQUESTED || (cancel.isCancelled && !closed.get())
                settle(entry, if (stopped) MessageState.STOPPED else MessageState.RECONCILIATION_REQUIRED)
            } catch (final: HovelStatusException) {
                if (!final.final) settle(entry, MessageState.RECONCILIATION_REQUIRED)
                else {
                    log(LogLevel.Warn, "conversations", "The server refused a message for good (HTTP ${final.status}); it is dropped")
                    store.forget(entry)
                }
            } catch (error: Exception) {
                log(LogLevel.Warn, "conversations", "A claimed message ended without an outcome", error)
                settle(entry, MessageState.RECONCILIATION_REQUIRED)
            }
        }

        /** Journal [state] as the outcome, then deliver it. True once the server has it. */
        private fun settle(entry: ClaimEntry, state: MessageState) =
            deliver(store.journal(entry.copy(phase = ClaimPhase.SETTLING, outcome = state)))

        /**
         * Settle the message on the server. A refusal means the server has moved on (settled, stopped or expired),
         * and a 400, 403 or 404 that the message is gone: the outcome is dropped. An unreachable server keeps it for
         * the next heartbeat.
         */
        private fun deliver(journaled: ClaimEntry): Boolean {
            // A heartbeat may have queued this delivery twice; the second finds it delivered.
            val entry = store.claims(conversationId).find { it.claimId == journaled.claimId && it.phase == ClaimPhase.SETTLING }
                ?: return true
            val messageId = entry.messageId ?: run { store.forget(entry); return true }
            val outcome = checkNotNull(entry.outcome)
            val revision = checkNotNull(entry.revision)
            fun send(at: Long) = api.progress(messageId, MessageProgress(entry.claimId, messageId, at, outcome))
            try {
                try { send(revision) } catch (refused: HovelRejectedException) {
                    // A crash between `running` and journaling its revision leaves the journal one behind.
                    if (outcome == MessageState.RECONCILIATION_REQUIRED) send(revision + 1) else throw refused
                }
            } catch (_: HovelRejectedException) {
            } catch (final: HovelStatusException) {
                if (!final.final) return false
                log(LogLevel.Warn, "conversations", "The server refused an outcome for good (HTTP ${final.status}); it is dropped")
            } catch (error: Exception) {
                log(LogLevel.Warn, "conversations", "A message outcome waits for the server", error)
                return false
            }
            store.forget(entry)
            return true
        }
    }

    companion object {
        /** Conversations that may run a message at once on one Mac. */
        const val MAX_RUNNING = 4
        const val CONNECTED = "Connected · Conversations run on this Mac"
        const val CONNECTING = "Connected · Reconnecting to live updates"
        const val OFFLINE = "Server unavailable; messages wait on the server"
        const val UPDATE_REQUIRED = "Update Hovel on this Mac to run every message in its conversations"
        /** The contract's refusal code for a client too old for what it asked for. */
        const val UPDATE_REQUIRED_CODE = "update-required"
    }
}
