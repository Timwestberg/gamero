package org.formulagen.wrapper.progression.embedded

import java.awt.event.KeyEvent
import kotlin.test.*

/** Boundary checks for the pinned engine's binary channel. */
class GodotEmbedCodecTest {
    /** Context identifiers are unsigned and can exceed a signed 32-bit integer. */
    @Test fun acceptsFullContextRange() {
        val packet = GodotEmbedCodec.encode(listOf("game_view:set_context_id", 1L, listOf(4206580512L)))
        assertEquals(4206580512L, GodotEmbedCodec.contextId(packet))
    }

    /** Non-surface debugger traffic never becomes an application command. */
    @Test fun discardsUnrelatedMessages() {
        assertNull(GodotEmbedCodec.contextId(GodotEmbedCodec.encode(listOf("output", 1L, listOf("hello")))))
    }

    /** A debugger stop must surface its error instead of looking like a frozen game. */
    @Test fun reportsScriptStops() {
        val error = GodotEmbedCodec.encode("Assertion failed: native handoff")
        val body = GodotEmbedCodec.buffer(16 + error.size + 8 + 8).apply {
            putInt(28); putInt(4)
            putInt(1); putInt(1)
            put(error)
            putInt(1); putInt(1)
            putInt(2); putInt(1)
        }.array()
        val name = GodotEmbedCodec.encode("debug_enter")
        val thread = GodotEmbedCodec.encode(1L)
        val packet = GodotEmbedCodec.buffer(8 + name.size + thread.size + body.size).apply {
            putInt(28); putInt(3); put(name); put(thread); put(body)
        }.array()
        assertEquals("Game script stopped: Assertion failed: native handoff",
            assertFailsWith<IllegalStateException> { GodotEmbedCodec.contextId(packet) }.message)
    }

    /** Truncated and invalid context messages cannot reach the native pointer boundary. */
    @Test fun rejectsInvalidContexts() {
        val packet = GodotEmbedCodec.encode(listOf("game_view:set_context_id", 1L, listOf(20L)))
        for (length in packet.indices) assertFails { GodotEmbedCodec.contextId(packet.copyOf(length)) }
        for (value in listOf(0L, -1L, 0x100000000L)) assertFails {
            GodotEmbedCodec.contextId(GodotEmbedCodec.encode(listOf("game_view:set_context_id", 1L, listOf(value))))
        }
    }

    /** Numeric values and byte arrays follow the documented little-endian Variant representation. */
    @Test fun nativeWireValues() {
        assertContentEquals(byteArrayOf(6,0,0,0, 0,5,0,0, 32,3,0,0), GodotEmbedCodec.encode(GodotEmbedCodec.Size(1280,800)))
        assertContentEquals(byteArrayOf(29,0,0,0, 3,0,0,0, 1,2,3,0), GodotEmbedCodec.encode(byteArrayOf(1,2,3)))
        val message = GodotEmbedCodec.message("embed:window_size", listOf(GodotEmbedCodec.Size(1280,800)))
        val length = GodotEmbedCodec.buffer(4).put(message, 0, 4).flip().int
        assertEquals(message.size - 4, length)
    }

    /** Gameplay physical keys retain their Godot identity, including special navigation codes. */
    @Test fun mapsGameplayKeys() {
        assertEquals(65, GameCanvas.keyCode(KeyEvent.VK_A))
        assertEquals(32, GameCanvas.keyCode(KeyEvent.VK_SPACE))
        assertEquals(4194305, GameCanvas.keyCode(KeyEvent.VK_ESCAPE))
        assertEquals(4194319, GameCanvas.keyCode(KeyEvent.VK_LEFT))
        assertNull(GameCanvas.keyCode(KeyEvent.VK_UNDEFINED))
    }
}
