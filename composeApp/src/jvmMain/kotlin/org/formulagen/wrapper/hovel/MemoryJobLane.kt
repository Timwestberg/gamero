package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A registered Mac's memory jobs, fed by the heartbeat the conversation runner sends: one job runs at a time, on its
 * own thread with its own journal, on the player's Codex ([provider], ready by [checkReadiness]). Consolidation has a
 * thread of its own, so a slow server call never delays a job. The heartbeat declares `memory-extraction` ([FEATURES])
 * so the server sends jobs.
 */
class MemoryJobLane(
    private val hostId: String,
    private val journal: ExecutionJournal,
    private val provider: TaskProvider,
    private val checkReadiness: () -> CodexCliProvider.Readiness,
    private val transport: HovelTransport,
    private val status: (String) -> Unit,
) : AutoCloseable {
    private val consolidation = Executors.newSingleThreadScheduledExecutor()
    private val executor = Executors.newSingleThreadExecutor()
    private val working = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun start() {
        // The first heartbeat declares this companion's features before the server is asked to plan memory jobs.
        consolidation.scheduleWithFixedDelay({ if (!closed.get()) consolidate() }, 1, CONSOLIDATE_MINUTES, TimeUnit.MINUTES)
    }

    /**
     * A heartbeat's answer: offer its first memory job this companion can run, when the lane is free. Memory jobs arrive
     * only when this companion declares `memory-extraction`. A failed or uncertain job needs nothing here: the server
     * closes it and plans the day again, up to its attempt limit.
     */
    fun heartbeat(response: JsonObject) {
        if (closed.get() || working.get()) return
        // A job this version cannot read is not the server being down; say what fixes it.
        runCatching { offer(response) }.onFailure { status(MEMORY_JOB_UNREADABLE) }
    }

    private fun offer(response: JsonObject) {
        val ready = checkReadiness() == CodexCliProvider.Readiness.READY
        val job = response["memoryJobs"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { isRunnableMemoryJob(it) && needsWork(it, ready) } ?: return
        val request = hovelMemoryJobRequest(job, hostId)
        if (!working.compareAndSet(false, true)) return
        executor.execute {
            try {
                val delivered = try {
                    ExecutionCoordinator(journal, provider, HovelMemoryJobRemote(transport, job)).accept(request)
                    true
                } catch (_: Exception) { false }
                // A delivered result lets the server apply it and plan the next day now, not at the next timer.
                if (delivered && !closed.get()) runCatching { consolidation.execute(::consolidate) }
            } finally { working.set(false) }
        }
    }

    /** Asks the server to advance memory. Only the extraction computer may; any other gets 403, and every failure waits for the next call. */
    internal fun consolidate() {
        try { transport.post(NativeRoute.MEMORY_CONSOLIDATE.path(), JsonObject(emptyMap())) } catch (_: Exception) {}
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val executors = listOf(consolidation, executor)
        executors.forEach { it.shutdownNow() }
        // Closing the journal prevents a stale worker from acquiring a new launch boundary.
        journal.close()
        // One deadline for both, so closing never waits more than 5 seconds.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        executors.forEach { it.awaitTermination(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
    }

    companion object {
        const val CONSOLIDATE_MINUTES = 5L
        const val MEMORY_JOB_UNREADABLE = "Connected · Update Hovel on this computer to run its memory jobs"

        /** What a heartbeat declares for a companion that runs memory jobs: the server sends them only to it. */
        val FEATURES = listOf("memory-extraction")

        /** Unfinished on the server; a queued job only once Codex is ready to launch it. */
        private fun needsWork(item: JsonObject, ready: Boolean): Boolean {
            val state = item.getValue("progress").jsonObject.getValue("state").jsonPrimitive.content
            return state in setOf("queued", "claimed", "running") && (state != "queued" || ready)
        }
    }
}
