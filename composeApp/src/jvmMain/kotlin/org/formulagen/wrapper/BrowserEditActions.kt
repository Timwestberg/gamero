package org.formulagen.wrapper

import java.awt.Component
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame

internal enum class BrowserEditAction(val apply: (CefFrame) -> Unit) {
    UNDO(CefFrame::undo), REDO(CefFrame::redo), CUT(CefFrame::cut), COPY(CefFrame::copy),
    PASTE(CefFrame::paste), SELECT_ALL(CefFrame::selectAll),
}

/** Explicit native menu actions only: JCEF owns clipboard access and editing semantics. */
internal class BrowserEditActions(
    private val browser: () -> CefBrowser?,
    private val focusOwner: () -> Component? = { KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner },
) {
    fun perform(action: BrowserEditAction): Boolean {
        val target = browser() ?: return false
        if (target.isClosing || target.isClosed) return false
        val owner = focusOwner() ?: return false
        val component = target.uiComponent
        if (owner !== component && !SwingUtilities.isDescendingFrom(owner, component)) return false
        val frame = target.focusedFrame ?: return false
        if (!frame.isValid || !frame.isFocused) return false
        action.apply(frame)
        return true
    }
}
