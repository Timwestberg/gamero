package org.formulagen.wrapper

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import org.formulagen.wrapper.hovel.AUTO_ENTER_KEY
import org.formulagen.wrapper.hovel.autoEnterRequested
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

/** Launch-environment variable that selects the product (`hovel`), as on desktop and Android. */
internal const val PRODUCT_ENVIRONMENT_KEY = "GAMERO_PRODUCT"

/** Info.plist key fed from `GAMERO_PRODUCT` in `iosApp/Configuration/Config.xcconfig`. */
internal const val PRODUCT_INFO_PLIST_KEY = "GameroProduct"

/** The product name: the launch environment first, then Info.plist, first non-blank wins, trimmed. */
internal fun iosProduct(environment: String?, infoPlist: String?): String? =
    listOf(environment, infoPlist).firstOrNull { !it.isNullOrBlank() }?.trim()

/** Reads [iosProduct]'s inputs from the running process and the main bundle. */
internal fun resolveIosProduct(): String? = iosProduct(
    environment = NSProcessInfo.processInfo.environment[PRODUCT_ENVIRONMENT_KEY] as? String,
    infoPlist = NSBundle.mainBundle.objectForInfoDictionaryKey(PRODUCT_INFO_PLIST_KEY) as? String,
)

/**
 * [AUTO_ENTER_KEY] from the launch environment, honored only in a debug binary, so a Release
 * build never skips the start screen. It is never read from Info.plist.
 */
@OptIn(ExperimentalNativeApi::class)
internal fun resolveIosAutoEnter(): Boolean =
    Platform.isDebugBinary && autoEnterRequested(NSProcessInfo.processInfo.environment[AUTO_ENTER_KEY] as? String)

/** The `scheme://host[:port]` origin of [url], or [url] itself when it cannot be parsed. */
internal fun originOf(url: String): String {
    val components = NSURLComponents.componentsWithString(url) ?: return url
    val port = components.port?.let { ":$it" } ?: ""
    return "${components.scheme}://${components.host}$port"
}
