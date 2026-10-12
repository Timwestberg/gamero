package org.formulagen.wrapper

import java.awt.Panel
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame

class BrowserEditActionsTest {
    private val component = Panel()
    private val child = Panel().also(component::add)
    private val calls = mutableListOf<String>()
    private var valid = true
    private var focused = true
    private var closing = false
    private var closed = false
    private val frame = Proxy.newProxyInstance(CefFrame::class.java.classLoader, arrayOf(CefFrame::class.java)) { _, method, _ ->
        when (method.name) {
            "isValid" -> valid
            "isFocused" -> focused
            else -> { calls.add(method.name); null }
        }
    } as CefFrame
    private var availableFrame: CefFrame? = frame
    private val browser = Proxy.newProxyInstance(CefBrowser::class.java.classLoader, arrayOf(CefBrowser::class.java)) { _, method, _ ->
        when (method.name) {
            "getUIComponent" -> component
            "getFocusedFrame" -> availableFrame
            "isClosing" -> closing
            "isClosed" -> closed
            else -> error("Unexpected browser call: ${method.name}")
        }
    } as CefBrowser

    @Test fun `each explicit action calls its native operation exactly once`() {
        val actions = BrowserEditActions({ browser }, { component })
        BrowserEditAction.entries.forEach { assertTrue(actions.perform(it)) }
        assertEquals(listOf("undo", "redo", "cut", "copy", "paste", "selectAll"), calls)
    }

    @Test fun `a focused browser child accepts paste`() {
        assertTrue(BrowserEditActions({ browser }, { child }).perform(BrowserEditAction.PASTE))
        assertEquals(listOf("paste"), calls)
    }

    @Test fun `unrelated or missing focus cannot paste into a stale browser frame`() {
        for (owner in listOf(Panel(), null)) {
            assertFalse(BrowserEditActions({ browser }, { owner }).perform(BrowserEditAction.PASTE))
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun `closed and closing browsers receive no editing command`() {
        val actions = BrowserEditActions({ browser }, { component })
        closing = true
        assertFalse(actions.perform(BrowserEditAction.PASTE))
        closing = false
        closed = true
        assertFalse(actions.perform(BrowserEditAction.PASTE))
        assertTrue(calls.isEmpty())
    }

    @Test fun `missing invalid and unfocused frames receive no editing command`() {
        val actions = BrowserEditActions({ browser }, { component })
        valid = false
        assertFalse(actions.perform(BrowserEditAction.PASTE))
        valid = true
        focused = false
        assertFalse(actions.perform(BrowserEditAction.PASTE))
        availableFrame = null
        assertFalse(actions.perform(BrowserEditAction.PASTE))
        assertTrue(calls.isEmpty())
    }

    @Test fun `before browser creation and after resource release actions are inert`() {
        assertFalse(BrowserEditActions({ null }, { component }).perform(BrowserEditAction.PASTE))
        assertTrue(calls.isEmpty())
    }
}
