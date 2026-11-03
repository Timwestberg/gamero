package org.formulagen.wrapper.workspace

import java.awt.event.InputEvent.ALT_DOWN_MASK
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.InputEvent.META_DOWN_MASK
import java.awt.event.InputEvent.SHIFT_DOWN_MASK
import java.awt.event.KeyEvent

/** What a workspace window's menu items and keyboard shortcuts do. */
internal sealed interface WorkspaceAction {
    data object NewTab : WorkspaceAction
    data object CloseTab : WorkspaceAction
    /** The tab at zero-based [index] (⌘1 to ⌘8). */
    data class SelectTab(val index: Int) : WorkspaceAction
    /** ⌘9, as in browsers. A window holds at most `TABS` (8) tabs. */
    data object SelectLastTab : WorkspaceAction
    data object NextTab : WorkspaceAction
    data object PreviousTab : WorkspaceAction
    data object ZoomIn : WorkspaceAction
    data object ZoomOut : WorkspaceAction
    data object ZoomReset : WorkspaceAction
}

/**
 * The window shortcut a key press in a terminal stands for, or null when it is the terminal's:
 * ⌘T, ⌘W, ⌘1 to ⌘9, ⌘⇧] and ⌘⇧[ (also ⌃Tab and ⌃⇧Tab, as in Terminal), ⌘+ (⌘= too), ⌘− and ⌘0.
 * Only key presses count; their typed events are the terminal's to drop.
 *
 * The menu items carry the same shortcuts. macOS also hands a ⌘ key to the menu bar after the
 * terminal has seen it, so [WorkspaceWindowState.performFromMenu] ignores a menu item that repeats
 * the action a key press has just performed.
 */
internal fun workspaceShortcut(event: KeyEvent): WorkspaceAction? {
    if (event.id != KeyEvent.KEY_PRESSED) return null
    val modifiers = event.modifiersEx and (META_DOWN_MASK or CTRL_DOWN_MASK or ALT_DOWN_MASK or SHIFT_DOWN_MASK)
    val code = event.keyCode
    return when (modifiers) {
        META_DOWN_MASK -> when (code) {
            KeyEvent.VK_T -> WorkspaceAction.NewTab
            KeyEvent.VK_W -> WorkspaceAction.CloseTab
            in KeyEvent.VK_1..KeyEvent.VK_8 -> WorkspaceAction.SelectTab(code - KeyEvent.VK_1)
            KeyEvent.VK_9 -> WorkspaceAction.SelectLastTab
            KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> WorkspaceAction.ZoomIn
            KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> WorkspaceAction.ZoomOut
            KeyEvent.VK_0 -> WorkspaceAction.ZoomReset
            else -> byCharacter(event.keyChar)
        }
        META_DOWN_MASK or SHIFT_DOWN_MASK -> when (code) {
            KeyEvent.VK_CLOSE_BRACKET -> WorkspaceAction.NextTab
            KeyEvent.VK_OPEN_BRACKET -> WorkspaceAction.PreviousTab
            // ⌘+ on a US keyboard is ⌘⇧=.
            KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS -> WorkspaceAction.ZoomIn
            else -> byCharacter(event.keyChar)
        }
        CTRL_DOWN_MASK -> if (code == KeyEvent.VK_TAB) WorkspaceAction.NextTab else null
        CTRL_DOWN_MASK or SHIFT_DOWN_MASK -> if (code == KeyEvent.VK_TAB) WorkspaceAction.PreviousTab else null
        else -> null
    }
}

/** Layouts that move the brackets or plus still type `}`, `{` or `+` with ⌘. */
private fun byCharacter(char: Char): WorkspaceAction? = when (char) {
    '}' -> WorkspaceAction.NextTab
    '{' -> WorkspaceAction.PreviousTab
    '+' -> WorkspaceAction.ZoomIn
    else -> null
}

/** The terminal font, its size and the zoom limits. */
internal object TerminalFont {
    /** The owner's choice: SF Mono when AWT can see it by name, else Menlo. Never loaded from a file. */
    const val PREFERRED = "SF Mono"
    const val FALLBACK = "Menlo"
    const val DEFAULT_SIZE = 13
    const val MIN_SIZE = 9
    const val MAX_SIZE = 36

    /** The family terminals use on this Mac, looked up once. */
    val family: String by lazy { resolveFamily(availableFamilies()) }

    internal fun resolveFamily(available: Set<String>): String = if (PREFERRED in available) PREFERRED else FALLBACK

    private fun availableFamilies(): Set<String> = try {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    } catch (error: Throwable) {
        rethrowIfFatal(error)
        emptySet()
    }

    /** [size] moved by [steps] points, within the limits. */
    fun zoomed(size: Int, steps: Int): Int = (size + steps).coerceIn(MIN_SIZE, MAX_SIZE)
}
