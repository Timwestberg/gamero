package org.formulagen.wrapper.conversations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.chat.loggingDaemonThreads
import org.formulagen.wrapper.hovel.HovelEventLines
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/**
 * This Mac as the worker for its projects' conversations. Once the account is signed in and the Mac
 * registered, it runs from launch with no page involved:
 *
 * - **Connection:** it holds the host event stream, reconnecting with backoff, and sends a heartbeat
 *   every [heartbeatEvery] (readiness, the provider directory and usage). Each heartbeat's answer goes
 *   to [onHeartbeat] (memory jobs ride it).
 * - **Claiming:** `work.available` wakes that conversation's [Session], which claims its next message
 *   with a claim id journaled first ([ConversationStore.journal]), runs it through the [engine], and
 *   settles it. One message runs at a time per conversation; at most [maxRunning] conversations run at
 *   once, and the others wait for a slot before they claim.
 * - **Crash safety:** a claim journaled but not launched is claimed again with the same id; one that may
 *   have launched settles `reconciliation-required` and never runs again; a decided outcome is
 *   delivered until the server takes it.
 * - **Control:** `stop.requested` and `message.cancelled` stop the running message; `approval.answered`
 *   closes the Mac's dialog when the phone answered first; `project.changed` publishes that project's
 *   folders. `mac-update-required` leaves the message queued on the server and says so in [status].
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
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val slots = Semaphore(maxRunning, true)
    private val closed = AtomicBoolean()
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-conversation-heartbeat"))
    private val control: ExecutorService = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-conversation-control"))
    @Volatile private var stream: HovelEventLines? = null
    @Volatile private var streamThread: Thread? = null

    /** Whether the host stream is connected now. */
    @Volatile var connected = false
        private set

    /** Recover journaled claims, then start the heartbeat and the stream. */
    fun start() {
        store.pendingClaims().forEach { session(it.conversationId).recover(it) }
        timer.scheduleWithFixedDelay(::beat, 0, heartbeatEvery.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        streamThread = Thread.ofPlatform().daemon().name("hovel-conversation-stream").start(::listen)
    }

    /** One heartbeat; on success, journaled work that waited for the server is tried again. */
    internal fun beat() {
        if (closed.get()) return
        try {
            onHeartbeat(api.heartbeat(heartbeat()))
            status(if (connected) CONNECTED else CONNECTING)
        } catch (error: Exception) {
            status(OFFLINE)
            return
        }
        store.pendingClaims().forEach { session(it.conversationId).retry(it) }
        runCatching { publisher.publishPending() }
    }

    /** Hold the host stream, reconnecting with doubling backoff, until closed. */
    private fun listen() {
        var wait = firstBackoff.inWholeMilliseconds
        while (!closed.get()) {
            try {
                val lines = api.openStream()
                stream = lines
                if (closed.get()) { lines.close(); return }
                connected = true
                wait = firstBackoff.inWholeMilliseconds
                status(CONNECTED)
                lines.use { HostEvents.read(it.lines(), ::dispatch) }
            } catch (_: Exception) {
            } finally {
                connected = false
                stream = null
            }
            if (closed.get()) return
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
            wait = minOf(wait * 2, maxBackoff.inWholeMilliseconds)
        }
    }

    /** Act on one host event. Nothing here blocks the stream: work goes to the sessions, reads to [control]. */
    internal fun dispatch(event: HostStreamEvent) {
        if (closed.get()) return
        when (event) {
            is HostStreamEvent.WorkAvailable -> session(event.conversationId).poke()
            is HostStreamEvent.StopRequested -> sessions[event.conversationId]?.stop()
            is HostStreamEvent.MessageCancelled -> sessions.values.forEach { it.cancel(event.messageId) }
            is HostStreamEvent.ApprovalAnswered -> later { approvals.answered(event.approvalId) }
            is HostStreamEvent.ProjectChanged -> later { publisher.publish(event.projectId) }
        }
    }

    private fun later(task: () -> Unit) = runCatching { control.execute { runCatching(task) } }

    private fun session(conversationId: String) = sessions.computeIfAbsent(conversationId, ::Session)

    /** The conversations running a message now. */
    val running: Set<String> get() = sessions.filterValues { it.busy }.keys

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessions.values.forEach { it.stop() }
        runCatching { stream?.close() }
        streamThread?.interrupt()
        val executors = listOf(timer, control) + sessions.values.map { it.executor }
        executors.forEach { it.shutdownNow() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        executors.forEach { it.awaitTermination(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
    }

    /** One message running in a conversation: its id and its Stop. */
    private class Running(val messageId: String, val cancel: CancelSignal)

    /** One conversation's serial lane: its claims, its running message, and its journal. */
    private inner class Session(val conversationId: String) {
        /** One thread at most, which ends after a minute idle, so conversations seen once hold no thread. */
        val executor: ExecutorService = ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS, LinkedBlockingQueue(),
            loggingDaemonThreads("hovel-conversation-$conversationId"))
        private val woken = AtomicBoolean()
        @Volatile private var current: Running? = null

        val busy: Boolean get() = current != null

        /** Claim and run messages until none is queued. A poke while it works makes it look again after. */
        fun poke() {
            if (woken.compareAndSet(false, true)) submit { woken.set(false); drain() }
        }

        fun stop() { current?.cancel?.cancel() }

        fun cancel(messageId: String) { current?.takeIf { it.messageId == messageId }?.cancel?.cancel() }

        /** A claim journaled before a restart: run on, settle as uncertain, or deliver its outcome. */
        fun recover(entry: ClaimEntry) = when (entry.phase) {
            ClaimPhase.CLAIMING, ClaimPhase.CLAIMED -> poke()
            ClaimPhase.LAUNCHING -> submit { settle(entry, MessageState.RECONCILIATION_REQUIRED) }
            ClaimPhase.SETTLING -> submit { deliver(entry) }
        }

        /** After a heartbeat: a claim that could not reach the server, or an undelivered outcome, is tried again. */
        fun retry(entry: ClaimEntry) {
            if (busy) return
            when (entry.phase) {
                ClaimPhase.CLAIMING, ClaimPhase.CLAIMED -> poke()
                ClaimPhase.SETTLING -> submit { deliver(entry) }
                ClaimPhase.LAUNCHING -> Unit
            }
        }

        private fun submit(task: () -> Unit) { runCatching { executor.execute(task) } }

        private fun drain() {
            while (!closed.get()) {
                try { slots.acquire() } catch (_: InterruptedException) { return }
                val ran = try { claimOnce() } finally { slots.release() }
                if (!ran) return
            }
        }

        /** Claim this conversation's next message and run it. False when there was nothing to run. */
        private fun claimOnce(): Boolean {
            val entry = store.claims(conversationId).firstOrNull { it.phase == ClaimPhase.CLAIMING || it.phase == ClaimPhase.CLAIMED }
                ?: store.journal(ClaimEntry(UUID.randomUUID().toString(), conversationId, ClaimPhase.CLAIMING))
            val response = try { api.claim(ClaimRequest(entry.claimId, conversationId)) } catch (_: HovelRejectedException) {
                store.forget(entry); return false
            } catch (_: kotlinx.serialization.SerializationException) {
                // A claim this build cannot read names something newer than it: the same claim id is asked again after an update.
                status(UPDATE_REQUIRED); return false
            } catch (error: Exception) {
                // Unreachable or unreadable: the journaled claim id is tried again after the next heartbeat.
                log(LogLevel.Warn, "conversations", "Claiming a message failed", error)
                return false
            }
            return when (response) {
                ClaimResponse.Empty -> { store.forget(entry); false }
                is ClaimResponse.MacUpdateRequired -> { store.forget(entry); status(UPDATE_REQUIRED); false }
                is ClaimResponse.Claimed -> { run(entry, response.claim); true }
            }
        }

        /** Journal the claim, cross the launch boundary, run it, and settle it. */
        private fun run(claiming: ClaimEntry, claim: ClaimResult) {
            check(claim.claimId == claiming.claimId && claim.conversation.id == conversationId && claim.message.state == MessageState.CLAIMED) {
                "The server answered a claim that is not this one"
            }
            val messageId = claim.message.id
            folders.learned(store.accountId, claim.project.id, claim.project.revision)
            runCatching { publisher.publish(claim.project.id) }
            var entry = store.journal(claiming.copy(phase = ClaimPhase.CLAIMED, messageId = messageId, revision = claim.message.revision))
            entry = store.journal(entry.copy(phase = ClaimPhase.LAUNCHING))
            val cancel = CancelSignal()
            current = Running(messageId, cancel)
            val output = ClaimOutput(api, claim.claimId, messageId)
            try {
                val claimed = claim.message.revision
                val revision = api.progress(messageId, MessageProgress(claim.claimId, messageId, claimed, MessageState.RUNNING))
                entry = store.journal(entry.copy(revision = revision ?: (claimed + 1)))
                val state = engine.run(claim, output, cancel)
                output.drain()
                settle(entry, state)
            } catch (_: HovelRejectedException) {
                // The server took the claim back (stopped, expired or reassigned): nothing of it is ours to settle.
                store.forget(entry)
            } catch (error: Exception) {
                log(LogLevel.Warn, "conversations", "A claimed message ended without an outcome", error)
                settle(entry, MessageState.RECONCILIATION_REQUIRED)
            } finally {
                output.close()
                current = null
            }
        }

        /** Journal [state] as the outcome, then deliver it. */
        private fun settle(entry: ClaimEntry, state: MessageState) = deliver(store.journal(entry.copy(phase = ClaimPhase.SETTLING, outcome = state)))

        /**
         * Settle the message on the server. A refusal means the server has moved on (settled, stopped or
         * expired), and the outcome is dropped; an unreachable server keeps it for the next heartbeat.
         */
        private fun deliver(journaled: ClaimEntry) {
            // A heartbeat may have queued this delivery twice; the second finds it delivered.
            val entry = store.claims(conversationId).find { it.claimId == journaled.claimId && it.phase == ClaimPhase.SETTLING } ?: return
            val messageId = entry.messageId ?: return store.forget(entry)
            val outcome = checkNotNull(entry.outcome)
            val revision = checkNotNull(entry.revision)
            fun send(at: Long) = api.progress(messageId, MessageProgress(entry.claimId, messageId, at, outcome))
            try {
                try { send(revision) } catch (refused: HovelRejectedException) {
                    // A crash between `running` and journaling its revision leaves the journal one behind.
                    if (outcome == MessageState.RECONCILIATION_REQUIRED) send(revision + 1) else throw refused
                }
                store.forget(entry)
            } catch (_: HovelRejectedException) {
                store.forget(entry)
            } catch (error: Exception) {
                log(LogLevel.Warn, "conversations", "A message outcome waits for the server", error)
            }
        }
    }

    companion object {
        /** Conversations that may run a message at once on one Mac. */
        const val MAX_RUNNING = 4
        const val CONNECTED = "Connected · Conversations run on this Mac"
        const val CONNECTING = "Connected · Reconnecting to live updates"
        const val OFFLINE = "Server unavailable; messages wait on the server"
        const val UPDATE_REQUIRED = "Update Hovel on this Mac to run every message in its conversations"
    }
}
