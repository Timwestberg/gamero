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
import kotlin.time.Duration.Companion.minutes
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
 * - **Crash safety:** a claim journaled but not launched is claimed again with the same id, and no new claim id
 *   is made while anything is journaled in that conversation; one that may have launched settles
 *   `reconciliation-required` and never runs again; a decided outcome is delivered until the server takes it. A
 *   refusal mid-run settles the message rather than leaving it claimed; a 409 on a claim or a settle means the
 *   claim moved on, and a 400, 403 or 404 that it is gone for good: either drops the journal entry, and a dropped
 *   claim or stop is never claimed again in a loop. A claim answer this build cannot read keeps its claim id
 *   journaled and waits longer before each try.
 * - **A service older than this Mac:** a 400 `unsupported-contract` from any companion route is never final. Every
 *   claim and outcome stays journaled, [status] says the service is older, and every call (claims, settles, the
 *   stream, the heartbeat's retries) waits from the heartbeat interval, doubling to 10 minutes, until one gets
 *   through; claims are then asked again with their journaled ids.
 * - **Control:** `stop.requested` and `message.cancelled` stop the running message, and a Stop that arrives
 *   while a claim is on its way applies once it lands; `approval.answered` closes the Mac's dialog when the phone
 *   answered first; `project.changed` publishes that project's folders. `mac-update-required` leaves the
 *   message queued on the server and says so in [status].
 * - **Stops the Mac lost:** under contract 2 a claim answers `stop-requested` with a message the person stopped,
 *   now under that claim (a replay that never learned its message, or a takeover of a claim this Mac lost); a
 *   server before it refuses the replay with 409 `stop-requested` instead. Either way the message settles
 *   `stopped` with no provider turn, and the lane claims again once the server takes it. A `stop.requested` for a
 *   conversation where this Mac runs and claims nothing claims it once, so a lost claim settles too.
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
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val slots = Semaphore(maxRunning, true)
    private val closed = AtomicBoolean()
    private val control: ExecutorService = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-conversation-control"))
    private val lastLine = AtomicLong()
    /** While a companion route says the service is older than this Mac's contract: every call waits for it first. */
    private val older = Backoff(heartbeatEvery, MAX_RETRY_WAIT, now)
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
                // hovel-service's own heartbeat answers a newer Mac; its companion routes do not.
                status(when { older.failing -> SERVICE_OLDER; connected -> CONNECTED; else -> CONNECTING })
                runCatching { onHeartbeat(response) }
                    .onFailure { log(LogLevel.Warn, "conversations", "A heartbeat's tasks could not run", it) }
            }.onFailure { status(OFFLINE) }
            // A service older than this Mac answers none of it: everything waits, journaled, until the wait ends.
            if (!older.blocked) {
                pendingInOrder().forEach { session(it.conversationId).retry(it) }
                publisher.publishPending()
            }
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
                older.passed()
                lastLine.set(now())
                wait = firstBackoff.inWholeMilliseconds
                status(CONNECTED)
                lines.use { open -> HostEvents.read(open.lines().onEach { lastLine.set(now()) }, ::dispatch) }
            } catch (error: Exception) {
                if (error is HovelStatusException && error.serverOlder) serviceOlder(error)
            } finally {
                connected = false
                stream = null
            }
            if (closed.get()) return
            // A service older than this Mac is asked again only once its wait ends.
            try { sleep(maxOf(wait, TimeUnit.NANOSECONDS.toMillis(older.remaining))) } catch (_: InterruptedException) { return }
            wait = minOf(wait * 2, maxBackoff.inWholeMilliseconds)
        }
    }

    /**
     * A companion route answered 400 `unsupported-contract`: the service is older than this Mac. Nothing is dropped
     * for it; every call waits longer before the next (the first of a run is logged), and [status] says why.
     */
    private fun serviceOlder(refusal: HovelStatusException) {
        if (older.failed()) log(LogLevel.Warn, "conversations", "Hovel's service is older than this Mac; its work waits here", refusal)
        status(SERVICE_OLDER)
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

    /** How a settle ended: the server took it, it was dropped (moved on, or gone for good), or it waits for the server. */
    private enum class Delivery { TAKEN, DROPPED, WAITING }

    /**
     * A wait that starts at [first] when a call fails in a way asking again soon cannot fix, doubles with each round
     * that fails after, up to [max], and ends with the first call that gets through. Calls that fail together, while
     * the wait one of them started still runs, count once. [now] is in nanoseconds.
     */
    private class Backoff(private val first: Duration, private val max: Duration, private val now: () -> Long) {
        private var wait = 0L
        private var until = 0L

        /** Whether a run of failures is on, its wait over or not. */
        val failing: Boolean @Synchronized get() = wait > 0

        /** Whether to hold off now. */
        val blocked: Boolean @Synchronized get() = wait > 0 && now() - until < 0

        /** Nanoseconds left of the wait. */
        val remaining: Long @Synchronized get() = if (blocked) until - now() else 0L

        /** One more failure: wait longer, unless a wait is already running. True for the first of a run. */
        @Synchronized fun failed(): Boolean {
            if (blocked) return false
            val starts = wait == 0L
            wait = if (starts) first.inWholeNanoseconds else minOf(wait * 2, max.inWholeNanoseconds)
            until = now() + wait
            return starts
        }

        /** A call got through: the run is over. */
        @Synchronized fun passed() { wait = 0L }
    }

    /** One conversation's serial lane: its claims, its running message, and its journal. */
    private inner class Session(val conversationId: String) {
        /** One thread at most, which ends after a minute idle. */
        val executor: ExecutorService = ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS, LinkedBlockingQueue(),
            loggingDaemonThreads("hovel-conversation-$conversationId"))
        private val woken = AtomicBoolean()
        @Volatile private var current: Running? = null
        @Volatile private var claiming = false
        /** A Stop that came while a claim was on its way: it applies to that claim once it lands. */
        @Volatile private var stopPending = false
        /** After claim answers this build could not read: the wait before the next claim. */
        private val unreadable = Backoff(heartbeatEvery, MAX_RETRY_WAIT, now)

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

        /**
         * The person's Stop for this conversation. It ends the running message, and applies to a claim on its way once
         * it lands. An idle lane claims once instead, through its executor and with the claim id it has journaled if
         * any: the server answers `stop-requested` with the message to settle, so a claim this Mac lost, or never
         * learned the message of, still settles. An outcome journaled and on its way claims nothing ([claimOnce]): it
         * settles the message itself. The server repeats the event on every connect until the message settles; a
         * repeat finds the claim on its way, or a settled message and nothing to stop.
         */
        fun stop() {
            current?.let { it.cancel.cancel(); return }
            if (claiming) stopPending = true else if (!closed.get()) poke()
        }

        fun cancel(messageId: String) { current?.takeIf { it.messageId == messageId }?.cancel?.cancel() }

        /** A claim journaled before a restart: run on, settle as uncertain, or deliver its outcome; then look for more. */
        fun recover(entry: ClaimEntry) = when (entry.phase) {
            ClaimPhase.CLAIMING, ClaimPhase.CLAIMED -> poke()
            ClaimPhase.LAUNCHING -> submit { if (settle(entry, MessageState.RECONCILIATION_REQUIRED) != Delivery.WAITING) poke() }
            ClaimPhase.SETTLING -> submit { if (deliver(entry) != Delivery.WAITING) poke() }
        }

        /** After a heartbeat: a claim that could not reach the server, or an undelivered outcome, is tried again. */
        fun retry(entry: ClaimEntry) {
            if (current != null) return
            when (entry.phase) {
                ClaimPhase.CLAIMING, ClaimPhase.CLAIMED -> poke()
                // The server holds the conversation's next message until this one settles.
                ClaimPhase.SETTLING -> submit { if (deliver(entry) != Delivery.WAITING) poke() }
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

        /**
         * Claim this conversation's next message and run it. False when there was nothing to run, or nothing more
         * until the server or the heartbeat says so. A claim id journaled and not yet run is asked again; a new one
         * is made only while nothing is journaled here, so an outcome on its way settles before the next claim and
         * no claim takes over this Mac's own message.
         */
        private fun claimOnce(): Boolean {
            if (older.blocked || unreadable.blocked) return false
            val journaled = store.claims(conversationId)
            val replayed = journaled.firstOrNull { it.phase.unrun }
            if (replayed == null && journaled.isNotEmpty()) return false
            val entry = replayed ?: store.journal(ClaimEntry(UUID.randomUUID().toString(), conversationId, ClaimPhase.CLAIMING))
            claiming = true
            val response = try {
                api.claim(ClaimRequest(entry.claimId, conversationId))
            } catch (refused: HovelRejectedException) {
                older.passed()
                // A server before contract 2 refuses the replay of a claim whose message was stopped; settled below.
                if (refused.code == ClaimOutput.STOP_REQUESTED) null else {
                    // Moved on (another claim holds its message, or it settled), or too old: forget it, and look no further.
                    store.forget(entry)
                    stopPending = false
                    if (refused.code == UPDATE_REQUIRED_CODE) status(UPDATE_REQUIRED)
                    return false
                }
            } catch (refusal: HovelStatusException) {
                // A service older than this Mac: the claim id stays journaled, asked again once the wait ends.
                if (refusal.serverOlder) { serviceOlder(refusal); return false }
                if (!refusal.final) return false
                older.passed()
                log(LogLevel.Warn, "conversations", "The server refused a claim for good (HTTP ${refusal.status}); it is dropped")
                store.forget(entry)
                stopPending = false
                return false
            } catch (error: SerializationException) {
                older.passed()
                // Not a contract answer, so not "update Hovel": only `mac-update-required` or an `update-required`
                // refusal says that. The claim id stays journaled, so the server's claim is never orphaned, and the
                // lane waits longer each time before it asks again; the first in a row is logged.
                if (unreadable.failed()) log(LogLevel.Warn, "conversations", "A claim answer could not be read; claiming waits", error)
                return false
            } catch (error: Exception) {
                // Unreachable: the journaled claim id is tried again after the next heartbeat.
                log(LogLevel.Warn, "conversations", "Claiming a message failed", error)
                return false
            } finally {
                claiming = false
            }
            older.passed()
            unreadable.passed()
            return when (response) {
                null -> stopReplayed(entry, replay = replayed != null)
                ClaimResponse.Empty -> { store.forget(entry); stopPending = false; false }
                is ClaimResponse.MacUpdateRequired -> { store.forget(entry); stopPending = false; status(UPDATE_REQUIRED); false }
                is ClaimResponse.StopRequested -> settleStopped(entry, response.message)
                is ClaimResponse.Claimed -> { run(entry, response.claim); true }
            }
        }

        /**
         * The claim answered `stop-requested`: [message] is the conversation's in-flight message, which the person
         * asked to stop, now under [entry]'s claim. Its id and revision are journaled with the outcome, and it settles
         * `stopped` at that revision under this claim; no provider turn starts. Once the server takes it the lane
         * claims again, for what is queued; a settle refused as moved on ends the look, so a stop never loops. The
         * pending Stop is cleared once the outcome is journaled, so a repeat of the event meanwhile never reaches the
         * conversation's next message.
         */
        private fun settleStopped(entry: ClaimEntry, message: ConversationMessage): Boolean {
            val settleable = message.conversationId == conversationId && message.author == MessageAuthor.USER &&
                (message.state == MessageState.CLAIMED || message.state == MessageState.RUNNING)
            val again = if (settleable) {
                settle(entry.copy(messageId = message.id, revision = message.revision), MessageState.STOPPED) == Delivery.TAKEN
            } else {
                log(LogLevel.Warn, "conversations", "A stop answer named a message this claim cannot settle; it is dropped")
                store.forget(entry)
                false
            }
            stopPending = false
            return again
        }

        /**
         * A server before contract 2 refused the replay of a claim whose message the person asked to stop. A claim
         * journaled `claimed` knows its message and settles it `stopped`, claiming again once the server takes it. One
         * journaled only as `claiming` never learned its message: it is dropped and the lane claims once afresh, which
         * a contract 2 server answers with the message to settle. The same refusal of a claim id made just now is not
         * a replay, so it is dropped and ends the look.
         */
        private fun stopReplayed(entry: ClaimEntry, replay: Boolean): Boolean {
            val named = entry.phase == ClaimPhase.CLAIMED && entry.messageId != null && entry.revision != null
            val again = if (named) settle(entry, MessageState.STOPPED) == Delivery.TAKEN else { store.forget(entry); replay }
            stopPending = false
            return again
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
                ClaimOutput(api, claim.claimId, messageId, onStopRequested = cancel::cancel).use { output ->
                    launch(entry, claim, output, cancel)
                }
            } finally {
                current = null
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
            } catch (refusal: HovelStatusException) {
                // A service older than this Mac: the outcome waits, journaled, until it answers.
                if (refusal.serverOlder) serviceOlder(refusal)
                if (!refusal.final) settle(entry, MessageState.RECONCILIATION_REQUIRED)
                else {
                    log(LogLevel.Warn, "conversations", "The server refused a message for good (HTTP ${refusal.status}); it is dropped")
                    store.forget(entry)
                }
            } catch (error: Exception) {
                log(LogLevel.Warn, "conversations", "A claimed message ended without an outcome", error)
                settle(entry, MessageState.RECONCILIATION_REQUIRED)
            }
        }

        /** Journal [state] as the outcome, then deliver it. */
        private fun settle(entry: ClaimEntry, state: MessageState): Delivery =
            deliver(store.journal(entry.copy(phase = ClaimPhase.SETTLING, outcome = state)))

        /**
         * Settle the message on the server under the entry's claim. A refusal means the server has moved on (another
         * claim holds the message, or it settled, stopped or expired), and a 400, 403 or 404 that the message is gone:
         * the outcome is dropped. An unreachable server, or one older than this Mac, keeps it for a later try.
         */
        private fun deliver(journaled: ClaimEntry): Delivery {
            // A heartbeat may have queued this delivery twice; the second finds it delivered.
            val entry = store.claims(conversationId).find { it.claimId == journaled.claimId && it.phase == ClaimPhase.SETTLING }
                ?: return Delivery.TAKEN
            if (older.blocked) return Delivery.WAITING
            val messageId = entry.messageId ?: run { store.forget(entry); return Delivery.DROPPED }
            val outcome = checkNotNull(entry.outcome)
            val revision = checkNotNull(entry.revision)
            fun send(at: Long) = api.progress(messageId, MessageProgress(entry.claimId, messageId, at, outcome))
            val delivery = try {
                try { send(revision) } catch (refused: HovelRejectedException) {
                    // A crash between `running` and journaling its revision leaves the journal one behind.
                    if (outcome == MessageState.RECONCILIATION_REQUIRED) send(revision + 1) else throw refused
                }
                Delivery.TAKEN
            } catch (_: HovelRejectedException) {
                Delivery.DROPPED
            } catch (refusal: HovelStatusException) {
                if (refusal.serverOlder) { serviceOlder(refusal); return Delivery.WAITING }
                if (!refusal.final) return Delivery.WAITING
                log(LogLevel.Warn, "conversations", "The server refused an outcome for good (HTTP ${refusal.status}); it is dropped")
                Delivery.DROPPED
            } catch (error: Exception) {
                log(LogLevel.Warn, "conversations", "A message outcome waits for the server", error)
                return Delivery.WAITING
            }
            older.passed()
            store.forget(entry)
            return delivery
        }
    }

    companion object {
        /** Conversations that may run a message at once on one Mac. */
        const val MAX_RUNNING = 4
        const val CONNECTED = "Connected · Conversations run on this Mac"
        const val CONNECTING = "Connected · Reconnecting to live updates"
        const val OFFLINE = "Server unavailable; messages wait on the server"
        const val UPDATE_REQUIRED = "Update Hovel on this Mac to run every message in its conversations"
        const val SERVICE_OLDER = "Hovel's service is older than this Mac; messages wait until it updates"
        /** The longest wait before asking again: after claim answers this build cannot read, or a service older than it. */
        val MAX_RETRY_WAIT = 10.minutes
        /** The contract's refusal code for a client too old for what it asked for. */
        const val UPDATE_REQUIRED_CODE = "update-required"
    }
}
