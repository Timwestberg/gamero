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
data class HostCapabilitiesResult(val methods: List<String>, val events: List<String>)

@Serializable
data class HostInfoResult(val platform: HostPlatform, val appVersion: String, val bridgeVersion: Int = BridgeProtocol.VERSION)

/**
 * The methods the router answers itself. Each capability declares its own
 * methods beside its interface (`LinkMethods`, `HapticMethods`, ...).
 */
object BridgeMethods {
    val HOST_CAPABILITIES = BridgeMethod("host.capabilities", NoArgs.serializer(), HostCapabilitiesResult.serializer())
    val HOST_INFO = BridgeMethod("host.info", NoArgs.serializer(), HostInfoResult.serializer())
}

/** One method and the code that runs it. Made by [BridgeMethod.handle]. */
internal class MethodHandler(
    val method: BridgeMethod<*, *>,
    val accepts: (JsonObject) -> Boolean,
    val invoke: suspend (JsonObject) -> JsonElement,
)

/** Binds [body] to this method. The router has checked the arguments before [body] runs. */
internal fun <A, R> BridgeMethod<A, R>.handle(body: suspend (A) -> R): MethodHandler = MethodHandler(
    method = this,
    accepts = { args -> decodeArgs(args) != null },
    invoke = { args -> encodeResult(body(decodeArgs(args)!!)) },
)

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
 * dot-separated ASCII labels whose last label has a letter, an optional port,
 * then printable ASCII with no backslash. A pattern, not a URL parser, so
 * every host decides the same way. It spells out case instead of using
 * `IGNORE_CASE`, which folds Unicode case on the JVM and would accept `httpſ`.
 */
private val HTTPS_URL = Regex(
    "^[hH][tT][tT][pP][sS]://(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)*(?=[A-Za-z0-9-]*[A-Za-z])[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?" +
        "(?::([0-9]{1,5}))?(?:[/?#][!-\\[\\]-~]*)?$",
)
