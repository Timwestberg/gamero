package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import org.formulagen.wrapper.companion.*

/** Optional session port. Callers must supply trusted local generation verification; no default permits launch. */
class BrainHostWork(
    private val hostId: String,
    executionJournal: ExecutionJournal,
    private val presentationJournal: ExecutionJournal,
    provider: TaskProvider,
    private val remote: BrainHovelRemote,
    verifyLocalAccount: (JsonObject) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val worker = BrainCodexWorker(executionJournal, presentationJournal, provider, provider,
        remote, verifyLocalAccount, remote::authorizeLaunch)

    /** The session's single execution thread serializes both roles and reconnect delivery. */
    fun pollOnce(reconcile: (String?, Exception) -> Unit) {
        check(!closed.get())
        for (input in remote.work()) {
            if (closed.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Session closed")
            try { worker.execute(brainAssignment(input, hostId)) }
            catch (interrupted: InterruptedException) { throw interrupted }
            catch (failure: Exception) {
                val taskId = ((input as? JsonObject)?.get("request") as? JsonObject)?.get("id") as? JsonPrimitive
                reconcile(taskId?.content, failure)
            }
        }
    }

    override fun close() {
        closed.set(true)
        presentationJournal.close()
    }
}
