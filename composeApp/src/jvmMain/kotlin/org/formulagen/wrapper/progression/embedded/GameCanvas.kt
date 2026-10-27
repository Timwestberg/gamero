package org.formulagen.wrapper.progression.embedded

import java.awt.Canvas
import java.awt.Color
import java.awt.event.*

/** Input belongs to the game only while its surface is active. */
internal class GameCanvas(private val game: MacEmbeddedGame) : Canvas() {
    private val heldKeys = mutableSetOf<Int>()
    private var buttons = 0
    private var previousX = 0
    private var previousY = 0
    var acceptsInput = true
        set(value) { if (field && !value) release(); field = value }

    init {
        background = Color(12, 15, 19)
        isFocusable = true
        focusTraversalKeysEnabled = false
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) { release() }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) { key(event, true) }
            override fun keyReleased(event: KeyEvent) { key(event, false) }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (acceptsInput) { requestFocusInWindow(); mouse(event, true) }
            }
            override fun mouseReleased(event: MouseEvent) { mouse(event, false) }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) { motion(event) }
            override fun mouseDragged(event: MouseEvent) { motion(event) }
        })
        addMouseWheelListener { event ->
            if (acceptsInput) {
                val button = if (event.wheelRotation < 0) 4 else 5
                repeat(kotlin.math.abs(event.wheelRotation).coerceAtMost(10)) {
                    game.input(mousePacket(event, button, true)); game.input(mousePacket(event, button, false))
                }
            }
        }
    }

    /** Clear native and engine state together across modal or focus transitions. */
    fun release() { heldKeys.clear(); buttons = 0; game.releaseInput() }

    /** Preserve press, release, repeat and physical key identity. */
    private fun key(event: KeyEvent, pressed: Boolean) {
        if (!acceptsInput) return
        val code = keyCode(event.keyCode) ?: return
        val echo = pressed && !heldKeys.add(code)
        if (!pressed) heldKeys.remove(code)
        val flags = modifiers(event) or (if (pressed) 32 else 0) or (if (echo) 16 else 0)
        game.input(GodotEmbedCodec.buffer(19).put(0).put(flags.toByte()).putInt(code).putInt(code).putInt(code)
            .putInt(if (event.keyChar == KeyEvent.CHAR_UNDEFINED) 0 else event.keyChar.code).put(0).array())
        event.consume()
    }

    /** Translate AWT's middle/right ordering into Godot's button numbering. */
    private fun mouse(event: MouseEvent, pressed: Boolean) {
        if (!acceptsInput) return
        val button = when (event.button) { 1 -> 1; 2 -> 3; 3 -> 2; else -> return }
        buttons = if (pressed) buttons or (1 shl (button - 1)) else buttons and (1 shl (button - 1)).inv()
        game.input(mousePacket(event, button, pressed))
    }

    /** Encode point coordinates; Godot applies the render scale. */
    private fun mousePacket(event: MouseEvent, button: Int, pressed: Boolean): ByteArray = GodotEmbedCodec.buffer(12)
        .put(1).put((modifiers(event) or (if (pressed) 32 else 0) or (if (event.clickCount > 1) 64 else 0)).toByte())
        .put(button.toByte()).putFloat(event.x.toFloat()).putFloat(event.y.toFloat()).put(buttons.toByte()).array()

    /** Keep aiming and hover coordinates synchronized with the embedded surface. */
    private fun motion(event: MouseEvent) {
        if (acceptsInput) game.input(GodotEmbedCodec.buffer(31).put(2).put(modifiers(event).toByte())
            .putFloat(event.x.toFloat()).putFloat(event.y.toFloat()).putFloat(0f).putFloat(0f).putFloat(0f)
            .putFloat((event.x - previousX).toFloat()).putFloat((event.y - previousY).toFloat()).put(buttons.toByte()).array())
        previousX = event.x; previousY = event.y
    }

    companion object {
        /** Match the pinned engine's key modifier bits. */
        private fun modifiers(event: InputEvent): Int = (if (event.isShiftDown) 1 else 0) or
            (if (event.isControlDown) 2 else 0) or (if (event.isAltDown) 4 else 0) or (if (event.isMetaDown) 8 else 0)

        /** Map common gameplay keys and navigation without depending on keyboard layout text. */
        internal fun keyCode(awt: Int): Int? {
            if (awt in KeyEvent.VK_A..KeyEvent.VK_Z || awt in KeyEvent.VK_0..KeyEvent.VK_9 || awt == KeyEvent.VK_SPACE) return awt
            val special = when (awt) {
                KeyEvent.VK_ESCAPE -> 1; KeyEvent.VK_TAB -> 2; KeyEvent.VK_BACK_SPACE -> 4; KeyEvent.VK_ENTER -> 5
                KeyEvent.VK_LEFT -> 15; KeyEvent.VK_UP -> 16; KeyEvent.VK_RIGHT -> 17; KeyEvent.VK_DOWN -> 18
                KeyEvent.VK_SHIFT -> 21; KeyEvent.VK_CONTROL -> 22; KeyEvent.VK_META -> 23; KeyEvent.VK_ALT -> 24
                in KeyEvent.VK_F1..KeyEvent.VK_F12 -> 28 + awt - KeyEvent.VK_F1
                else -> return null
            }
            return (1 shl 22) or special
        }
    }
}
