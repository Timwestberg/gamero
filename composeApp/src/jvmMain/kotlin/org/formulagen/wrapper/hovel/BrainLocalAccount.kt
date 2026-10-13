package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import java.util.UUID

/** Evidence from an approved native identity adapter, not login-status text or a server assignment. */
data class BrainLocalObservation(
    val localAccountRef: String,
    val accountGeneration: Long,
    val sequence: Long,
    val available: Boolean,
    val observedAtMillis: Long,
)

/**
 * Implementations must be thread-safe and persist generation/sequence before returning evidence.
 * Account replacement/re-authentication changes generation. Reusing a sequence must return identical evidence.
 * No production adapter exists yet: missing, unsupported or ambiguous identity must return null.
 */
fun interface BrainLocalAccountSource { fun current(): BrainLocalObservation? }

/** Checks current native evidence again at each launch; publication never grants launch permission. */
class BrainLocalAccountVerifier(
    private val hostId: String,
    private val source: BrainLocalAccountSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init { require(UUID.fromString(hostId).toString() == hostId) }

    fun verify(selection: JsonObject) {
        val observation = fresh() ?: error("Trusted local account evidence is unavailable")
        check(observation.available) { "Local account is unavailable" }
        check(selection == buildJsonObject {
            put("hostId", hostId); put("runtimeId", "codex"); put("providerId", "openai")
            put("localAccountRef", observation.localAccountRef); put("accountGeneration", observation.accountGeneration)
        }) { "Current local account or generation differs" }
    }

    fun publish(remote: BrainHovelRemote) {
        val observation = fresh() ?: return
        remote.publishObservation(buildJsonObject {
            put("available", observation.available); put("sequence", observation.sequence)
            put("models", JsonArray(emptyList())) // Model equivalence has not been independently established.
            put("local", buildJsonObject {
                put("runtimeId", "codex"); put("providerId", "openai")
                put("localAccountRef", observation.localAccountRef); put("accountGeneration", observation.accountGeneration)
            })
        })
    }

    private fun fresh(): BrainLocalObservation? {
        val observation = source.current() ?: return null
        require(UUID.fromString(observation.localAccountRef).toString() == observation.localAccountRef)
        require(observation.accountGeneration in 1..9_007_199_254_740_991L && observation.sequence in 1..9_007_199_254_740_991L)
        val current = now()
        check(observation.observedAtMillis >= 0 && current >= observation.observedAtMillis &&
            current - observation.observedAtMillis < 60_000) { "Local account evidence is stale" }
        return observation
    }
}
