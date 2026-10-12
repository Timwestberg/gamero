package org.formulagen.wrapper.companion

data class ProviderResult(val succeeded: Boolean, val text: String)
fun interface TaskProvider { fun execute(request: ExecutionRequest): ProviderResult }

/** The product adapter implements its server protocol; this core owns launch idempotency. */
interface ExecutionRemote {
    fun claim(record: ExecutionRecord)
    fun running(record: ExecutionRecord)
    fun publish(record: ExecutionRecord, preview: String)
}

class ExecutionCoordinator(
    private val journal: ExecutionJournal,
    private val provider: TaskProvider,
    private val remote: ExecutionRemote,
) {
    @Synchronized
    fun accept(request: ExecutionRequest): ExecutionRecord {
        var record = journal.prepare(request)
        if (record.phase == ExecutionPhase.PREPARED) {
            // A failed/ambiguous claim may be retried with this same durable claim ID.
            remote.claim(record)
            record = journal.begin(record)
            try {
                remote.running(record)
                val result = provider.execute(request)
                record = journal.complete(record, result.succeeded, result.text)
            } catch (_: Exception) {
                // From this boundary, even a process-start error may hide a side effect.
                record = journal.read(request.id) ?: error("Durable launch receipt is missing")
                if (record.phase == ExecutionPhase.LAUNCHING) record = journal.reconcile(record)
            }
        } else if (record.phase == ExecutionPhase.LAUNCHING) {
            record = journal.reconcile(record)
        }
        // Terminal/ambiguous records are durable before delivery. A reconnect only republishes.
        remote.publish(record, journal.preview(record))
        return record
    }
}
