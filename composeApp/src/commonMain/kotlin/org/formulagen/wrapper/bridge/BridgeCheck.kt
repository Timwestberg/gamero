package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Support for one method on this host. A method missing from the map is unknown. */
enum class MethodSupport { SUPPORTED, UNSUPPORTED }

sealed interface BridgeCheck {
    data object WatchContext : BridgeCheck
    data class Call(val contextId: String, val method: String, val args: JsonObject) : BridgeCheck
    data class Reject(val code: BridgeErrorCode, val method: String? = null) : BridgeCheck
}

private val WATCH_CONTEXT_KEYS = setOf("version", "method", "args")
private val REQUEST_KEYS = setOf("version", "contextId", "method", "args")

/**
 * The router checks, in the order of `checkHostBridgeRequest` in event-contracts.
 * The first failure wins, so the shared fixtures pin this order.
 */
internal fun checkBridgeRequest(
    raw: String,
    frame: FrameFacts,
    contextId: String?,
    support: (String) -> MethodSupport?,
    acceptsArgs: (String, JsonObject) -> Boolean,
): BridgeCheck {
    if (!frame.mainFrame || !frame.trustedOrigin) return BridgeCheck.Reject(BridgeErrorCode.FORBIDDEN)
    if (raw.length > BridgeProtocol.MAX_REQUEST_CHARS) return BridgeCheck.Reject(BridgeErrorCode.INVALID)
    val message = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: return BridgeCheck.Reject(BridgeErrorCode.INVALID)
    val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    if (message.keys == WATCH_CONTEXT_KEYS && method == BridgeProtocol.WATCH_CONTEXT &&
        BridgeEnvelopes.isVersion(message["version"]) && BridgeEnvelopes.isEmptyObject(message["args"])
    ) return BridgeCheck.WatchContext

    val requestContext = (message["contextId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val args = message["args"] as? JsonObject
    if (message.keys != REQUEST_KEYS || !BridgeEnvelopes.isVersion(message["version"]) ||
        requestContext == null || !BridgeProtocol.UUID_PATTERN.matches(requestContext) ||
        method == null || !BridgeProtocol.METHOD_PATTERN.matches(method) || args == null
    ) return BridgeCheck.Reject(BridgeErrorCode.INVALID)

    if (contextId == null || requestContext != contextId) return BridgeCheck.Reject(BridgeErrorCode.EXPIRED)
    when (support(method)) {
        null -> return BridgeCheck.Reject(BridgeErrorCode.UNKNOWN_METHOD)
        MethodSupport.UNSUPPORTED -> return BridgeCheck.Reject(BridgeErrorCode.UNSUPPORTED, method)
        MethodSupport.SUPPORTED -> Unit
    }
    if (!acceptsArgs(method, args)) return BridgeCheck.Reject(BridgeErrorCode.INVALID)
    return BridgeCheck.Call(requestContext, method, args)
}
