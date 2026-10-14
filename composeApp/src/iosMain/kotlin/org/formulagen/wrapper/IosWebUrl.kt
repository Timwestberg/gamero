package org.formulagen.wrapper

import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURLComponents

/** Launch-environment variable that overrides the web app URL (set from `simctl` via `SIMCTL_CHILD_GAMERO_WEB_URL`). */
internal const val WEB_URL_ENVIRONMENT_KEY = "GAMERO_WEB_URL"

/** Info.plist key fed from `GAMERO_WEB_URL` in `iosApp/Configuration/Config.xcconfig`. */
internal const val WEB_URL_INFO_PLIST_KEY = "GameroWebURL"

private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")

/**
 * Picks the web app URL: the launch environment first, then Info.plist.
 * The first non-blank source wins, and it is validated with the same rules as desktop's `desktopWebUrl`:
 * https only, http only on loopback, a host is required, no userinfo, no fragment.
 * Returns null when no source is set or the chosen one is invalid.
 */
internal fun iosWebUrl(environment: String?, infoPlist: String?): String? {
    val raw = listOf(environment, infoPlist).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
    val components = NSURLComponents.componentsWithString(raw) ?: return null
    val host = components.host
    if (host.isNullOrEmpty() || components.user != null || components.password != null || components.fragment != null) return null
    val scheme = components.scheme?.lowercase()
    if (scheme != "https" && !(scheme == "http" && host.lowercase() in loopbackHosts)) return null
    return components.string
}

/** Reads [iosWebUrl]'s inputs from the running process and the main bundle. */
internal fun resolveIosWebUrl(): String? = iosWebUrl(
    environment = NSProcessInfo.processInfo.environment[WEB_URL_ENVIRONMENT_KEY] as? String,
    infoPlist = NSBundle.mainBundle.objectForInfoDictionaryKey(WEB_URL_INFO_PLIST_KEY) as? String,
)

/** The `scheme://host[:port]` origin of [url], or [url] itself when it cannot be parsed. */
internal fun originOf(url: String): String {
    val components = NSURLComponents.componentsWithString(url) ?: return url
    val port = components.port?.let { ":$it" } ?: ""
    return "${components.scheme}://${components.host}$port"
}
