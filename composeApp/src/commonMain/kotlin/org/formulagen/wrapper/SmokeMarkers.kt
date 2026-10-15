package org.formulagen.wrapper

import org.formulagen.wrapper.bridge.BridgeProtocol
import org.formulagen.wrapper.bridge.HostPlatform
import org.formulagen.wrapper.bridge.TrustedOrigin

/**
 * The log lines the smoke scripts wait for (`tools/ios-smoke.sh`, `tools/android-smoke.sh`,
 * docs/desktop.md). Every shell logs the same lines, so one pass rule covers every platform.
 * Nothing the page controls reaches the log unchecked: the URL is reduced to its origin, the
 * title loses line breaks, and a method name that is not a v2 method name is logged as `invalid`.
 */
object SmokeMarkers {
    /** `GAMERO_PAGE_LOADED origin=<scheme://host[:port]> title=<title>`. */
    fun pageLoaded(url: String, title: String?): String {
        val origin = runCatching { TrustedOrigin(url).rule }.getOrDefault("invalid")
        return "GAMERO_PAGE_LOADED origin=$origin title=${title?.let(::oneLine)}"
    }

    /**
     * `GAMERO_BRIDGE_ANSWERED platform=<desktop|android|ios> product=<product> method=<method> outcome=<outcome>`.
     * [outcome] is `ok` or the v2 error code, such as `unsupported`.
     */
    fun bridgeAnswered(platform: HostPlatform, product: String, method: String?, outcome: String): String {
        val name = method?.takeIf { it == BridgeProtocol.WATCH_CONTEXT || BridgeProtocol.METHOD_PATTERN.matches(it) } ?: "invalid"
        return "GAMERO_BRIDGE_ANSWERED platform=${platform.wireName} product=${oneLine(product)} method=$name outcome=${oneLine(outcome)}"
    }

    private fun oneLine(value: String): String = value.replace(Regex("[\\r\\n]+"), " ")
}
