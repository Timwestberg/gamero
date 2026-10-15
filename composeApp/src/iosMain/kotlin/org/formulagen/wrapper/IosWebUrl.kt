package org.formulagen.wrapper

import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURLComponents

/** Launch-environment variable that overrides the web app URL (set from `simctl` via `SIMCTL_CHILD_GAMERO_WEB_URL`). */
internal const val WEB_URL_ENVIRONMENT_KEY = "GAMERO_WEB_URL"

/** Info.plist key fed from `GAMERO_WEB_URL` in `iosApp/Configuration/Config.xcconfig`. */
internal const val WEB_URL_INFO_PLIST_KEY = "GameroWebURL"

/**
 * Picks the web app URL: the launch environment first, then Info.plist.
 * The first non-blank source wins, and it must pass the shared [isAllowedWebUrl] rule.
 * Returns null when no source is set or the chosen one is invalid.
 */
internal fun iosWebUrl(environment: String?, infoPlist: String?): String? {
    val raw = listOf(environment, infoPlist).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
    val components = NSURLComponents.componentsWithString(raw) ?: return null
    val hasUserInfo = components.user != null || components.password != null
    if (!isAllowedWebUrl(components.scheme, components.host, hasUserInfo, components.fragment != null)) return null
    return components.string
}

/** Reads [iosWebUrl]'s inputs from the running process and the main bundle. */
internal fun resolveIosWebUrl(): String? = iosWebUrl(
    environment = NSProcessInfo.processInfo.environment[WEB_URL_ENVIRONMENT_KEY] as? String,
    infoPlist = NSBundle.mainBundle.objectForInfoDictionaryKey(WEB_URL_INFO_PLIST_KEY) as? String,
)

/** Launch-environment variable and Info.plist key that name the product, as `GAMERO_PRODUCT` does on desktop. */
internal const val PRODUCT_ENVIRONMENT_KEY = "GAMERO_PRODUCT"
internal const val PRODUCT_INFO_PLIST_KEY = "GameroProduct"

/** True when the launch environment, or else Info.plist, names Hovel as the product. */
internal fun isIosHovelProduct(): Boolean {
    val environment = NSProcessInfo.processInfo.environment[PRODUCT_ENVIRONMENT_KEY] as? String
    val infoPlist = NSBundle.mainBundle.objectForInfoDictionaryKey(PRODUCT_INFO_PLIST_KEY) as? String
    return listOf(environment, infoPlist).firstOrNull { !it.isNullOrBlank() }?.trim() == "hovel"
}

/** The `scheme://host[:port]` origin of [url], or [url] itself when it cannot be parsed. */
internal fun originOf(url: String): String {
    val components = NSURLComponents.componentsWithString(url) ?: return url
    val port = components.port?.let { ":$it" } ?: ""
    return "${components.scheme}://${components.host}$port"
}
