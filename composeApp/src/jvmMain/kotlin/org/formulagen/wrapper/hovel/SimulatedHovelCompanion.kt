package org.formulagen.wrapper.hovel

import java.nio.file.Path
import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*

/** Local deterministic smoke only: no executable, credentials, account registration or HTTP dependencies. */
class SimulatedHovelCompanion(directory: Path) : HovelNativeCompanion {
    private val hostId = "00000000-0000-4000-8000-000000000001"
    private val taskId = "00000000-0000-4000-8000-000000000002"
    private val journal = ExecutionJournal(directory.resolve("simulated-journal"), hostId)
    @Volatile private var closed = false
    val launchesThisStart: Int
    override val status = "Simulated provider smoke: real account connection and inference are disabled."
    override val approval: ConnectionApproval? = null

    init {
        var launches = 0
        try {
            val record = ExecutionCoordinator(journal,
                TaskProvider { launches++; ProviderResult(true, "Simulated smoke receipt") },
                object : ExecutionRemote {
                    override fun claim(record: ExecutionRecord) = Unit
                    override fun running(record: ExecutionRecord) = Unit
                    override fun publish(record: ExecutionRecord, preview: String) = Unit
                }).accept(ExecutionRequest(taskId, hostId, "Fixed simulated smoke task"))
            check(record.phase == ExecutionPhase.SUCCEEDED) { "Simulated journal requires reconciliation" }
            launchesThisStart = launches
            println("GAMERO_SIMULATED_PROVIDER: launchesThisStart=$launches state=${record.phase}")
        } catch (error: Exception) { journal.close(); throw error }
    }

    override suspend fun dispatch(method: String, args: JsonObject): JsonObject {
        check(!closed)
        require(method == "capabilities" && args.isEmpty()) { "Simulated mode forbids account connection and task dispatch" }
        return buildJsonObject {
            put("version", 1); put("connectComputer", false); put("cloud", false); put("sandbox", "read-only")
            put("providerMode", "simulated"); put("launchesThisStart", launchesThisStart)
        }
    }

    override fun invalidatePending() = Unit
    override fun close() {
        closed = true
        journal.close()
    }
}
