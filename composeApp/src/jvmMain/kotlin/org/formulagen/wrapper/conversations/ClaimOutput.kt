package org.formulagen.wrapper.conversations

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.bridge.BrainAttemptState
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.util.loggingDaemonThreads
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/**
 * What one claimed message sends, every call naming its claim. A [checkpoint] is durable: it is
 * retried with backoff until stored, and a refusal (409) means the claim is gone, so it is thrown.
 * A [delta] is best effort: pieces of one reply are numbered from 0, cut to the contract's size,
 * sent in order on one thread, and coalesced while a send is in flight.
 *
 * Once the person asked to stop the message, the server refuses every new step with `stop-requested`
 * (a lost `stop.requested` event included); any such refusal calls [onStopRequested] first, so the
 * message stops as the person's Stop does and settles `stopped`.
 *
 * [listener], when this Mac speaks the message's replies, hears each main-conversation delta, each
 * coordinator reply the run writes, and the first sign of a delegated task, as it happens and before
 * any of it reaches the server.
 */
internal class ClaimOutput(
    private val api: ConversationApi,
    val claimId: String,
    val messageId: String,
    private val attempts: Int = 6,
    private val backoffMillis: Long = 500,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val onStopRequested: () -> Unit = {},
    private val listener: ReplyListener? = null,
) : AutoCloseable {
    private val sender = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-conversation-deltas"))
    private val pending = LinkedHashMap<Pair<String, String?>, StringBuilder>()
    private val sequence = HashMap<String, Int>()
    private var flushing = false

    /** Store [checkpoint], retrying until it is stored. Throws when the claim is refused or the server stays unreachable. */
    fun checkpoint(checkpoint: MessageCheckpoint) {
        var wait = backoffMillis
        repeat(attempts) { attempt ->
            try {
                once(checkpoint)
                return
            } catch (refused: HovelRejectedException) {
                throw refused
            } catch (error: Exception) {
                if (attempt == attempts - 1) throw error
                sleep(wait)
                wait = minOf(wait * 2, MAX_BACKOFF_MILLIS)
            }
        }
    }

    /** Store [checkpoint] with one try: a refusal or a failure is thrown. */
    fun once(checkpoint: MessageCheckpoint) =
        stopAware { api.checkpoint(messageId, MessageCheckpointRequest(checkpoint, claimId, messageId)) }

    /**
     * Store [checkpoint] with one try; a failure or refusal is only logged. Approvals use this, so an
     * unreachable server never holds a dialog back.
     */
    fun bestEffort(checkpoint: MessageCheckpoint): Boolean = try { once(checkpoint); true } catch (error: Exception) {
        log(LogLevel.Warn, "conversations", "A ${checkpoint::class.simpleName} checkpoint was not stored", error)
        false
    }

    /** Stream [text] as the next piece of reply [replyId] in [threadId] (null: the main conversation). */
    fun delta(replyId: String, threadId: String?, text: String) {
        if (text.isEmpty()) return
        listener?.let { runCatching { if (threadId == null) it.delta(replyId, text) else it.threadStarted() } }
        synchronized(pending) {
            pending.getOrPut(replyId to threadId) { StringBuilder() }.append(text)
            if (flushing) return
            flushing = true
        }
        runCatching { sender.execute(::flush) }.onFailure { synchronized(pending) { flushing = false } }
    }

    /**
     * The run wrote [checkpoint], to be stored next: a coordinator reply in the main conversation, and a thread queued
     * or working (not one a crash left behind, which recovery marks interrupted), reach [listener].
     */
    fun wrote(checkpoint: MessageCheckpoint) {
        val heard = listener ?: return
        when (checkpoint) {
            is MessageCheckpoint.MessageAppended -> checkpoint.message
                .takeIf { it.author == MessageAuthor.COORDINATOR && it.threadId == null }
                ?.let { runCatching { heard.appended(it.id, it.text) } }
            is MessageCheckpoint.ThreadUpserted -> if (checkpoint.thread.state in WORKING) runCatching { heard.threadStarted() }
            else -> Unit
        }
    }

    /** Wait until every piece queued so far was sent or dropped. */
    fun drain(timeoutMillis: Long = 5_000) {
        runCatching { sender.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS) }
    }

    private fun flush() {
        while (true) {
            val batch = synchronized(pending) {
                if (pending.isEmpty()) { flushing = false; return }
                pending.entries.map { it.key to it.value.toString() }.also { pending.clear() }
            }
            for ((key, text) in batch) for (piece in pieces(text)) {
                val seq = sequence.merge(key.first, 1, Int::plus)!! - 1
                val delta = ReplyDeltaRequest(claimId, messageId, ReplyDelta(key.first, seq, piece, key.second))
                runCatching { stopAware { api.delta(messageId, delta) } }
            }
        }
    }

    override fun close() {
        drain()
        sender.shutdownNow()
    }

    /** [call], telling [onStopRequested] when the server refuses it because the person asked to stop. */
    private fun <T> stopAware(call: () -> T): T = try { call() } catch (refused: HovelRejectedException) {
        if (refused.code == STOP_REQUESTED) onStopRequested()
        throw refused
    }

    companion object {
        private const val MAX_BACKOFF_MILLIS = 8_000L

        /** A thread in these states is a delegated task under way. */
        private val WORKING = setOf(BrainAttemptState.QUEUED, BrainAttemptState.AWAITING_APPROVAL, BrainAttemptState.RUNNING)

        /** The contract's refusal of a step on a message the person asked to stop. */
        const val STOP_REQUESTED = "stop-requested"

        /** [text] in pieces of at most `COMPANION_LIMITS.delta` characters, never splitting a surrogate pair. */
        fun pieces(text: String): List<String> {
            val result = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(text.length, start + CompanionLimits.DELTA)
                if (end < text.length && text[end - 1].isHighSurrogate()) end -= 1
                result += text.substring(start, end)
                start = end
            }
            return result
        }
    }
}
