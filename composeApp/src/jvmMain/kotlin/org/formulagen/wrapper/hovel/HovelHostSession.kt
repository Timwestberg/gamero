package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Heartbeats stay responsive while a separately owned task worker is busy. */
class HovelHostSession(
    private val hostId: String,
    private val journal: ExecutionJournal,
    private val provider: CodexCliProvider,
    private val transport: HovelTransport,
    private val status: (String) -> Unit,
    private val ember: (EmberCompanionSnapshot) -> Unit = {},
) : AutoCloseable {
    private val polling = Executors.newSingleThreadScheduledExecutor()
    private val execution = Executors.newSingleThreadExecutor()
    private val working = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val emberLock = Any()
    private val emberRevision = java.util.concurrent.atomic.AtomicLong()
    private var emberOnline = true
    private var emberProvider: String? = null
    private var emberTasks: List<EmberTaskProgress> = emptyList()
    private var emberSticky: EmberTaskProgress? = null

    fun start() {
        polling.scheduleWithFixedDelay({
            if (!closed.get()) runCatching { heartbeat() }.onFailure { status("Server unavailable; tasks remain on this computer") }
        }, 0, 10, TimeUnit.SECONDS)
    }

    private fun heartbeat() {
        val ticket = emberRevision.get()
        val readiness = provider.readiness()
        val providerState = when (readiness) {
            CodexCliProvider.Readiness.READY -> "ready"
            CodexCliProvider.Readiness.AUTH_REQUIRED -> "auth-required"
            CodexCliProvider.Readiness.UNAVAILABLE -> "unavailable"
        }
        val response = transport.post("heartbeat", hovelHeartbeat(providerState))
        status(if (readiness == CodexCliProvider.Readiness.READY) "Connected · Codex ready" else "Connected · Sign in to Codex using ChatGPT")
        if (response["tasks"] is JsonArray) {
            publishHeartbeat(providerState, response, stale = emberRevision.get() != ticket)
        }
        if (working.get()) return
        val task = response.getValue("tasks").jsonArray.map { it.jsonObject }.firstOrNull {
            val state = it.getValue("progress").jsonObject.getValue("state").jsonPrimitive.content
            // Outcomes already delivered need no repeated network requests. A durable local
            // outcome behind a still-active server task will be republished by the coordinator.
            state in setOf("queued", "claimed", "running") &&
                (state != "queued" || readiness == CodexCliProvider.Readiness.READY)
        } ?: return
        val request = hovelExecutionRequest(task, hostId)
        if (!working.compareAndSet(false, true)) return
        execution.execute {
            try {
                noteLocal(request.id, "running", null)
                val record = ExecutionCoordinator(journal, provider, HovelTaskRemote(transport, task)).accept(request)
                noteLocal(record.request.id, emberPhaseState(record.phase), record.result)
            } catch (_: Exception) {
                status("Task needs reconciliation on this computer; no automatic host change")
                noteLocal(request.id, "reconciliation-required", "Task needs reconciliation on this computer")
            } finally { working.set(false) }
        }
    }

    private fun publishHeartbeat(providerState: String, response: JsonObject, stale: Boolean) {
        val merged = synchronized(emberLock) {
            if (!emberOnline) return
            emberProvider = providerState
            val next = mergeEmberTasks(emberTasksFromHeartbeat(response), emberSticky, stale)
            emberSticky = next.sticky
            emberTasks = next.tasks
            EmberCompanionSnapshot(true, emberProvider, emberTasks)
        }
        emit(merged)
    }

    private fun noteLocal(id: String, state: String, text: String?) {
        val task = EmberTaskProgress(id, state, text)
        val next = synchronized(emberLock) {
            if (!emberOnline) return
            val tasks = emberTasks.toMutableList()
            val index = tasks.indexOfFirst { it.id == id }
            if (index >= 0) tasks[index] = task else tasks.add(0, task)
            emberTasks = tasks
            if (state in EMBER_TERMINAL_STATES) {
                emberSticky = task
                emberRevision.incrementAndGet()
            } else if (emberSticky?.id == id) {
                emberSticky = null
            }
            EmberCompanionSnapshot(true, emberProvider, emberTasks)
        }
        emit(next)
    }

    private fun emit(snapshot: EmberCompanionSnapshot) {
        val current = synchronized(emberLock) {
            if (!emberOnline && snapshot.online) return
            snapshot
        }
        runCatching { ember(current) }
    }

    private fun emberPhaseState(phase: ExecutionPhase) = when (phase) {
        ExecutionPhase.SUCCEEDED -> "succeeded"
        ExecutionPhase.FAILED -> "failed"
        ExecutionPhase.RECONCILIATION_REQUIRED -> "reconciliation-required"
        ExecutionPhase.PREPARED, ExecutionPhase.LAUNCHING -> "running"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val offline = synchronized(emberLock) {
            emberOnline = false
            emberTasks = emptyList()
            emberSticky = null
            EmberCompanionSnapshot(false, emberProvider, emptyList())
        }
        emit(offline)
        polling.shutdownNow()
        execution.shutdownNow()
        // Closing the journal prevents stale workers from acquiring a new launch boundary.
        journal.close()
        polling.awaitTermination(3, TimeUnit.SECONDS)
        execution.awaitTermination(5, TimeUnit.SECONDS)
    }
}
