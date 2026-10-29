package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Codex lanes of a registered Mac, fed by the heartbeat the conversation runner sends: one Hovel task
 * (when a task workspace is configured, until step E removes tasks) and one memory job may run at once,
 * each on its own thread with its own journal. Consolidation has a thread of its own too, so a slow
 * server call never delays a job.
 */
class HovelHostSession(
    private val hostId: String,
    journal: ExecutionJournal?,
    provider: TaskProvider?,
    memoryJournal: ExecutionJournal,
    memoryProvider: TaskProvider,
    private val checkReadiness: () -> CodexCliProvider.Readiness,
    private val transport: HovelTransport,
    private val status: (String) -> Unit,
) : AutoCloseable {
    init {
        require(journal !== memoryJournal) { "Tasks and memory jobs need separate journals" }
        require((journal == null) == (provider == null)) { "The task lane needs its journal and its provider" }
    }

    private val consolidation = Executors.newSingleThreadScheduledExecutor()
    private val tasks = if (journal != null && provider != null) Lane(journal, provider) else null
    private val memory = Lane(memoryJournal, memoryProvider)
    private val closed = AtomicBoolean(false)

    /** What this companion declares on its heartbeats ([hovelHostFeatures]). */
    val features: List<String> = hovelHostFeatures(tasks = tasks != null, memory = true)

    fun start() {
        // The first heartbeat declares this companion's features before the server is asked to plan memory jobs.
        consolidation.scheduleWithFixedDelay({ if (!closed.get()) consolidate() }, 1, CONSOLIDATE_MINUTES, TimeUnit.MINUTES)
    }

    /**
     * The heartbeat's `providerState` for Hovel tasks: Codex's own readiness. Null without the task lane, so the
     * heartbeat reports the provider directory's instead.
     */
    fun taskProviderState(): String? = tasks?.let {
        when (checkReadiness()) {
            CodexCliProvider.Readiness.READY -> "ready"
            CodexCliProvider.Readiness.AUTH_REQUIRED -> "auth-required"
            CodexCliProvider.Readiness.UNAVAILABLE -> "unavailable"
        }
    }

    /**
     * A heartbeat's answer: offer its first task and its first memory job this companion can run, each when its
     * lane is free. A malformed task never holds up a memory job, nor the reverse; the task's failure is thrown
     * after the memory job was offered.
     */
    fun heartbeat(response: JsonObject) {
        if (closed.get()) return
        val ready = checkReadiness() == CodexCliProvider.Readiness.READY
        val taskFailure = runCatching { offerTask(response, ready) }.exceptionOrNull()
        // A job this version cannot read is not the server being down; say what fixes it.
        runCatching { offerMemoryJob(response, ready) }.onFailure { status(MEMORY_JOB_UNREADABLE) }
        taskFailure?.let { throw it }
    }

    private fun offerTask(response: JsonObject, ready: Boolean) {
        val lane = tasks ?: return
        if (lane.busy) return
        // Outcomes already delivered need no repeated network requests. A durable local
        // outcome behind a still-active server task will be republished by the coordinator.
        val task = response["tasks"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { needsWork(it, ready) } ?: return
        val request = hovelExecutionRequest(task, hostId)
        lane.offer {
            try {
                ExecutionCoordinator(lane.journal, lane.provider, HovelTaskRemote(transport, task)).accept(request)
            } catch (_: Exception) { status(TASK_RECONCILIATION) }
        }
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
        val executors = listOfNotNull(consolidation, tasks?.executor, memory.executor)
        executors.forEach { it.shutdownNow() }
        // Closing the journals prevents stale workers from acquiring a new launch boundary.
        tasks?.journal?.close()
        memory.journal.close()
        // One deadline for all of them, so closing never waits more than 5 seconds.
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
        const val TASK_RECONCILIATION = "Task needs reconciliation on this computer; no automatic host change"

        /** Unfinished on the server; a queued item only once Codex is ready to launch it. */
        private fun needsWork(item: JsonObject, ready: Boolean): Boolean {
            val state = item.getValue("progress").jsonObject.getValue("state").jsonPrimitive.content
            return state in setOf("queued", "claimed", "running") && (state != "queued" || ready)
        }
    }
}
