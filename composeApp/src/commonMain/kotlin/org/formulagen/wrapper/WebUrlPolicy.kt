package org.formulagen.wrapper

private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1")

/**
 * The shared rule for which web app URLs a Gamero shell may load: https only, http only on
 * loopback, a host is required, no userinfo, no fragment. Platforms parse the URL and pass its parts.
 * IPv6 hosts may be given with or without brackets.
 */
internal fun isAllowedWebUrl(scheme: String?, host: String?, hasUserInfo: Boolean, hasFragment: Boolean): Boolean {
    if (host.isNullOrEmpty() || hasUserInfo || hasFragment) return false
    return when (scheme?.lowercase()) {
        "https" -> true
        "http" -> host.lowercase().removePrefix("[").removeSuffix("]") in loopbackHosts
        else -> false
    }
}
