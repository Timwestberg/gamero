package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/**
 * The transport rules for a WebView that gives each document one message port
 * and no reply per message, such as Android's `addWebMessageListener`. The
 * platform file only moves strings between the port and this class.
 *
 * A port carries frames, because replies and events share it:
 * - page → host: `{"id": n, "request": "<host-bridge.v2 request>"}`
 * - host → page: `{"id": n, "reply": {…}}` or `{"event": {…}}`
 *
 * `n` is a whole number from 0 to 2^53 − 1 that the page picks. The request
 * stays a string, so the router checks the exact text the page sent.
 *
 * The platform makes a new port for each document, so a main-frame message
 * on a new port means the document changed. A reply for the old document
 * goes to the old port, which no page reads. Call [receive] from one thread.
 */
class MessagePortTransport(private val bridge: HostBridge) {
    private var mainPort: Any? = null

    /**
     * Handles one frame from [port]. [send] posts a string to that port; it
     * may be called from any thread, so it must move to the thread the
     * platform API needs.
     */
    fun receive(message: String, frame: PageFrame, port: Any, send: (String) -> Unit) {
        if (frame.mainFrame && port !== mainPort) {
            if (mainPort != null) bridge.documentChanged()
            mainPort = port
        }
        val parsed = parse(message)
        if (parsed == null) {
            log(LogLevel.Warn, TAG, "Dropped a message that is not a host bridge frame")
            return
        }
        val (id, request) = parsed
        val channel = object : BridgeChannel {
            override fun reply(json: String) = send("""{"id":$id,"reply":$json}""")
            override fun event(json: String) = send("""{"event":$json}""")
        }
        if (request == null) channel.reply(bridgeFailureEnvelope(BridgeErrorCode.INVALID))
        else bridge.receive(request, frame, channel)
    }

    /** The id and request of a frame. The request is null when the frame has an id but no request string. */
    private fun parse(message: String): Pair<Long, String?>? {
        if (message.length > MAX_FRAME_CHARS) return null
        val frame = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject ?: return null
        val id = (frame["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..MAX_ID } ?: return null
        val request = (frame["request"] as? JsonPrimitive)?.takeIf { it.isString && frame.keys == FRAME_KEYS }?.content
        return id to request
    }

    private companion object {
        const val TAG = "MessagePortTransport"
        val FRAME_KEYS = setOf("id", "request")
        const val MAX_ID = (1L shl 53) - 1

        /** A request at the size limit, with every character escaped as `\uXXXX`. Larger frames are dropped unread. */
        const val MAX_FRAME_CHARS = BridgeProtocol.MAX_REQUEST_CHARS * 6 + 64
    }
}
