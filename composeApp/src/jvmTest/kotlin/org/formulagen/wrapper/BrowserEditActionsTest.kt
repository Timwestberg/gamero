package org.formulagen.wrapper

import java.awt.Panel
import java.lang.reflect.Proxy
import javax.swing.JMenuItem
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

    private fun actions(owner: java.awt.Component? = component, permanent: java.awt.Component? = owner, active: Boolean = true) =
        BrowserEditActions({ browser }, { owner }, { permanent }, { active }, { _, _ -> })

    @Test fun `each explicit action calls its native operation exactly once`() {
        val actions = actions()
        BrowserEditAction.entries.forEach { assertTrue(actions.perform(it)) }
        assertEquals(listOf("undo", "redo", "cut", "copy", "paste", "selectAll"), calls)
    }

    @Test fun `a focused browser child accepts paste`() {
        assertTrue(actions(child).perform(BrowserEditAction.PASTE))
        assertEquals(listOf("paste"), calls)
    }

    @Test fun `unrelated or missing focus cannot paste into a stale browser frame`() {
        for (owner in listOf(Panel(), null)) {
            assertFalse(actions(owner).perform(BrowserEditAction.PASTE))
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun `closed and closing browsers receive no editing command`() {
        val actions = actions()
        closing = true
        assertFalse(actions.perform(BrowserEditAction.PASTE))
        closing = false
        closed = true
        assertFalse(actions.perform(BrowserEditAction.PASTE))
        assertTrue(calls.isEmpty())
    }

    @Test fun `missing and invalid frames receive no editing command`() {
        val actions = actions()
        valid = false
        assertFalse(actions.perform(BrowserEditAction.PASTE))
        valid = true
        availableFrame = null
        assertFalse(actions.perform(BrowserEditAction.PASTE))
        assertTrue(calls.isEmpty())
    }

    @Test fun `native menu temporary focus retains the browser editing target`() {
        focused = false
        for (owner in listOf(JMenuItem("Paste"), null)) {
            assertTrue(actions(owner, child).perform(BrowserEditAction.PASTE))
        }
        assertEquals(listOf("paste", "paste"), calls)
    }

    @Test fun `permanent browser focus cannot override another control or inactive window`() {
        assertFalse(actions(Panel(), child).perform(BrowserEditAction.PASTE))
        assertFalse(actions(null, child, active = false).perform(BrowserEditAction.PASTE))
        assertFalse(actions(JMenuItem("Paste"), null).perform(BrowserEditAction.PASTE))
        assertTrue(calls.isEmpty())
    }

    @Test fun `diagnostics report bounded action and outcome only`() {
        val outcomes = mutableListOf<Pair<BrowserEditAction, BrowserEditOutcome>>()
        val actions = BrowserEditActions({ browser }, { component }, { component }, { true }) { action, outcome ->
            outcomes.add(action to outcome)
        }
        assertTrue(actions.perform(BrowserEditAction.PASTE))
        closing = true
        assertFalse(actions.perform(BrowserEditAction.COPY))
        assertEquals(listOf(BrowserEditAction.PASTE to BrowserEditOutcome.DISPATCHED,
            BrowserEditAction.COPY to BrowserEditOutcome.BROWSER_CLOSED), outcomes)
    }

    @Test fun `focus diagnostics distinguish ancestry and menus without reading controls`() {
        val ancestor = Panel().also { it.add(component) }
        assertEquals(BrowserFocusRelation.NONE, browserFocusRelation(component, null))
        assertEquals(BrowserFocusRelation.BROWSER, browserFocusRelation(component, component))
        assertEquals(BrowserFocusRelation.DESCENDANT, browserFocusRelation(component, child))
        assertEquals(BrowserFocusRelation.ANCESTOR, browserFocusRelation(component, ancestor))
        assertEquals(BrowserFocusRelation.MENU, browserFocusRelation(component, JMenuItem("private text")))
        assertEquals(BrowserFocusRelation.OTHER, browserFocusRelation(component, Panel()))
    }

    @Test fun `before browser creation and after resource release actions are inert`() {
        assertFalse(BrowserEditActions({ null }, { component }).perform(BrowserEditAction.PASTE))
        assertTrue(calls.isEmpty())
    }
}
