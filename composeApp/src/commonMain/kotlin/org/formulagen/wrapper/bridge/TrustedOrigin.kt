package org.formulagen.wrapper.bridge

/**
 * The one origin whose main frame may call the host: the scheme, host and port
 * of the launch URL. Every transport checks frames with it, so a platform does
 * not parse origins its own way.
 */
class TrustedOrigin(url: String) {
    private val trusted = parse(url) ?: throw IllegalArgumentException("A trusted HTTP(S) origin is required")

    /** True when [url], a frame URL or origin that the platform reported, has the trusted origin. */
    fun matches(url: String): Boolean = parse(url) == trusted

    /** `scheme://host[:port]`, without a default port. Android's `allowedOriginRules` takes this form. */
    val rule: String = buildString {
        append(trusted.scheme).append("://").append(trusted.host)
        if (trusted.port != DEFAULT_PORTS.getValue(trusted.scheme)) append(':').append(trusted.port)
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private companion object {
        val DEFAULT_PORTS = mapOf("https" to 443, "http" to 80)
        val HOST = Regex("^[a-z0-9.-]+$|^\\[[0-9a-f:.]+]$")

        /** HTTP(S) only, with a host and no user info. Anything else has no origin to trust. */
        fun parse(value: String): Origin? {
            val separator = value.indexOf("://")
            if (separator <= 0) return null
            val scheme = value.substring(0, separator).lowercase()
            val defaultPort = DEFAULT_PORTS[scheme] ?: return null
            val rest = value.substring(separator + 3)
            val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).takeIf { it >= 0 } ?: rest.length)
            if ('@' in authority) return null
            val portStart = authority.lastIndexOf(':').takeIf { it > authority.lastIndexOf(']') } ?: -1
            val host = (if (portStart >= 0) authority.substring(0, portStart) else authority).lowercase()
            if (host.isEmpty() || !HOST.matches(host)) return null
            val port = if (portStart < 0) defaultPort else {
                val digits = authority.substring(portStart + 1)
                if (digits.isEmpty()) defaultPort
                else digits.takeIf { it.length <= 5 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1..65535 } ?: return null
            }
            return Origin(scheme, host, port)
        }
    }
}
