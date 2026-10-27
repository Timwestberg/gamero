package org.formulagen.wrapper.progression.embedded

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal Godot 4.8 Variant wire codec for the local embedding channel. */
internal object GodotEmbedCodec {
    const val MAX_PACKET = 8 * 1024 * 1024
    data class Size(val width: Int, val height: Int)

    /** Allocate a little-endian buffer used by Godot's native codecs. */
    fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** Frame an editor message with its main-thread target and arguments. */
    fun message(name: String, arguments: List<Any>): ByteArray {
        val payload = encode(listOf(name, 1L, arguments))
        return buffer(payload.size + 4).putInt(payload.size).put(payload).array()
    }

    /** Encode only the Variant types needed for surface and input control. */
    fun encode(value: Any): ByteArray = when (value) {
        is String -> {
            val bytes = value.toByteArray(Charsets.UTF_8)
            buffer(8 + padded(bytes.size)).putInt(4).putInt(bytes.size).put(bytes).array()
        }
        is Long -> buffer(12).putInt(2 or (1 shl 16)).putLong(value).array()
        is Int -> buffer(8).putInt(2).putInt(value).array()
        is Size -> buffer(12).putInt(6).putInt(value.width).putInt(value.height).array()
        is ByteArray -> buffer(8 + padded(value.size)).putInt(29).putInt(value.size).put(value).array()
        is List<*> -> {
            val parts = value.map { encode(requireNotNull(it)) }
            buffer(8 + parts.sumOf { it.size }).apply { putInt(28); putInt(parts.size); parts.forEach { put(it) } }.array()
        }
        else -> error("Unsupported embedding value: ${value.javaClass.simpleName}")
    }

    /** Inspect only the context announcement; other debugger messages are discarded. */
    fun contextId(payload: ByteArray): Long? {
        require(payload.size in 8..MAX_PACKET)
        val data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(data.int == 28 && data.int == 3) { "Invalid debugger envelope" }
        require(data.int == 4)
        val length = data.int
        require(length in 0..data.remaining() && padded(length) <= data.remaining())
        val bytes = ByteArray(length).also(data::get)
        data.position(data.position() + padded(length) - length)
        val name = bytes.toString(Charsets.UTF_8)
        if (name == "debug_enter") {
            readInteger(data)
            require(data.int == 28 && data.int == 4)
            require(data.int == 1); data.int
            require(data.int == 4)
            val errorLength = data.int
            require(errorLength in 0..data.remaining())
            val error = ByteArray(errorLength).also(data::get).toString(Charsets.UTF_8)
            throw IllegalStateException("Game script stopped: $error")
        }
        if (name != "game_view:set_context_id") return null
        readInteger(data)
        require(data.int == 28 && data.int == 1)
        val context = readInteger(data)
        require(context in 1..0xffffffffL && !data.hasRemaining())
        return context
    }

    /** Read the two integer widths emitted by Godot's serializer. */
    private fun readInteger(data: ByteBuffer): Long = when (data.int) {
        2 -> data.int.toLong()
        2 or (1 shl 16) -> data.long
        else -> error("Invalid embedding integer")
    }

    /** Godot aligns variable-length values to four-byte boundaries. */
    private fun padded(size: Int): Int = (size + 3) and -4
}
