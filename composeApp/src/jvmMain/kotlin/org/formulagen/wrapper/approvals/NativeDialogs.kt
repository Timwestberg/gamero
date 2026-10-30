package org.formulagen.wrapper.approvals

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * One native question for the player: a [title], its [lines], and its [buttons] in order, each with
 * the value it answers. [dismiss] is the default: what closing the dialog, Escape or Enter answers
 * (Deny, Not now), and the only button that is the dialog's primary one.
 */
internal data class NativeDialog<T>(val title: String, val lines: List<String>, val buttons: List<Pair<String, T>>, val dismiss: T) {
    init { require(buttons.isNotEmpty() && buttons.any { it.second == dismiss }) }

    /** The dismiss button's position, for the screen. */
    val dismissIndex: Int get() = buttons.indexOfFirst { it.second == dismiss }
}

/**
 * Shows a [NativeDialog] on the screen: the thin, untestable part (Compose, in [ComposeDialogPresenter]).
 * [present] never blocks: it shows the dialog, brings Hovel forward, and later calls `answer` with
 * the chosen button's index. The returned function takes the dialog down again (after an answer,
 * a timeout or a cancel), and is safe to call more than once.
 */
internal fun interface DialogPresenter {
    fun present(dialog: NativeDialog<*>, answer: (Int) -> Unit): () -> Unit
}

/**
 * How a native question ended: the person [Chosen] a button here, another surface (the phone)
 * answered it first and the dialog was taken down ([AnsweredElsewhere]), or nobody answered in time
 * or it was cancelled ([Unanswered]).
 */
internal sealed interface DialogOutcome<out T> {
    data class Chosen<T>(val value: T) : DialogOutcome<T>
    data object AnsweredElsewhere : DialogOutcome<Nothing>
    data object Unanswered : DialogOutcome<Nothing>
}

/**
 * The native dialogs' rules, apart from how they look: one dialog at a time, in the order asked;
 * no answer within [timeout] of showing, or a [CancelSignal] (before or while it shows), is no
 * answer. A request still waiting its turn when cancelled is never shown.
 */
internal class NativeDialogs(private val presenter: DialogPresenter, private val timeout: Duration = APPROVAL_TIMEOUT) {
    private val turn = Semaphore(1, true)

    /** The chosen button's value, or null for no answer. [shown] runs once the dialog is on screen. Blocks. */
    fun <T> ask(dialog: NativeDialog<T>, cancel: CancelSignal, shown: () -> Unit = {}): T? =
        (answer(dialog, cancel, CancelSignal(), shown) as? DialogOutcome.Chosen)?.value

    /**
     * As [ask], racing another surface: when [elsewhere] fires first (before or while the dialog
     * shows), the dialog is taken down, or never shown, and the outcome is
     * [DialogOutcome.AnsweredElsewhere]. A button chosen here first wins, and [elsewhere] firing
     * after it changes nothing. Blocks.
     */
    fun <T> answer(dialog: NativeDialog<T>, cancel: CancelSignal, elsewhere: CancelSignal, shown: () -> Unit = {}): DialogOutcome<T> {
        val answer = CompletableFuture<DialogOutcome<T>>()
        val unanswered: DialogOutcome<T> = DialogOutcome.Unanswered
        val other: DialogOutcome<T> = DialogOutcome.AnsweredElsewhere
        cancel.onCancel { answer.complete(unanswered) }.use {
            elsewhere.onCancel { answer.complete(other) }.use {
                if (answer.isDone) return answer.get()
                // Wait for any earlier dialog to be answered, but give up as soon as this one is cancelled or answered elsewhere.
                while (!turn.tryAcquire(50, TimeUnit.MILLISECONDS)) if (answer.isDone) return answer.get()
                try {
                    if (answer.isDone) return answer.get()
                    val dismiss = presenter.present(dialog) { choice ->
                        answer.complete(dialog.buttons.getOrNull(choice)?.let { DialogOutcome.Chosen(it.second) } ?: unanswered)
                    }
                    shown()
                    return try { answer.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
                        catch (_: TimeoutException) { unanswered }
                        finally { dismiss() }
                } finally { turn.release() }
            }
        }
    }
}

/** How long an approval or start-of-work dialog waits for the player before it counts as Deny. */
internal val APPROVAL_TIMEOUT: Duration = 10.minutes

/** How long a new dialog's buttons stay disabled, so a click or key press meant for something else cannot answer it. */
internal const val DIALOG_ARMING_MILLIS = 750L
