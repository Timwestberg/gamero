package org.formulagen.wrapper.approvals

import org.formulagen.wrapper.bridge.ChatWaiting
import org.formulagen.wrapper.bridge.WaitingKind
import org.formulagen.wrapper.chat.TurnEvents

/**
 * The one way a working runtime asks the player, shared by Claude and Codex. A request is
 * [register]ed on the protocol's reader thread, in order, so a withdrawal or a stop right after it
 * finds it; it is then [ask]ed on its own thread, because the dialog blocks. The turn's page shows
 * `awaiting-you` only while a dialog is actually on screen, and the next pending request's summary
 * when another follows. A cancel, a stop, a closed chat or a broker failure is always Deny.
 */
internal class ApprovalRelay(private val broker: ApprovalBroker) {
    /** A request waiting on the player; [events] is the turn that asked, or null when none can show it. */
    inner class Pending internal constructor(val request: ApprovalRequest, val events: TurnEvents?) {
        val cancel = CancelSignal()
    }

    private val pending = LinkedHashMap<Any, Pending>()

    /** Record [request] under [key] before anything else is read. A request no turn can show is cancelled at once. */
    fun register(key: Any, request: ApprovalRequest, events: TurnEvents?): Pending {
        val entry = Pending(request, events)
        synchronized(pending) { pending[key] = entry }
        if (events == null) entry.cancel.cancel()
        return entry
    }

    /** Ask the player natively; the dialog's answer, unless anything cancelled it, which denies. Blocks. */
    fun ask(key: Any, entry: Pending): ApprovalDecision {
        val decision = try {
            if (entry.cancel.isCancelled) ApprovalDecision.DENY
            else broker.ask(entry.request, entry.cancel) { entry.events?.waiting(ChatWaiting(WaitingKind.APPROVAL, entry.request.summary())) }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            ApprovalDecision.DENY
        }
        val next = synchronized(pending) {
            pending.remove(key)
            pending.values.firstOrNull { it.events === entry.events && !it.cancel.isCancelled }
        }
        // The page shows the next request of the same turn, which is about to show, or no longer waits.
        entry.events?.waiting(next?.let { ChatWaiting(WaitingKind.APPROVAL, it.request.summary()) })
        return if (entry.cancel.isCancelled) ApprovalDecision.DENY else decision
    }

    /** The provider withdrew request [key]. */
    fun cancel(key: Any) { synchronized(pending) { pending[key] }?.cancel?.cancel() }

    /** A stop or a closed chat: everything pending is denied. */
    fun cancelAll() { synchronized(pending) { pending.values.toList() }.forEach { it.cancel.cancel() } }
}
