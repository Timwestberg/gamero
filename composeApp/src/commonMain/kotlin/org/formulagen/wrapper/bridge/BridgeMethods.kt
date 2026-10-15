package org.formulagen.wrapper.bridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * One method of `host-bridge.v2`: its wire name, its argument and result
 * serializers, and any rule a serializer cannot express. Mirrors
 * `HOST_BRIDGE_METHODS` in event-contracts.
 */
class BridgeMethod<A, R>(
    val name: String,
    val args: KSerializer<A>,
    val result: KSerializer<R>,
    val accepts: (A) -> Boolean = { true },
)

/** Event names of `host-bridge.v2`. Mirrors `HOST_BRIDGE_EVENTS`. */
object BridgeEvents {
    const val LIFECYCLE_PAUSE = "lifecycle.pause"
    const val LIFECYCLE_RESUME = "lifecycle.resume"
    val ALL = listOf(LIFECYCLE_PAUSE, LIFECYCLE_RESUME)
}

@Serializable
data object NoArgs

@Serializable
data object NoResult

@Serializable
enum class HostPlatform {
    @SerialName("desktop") DESKTOP,
    @SerialName("android") ANDROID,
    @SerialName("ios") IOS,
}

@Serializable
enum class HapticEffect {
    @SerialName("light") LIGHT,
    @SerialName("medium") MEDIUM,
    @SerialName("heavy") HEAVY,
    @SerialName("selection") SELECTION,
    @SerialName("success") SUCCESS,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

@Serializable
data class HostCapabilitiesResult(val methods: List<String>, val events: List<String>)

@Serializable
data class HostInfoResult(val platform: HostPlatform, val appVersion: String, val bridgeVersion: Int = BridgeProtocol.VERSION)

@Serializable
data class LinkOpenArgs(val url: String)

@Serializable
data class HapticPlayArgs(val effect: HapticEffect)

@Serializable
data class CompanionCapabilitiesResult(val connectComputer: Boolean = true, val cloud: Boolean = false, val sandbox: String = "read-only")

@Serializable
data class CompanionPrepareArgs(val ownerId: String)

@Serializable
data class CompanionRegistration(val id: String, val name: String, val tokenHash: String)

@Serializable
data class CompanionActivateArgs(val ownerId: String, val hostId: String)

object BridgeMethods {
    val HOST_CAPABILITIES = BridgeMethod("host.capabilities", NoArgs.serializer(), HostCapabilitiesResult.serializer())
    val HOST_INFO = BridgeMethod("host.info", NoArgs.serializer(), HostInfoResult.serializer())
    val LINK_OPEN = BridgeMethod("link.open", LinkOpenArgs.serializer(), NoResult.serializer()) { isSafeHttpsUrl(it.url) }
    val HAPTIC_PLAY = BridgeMethod("haptic.play", HapticPlayArgs.serializer(), NoResult.serializer())
    val COMPANION_CAPABILITIES =
        BridgeMethod("companion.capabilities", NoArgs.serializer(), CompanionCapabilitiesResult.serializer())
    val COMPANION_PREPARE = BridgeMethod("companion.prepareConnection", CompanionPrepareArgs.serializer(), CompanionRegistration.serializer()) {
        BridgeProtocol.UUID_PATTERN.matches(it.ownerId)
    }
    val COMPANION_ACTIVATE = BridgeMethod("companion.activateConnection", CompanionActivateArgs.serializer(), JsonObject.serializer()) {
        BridgeProtocol.UUID_PATTERN.matches(it.ownerId) && BridgeProtocol.UUID_PATTERN.matches(it.hostId)
    }

    /** Every method any host defines. A method not listed here is `unknown-method` everywhere. */
    val ALL: List<BridgeMethod<*, *>> = listOf(
        HOST_CAPABILITIES, HOST_INFO, LINK_OPEN, HAPTIC_PLAY, COMPANION_CAPABILITIES, COMPANION_PREPARE, COMPANION_ACTIVATE,
    )
}

/** Strict: an unknown key fails, as `z.strictObject` does. */
internal val bridgeJson = Json { ignoreUnknownKeys = false; isLenient = false; explicitNulls = false; encodeDefaults = true }

/** Decodes and checks arguments, or returns null when they do not match the method. */
internal fun <A> BridgeMethod<A, *>.decodeArgs(args: JsonObject): A? {
    val decoded = runCatching { bridgeJson.decodeFromJsonElement(this.args, args) }.getOrNull() ?: return null
    return decoded.takeIf { accepts(it) }
}

internal fun <R> BridgeMethod<*, R>.encodeResult(result: R): JsonElement = bridgeJson.encodeToJsonElement(this.result, result)

/**
 * HTTPS with a host and no credentials. The system browser must not open
 * `intent:`, `file:` or `javascript:`, and must not send a password in the URL.
 */
internal fun isSafeHttpsUrl(value: String): Boolean {
    val port = HTTPS_URL.matchEntire(value)?.groupValues?.get(1) ?: return false
    return port.isEmpty() || port.toInt() in 1..65535
}

/**
 * `HOST_BRIDGE_HTTPS_URL_PATTERN` in event-contracts: `https://`, a host of
 * dot-separated labels, an optional port, then printable ASCII with no
 * backslash. A pattern, not a URL parser, so every host decides the same way.
 */
private val HTTPS_URL = Regex("^https://[a-z0-9-]+(?:\\.[a-z0-9-]+)*(?::(\\d{1,5}))?(?:[/?#][!-\\[\\]-~]*)?$", RegexOption.IGNORE_CASE)
