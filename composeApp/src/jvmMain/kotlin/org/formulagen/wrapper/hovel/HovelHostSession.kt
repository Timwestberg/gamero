package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The memory lane of a registered Mac: one memory job at a time, from the heartbeat the conversation
 * runner sends, with its own journal, and the consolidation timer. Conversations replaced the task
 * lane; this keeps the memory work it ran beside. Consolidation has a thread of its own, so a slow
 * server call never delays a job.
 */
class HovelHostSession(
    private val hostId: String,
    memoryJournal: ExecutionJournal,
    memoryProvider: TaskProvider,
    private val checkReadiness: () -> CodexCliProvider.Readiness,
    private val transport: HovelTransport,
    private val status: (String) -> Unit,
) : AutoCloseable {
    private val consolidation = Executors.newSingleThreadScheduledExecutor()
    private val memory = Lane(memoryJournal, memoryProvider)
    private val closed = AtomicBoolean(false)

    fun start() {
        // The first heartbeat declares this companion's features before the server is asked to plan memory jobs.
        consolidation.scheduleWithFixedDelay({ if (!closed.get()) consolidate() }, 1, CONSOLIDATE_MINUTES, TimeUnit.MINUTES)
    }

    /** A heartbeat's answer: offer its first memory job this companion can run, when the lane is free. */
    fun heartbeat(response: JsonObject) {
        if (closed.get()) return
        val ready = checkReadiness() == CodexCliProvider.Readiness.READY
        // A job this version cannot read is not the server being down; say what fixes it.
        runCatching { offerMemoryJob(response, ready) }.onFailure { status(MEMORY_JOB_UNREADABLE) }
    }

    /**
     * Memory jobs arrive only when this companion declares `memory-extraction`. A failed or uncertain job
     * needs nothing here: the server closes it and plans the day again, up to its attempt limit.
     */
    private fun offerMemoryJob(response: JsonObject, ready: Boolean) {
        if (memory.busy) return
        val job = response["memoryJobs"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { isRunnableMemoryJob(it) && needsWork(it, ready) } ?: return
        val request = hovelMemoryJobRequest(job, hostId)
        memory.offer {
            val delivered = try {
                ExecutionCoordinator(memory.journal, memory.provider, HovelMemoryJobRemote(transport, job)).accept(request)
                true
            } catch (_: Exception) { false }
            // A delivered result lets the server apply it and plan the next day now, not at the next timer.
            if (delivered && !closed.get()) runCatching { consolidation.execute(::consolidate) }
        }
    }

    /** Asks the server to advance memory. Only the extraction computer may; any other gets 403, and every failure waits for the next call. */
    internal fun consolidate() {
        try { transport.post("memory/consolidate", JsonObject(emptyMap())) } catch (_: Exception) {}
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val executors = listOf(consolidation, memory.executor)
        executors.forEach { it.shutdownNow() }
        // Closing the journal prevents a stale worker from acquiring a new launch boundary.
        memory.journal.close()
        // One deadline for both, so closing never waits more than 5 seconds.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        executors.forEach { it.awaitTermination(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
    }

    /** One kind of work: a journal, a provider, and a single thread that runs one item at a time. */
    private class Lane(val journal: ExecutionJournal, val provider: TaskProvider) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        private val working = AtomicBoolean(false)
        val busy get() = working.get()

        fun offer(work: () -> Unit) {
            if (!working.compareAndSet(false, true)) return
            executor.execute { try { work() } finally { working.set(false) } }
        }
    }

    companion object {
        const val CONSOLIDATE_MINUTES = 5L
        const val MEMORY_JOB_UNREADABLE = "Connected · Update Hovel on this computer to run its memory jobs"

        /** Unfinished on the server; a queued item only once Codex is ready to launch it. */
        private fun needsWork(item: JsonObject, ready: Boolean): Boolean {
            val state = item.getValue("progress").jsonObject.getValue("state").jsonPrimitive.content
            return state in setOf("queued", "claimed", "running") && (state != "queued" || ready)
        }
    }
}
