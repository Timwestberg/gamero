package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*

/** Tasks and memory jobs shaped as the server sends them, for the Hovel tests. */
internal object HovelTestFixtures {
    const val MEMORY_JOB_INPUT = "Extract memory notes from these exchanges."
    val QUEUED = buildJsonObject { put("state", "queued") }

    /** A local task with character context and no follow-up. */
    fun task(host: String, owner: String, id: String, progress: JsonObject = QUEUED, revision: Int = 0) = buildJsonObject {
        put("ownerId", owner); put("characterMemory", "Ada remembers")
        put("revision", revision); put("progress", progress)
        put("request", buildJsonObject { put("id", id); put("hostId", host); put("mode", "local"); put("prompt", "Inspect") })
    }

    /** A memory job with every field event-contracts `hovelMemoryJob` requires. */
    fun memoryJob(
        host: String, owner: String, id: String, progress: JsonObject = QUEUED, revision: Int = 0,
        model: String = "runtime-default", effort: String = "low", providerId: String = "codex",
    ) = buildJsonObject {
        put("attempt", 1); put("createdAt", "2026-10-28T00:00:00.000Z"); put("day", "2026-10-27")
        put("effort", effort); put("hostId", host); put("id", id); put("input", MEMORY_JOB_INPUT)
        put("kind", "extract"); put("model", model); put("ownerId", owner); put("progress", progress)
        put("providerId", providerId); put("revision", revision); put("updatedAt", "2026-10-28T00:00:00.000Z")
    }
}
