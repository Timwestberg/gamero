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

    @Test fun `POST-only native events dispatch all standard shortcuts once`() {
        for (code in listOf(90, 88, 67, 86, 65)) assertTrue(handler.onKeyEvent(native, event(code)))
        assertTrue(handler.onKeyEvent(native, event(90, EVENTFLAG_COMMAND_DOWN or EVENTFLAG_SHIFT_DOWN)))
        assertEquals(listOf("undo", "cut", "copy", "paste", "selectAll", "redo"), calls)
    }

    @Test fun `one press sequence edits once and a subsequent or repeating raw press edits again`() {
        for (type in EventType.entries) handler.onKeyEvent(native, event(type = type))
        assertEquals(listOf("paste"), calls)
        assertTrue(handler.onKeyEvent(native, event()))
        assertTrue(handler.onKeyEvent(native, event()))
        assertEquals(listOf("paste", "paste", "paste"), calls)
    }

    @Test fun `PRE marks a shortcut without editing and POST executes once`() {
        repeat(3) {
            val shortcut = BoolRef(false)
            assertFalse(handler.onPreKeyEvent(native, event(), shortcut))
            assertTrue(shortcut.get())
            assertEquals(it, calls.size, "PRE never invokes an edit")
            assertTrue(handler.onKeyEvent(native, event()))
            for (type in listOf(EventType.KEYEVENT_KEYDOWN, EventType.KEYEVENT_CHAR, EventType.KEYEVENT_KEYUP)) {
                assertFalse(handler.onPreKeyEvent(native, event(type = type), BoolRef(false)))
                assertFalse(handler.onKeyEvent(native, event(type = type)))
            }
            assertEquals(it + 1, calls.size)
        }
        assertEquals(listOf("paste", "paste", "paste"), calls)
    }

    @Test fun `POST rechecks activation and current browser after PRE`() {
        assertFalse(handler.onPreKeyEvent(native, event(), BoolRef(false)))
        active = false
        assertFalse(handler.onKeyEvent(native, event()))
        active = true
        assertFalse(handler.onPreKeyEvent(native, event(), BoolRef(false)))
        target = browser(id = 8)
        assertFalse(handler.onKeyEvent(native, event()))
        assertTrue(calls.isEmpty())
    }

    @Test fun `PRE cannot mark shortcuts from another browser or inactive window`() {
        for (source in listOf(null, browser(id = 8), browser(view = Panel()))) {
            val shortcut = BoolRef(false)
            assertFalse(handler.onPreKeyEvent(source, event(), shortcut))
            assertFalse(shortcut.get())
        }
        active = false
        val shortcut = BoolRef(false)
        assertFalse(handler.onPreKeyEvent(native, event(), shortcut))
        assertFalse(shortcut.get())
        assertTrue(calls.isEmpty())
    }

    @Test fun `unsupported modifiers and keys remain browser owned`() {
        for (modifiers in listOf(0, EVENTFLAG_CONTROL_DOWN, EVENTFLAG_COMMAND_DOWN or EVENTFLAG_CONTROL_DOWN,
            EVENTFLAG_COMMAND_DOWN or EVENTFLAG_ALT_DOWN, EVENTFLAG_COMMAND_DOWN or EVENTFLAG_SHIFT_DOWN)) {
            assertNull(browserEditShortcut(event(modifiers = modifiers)))
            val shortcut = BoolRef(false)
            assertFalse(handler.onPreKeyEvent(native, event(modifiers = modifiers), shortcut))
            assertFalse(shortcut.get())
            assertFalse(handler.onKeyEvent(native, event(modifiers = modifiers)))
        }
        assertNull(browserEditShortcut(event(code = 66)))
        assertEquals(BrowserEditAction.PASTE, browserEditShortcut(event(modifiers = EVENTFLAG_COMMAND_DOWN or EVENTFLAG_CAPS_LOCK_ON)))
        assertFalse(handler.onPreKeyEvent(native, null, null))
        assertTrue(calls.isEmpty())
    }

    @Test fun `other browser or component cannot target the hosted frame`() {
        for (source in listOf(null, browser(id = 8), browser(view = Panel()))) {
            assertFalse(handler.onKeyEvent(source, event()))
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun `deactivation blocks immediately and reactivation allows a new press`() {
        active = false
        assertFalse(handler.onKeyEvent(native, event()))
        assertTrue(calls.isEmpty())
        active = true
        assertTrue(handler.onKeyEvent(native, event()))
        assertEquals(listOf("paste"), calls)
    }

    @Test fun `closed closing and detached browser cannot edit`() {
        closed = true
        assertFalse(handler.onKeyEvent(native, event()))
        closed = false
        closing = true
        assertFalse(handler.onKeyEvent(native, event()))
        closing = false
        target = null
        assertFalse(handler.onKeyEvent(native, event()))
        assertTrue(calls.isEmpty())
    }

    @Test fun `invalid and missing focused frames cannot edit`() {
        valid = false
        assertFalse(handler.onKeyEvent(native, event()))
        selectedFrame = null
        assertFalse(handler.onKeyEvent(native, event()))
        assertTrue(calls.isEmpty())
    }

    @Test fun `unmatched events expose bounded structural metadata without editing`() {
        val shapes = mutableListOf<BrowserKeyShape>()
        val diagnosticHandler = BrowserEditKeyboard({ target }, { active }, { _, _ -> }, BrowserKeyDiagnostics(shapes::add))
        val unexpected = CefKeyEvent(EventType.KEYEVENT_KEYDOWN, EVENTFLAG_COMMAND_DOWN, 12345, 54321,
            false, '\u1234', '\u5678', true)
        repeat(5) { assertFalse(diagnosticHandler.onPreKeyEvent(native, unexpected, null)) }
        assertFalse(diagnosticHandler.onKeyEvent(native, unexpected))
        assertEquals(listOf(BrowserKeyPhase.PRE, BrowserKeyPhase.POST), shapes.map { it.phase })
        assertTrue(shapes.all { it.command && !it.editKey && it.editable })
        for (secret in listOf("12345", "54321", "\u1234", "\u5678")) assertFalse(shapes.toString().contains(secret))
        assertTrue(calls.isEmpty())
    }

    @Test fun `diagnostics are capped with reserved space for Command events`() {
        val shapes = mutableListOf<BrowserKeyShape>()
        val diagnostics = BrowserKeyDiagnostics(shapes::add)
        for (command in listOf(0, EVENTFLAG_COMMAND_DOWN)) {
            for (type in EventType.entries) {
                for (flags in listOf(0, EVENTFLAG_SHIFT_DOWN, EVENTFLAG_CONTROL_DOWN, EVENTFLAG_ALT_DOWN)) {
                    for (phase in BrowserKeyPhase.entries) diagnostics.observe(phase, event(modifiers = command or flags, type = type))
                }
            }
        }
        assertEquals(16, shapes.count { !it.command })
        assertEquals(16, shapes.count { it.command })
    }

    @Test fun `bare escape is handled by the voice session and otherwise stays with the page`() {
        assertTrue(isConversationEscape(event(code = 27, modifiers = 0)))
        assertFalse(isConversationEscape(event(code = 27, modifiers = EVENTFLAG_SHIFT_DOWN)))
        assertFalse(isConversationEscape(event(code = 27, modifiers = 0, type = EventType.KEYEVENT_KEYUP)))
        var handled = false
        val voice = BrowserEditKeyboard({ target }, { active }, { _, _ -> }, onEscape = { handled = true; true })
        assertTrue(voice.onKeyEvent(native, event(code = 27, modifiers = 0)))
        assertTrue(handled)
        val page = BrowserEditKeyboard({ target }, { active }, { _, _ -> }, onEscape = { false })
        assertFalse(page.onKeyEvent(native, event(code = 27, modifiers = 0)))
        assertTrue(calls.isEmpty())
    }
}
