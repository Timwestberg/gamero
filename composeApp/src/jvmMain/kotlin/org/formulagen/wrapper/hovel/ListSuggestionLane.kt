package org.formulagen.wrapper.hovel

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.companion.CodexCliProvider
import org.formulagen.wrapper.companion.ExecutionRequest
import org.formulagen.wrapper.companion.ProviderChoice
import org.formulagen.wrapper.companion.TaskProvider
import org.formulagen.wrapper.conversations.fromWire
import org.formulagen.wrapper.conversations.wire
import org.formulagen.wrapper.util.loggingDaemonThreads

/**
 * A registered Mac's list suggestions, built like [MemoryJobLane]: `list.suggestions.requested` hands a request id to
 * [request], and one request runs at a time, on its own thread, on the player's Codex ([provider], ready by
 * [checkReadiness]). The lane reads the request's `suggestionWork`, gives Codex only that (in an empty private
 * workspace, read-only, asked to use no tools), and posts a `suggestionReply`:
 *
 * - `ready` with Codex's suggestions, when its answer parses within the contract's bounds ([parseSuggestions]);
 * - `unavailable` when Codex is not ready, fails, or answers anything else;
 * - `busy` for a second request while one runs.
 *
 * Codex is stopped at [deadline], the server's own wait, and nothing is posted then: the server has already answered
 * the page `timeout`. A request whose work cannot be read is gone, and gets no reply. Nothing is kept: Codex's answer
 * file in [outputs] is deleted after each request. The heartbeat declares [FEATURES], so the server sends the event
 * only to a Mac that has this lane.
 */
internal class ListSuggestionLane(
    private val hostId: String,
    private val provider: TaskProvider,
    private val checkReadiness: () -> CodexCliProvider.Readiness,
    private val exchange: NativeExchange,
    /** Where [provider] writes each answer, as `<request id>.txt`; null when it writes none. */
    private val outputs: Path?,
    private val deadline: Duration = SERVER_WAIT,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-list-suggestions"))
    private val alarms = Executors.newSingleThreadScheduledExecutor(loggingDaemonThreads("hovel-list-suggestions-alarm"))
    /** `busy` replies, off the stream thread; overflow is left to the server's own wait. */
    private val replies = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        loggingDaemonThreads("hovel-list-suggestions-busy"))
    private val active = AtomicReference<String?>(null)
    private val closed = AtomicBoolean(false)

    /** Deletes answers a crash left in [outputs], so no suggestion outlives its request. */
    fun start() {
        val folder = outputs ?: return
        if (!Files.isDirectory(folder)) return
        Files.list(folder).use { files -> files.filter { it.fileName.toString().endsWith(".txt") }.forEach { Files.deleteIfExists(it) } }
    }

    /** Answer [requestId] on the lane's thread, or `busy` while another runs. A replay of the running one is ignored. */
    fun request(requestId: String) {
        if (closed.get() || !SharedRules.isRecordId(requestId) || active.get() == requestId) return
        if (!active.compareAndSet(null, requestId)) {
            runCatching { replies.execute { post(requestId, SuggestionReply.Busy) } }
            return
        }
        runCatching {
            executor.execute {
                try {
                    answer(requestId)?.let { if (!closed.get()) post(requestId, it) }
                } finally { active.set(null) }
            }
        }.onFailure { active.set(null) }
    }

    /** The reply for [requestId], or null when there is none to send. */
    internal fun answer(requestId: String): SuggestionReply? {
        val work = try {
            fromWire(SuggestionWork.serializer(), exchange.exchange("GET", NativeRoute.LIST_SUGGESTIONS_READ.path(requestId), null))
        } catch (_: Exception) { return null }
        if (checkReadiness() != CodexCliProvider.Readiness.READY) return SuggestionReply.Unavailable
        val expired = AtomicBoolean(false)
        val worker = Thread.currentThread()
        val alarm = alarms.schedule({ expired.set(true); worker.interrupt() }, deadline.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return try {
            val result = provider.execute(ExecutionRequest(requestId, hostId, suggestionPrompt(work), CHOICE))
            if (expired.get()) null
            else if (!result.succeeded) SuggestionReply.Unavailable
            else parseSuggestions(result.text)?.let { SuggestionReply.Ready(it) } ?: SuggestionReply.Unavailable
        } catch (_: Exception) {
            if (expired.get()) null else SuggestionReply.Unavailable
        } finally {
            alarm.cancel(false)
            // Clear a late alarm's interrupt.
            Thread.interrupted()
            outputs?.let { runCatching { Files.deleteIfExists(it.resolve("$requestId.txt")) } }
        }
    }

    /** Sends [reply]; a request the server has finished answers 404, and nothing is retried. */
    private fun post(requestId: String, reply: SuggestionReply) {
        runCatching {
            exchange.exchange("POST", NativeRoute.LIST_SUGGESTIONS_REPLY.path(requestId), wire(SuggestionReply.serializer(), reply))
        }
    }

    /** Stops the lane: a running Codex is interrupted, and nothing more is sent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val executors = listOf(executor, alarms, replies)
        executors.forEach { it.shutdownNow() }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        executors.forEach { it.awaitTermination(maxOf(0L, end - System.nanoTime()), TimeUnit.NANOSECONDS) }
    }

    companion object {
        /** What a heartbeat declares for a Mac with this lane: the server sends suggestion requests only to it. */
        val FEATURES = listOf("list-suggestions")

        /**
         * hovel-service's `LIST_SUGGESTION_LIMITS.timeoutMs`: how long the page's call waits. Provisional: real runs
         * set the final number, on both sides.
         */
        val SERVER_WAIT: Duration = 40.seconds

        /** Codex's own model at low effort, so an answer comes inside [SERVER_WAIT]; tune from real runs. */
        private val CHOICE = ProviderChoice(ProviderChoice.RUNTIME_DEFAULT, "low")
    }
}
