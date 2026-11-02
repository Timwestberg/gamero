package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*

/** Memory jobs shaped as the server sends them, for the Hovel tests. */
internal object HovelTestFixtures {
    const val MEMORY_JOB_INPUT = "Extract memory notes from these exchanges."
    val QUEUED = buildJsonObject { put("state", "queued") }

    /** A memory job with every field event-contracts `hovelMemoryJob` requires. */
    fun memoryJob(
        host: String, owner: String, id: String, progress: JsonObject = QUEUED, revision: Int = 0,
        model: String = "runtime-default", effort: String = "low", runtimeId: String = "codex",
    ) = buildJsonObject {
        put("attempt", 1); put("createdAt", "2026-10-28T00:00:00.000Z"); put("day", "2026-10-27")
        put("effort", effort); put("hostId", host); put("id", id); put("input", MEMORY_JOB_INPUT)
        put("kind", "extract"); put("model", model); put("ownerId", owner); put("progress", progress)
        put("revision", revision); put("runtimeId", runtimeId); put("updatedAt", "2026-10-28T00:00:00.000Z")
    }
}
