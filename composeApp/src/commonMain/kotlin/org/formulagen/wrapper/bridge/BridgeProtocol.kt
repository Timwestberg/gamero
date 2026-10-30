package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * `host-bridge.v2`. The contract and its fixtures live in
 * server-platform `event-contracts/src/host-bridge`; this file must agree with them.
 */
object BridgeProtocol {
    const val VERSION = 2

    /** UTF-16 code units, the unit both JS and Kotlin `String.length` count. */
    const val MAX_REQUEST_CHARS = 64 * 1024
    const val MAX_ERROR_MESSAGE_CHARS = 256
    const val WATCH_CONTEXT = "watchContext"

    val METHOD_PATTERN = Regex("^[a-z][a-zA-Z0-9]{0,31}\\.[a-z][a-zA-Z0-9]{0,31}$")

    /** The pattern zod 4 uses for `z.uuid()`. */
    val UUID_PATTERN = Regex(
        "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}" +
            "|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$",
    )
}

/** Failure codes, in the order the router checks for them. The page reads only the code. */
enum class BridgeErrorCode(val wire: String, val message: String) {
    FORBIDDEN("forbidden", "This frame cannot call the host"),
    INVALID("invalid", "The request does not match host-bridge.v2"),
    EXPIRED("expired", "The bridge context expired; open a new one"),
    UNKNOWN_METHOD("unknown-method", "This host does not define this method"),
    UNSUPPORTED("unsupported", "This host does not support this method"),
    BUSY("busy", "Another call to this capability is running"),
    DECLINED("declined", "The person declined the request"),
    FAILED("failed", "The host could not complete the call"),
}

/**
 * Facts a transport reads from the platform API, never from the page.
 * [documentId] changes on each navigation, so a context cannot outlive its document.
 */
data class FrameFacts(val mainFrame: Boolean, val trustedOrigin: Boolean, val documentId: String)

/** Thrown by a single-flight capability that is already running a call. The router answers `busy`. */
class BridgeBusyException : IllegalStateException("Another call to this capability is running")

/**
 * Fixed compatibility copy, safe to expose without leaking a storage path or raw exception. The router answers it as
 * `failed` with [MESSAGE], which mirrors `HOST_BRIDGE_UPDATE_REQUIRED_MESSAGE` (`fixtures/limits.json`), so a page can
 * tell it from other failures.
 */
class BridgeUpdateRequiredException : IllegalStateException(MESSAGE) {
    companion object {
        const val MESSAGE = "Update Hovel and reload this page."
    }
}

/** Thrown by a capability when the person said no. The router answers `declined`. */
class BridgeDeclinedException(message: String) : IllegalStateException(message)

/** A failure reply with the code's standard message, for transports that reject before the router. */
internal fun bridgeFailureEnvelope(code: BridgeErrorCode): String = BridgeEnvelopes.failure(code, code.message)

internal object BridgeEnvelopes {
    fun watchContextReply(contextId: String): String = buildJsonObject {
        put("version", BridgeProtocol.VERSION)
        put("contextId", contextId)
    }.toString()

    fun success(result: JsonElement): String = buildJsonObject {
        put("version", BridgeProtocol.VERSION)
        put("ok", true)
        put("result", result)
    }.toString()

    fun failure(code: BridgeErrorCode, message: String): String = buildJsonObject {
        put("version", BridgeProtocol.VERSION)
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code.wire)
            put("message", message.take(BridgeProtocol.MAX_ERROR_MESSAGE_CHARS))
        })
    }.toString()

    fun event(name: String, data: JsonElement): String = buildJsonObject {
        put("version", BridgeProtocol.VERSION)
        put("event", name)
        put("data", data)
    }.toString()

    /** True for the JSON number 2, as zod's `z.literal(2)` reads it; false for the string "2". */
    fun isVersion(element: JsonElement?): Boolean =
        element is JsonPrimitive && !element.isString && element.doubleOrNull == BridgeProtocol.VERSION.toDouble()

    fun isEmptyObject(element: JsonElement?): Boolean = element is JsonObject && element.isEmpty()
}
