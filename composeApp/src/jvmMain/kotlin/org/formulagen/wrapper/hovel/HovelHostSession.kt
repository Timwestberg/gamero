package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Heartbeats stay responsive while separately owned workers are busy: one user task and one memory
 * job may run at once, each on its own thread with its own journal.
 */
class HovelHostSession(
    private val hostId: String,
    journal: ExecutionJournal,
    provider: TaskProvider,
    memoryJournal: ExecutionJournal,
    memoryProvider: TaskProvider,
    private val checkReadiness: () -> CodexCliProvider.Readiness,
    private val transport: HovelTransport,
    private val status: (String) -> Unit,
) : AutoCloseable {
    init { require(journal !== memoryJournal) { "Tasks and memory jobs need separate journals" } }

    private val polling = Executors.newSingleThreadScheduledExecutor()
    private val tasks = Lane(journal, provider)
    private val memory = Lane(memoryJournal, memoryProvider)
    private val closed = AtomicBoolean(false)

    fun start() {
        polling.scheduleWithFixedDelay({
            if (!closed.get()) runCatching { heartbeat() }.onFailure { status("Server unavailable; tasks remain on this computer") }
        }, 0, 10, TimeUnit.SECONDS)
        // The first heartbeat declares this companion's features before the server is asked to plan memory jobs.
        polling.scheduleWithFixedDelay({ if (!closed.get()) consolidate() }, 1, CONSOLIDATE_MINUTES, TimeUnit.MINUTES)
    }

    internal fun heartbeat() {
        val readiness = checkReadiness()
        val response = transport.post("heartbeat", hovelHeartbeat(when (readiness) {
            CodexCliProvider.Readiness.READY -> "ready"
            CodexCliProvider.Readiness.AUTH_REQUIRED -> "auth-required"
            CodexCliProvider.Readiness.UNAVAILABLE -> "unavailable"
        }))
        status(if (readiness == CodexCliProvider.Readiness.READY) "Connected · Codex ready" else "Connected · Open Profile → Providers to connect Codex")
        val ready = readiness == CodexCliProvider.Readiness.READY
        // A malformed task never holds up a memory job, nor the reverse; the first failure still reaches the status line.
        listOf(runCatching { offerTask(response, ready) }, runCatching { offerMemoryJob(response, ready) })
            .firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
    }

    private fun offerTask(response: JsonObject, ready: Boolean) {
        if (tasks.busy) return
        // Outcomes already delivered need no repeated network requests. A durable local
        // outcome behind a still-active server task will be republished by the coordinator.
        val task = response.getValue("tasks").jsonArray.map { it.jsonObject }.firstOrNull { needsWork(it, ready) } ?: return
        val request = hovelExecutionRequest(task, hostId)
        tasks.offer {
            try {
                ExecutionCoordinator(tasks.journal, tasks.provider, HovelTaskRemote(transport, task)).accept(request)
            } catch (_: Exception) { status("Task needs reconciliation on this computer; no automatic host change") }
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
            if (delivered) consolidate()
        }
    }

    /** Asks the server to advance memory. Only the extraction computer may; any other gets 403, and every failure waits for the next call. */
    internal fun consolidate() {
        try { transport.post("memory/consolidate", JsonObject(emptyMap())) } catch (_: Exception) {}
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        polling.shutdownNow()
        tasks.executor.shutdownNow()
        memory.executor.shutdownNow()
        // Closing the journals prevents stale workers from acquiring a new launch boundary.
        tasks.journal.close()
        memory.journal.close()
        polling.awaitTermination(3, TimeUnit.SECONDS)
        tasks.executor.awaitTermination(5, TimeUnit.SECONDS)
        memory.executor.awaitTermination(5, TimeUnit.SECONDS)
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

        /** Unfinished on the server; a queued item only once Codex is ready to launch it. */
        private fun needsWork(item: JsonObject, ready: Boolean): Boolean {
            val state = item.getValue("progress").jsonObject.getValue("state").jsonPrimitive.content
            return state in setOf("queued", "claimed", "running") && (state != "queued" || ready)
        }
    }
}
