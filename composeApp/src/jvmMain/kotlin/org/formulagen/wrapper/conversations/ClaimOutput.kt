package org.formulagen.wrapper.conversations

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.chat.loggingDaemonThreads
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/**
 * What one claimed message sends, every call naming its claim. A [checkpoint] is durable: it is
 * retried with backoff until stored, and a refusal (409) means the claim is gone, so it is thrown.
 * A [delta] is best effort: pieces of one reply are numbered from 0, cut to the contract's size,
 * sent in order on one thread, and coalesced while a send is in flight.
 */
internal class ClaimOutput(
    private val api: ConversationApi,
    val claimId: String,
    val messageId: String,
    private val attempts: Int = 6,
    private val backoffMillis: Long = 500,
    private val sleep: (Long) -> Unit = Thread::sleep,
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
                api.checkpoint(messageId, MessageCheckpointRequest(checkpoint, claimId, messageId))
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

    /**
     * Store [checkpoint] with one try; a failure or refusal is only logged. Approvals use this, so an
     * unreachable server never holds a dialog back.
     */
    fun bestEffort(checkpoint: MessageCheckpoint): Boolean = try {
        api.checkpoint(messageId, MessageCheckpointRequest(checkpoint, claimId, messageId)); true
    } catch (error: Exception) {
        log(LogLevel.Warn, "conversations", "A ${checkpoint::class.simpleName} checkpoint was not stored", error)
        false
    }

    /** Stream [text] as the next piece of reply [replyId] in [threadId] (null: the main conversation). */
    fun delta(replyId: String, threadId: String?, text: String) {
        if (text.isEmpty()) return
        synchronized(pending) {
            pending.getOrPut(replyId to threadId) { StringBuilder() }.append(text)
            if (flushing) return
            flushing = true
        }
        runCatching { sender.execute(::flush) }.onFailure { synchronized(pending) { flushing = false } }
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
                runCatching { api.delta(messageId, ReplyDeltaRequest(claimId, messageId, ReplyDelta(key.first, seq, piece, key.second))) }
            }
        }
    }

    override fun close() {
        drain()
        sender.shutdownNow()
    }

    companion object {
        private const val MAX_BACKOFF_MILLIS = 8_000L

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
