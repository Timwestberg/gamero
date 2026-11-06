package org.formulagen.wrapper.hovel

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.formulagen.wrapper.storage.writeDurably

class MemoryContextUnavailable(cause: Exception) : RuntimeException("Memory context could not be verified; work is paused", cause)

/**
 * One account/host barrier shared by conversation runs and extraction. Cleanup waits for every
 * writer to leave, clears registered local stores, persists its generation, then acknowledges.
 * Lost acknowledgements retry safely; a failed/offline check never authorizes another turn.
 */
class MemoryCleanup(private val transport: HovelTransport, private val marker: Path) {
    private val lock = Any()
    private val polling = Any()
    private val active = mutableMapOf<Any, () -> Unit>()
    private val cleaners = mutableListOf<() -> Unit>()
    private var epoch = if (Files.exists(marker)) Files.readString(marker).trim().toLong().also { require(it >= 0) } else -1L
    @Volatile var ready: Boolean = false
        private set

    fun register(clean: () -> Unit) { synchronized(lock) { check(!ready && active.isEmpty()); cleaners += clean } }

    /** Called after heartbeat feature registration, including after reconnect and restart. */
    fun poll(): Boolean = synchronized(polling) poll@ {
        try {
            val remote = state(JsonObject(emptyMap()))
            val target = remote.getValue("epoch").jsonPrimitive.long
            check(target >= epoch) { "Server cleanup generation went backwards; restore its deletion fences" }
            val acknowledged = remote["acknowledgedEpoch"]?.jsonPrimitive?.longOrNull
            val cancels = synchronized(lock) {
                if (epoch == target && acknowledged == target) { ready = true; return@poll true }
                ready = false
                active.values.toList()
            }
            cancels.forEach { runCatching(it) }
            if (synchronized(lock) { active.isNotEmpty() }) return@poll false
            // New runs cannot enter while ready is false. Never acknowledge partial local cleanup.
            if (epoch != target) {
                // Generation zero has never requested cleanup; preserve ordinary first-run context.
                if (target > 0 || epoch >= 0) cleaners.forEach { it() }
                writeDurably(marker, target.toString().toByteArray())
                epoch = target
            }
            val ack = transport.post(NativeRoute.MEMORY_CLEANUP_ACK.path(), buildJsonObject { put("epoch", target) })
            check(ack.getValue("epoch").jsonPrimitive.long == target && ack.getValue("valid").jsonPrimitive.boolean)
            synchronized(lock) { ready = true }
            true
        } catch (_: Exception) {
            val cancels = synchronized(lock) { ready = false; active.values.toList() }
            cancels.forEach { runCatching(it) }
            false
        }
    }

    /** Before each provider submission, not merely at claim time. The submission itself can still race deletion. */
    fun check() {
        val expected = synchronized(lock) {
            if (!ready) throw MemoryContextUnavailable(IllegalStateException("Memory cleanup is pending"))
            epoch
        }
        try {
            val remote = state(buildJsonObject { put("expectedEpoch", expected) })
            check(remote.getValue("valid").jsonPrimitive.boolean && remote.getValue("epoch").jsonPrimitive.long == expected) {
                "Memory context changed; retry after cleanup"
            }
            synchronized(lock) { check(ready && epoch == expected) { "Memory context changed" } }
        } catch (error: Exception) {
            synchronized(lock) { ready = false }
            throw MemoryContextUnavailable(error)
        }
    }

    /** Holds cleanup off until all callbacks/writes belonging to this run have finished. */
    fun <T> run(cancel: () -> Unit, action: () -> T): T {
        val key = Any()
        synchronized(lock) { check(ready) { "Memory cleanup is pending" }; active[key] = cancel }
        try { return action() } finally { synchronized(lock) { active.remove(key) } }
    }

    private fun state(request: JsonObject) = transport.post(NativeRoute.MEMORY_CONTEXT_CHECK.path(), request)

    companion object { const val FEATURE = "memory-cleanup-v1" }
}
