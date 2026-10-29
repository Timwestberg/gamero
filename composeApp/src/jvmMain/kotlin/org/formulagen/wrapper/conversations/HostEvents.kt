package org.formulagen.wrapper.conversations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Server-sent events from the host stream, read as `hostStreamEvent`s. A frame's `data` is the event
 * itself, or the server's SSE envelope whose `payload` it is. A heartbeat, a comment, an event type
 * this build does not know, or malformed data is skipped, so one odd frame never drops the stream.
 */
internal object HostEvents {
    /** Hands each event of [lines] to [onEvent], frame by frame, until the lines end. */
    fun read(lines: Sequence<String>, onEvent: (HostStreamEvent) -> Unit) {
        val data = StringBuilder()
        var hasData = false
        for (raw in lines) {
            val line = raw.removeSuffix("\r")
            when {
                line.isEmpty() -> {
                    if (hasData) decode(data.toString())?.let(onEvent)
                    data.setLength(0); hasData = false
                }
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    if (hasData) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" ")); hasData = true
                    check(data.length <= MAX_FRAME) { "Host event frame too long" }
                }
                else -> Unit
            }
        }
    }

    /** One frame's data as a host event, or null when it is none this build reads. */
    fun decode(data: String): HostStreamEvent? = runCatching {
        val element = lenient.parseToJsonElement(data) as? JsonObject ?: return null
        val event = (element["payload"] as? JsonObject)?.takeIf { "sequence" in element } ?: element
        fromWire(HostStreamEvent.serializer(), event)
    }.getOrNull()

    /** The longest frame kept: host events name identifiers only. */
    private const val MAX_FRAME = 64 * 1024
    private val lenient = Json
}
