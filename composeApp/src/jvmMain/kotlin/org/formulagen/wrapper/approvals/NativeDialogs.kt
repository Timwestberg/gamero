package org.formulagen.wrapper.approvals

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * One native question for the player: a [title], its [lines], and its [buttons] in order. Closing
 * the dialog answers [dismissIndex] (Deny, Not now).
 */
internal data class NativeDialog(val title: String, val lines: List<String>, val buttons: List<String>, val dismissIndex: Int) {
    init { require(buttons.isNotEmpty() && dismissIndex in buttons.indices) }
}

/**
 * Shows a [NativeDialog] on the screen: the thin, untestable part (Compose, in [ComposeDialogPresenter]).
 * [present] never blocks: it shows the dialog, brings Hovel forward, and later calls `answer` with
 * the chosen button's index. The returned function takes the dialog down again (after an answer,
 * a timeout or a cancel), and is safe to call more than once.
 */
internal fun interface DialogPresenter {
    fun present(dialog: NativeDialog, answer: (Int) -> Unit): () -> Unit
}

/**
 * The native dialogs' rules, apart from how they look: one dialog at a time, in the order asked;
 * no answer within [timeout] of showing, or a [CancelSignal] (before or while it shows), is no
 * answer. A request still waiting its turn when cancelled is never shown.
 */
internal class NativeDialogs(private val presenter: DialogPresenter, private val timeout: Duration = APPROVAL_TIMEOUT) {
    private val turn = Semaphore(1, true)

    /** The chosen button's index, or null for no answer. Blocks. */
    fun ask(dialog: NativeDialog, cancel: CancelSignal): Int? {
        val answer = CompletableFuture<Int?>()
        cancel.onCancel { answer.complete(null) }.use {
            if (answer.isDone) return null
            // Wait for any earlier dialog to be answered, but give up as soon as this one is cancelled.
            while (!turn.tryAcquire(50, TimeUnit.MILLISECONDS)) if (answer.isDone) return null
            try {
                if (answer.isDone) return null
                val dismiss = presenter.present(dialog) { choice -> answer.complete(choice.takeIf { it in dialog.buttons.indices }) }
                try { return answer.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { return null }
                finally { dismiss() }
            } finally { turn.release() }
        }
    }
}

/** How long an approval or start-of-work dialog waits for the player before it counts as Deny. */
internal val APPROVAL_TIMEOUT: Duration = 10.minutes
