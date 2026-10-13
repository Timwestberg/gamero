package org.formulagen.wrapper

import java.awt.Component
import java.awt.KeyboardFocusManager
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame

internal enum class BrowserEditAction(val apply: (CefFrame) -> Unit) {
    UNDO(CefFrame::undo), REDO(CefFrame::redo), CUT(CefFrame::cut), COPY(CefFrame::copy),
    PASTE(CefFrame::paste), SELECT_ALL(CefFrame::selectAll),
}

internal enum class BrowserEditOutcome {
    NO_BROWSER, BROWSER_CLOSED, WINDOW_INACTIVE, FOCUS_OUTSIDE_BROWSER, NO_FRAME, FRAME_INVALID, DISPATCHED,
}

private fun inside(owner: Component?, component: Component): Boolean =
    owner != null && (owner === component || SwingUtilities.isDescendingFrom(owner, component))

internal enum class BrowserFocusRelation { NONE, BROWSER, DESCENDANT, ANCESTOR, MENU, OTHER }

/** Describe structural focus only; never stringify controls or their contents. */
internal fun browserFocusRelation(component: Component, owner: Component?): BrowserFocusRelation = when {
    owner == null -> BrowserFocusRelation.NONE
    owner === component -> BrowserFocusRelation.BROWSER
    SwingUtilities.isDescendingFrom(owner, component) -> BrowserFocusRelation.DESCENDANT
    SwingUtilities.isDescendingFrom(component, owner) -> BrowserFocusRelation.ANCESTOR
    owner is JMenuItem || owner is JPopupMenu -> BrowserFocusRelation.MENU
    else -> BrowserFocusRelation.OTHER
}

/** A native menu may temporarily take focus; never use historical focus over another text control. */
internal fun browserOwnsEditingFocus(component: Component, current: Component?, permanent: Component?): Boolean {
    if (inside(current, component)) return true
    val temporaryMenuFocus = current == null || current is JMenuItem || current is JPopupMenu
    return temporaryMenuFocus && inside(permanent, component)
}

/** Explicit native menu actions only: JCEF owns clipboard access and editing semantics. */
internal class BrowserEditActions(
    private val browser: () -> CefBrowser?,
    private val focusOwner: () -> Component? = { KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner },
    private val permanentFocusOwner: () -> Component? = { KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner },
    private val windowActive: (Component) -> Boolean = { SwingUtilities.getWindowAncestor(it)?.isActive == true },
    private val report: (BrowserEditAction, BrowserEditOutcome) -> Unit = { action, outcome ->
        println("GAMERO_EDIT_ACTION: action=$action outcome=$outcome")
    },
) {
    fun perform(action: BrowserEditAction): Boolean {
        fun finish(outcome: BrowserEditOutcome): Boolean {
            report(action, outcome)
            return outcome == BrowserEditOutcome.DISPATCHED
        }
        val target = browser() ?: return finish(BrowserEditOutcome.NO_BROWSER)
        if (target.isClosing || target.isClosed) return finish(BrowserEditOutcome.BROWSER_CLOSED)
        val component = target.uiComponent
        if (!windowActive(component)) return finish(BrowserEditOutcome.WINDOW_INACTIVE)
        val current = focusOwner()
        val permanent = permanentFocusOwner()
        if (!browserOwnsEditingFocus(component, current, permanent)) {
            println("GAMERO_EDIT_FOCUS: current=${browserFocusRelation(component, current)} permanent=${browserFocusRelation(component, permanent)} " +
                "currentClass=${current?.javaClass?.name} permanentClass=${permanent?.javaClass?.name} " +
                "sameWindow=${current != null && SwingUtilities.getWindowAncestor(current) === SwingUtilities.getWindowAncestor(component)}")
            return finish(BrowserEditOutcome.FOCUS_OUTSIDE_BROWSER)
        }
        val frame = target.focusedFrame ?: return finish(BrowserEditOutcome.NO_FRAME)
        if (!frame.isValid) return finish(BrowserEditOutcome.FRAME_INVALID)
        // getFocusedFrame selects the editing target. A temporary native menu can
        // make isFocused false; querying it again would discard a valid command.
        action.apply(frame)
        return finish(BrowserEditOutcome.DISPATCHED)
    }
}
