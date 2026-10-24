package org.formulagen.wrapper.voice

const val ROUTE_ON_DEVICE = "on_device"
const val ROUTE_SERVER = "server"

/** The host names the tier. This module does not guess chip names. */
data class DeviceProfile(val tierId: String)

data class TierRule(val id: String, val onDevice: Boolean)

data class TierCache(val route: String?, val configVersion: String?)

data class BenchmarkSample(val ttfaMs: Double, val rtf: Double)

data class TierDecision(val route: String, val reason: String, val cached: Boolean)

/**
 * Cached route wins when it was stored for this config. A tier marked off-device
 * skips the benchmark. Otherwise one sample of the caller's choosing (the plan
 * uses task_finished) has to clear TTFA and RTF or the route is the server.
 */
fun selectTier(
    device: DeviceProfile,
    rules: List<TierRule>,
    cache: TierCache,
    config: VoiceConfig,
    benchmark: () -> BenchmarkSample,
): TierDecision {
    if (cache.route == ROUTE_ON_DEVICE || cache.route == ROUTE_SERVER) {
        if (cache.configVersion == config.version) {
            return TierDecision(cache.route, "cached", cached = true)
        }
    }
    val rule = rules.firstOrNull { it.id == device.tierId }
    if (rule != null && !rule.onDevice) {
        return TierDecision(ROUTE_SERVER, "tier ${device.tierId} is below the floor", cached = false)
    }
    val sample = benchmark()
    val passed = sample.ttfaMs <= config.passTtfaMs && sample.rtf >= config.passRtf
    return if (passed) {
        TierDecision(ROUTE_ON_DEVICE, "benchmark passed", cached = false)
    } else {
        TierDecision(ROUTE_SERVER, "benchmark missed TTFA or RTF", cached = false)
    }
}
