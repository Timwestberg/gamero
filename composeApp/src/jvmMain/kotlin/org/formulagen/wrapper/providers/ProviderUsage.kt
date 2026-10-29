package org.formulagen.wrapper.providers

import java.time.Instant
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ProviderUsage
import org.formulagen.wrapper.bridge.ProviderUsageWindow
import org.formulagen.wrapper.bridge.RuntimeId

/** Prefer Codex's named bucket. Window duration, not primary/secondary position, determines its label. */
internal fun codexUsage(reply: JsonObject, checkedAt: Long): ProviderUsage {
    val buckets = reply["rateLimitsByLimitId"] as? JsonObject
    val limits = if (buckets != null) buckets["codex"] as? JsonObject else
        (reply["rateLimits"] as? JsonObject)?.takeIf { it.text("limitId") in listOf(null, "codex") }
    val windows = listOfNotNull(limits?.get("primary") as? JsonObject, limits?.get("secondary") as? JsonObject)
    fun window(minutes: Int): ProviderUsageWindow? = windows.singleOrNull { it.number("windowDurationMins") == minutes.toDouble() }
        ?.let { usageWindow(it.number("usedPercent"), it["resetsAt"].epochSeconds()) }
    return ProviderUsage(RuntimeId.CODEX, checkedAt, window(300), window(10_080))
}

/** Claude get_usage's aggregate subscription windows. Session cost and model-specific weekly buckets are unrelated. */
internal fun claudeUsage(reply: JsonObject?, checkedAt: Long): ProviderUsage {
    val limits = if ((reply?.get("rate_limits_available") as? JsonPrimitive)?.booleanOrNull == true)
        reply["rate_limits"] as? JsonObject else null
    fun window(key: String): ProviderUsageWindow? = (limits?.get(key) as? JsonObject)?.let {
        usageWindow(it.number("utilization"), it["resets_at"].epochSeconds())
    }
    return ProviderUsage(RuntimeId.CLAUDE_CODE, checkedAt, window("five_hour"), window("seven_day"))
}

/** Invalid percentages invalidate just their window. A missing reset never invents a deadline. */
private fun usageWindow(percent: Double?, resetsAt: Long?): ProviderUsageWindow? =
    percent?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { ProviderUsageWindow(it, resetsAt) }

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

/** Both providers use Unix seconds; Claude can also return an ISO instant. */
private fun JsonElement?.epochSeconds(): Long? {
    val value = this as? JsonPrimitive ?: return null
    val seconds = if (value.isString) runCatching { Instant.parse(value.content).epochSecond }.getOrNull() else value.longOrNull
    return seconds?.takeIf { it in 0..253_402_300_799L }
}
