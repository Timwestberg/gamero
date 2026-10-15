package org.formulagen.wrapper.bridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One method of `host-bridge.v2`: its wire name, its argument and result
 * serializers, how long a call may run, and any rule a serializer cannot
 * express. Mirrors `HOST_BRIDGE_METHODS` in event-contracts.
 */
class BridgeMethod<A, R>(
    val name: String,
    val args: KSerializer<A>,
    val result: KSerializer<R>,
    /** A call that runs longer is cancelled and answers `failed`. */
    val timeout: Duration = DEFAULT_TIMEOUT,
    val accepts: (A) -> Boolean = { true },
) {
    companion object {
        val DEFAULT_TIMEOUT: Duration = 30.seconds
    }
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

/**
 * One event of `host-bridge.v2`: its wire name and its payload serializer.
 * Mirrors `HOST_BRIDGE_EVENTS` in event-contracts.
 */
class BridgeEvent<D>(val name: String, val data: KSerializer<D>)

/** Sends typed events. The router gives one to each capability that has events. */
interface EventSink {
    fun <D> emit(event: BridgeEvent<D>, data: D)
}

/** An event payload with no fields. It encodes as `{}`. */
@Serializable
data object NoData

/** One method and the code that runs it. */
internal class MethodHandler(
    val method: BridgeMethod<*, *>,
    val accepts: (JsonObject) -> Boolean,
    val invoke: suspend (JsonObject) -> JsonElement,
)

/** A method bound to a capability of type [T]. Made by [BridgeMethod.handle]. */
class CapabilityHandler<in T> internal constructor(
    internal val method: BridgeMethod<*, *>,
    internal val bind: (T) -> MethodHandler,
)

/**
 * Binds [body] to this method for a capability of type [T]. The router has
 * checked the arguments before [body] runs.
 */
fun <T, A, R> BridgeMethod<A, R>.handle(body: suspend (capability: T, args: A) -> R): CapabilityHandler<T> =
    CapabilityHandler(this) { capability -> answer { args -> body(capability, args) } }

/** Binds [body] to this method with no capability. The router uses it for `host.*`. */
internal fun <A, R> BridgeMethod<A, R>.answer(body: suspend (A) -> R): MethodHandler = MethodHandler(
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
