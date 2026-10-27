package org.formulagen.wrapper.progression

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-only coordinator. Call on the UI thread; rewards and previews come from the ledger. */
class PresentationController(private val nextRequestId: () -> String) {
    private val mutable = MutableStateFlow(PresentationState())
    val state = mutable.asStateFlow()

    /** Previewing a card never emits a command or changes saved progression. */
    fun select(optionId: String) {
        val current = mutable.value
        if (current.pending != null || current.snapshot?.offer?.options?.none { it.id == optionId } != false) return
        mutable.value = current.copy(selectedId = optionId, error = null, saved = null)
    }

    /** Lock the current selection until the exact request is acknowledged. */
    fun begin(action: String): PresentationCommand? {
        val current = mutable.value
        val snapshot = current.snapshot ?: return null
        if (current.pending != null || snapshot.view == "closed") return null
        if (action == "choose" && (snapshot.view != "growth" || current.selectedId == null || snapshot.offer == null)) return null
        val command = PresentationCommand(nextRequestId(), snapshot.revision, action,
            if (action == "choose") snapshot.offer?.id.orEmpty() else "",
            if (action == "choose") current.selectedId.orEmpty() else "")
        mutable.value = current.copy(pending = command, error = null, saved = null)
        return command
    }

    /** Retry the same id after a timeout or failed save, never a second allocation. */
    fun retry(): PresentationCommand? {
        val current = mutable.value
        if (current.error == null) return null
        mutable.value = current.copy(error = null)
        return current.pending
    }

    /** Keep an uncertain command recoverable across a disconnected transport. */
    fun disconnected() {
        mutable.value = mutable.value.copy(error = "Connection interrupted. Your progress is safe. Reconnect or continue in the game window.")
    }

    /** Refresh state while retaining previews only for the same pending offer. */
    fun receive(message: GameMessage) {
        val snapshot = message.snapshot ?: return
        val current = mutable.value
        if (snapshot.revision < (current.snapshot?.revision ?: -1)) return
        val result = message.result?.takeIf { it.requestId.isNotEmpty() && it.requestId == current.pending?.requestId }
        val sameOffer = snapshot.offer?.id == current.snapshot?.offer?.id && snapshot.view == current.snapshot?.view
        val retryable = result?.status == "save_failed"
        val selection = current.selectedId?.takeIf { sameOffer && snapshot.offer?.options?.any { option -> option.id == it } == true }
        mutable.value = current.copy(
            snapshot = snapshot,
            selectedId = if (result?.status == "ok" || result?.status == "stale") null else selection,
            pending = if (result != null && !retryable) null else current.pending,
            error = when { result != null && result.status != "ok" -> result.message.ifBlank { "Progress changed. Review the choices and try again." }
                current.pending == null -> null
                else -> current.error },
            saved = if (result?.status == "ok" && current.pending?.action == "choose") "Upgrade saved" else null,
        )
    }
}
