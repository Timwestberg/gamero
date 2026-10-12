package org.formulagen.wrapper

import java.awt.Panel
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefKeyboardHandler.CefKeyEvent
import org.cef.handler.CefKeyboardHandler.CefKeyEvent.EventType
import org.cef.misc.BoolRef
import org.cef.misc.EventFlags.*

class BrowserEditKeyboardTest {
    private val component = Panel()
    private val calls = mutableListOf<String>()
    private var active = true
    private var closed = false
    private var closing = false
    private var valid = true
    private val frame = Proxy.newProxyInstance(CefFrame::class.java.classLoader, arrayOf(CefFrame::class.java)) { _, method, _ ->
        when (method.name) {
            "isValid" -> valid
            else -> { calls.add(method.name); null }
        }
    } as CefFrame
    private var selectedFrame: CefFrame? = frame
    private fun browser(id: Int = 7, view: Panel = component) =
        Proxy.newProxyInstance(CefBrowser::class.java.classLoader, arrayOf(CefBrowser::class.java)) { _, method, _ ->
            when (method.name) {
                "getIdentifier" -> id
                "getUIComponent" -> view
                "getFocusedFrame" -> selectedFrame
                "isClosing" -> closing
                "isClosed" -> closed
                else -> error("Unexpected browser call: ${method.name}")
            }
        } as CefBrowser
    private val wrapper = browser()
    private val native = browser()
    private var target: CefBrowser? = wrapper
    private val handler = BrowserEditKeyboard({ target }, { active }, { _, _ -> })
    private fun event(code: Int = 86, modifiers: Int = EVENTFLAG_COMMAND_DOWN,
                      type: EventType = EventType.KEYEVENT_RAWKEYDOWN) =
        CefKeyEvent(type, modifiers, code, 0, false, '\u0000', '\u0000', true)

    @Test fun `native wrapper identity dispatches all standard shortcuts once`() {
        val shortcut = BoolRef(false)
        for (code in listOf(90, 88, 67, 86, 65)) assertTrue(handler.onPreKeyEvent(native, event(code), shortcut))
        assertTrue(handler.onPreKeyEvent(native, event(90, EVENTFLAG_COMMAND_DOWN or EVENTFLAG_SHIFT_DOWN), shortcut))
        assertEquals(listOf("undo", "cut", "copy", "paste", "selectAll", "redo"), calls)
        assertFalse(shortcut.get())
    }

    @Test fun `one press sequence edits once and a subsequent or repeating raw press edits again`() {
        for (type in EventType.entries) handler.onPreKeyEvent(native, event(type = type), BoolRef(false))
        assertEquals(listOf("paste"), calls)
        assertTrue(handler.onPreKeyEvent(native, event(), BoolRef(false)))
        assertTrue(handler.onPreKeyEvent(native, event(), BoolRef(false)))
        assertEquals(listOf("paste", "paste", "paste"), calls)
    }

    @Test fun `post renderer callback never repeats prehandled edit`() {
        assertTrue(handler.onPreKeyEvent(native, event(), BoolRef(false)))
        assertFalse(handler.onKeyEvent(native, event()))
        assertEquals(listOf("paste"), calls)
    }

    @Test fun `unsupported modifiers and keys remain browser owned`() {
        for (modifiers in listOf(0, EVENTFLAG_CONTROL_DOWN, EVENTFLAG_COMMAND_DOWN or EVENTFLAG_CONTROL_DOWN,
            EVENTFLAG_COMMAND_DOWN or EVENTFLAG_ALT_DOWN, EVENTFLAG_COMMAND_DOWN or EVENTFLAG_SHIFT_DOWN)) {
            assertNull(browserEditShortcut(event(modifiers = modifiers)))
        }
        assertNull(browserEditShortcut(event(code = 66)))
        assertEquals(BrowserEditAction.PASTE, browserEditShortcut(event(modifiers = EVENTFLAG_COMMAND_DOWN or EVENTFLAG_CAPS_LOCK_ON)))
        assertFalse(handler.onPreKeyEvent(native, null, null))
        assertTrue(calls.isEmpty())
    }

    @Test fun `other browser or component cannot target the hosted frame`() {
        for (source in listOf(null, browser(id = 8), browser(view = Panel()))) {
            assertFalse(handler.onPreKeyEvent(source, event(), null))
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun `deactivation blocks immediately and reactivation allows a new press`() {
        active = false
        assertFalse(handler.onPreKeyEvent(native, event(), null))
        assertTrue(calls.isEmpty())
        active = true
        assertTrue(handler.onPreKeyEvent(native, event(), null))
        assertEquals(listOf("paste"), calls)
    }

    @Test fun `closed closing and detached browser cannot edit`() {
        closed = true
        assertFalse(handler.onPreKeyEvent(native, event(), null))
        closed = false
        closing = true
        assertFalse(handler.onPreKeyEvent(native, event(), null))
        closing = false
        target = null
        assertFalse(handler.onPreKeyEvent(native, event(), null))
        assertTrue(calls.isEmpty())
    }

    @Test fun `invalid and missing focused frames cannot edit`() {
        valid = false
        assertFalse(handler.onPreKeyEvent(native, event(), null))
        selectedFrame = null
        assertFalse(handler.onPreKeyEvent(native, event(), null))
        assertTrue(calls.isEmpty())
    }
}
