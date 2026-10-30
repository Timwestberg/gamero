package org.formulagen.wrapper.conversations

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderCapability
import org.formulagen.wrapper.bridge.ProviderDetail
import org.formulagen.wrapper.bridge.ProviderLimits
import org.formulagen.wrapper.bridge.ProviderScope
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.ProviderUsage
import org.formulagen.wrapper.bridge.ProviderUsageWindow
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.RuntimeProviderId

/**
 * What this computer's heartbeat reports: hovel-service's heartbeat ([features], and `providerState`) and
 * `computerHeartbeat` (each runtime's readiness and models, for Codex, Claude Code and Grok Build alike, and
 * their usage). Provider checks can start processes, so the directory is refreshed every [directoryEvery] and
 * usage every [usageEvery]; heartbeats in between send the last reading.
 *
 * `providerState` is read only by hovel-service's task routing, so it is the task lane's own Codex readiness
 * ([taskProviderState]) and `unavailable` without a task lane: web Hovel never queues a task to a Mac that cannot
 * run it. Conversation routing reads each runtime's readiness from the directory.
 */
internal class HostDirectory(
    private val providers: ProviderCapability,
    private val hostId: String,
    /** The runtimes this build offers; one gated off reads `unavailable`. */
    private val offered: Set<RuntimeId>,
    /** The runtimes that may work in a project folder here. */
    private val working: Set<RuntimeId>,
    private val features: List<String>,
    private val taskProviderState: () -> String? = { null },
    private val clock: Clock = Clock.systemUTC(),
    private val directoryEvery: Duration = 60.seconds,
    private val usageEvery: Duration = 5.minutes,
) {
    private var directory: Pair<Instant, ProviderDirectory>? = null
    private var usage: Pair<Instant, List<RuntimeAllowance>>? = null

    /** The heartbeat body, from cached readings no older than their interval. */
    @Synchronized fun heartbeat(): HostHeartbeat {
        val now = clock.instant()
        val current = directory?.takeIf { now < it.first.plusMillis(directoryEvery.inWholeMilliseconds) }?.second
            ?: read(now).also { directory = now to it }
        val allowances = usage?.takeIf { now < it.first.plusMillis(usageEvery.inWholeMilliseconds) }?.second
            ?: usage(current).also { usage = now to it }
        return HostHeartbeat(allowances, current, features, taskProviderState() ?: NO_TASK_LANE)
    }

    private companion object {
        /** `providerState` of a Mac that runs no Hovel tasks. */
        const val NO_TASK_LANE = "unavailable"
    }

    private fun read(now: Instant) = ProviderDirectory(now.truncatedTo(ChronoUnit.MILLIS).toString(), RuntimeId.entries.map { runtime ->
        if (runtime !in offered) return@map ProviderRuntime(false, emptyList(), ProviderReadiness.UNAVAILABLE, runtime)
        val scope = ProviderScope(runtime)
        val status = runCatching { runBlocking { providers.perform(ProviderAction.STATUS, scope) } }.getOrNull()
        val readiness = when {
            status?.state == ProviderState.CONNECTED -> ProviderReadiness.READY
            status?.state == ProviderState.DISCONNECTED -> ProviderReadiness.SIGNED_OUT
            status?.detail == ProviderDetail.NOT_INSTALLED -> ProviderReadiness.NOT_INSTALLED
            else -> ProviderReadiness.UNAVAILABLE
        }
        val models = if (readiness != ProviderReadiness.READY) emptyList()
            else runCatching { runBlocking { providers.models(scope) }.models }.getOrDefault(emptyList()).take(ProviderLimits.MODELS)
        ProviderRuntime(readiness == ProviderReadiness.READY && runtime in working, models, readiness, runtime)
    })

    private fun usage(directory: ProviderDirectory): List<RuntimeAllowance> = directory.runtimes
        .filter { it.readiness == ProviderReadiness.READY }
        .mapNotNull { runtime ->
            val reading = runCatching { runBlocking { providers.usage(ProviderScope(runtime.runtimeId)) } }.getOrNull() ?: return@mapNotNull null
            RuntimeAllowance(runtime.runtimeId, allowanceSnapshot(reading, hostId))
        }
}

/**
 * [usage] as event-contracts `allowanceSnapshot`: its session (five-hour) and weekly windows as
 * percent buckets for the whole account. The account reference is opaque and stable per computer and
 * runtime, never a credential.
 */
internal fun allowanceSnapshot(usage: ProviderUsage, hostId: String): JsonObject {
    fun bucket(id: String, label: String, minutes: Int, window: ProviderUsageWindow) = buildJsonObject {
        put("bucketId", id); put("label", label)
        putJsonObject("measurement") { put("kind", "percent"); put("unit", "percent"); put("used", window.usedPercent) }
        putJsonArray("modelIds") {}
        put("observedAt", usage.checkedAt); put("scope", "account")
        putJsonObject("window") {
            put("durationMinutes", minutes); put("id", id)
            put("resetsAt", window.resetsAt?.let { it * 1000 }); put("startsAt", JsonNull)
        }
    }
    val buckets = listOfNotNull(usage.session?.let { bucket("session", "Session", FIVE_HOURS, it) },
        usage.weekly?.let { bucket("weekly", "Weekly", SEVEN_DAYS, it) })
    val runtime = usage.runtimeId.wireName
    return buildJsonObject {
        putJsonObject("account") {
            put("accountRef", UUID.nameUUIDFromBytes("hovel-allowance:$hostId:$runtime".toByteArray()).toString())
            put("providerId", usage.runtimeId.providerId.wireName)
            put("subscriptionRef", JsonNull)
        }
        put("buckets", buildJsonArray { buckets.forEach { add(it) } })
        put("observedAt", usage.checkedAt)
        putJsonObject("source") { put("capability", "poll"); put("interface", "hovel-native-usage"); put("runtimeId", runtime) }
        put("staleAfterMs", STALE_AFTER_MS)
        put("state", if (buckets.isEmpty()) "unknown" else "available")
        put("version", 1)
    }
}

private val RuntimeProviderId.wireName: String get() = name.lowercase()

/** `ALLOWANCE_LIMITS.fiveHourMinutes`, `sevenDayMinutes` and `defaultStaleMs`. */
private const val FIVE_HOURS = 300
private const val SEVEN_DAYS = 10_080
private const val STALE_AFTER_MS = 300_000
