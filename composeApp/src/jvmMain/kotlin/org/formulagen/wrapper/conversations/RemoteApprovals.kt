package org.formulagen.wrapper.conversations

import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.formulagen.wrapper.approvals.APPROVAL_TIMEOUT
import org.formulagen.wrapper.approvals.ApprovalBroker
import org.formulagen.wrapper.approvals.ApprovalDecision
import org.formulagen.wrapper.approvals.ApprovalRequest
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.approvals.DialogOutcome
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.approvalDialog
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/**
 * Where a working turn's approvals go besides the Mac's own dialog: the claimed message's [output]
 * (for `approval.opened` and `approval.closed`), its [conversationId] and [threadId], and the
 * project's [folders], which decide what the phone may be shown.
 */
internal class ApprovalScope(
    val output: ClaimOutput,
    val conversationId: String,
    val threadId: String?,
    val folders: List<LocalFolder>,
    val companionName: String,
)

/** How one approval ended: the answer applied, and the surface it came from (none when nobody answered). */
internal data class ApprovalReceipt(val id: String, val kind: ApprovalKind, val option: ApprovalOption?, val by: ApprovalSurface?)

/**
 * The approval broker for conversations. A request from a lease with a registered [ApprovalScope] is
 * shown in the Mac's native dialog and relayed to the phone (`approval.opened`), and **the first
 * answer wins**:
 * - the person answers on the Mac: the answer is posted, and the approval as stored says which answer
 *   won (a phone answer that reached the server first is applied instead);
 * - another surface answers first (`approval.answered` on the host stream, read back from the server):
 *   the dialog closes with `answered-elsewhere` and that answer applies;
 * - nobody answers, or the work stops: Deny (or the dialog's default) applies and the approval is
 *   withdrawn (`approval.closed`).
 *
 * Every approval has an id and a [receipt]; a late or repeated answer finds no open approval and
 * changes nothing. A request from any other lease is the native dialog alone, as before. Folder
 * trust is never relayed.
 */
