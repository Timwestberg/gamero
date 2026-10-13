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
    private val brain: BrainHostWork? = null,
    private val status: (String) -> Unit,
) : AutoCloseable {
    private val polling = Executors.newSingleThreadScheduledExecutor()
    private val execution = Executors.newSingleThreadExecutor()
    private val working = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun start() {
        polling.scheduleWithFixedDelay({
            if (!closed.get()) runCatching { heartbeat() }.onFailure { status("Server unavailable; tasks remain on this computer") }
        }, 0, 10, TimeUnit.SECONDS)
    }

    private fun heartbeat() {
        val readiness = provider.readiness()
        val response = transport.post("heartbeat", buildJsonObject {
            put("providerState", when (readiness) {
                CodexCliProvider.Readiness.READY -> "ready"
                CodexCliProvider.Readiness.AUTH_REQUIRED -> "auth-required"
                CodexCliProvider.Readiness.UNAVAILABLE -> "unavailable"
            })
        })
        status(if (readiness == CodexCliProvider.Readiness.READY) "Connected · Codex ready" else "Connected · Sign in to Codex using ChatGPT")
        if (working.get()) return
        if (brain != null) {
            if (!working.compareAndSet(false, true)) return
            execution.execute {
                try { brain.pollOnce { taskId, _ ->
                    status("Task needs reconciliation on this computer: ${taskId ?: "invalid assignment"}")
                } }
                catch (_: Exception) { status("Task needs reconciliation on this computer; no automatic host change") }
                finally { working.set(false) }
            }
            return
        }
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
                ExecutionCoordinator(journal, provider, HovelTaskRemote(transport, task)).accept(request)
            } catch (_: Exception) { status("Task needs reconciliation on this computer; no automatic host change") }
            finally { working.set(false) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        polling.shutdownNow()
        execution.shutdownNow()
        // Closing the journal prevents stale workers from acquiring a new launch boundary.
        journal.close()
        brain?.close()
        polling.awaitTermination(3, TimeUnit.SECONDS)
        execution.awaitTermination(5, TimeUnit.SECONDS)
    }
}
