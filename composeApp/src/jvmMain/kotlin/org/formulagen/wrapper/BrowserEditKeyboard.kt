package org.formulagen.wrapper

import org.cef.browser.CefBrowser
import org.cef.handler.CefKeyboardHandler.CefKeyEvent
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.EventFlags

internal enum class BrowserKeyPhase { PRE, POST }

/** Structural categories only: never record key codes, characters, text, or event.toString(). */
internal data class BrowserKeyShape(
    val phase: BrowserKeyPhase,
    val type: CefKeyEvent.EventType,
    val command: Boolean,
    val control: Boolean,
    val alt: Boolean,
    val shift: Boolean,
    val editKey: Boolean,
    val editable: Boolean,
)

internal fun browserKeyShape(phase: BrowserKeyPhase, event: CefKeyEvent) = BrowserKeyShape(
    phase, event.type,
    event.modifiers and EventFlags.EVENTFLAG_COMMAND_DOWN != 0,
    event.modifiers and EventFlags.EVENTFLAG_CONTROL_DOWN != 0,
    event.modifiers and EventFlags.EVENTFLAG_ALT_DOWN != 0,
    event.modifiers and EventFlags.EVENTFLAG_SHIFT_DOWN != 0,
    event.windows_key_code in setOf(65, 67, 86, 88, 90),
    event.focus_on_editable_field,
)

/** At most 16 shapes each with/without Command; normal typing cannot exhaust Command diagnostics. */
internal class BrowserKeyDiagnostics(
    private val report: (BrowserKeyShape) -> Unit = { println("GAMERO_EDIT_EVENT: $it") },
) {
    private val seen = mutableSetOf<BrowserKeyShape>()
    @Synchronized fun observe(phase: BrowserKeyPhase, event: CefKeyEvent) {
        val shape = browserKeyShape(phase, event)
        if (seen.count { it.command == shape.command } < 16 && seen.add(shape)) report(shape)
    }
}

/** Native Mac key presses use RAWKEYDOWN; CHAR/KEYDOWN/KEYUP must not edit again. */
internal fun browserEditShortcut(event: CefKeyEvent): BrowserEditAction? {
    if (event.type != CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) return null
    val modifiers = event.modifiers
    if (modifiers and EventFlags.EVENTFLAG_COMMAND_DOWN == 0 ||
        modifiers and (EventFlags.EVENTFLAG_CONTROL_DOWN or EventFlags.EVENTFLAG_ALT_DOWN) != 0) return null
    val shift = modifiers and EventFlags.EVENTFLAG_SHIFT_DOWN != 0
    // JCEF exposes DOM-normalized Windows key codes on every platform.
    return when (event.windows_key_code) {
        90 -> if (shift) BrowserEditAction.REDO else BrowserEditAction.UNDO
        88 -> if (shift) null else BrowserEditAction.CUT
        67 -> if (shift) null else BrowserEditAction.COPY
        86 -> if (shift) null else BrowserEditAction.PASTE
        65 -> if (shift) null else BrowserEditAction.SELECT_ALL
        else -> null
    }
}

/** The callback browser proves the key's origin; AWT focus does not describe native JCEF focus. */
internal class BrowserEditKeyboard(
    private val browser: () -> CefBrowser?,
    private val windowActive: () -> Boolean,
    private val report: (BrowserEditAction, BrowserEditOutcome) -> Unit = { action, outcome ->
        println("GAMERO_EDIT_KEY: action=$action outcome=$outcome")
    },
    private val diagnostics: BrowserKeyDiagnostics = BrowserKeyDiagnostics(),
) : CefKeyboardHandlerAdapter() {
    override fun onPreKeyEvent(source: CefBrowser?, event: CefKeyEvent?, isKeyboardShortcut: BoolRef?): Boolean {
        event?.let { diagnostics.observe(BrowserKeyPhase.PRE, it) }
        val action = event?.let(::browserEditShortcut) ?: return false
        fun finish(outcome: BrowserEditOutcome): Boolean {
            report(action, outcome)
            return outcome == BrowserEditOutcome.DISPATCHED
        }
        val target = browser() ?: return finish(BrowserEditOutcome.NO_BROWSER)
        if (target.isClosing || target.isClosed) return finish(BrowserEditOutcome.BROWSER_CLOSED)
        if (!windowActive()) return finish(BrowserEditOutcome.WINDOW_INACTIVE)
        // KCEFBrowser wraps the native callback browser: reference equality would reject it.
        // Require both native identity and the exact hosted component, excluding other views/popups.
        if (source == null || source.identifier != target.identifier || source.uiComponent !== target.uiComponent) {
            return finish(BrowserEditOutcome.FOCUS_OUTSIDE_BROWSER)
        }
        val frame = target.focusedFrame ?: return finish(BrowserEditOutcome.NO_FRAME)
        if (!frame.isValid) return finish(BrowserEditOutcome.FRAME_INVALID)
        // Synchronous on CEF's UI thread. No deferred dispatch after focus/activation changes.
        action.apply(frame)
        return finish(BrowserEditOutcome.DISPATCHED)
    }

    override fun onKeyEvent(source: CefBrowser?, event: CefKeyEvent?): Boolean {
        event?.let { diagnostics.observe(BrowserKeyPhase.POST, it) }
        return false
    }
}