internal class RemoteApprovals(
    private val dialogs: NativeDialogs,
    private val api: ConversationApi,
    private val hostId: String,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalBroker {
    private val scopes = ConcurrentHashMap<String, ApprovalScope>()
    private val open = ConcurrentHashMap<String, OpenApproval>()
    private val receipts = object : LinkedHashMap<String, ApprovalReceipt>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ApprovalReceipt>?) = size > RECEIPTS
    }

    /** One approval awaiting its first answer: [elsewhere] fires when another surface's [remote] answer arrives. */
    private class OpenApproval(val kind: ApprovalKind) {
        val elsewhere = CancelSignal()
        val remote = AtomicReference<ApprovalOption?>()
    }

    /** Relay [lease]'s permission requests through [scope] until the returned handle is closed. */
    fun register(lease: String, scope: ApprovalScope): AutoCloseable {
        scopes[lease] = scope
        return AutoCloseable { scopes.remove(lease, scope) }
    }

    override fun ask(request: ApprovalRequest, cancel: CancelSignal, shown: () -> Unit): ApprovalDecision {
        val dialog = approvalDialog(request)
        val scope = request.lease?.let(scopes::get) ?: return dialogs.ask(dialog, cancel, shown) ?: ApprovalDecision.DENY
        val options = listOf(ApprovalOption.ALLOW_ONCE to ApprovalDecision.ALLOW_ONCE, ApprovalOption.DENY to ApprovalDecision.DENY)
        // "For this conversation" exists only on the Mac; the account records it as an allow.
        val optionOf = { decision: ApprovalDecision -> if (decision == ApprovalDecision.DENY) ApprovalOption.DENY else ApprovalOption.ALLOW_ONCE }
        return race(scope, ApprovalKind.PERMISSION, dialog, presentApproval(request, scope.folders), options, optionOf, cancel, shown)
            ?: ApprovalDecision.DENY
    }

    /**
     * The engine's own questions (start of work, a stage, the resume review), raced the same way.
     * [options] pairs each contract option with the dialog's value. Null when nobody answered.
     */
    fun <T> ask(scope: ApprovalScope, kind: ApprovalKind, dialog: NativeDialog<T>, options: List<Pair<ApprovalOption, T>>, cancel: CancelSignal): T? {
        val optionOf = { value: T -> options.first { it.second == value }.first }
        return race(scope, kind, dialog, presentDialog(dialog, scope.folders, scope.companionName), options, optionOf, cancel) {}
    }

    /** The host stream says another surface answered [approvalId]: read its answer, and close the Mac's dialog. Late ones change nothing. */
    fun answered(approvalId: String) {
        val pending = open[approvalId] ?: return
        val stored = try { api.approval(approvalId) } catch (error: Exception) {
            log(LogLevel.Warn, "conversations", "An approval answered elsewhere could not be read", error)
            return
        }
        val answer = stored.answer?.takeIf { stored.state == ApprovalState.ANSWERED && it.by != ApprovalSurface.MAC } ?: return
        if (pending.remote.compareAndSet(null, answer.option)) pending.elsewhere.cancel()
    }

    /** How approval [id] ended, while it is among the most recent. */
    fun receipt(id: String): ApprovalReceipt? = synchronized(receipts) { receipts[id] }

    /** The approvals still waiting for their first answer. */
    val openIds: Set<String> get() = open.keys.toSet()

    private fun <T> race(
        scope: ApprovalScope,
        kind: ApprovalKind,
        dialog: NativeDialog<T>,
        presentation: ApprovalPresentation,
        options: List<Pair<ApprovalOption, T>>,
        optionOf: (T) -> ApprovalOption,
        cancel: CancelSignal,
        shown: () -> Unit,
    ): T? {
        val id = UUID.randomUUID().toString()
        val pending = OpenApproval(kind)
        open[id] = pending
        val approval = AgentApproval(null, hostId, scope.conversationId, expiry(), id, kind, options.map { it.first }.distinct(), presentation,
            OPENED_REVISION, ApprovalState.OPEN, scope.threadId)
        scope.output.bestEffort(MessageCheckpoint.ApprovalOpened(approval))
        try {
            return when (val outcome = dialogs.answer(dialog, cancel, pending.elsewhere, shown)) {
                is DialogOutcome.Chosen -> {
                    val mine = optionOf(outcome.value)
                    val won = settleHere(scope, id, mine) ?: options.first { it.second == dialog.dismiss }.first
                    receipt(ApprovalReceipt(id, kind, won, if (won == mine) ApprovalSurface.MAC else ApprovalSurface.PHONE))
                    if (won == mine) outcome.value else options.first { it.first == won }.second
                }
                DialogOutcome.AnsweredElsewhere -> {
                    val option = checkNotNull(pending.remote.get())
                    receipt(ApprovalReceipt(id, kind, option, ApprovalSurface.PHONE))
                    options.firstOrNull { it.first == option }?.second ?: dialog.dismiss
                }
                DialogOutcome.Unanswered -> {
                    scope.output.bestEffort(MessageCheckpoint.ApprovalClosed(id, ApprovalOutcome.Withdrawn))
                    receipt(ApprovalReceipt(id, kind, null, null))
                    null
                }
            }
        } finally { open.remove(id) }
    }

    /**
     * Post the Mac's answer. Answers the option that won: the stored answer, which is the phone's
     * when it reached the server first; null when the Mac's was refused and the winner cannot be
     * read, so the caller applies the dialog's default. Unreachable, the Mac's answer stands and is
     * recorded as closed when the server takes it.
     */
    private fun settleHere(scope: ApprovalScope, id: String, option: ApprovalOption): ApprovalOption? = try {
        api.answer(id, ApprovalAnswer(OPENED_REVISION, option)).answer?.option ?: option
    } catch (_: HovelRejectedException) {
        runCatching { api.approval(id) }.getOrNull()?.answer?.option
    } catch (_: Exception) {
        scope.output.bestEffort(MessageCheckpoint.ApprovalClosed(id, ApprovalOutcome.Answered(option)))
        option
    }

    private fun receipt(receipt: ApprovalReceipt) = synchronized(receipts) { receipts[receipt.id] = receipt }

    private fun expiry() = clock.instant().plusMillis(APPROVAL_TIMEOUT.inWholeMilliseconds).truncatedTo(ChronoUnit.MILLIS).toString()

    companion object {
        /** The revision an approval opens at; the Mac's answer is checked against it. */
        const val OPENED_REVISION = 0L
        private const val RECEIPTS = 256
    }
}
