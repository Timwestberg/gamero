package org.formulagen.wrapper.approvals

import org.formulagen.wrapper.runtimes.TurnEvents

/**
 * The one way a working runtime asks the player, shared by Claude, Codex and Grok. A request is
 * [register]ed on the protocol's reader thread, in order, so a withdrawal or a stop right after it
 * finds it; it is then [ask]ed on its own thread, because the dialog blocks. A cancel, a stop, a
 * released lease or a broker failure is always Deny.
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
            else broker.ask(entry.request, entry.cancel) {}
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            ApprovalDecision.DENY
        }
        synchronized(pending) { pending.remove(key) }
        return if (entry.cancel.isCancelled) ApprovalDecision.DENY else decision
    }

    /** The provider withdrew request [key]. */
    fun cancel(key: Any) { synchronized(pending) { pending[key] }?.cancel?.cancel() }

    /** A stop or a released lease: everything pending is denied. */
    fun cancelAll() { synchronized(pending) { pending.values.toList() }.forEach { it.cancel.cancel() } }
}
