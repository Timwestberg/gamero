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
import org.formulagen.wrapper.phone.PhoneApprovalCopy
import org.formulagen.wrapper.logger.log

/**
 * Where a working turn's approvals go besides the Mac's own dialog: the claimed message's [output]
 * (for `approval.opened` and `approval.closed`), its [conversationId] and [threadId], and the
 * project's [folders], which decide what the phone may be shown. [later] queues a checkpoint the
 * server could not take now in the conversation's outbox.
 */
internal class ApprovalScope(
    val output: ClaimOutput,
    val conversationId: String,
    val threadId: String?,
    val folders: List<LocalFolder>,
    val companionName: String,
    val later: (MessageCheckpoint) -> Unit = {},
)

/** A conflict to show in the conversation ([threadId] null: the main conversation): the Mac ran one answer, the record shows another. */
internal data class ApprovalConflict(val threadId: String?, val text: String)

/** How one approval ended: the answer applied, and the surface it came from (none when nobody answered). */
internal data class ApprovalReceipt(val id: String, val kind: ApprovalKind, val option: ApprovalOption?, val by: ApprovalSurface?)

/**
 * The approval broker for conversations. A request from a lease with a registered [ApprovalScope] is
 * shown in the Mac's native dialog and relayed to the phone (`approval.opened`), and **the first
 * answer wins**:
 * - the person answers on the Mac: it is recorded as `approval.closed` (answered), fenced by the claim;
 *   when that is refused because another answer won, the stored approval's answer applies instead;
 * - another surface answers first (`approval.answered` on the host stream, read back from the server):
 *   the dialog closes with `answered-elsewhere` and that answer applies;
 * - nobody answers, or the work stops: the approval is withdrawn (`approval.closed`) and Deny (or the
 *   dialog's default) applies; when that close is refused because another answer reached the server
 *   first, that answer applies instead;
 * - the Mac was not listening (its host stream was down): on every reconnect it reads each approval still
 *   open here ([catchUp]), so an answer it missed closes the dialog then;
 * - a close this Mac could only queue (the server was unreachable) and the server later refused means
 *   the Mac already applied its own answer while another won on the server: [conflict] says so in the
 *   conversation, so the person sees which one ran.
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

    /** Read every approval still open here: on a host-stream reconnect, an answer given meanwhile closes its dialog. */
    fun catchUp() = openIds.forEach(::answered)

    /**
     * What the conversation says when the server refused [closed], a close this Mac queued while it could not reach
     * Hovel, because another answer reached it first: the Mac already applied its own (its receipt) and the record
     * shows the other. Null when they agree, or the winner cannot be read.
     */
    fun conflict(closed: MessageCheckpoint.ApprovalClosed): ApprovalConflict? {
        val stored = runCatching { api.approval(closed.approvalId) }.getOrNull() ?: return null
        val winner = stored.answer?.takeIf { stored.state == ApprovalState.ANSWERED && it.by != ApprovalSurface.MAC } ?: return null
        val applied = receipt(closed.approvalId)?.option
        if (applied == winner.option) return null
        val mine = applied?.let { "“${PhoneApprovalCopy.label(it)}”" } ?: "no answer"
        return ApprovalConflict(stored.threadId,
            "This Mac could not reach Hovel and applied $mine to a request, but your phone answered " +
                "“${PhoneApprovalCopy.label(winner.option)}” first. Hovel shows the phone's answer; this Mac went on with its own.")
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
        // Not relayed (the server was unreachable): the Mac's dialog is the only place to answer it.
        val relayed = scope.output.bestEffort(MessageCheckpoint.ApprovalOpened(approval))
        try {
            return when (val outcome = dialogs.answer(dialog, cancel, pending.elsewhere, shown)) {
                is DialogOutcome.Chosen -> {
                    val mine = optionOf(outcome.value)
                    val won = if (!relayed) mine else settleHere(scope, id, mine)
                    receipt(ApprovalReceipt(id, kind, won, if (won == mine) ApprovalSurface.MAC else ApprovalSurface.PHONE))
                    if (won == mine) outcome.value else options.first { it.first == won }.second
                }
                DialogOutcome.AnsweredElsewhere -> {
                    val option = checkNotNull(pending.remote.get())
                    receipt(ApprovalReceipt(id, kind, option, ApprovalSurface.PHONE))
                    options.firstOrNull { it.first == option }?.second ?: dialog.dismiss
                }
                DialogOutcome.Unanswered -> {
                    val winner = if (relayed) withdrawHere(scope, id) else null
                    receipt(ApprovalReceipt(id, kind, winner, winner?.let { ApprovalSurface.PHONE }))
                    winner?.let { option -> options.firstOrNull { it.first == option }?.second }
                }
            }
        } finally { open.remove(id) }
    }

    /**
     * Record the Mac's answer as `approval.closed`. Answers the option that won: the stored answer when the
     * close was refused because another answer reached the server first, and otherwise the person's own,
     * also when the winner cannot be read, so their answer is never replaced by the dialog's default.
     * Unreachable, the person's answer stands and its close waits in the conversation's outbox.
     */
    private fun settleHere(scope: ApprovalScope, id: String, option: ApprovalOption): ApprovalOption {
        val closed = MessageCheckpoint.ApprovalClosed(id, ApprovalOutcome.Answered(option))
        return try {
            scope.output.once(closed)
            option
        } catch (_: HovelRejectedException) {
            val stored = runCatching { api.approval(id) }.getOrNull()
            stored?.answer?.takeIf { stored.state == ApprovalState.ANSWERED }?.option ?: option
        } catch (_: Exception) {
            scope.later(closed)
            option
        }
    }

    /**
     * Withdraw approval [id] because nobody answered here. Answers the option that won when the close is refused because
     * another answer reached the server first; queues the close when the server cannot be reached.
     */
    private fun withdrawHere(scope: ApprovalScope, id: String): ApprovalOption? {
        val withdrawn = MessageCheckpoint.ApprovalClosed(id, ApprovalOutcome.Withdrawn)
        return try {
            scope.output.once(withdrawn)
            null
        } catch (_: HovelRejectedException) {
            val stored = runCatching { api.approval(id) }.getOrNull()
            stored?.answer?.takeIf { stored.state == ApprovalState.ANSWERED && it.by != ApprovalSurface.MAC }?.option
        } catch (_: Exception) {
            scope.later(withdrawn)
            null
        }
    }

    private fun receipt(receipt: ApprovalReceipt) = synchronized(receipts) { receipts[receipt.id] = receipt }

    /**
     * When the server lets the approval go: past the Mac dialog's own timeout, with room for the dialogs
     * queued before it, since the Mac withdraws it itself when its dialog ends unanswered.
     */
    private fun expiry() = clock.instant().plusMillis(EXPIRY_DIALOGS * APPROVAL_TIMEOUT.inWholeMilliseconds).truncatedTo(ChronoUnit.MILLIS).toString()

    companion object {
        /** The revision an approval opens at. */
        const val OPENED_REVISION = 0L
        private const val RECEIPTS = 256
        /** Dialog timeouts an approval's expiry allows for: its own, and dialogs shown before it. */
        private const val EXPIRY_DIALOGS = 3
    }
}
