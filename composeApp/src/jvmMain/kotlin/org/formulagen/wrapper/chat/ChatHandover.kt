package org.formulagen.wrapper.chat

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.formulagen.wrapper.bridge.ChatEntry
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.HandoverLimits
import org.formulagen.wrapper.bridge.HandoverMethod
import org.formulagen.wrapper.bridge.HandoverReason
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.providers.copy

/** What a `chat.handover` will do, decided from the record before any runtime runs. */
internal sealed interface HandoverPlan {
    /** Record [method] now; [context] is owed to the incoming runtime's next turn, when there is any. */
    data class Record(val method: HandoverMethod, val context: String?) : HandoverPlan

    /** Ask the outgoing runtime to summarize [transcript]; if it cannot, the incoming one gets [fallback], the recent messages. */
    data class Summarize(val transcript: String, val fallback: String) : HandoverPlan
}

/**
 * How one member hands the conversation to the next, as pure transforms of the record: a
 * `chat.handover`, and the notes the host records itself (a runtime's first turn, catching a runtime
 * up, a model change made by a send, recovering a lost conversation). [DesktopChat] runs summaries
 * and turns and applies these. Everything a runtime is handed is delimited data, never instructions.
 */
internal object Handovers {
    /** Longest transcript an outgoing member is asked to summarize; the oldest messages drop first. */
    const val TRANSCRIPT_LIMIT = 24_000

    /**
     * The same runtime keeps its own conversation. Another runtime that missed something gets a
     * summary when [from] can write one ([fromCanSummarize]: its runtime is on this Mac and has spoken
     * here), the recent messages otherwise; one that missed nothing is `up-to-date` when it has its
     * own conversation, and `fresh` when there was nothing at all.
     */
    fun plan(record: ChatRecord, from: TeamMember?, to: TeamMember, fromCanSummarize: Boolean): HandoverPlan {
        if (from != null && from.runtimeId == to.runtimeId) return HandoverPlan.Record(HandoverMethod.SAME_CONVERSATION, null)
        val unseen = unseen(record, to.runtimeId)
        val recent = recentMessages(unseen) ?: return HandoverPlan.Record(
            if (record.conversations[to.runtimeId] != null) HandoverMethod.UP_TO_DATE else HandoverMethod.FRESH, null)
        return if (from != null && fromCanSummarize) HandoverPlan.Summarize(transcript(unseen), recent)
        else HandoverPlan.Record(HandoverMethod.RECENT_MESSAGES, recent)
    }

    /** Record a handover [entry]; [context] becomes what its incoming runtime is owed (otherwise it keeps what it was owed). */
    fun handover(record: ChatRecord, entry: ChatEntry.Handover, context: String?): ChatRecord =
        record.copy(entries = (record.entries + entry).takeLast(ChatLimits.HISTORY), pendingContext = owe(record, entry.to.runtimeId, context))

    /**
     * What a send to [member] notes before the person's message, as the record it then extends: the
     * runtime's first turn here ([join]), catching up a runtime that missed another member's replies
     * ([catchUp]), or a new model for the current member ([modelChange]). At most one note.
     */
    fun beforeTurn(record: ChatRecord, member: TeamMember): ChatRecord =
        join(record, member) ?: catchUp(record, member) ?: modelChange(record, member) ?: record

    /**
     * A runtime with no conversation of its own and nothing owed takes its first turn here: `fresh`
     * when nothing came before, or `recent-messages`, which it is then owed. Null when it has a
     * conversation, is owed context, or the conversation already notes it taking over.
     */
    fun join(record: ChatRecord, member: TeamMember): ChatRecord? {
        val runtime = member.runtimeId
        if (record.conversations[runtime] != null || owedTo(record, runtime) != null) return null
        if (record.entries.filterIsInstance<ChatEntry.Handover>().lastOrNull()?.to?.runtimeId == runtime) return null
        val context = recentMessages(unseen(record, runtime))
        val method = if (context == null) HandoverMethod.FRESH else HandoverMethod.RECENT_MESSAGES
        return handover(record, entry(currentMember(record.entries), member, method, HandoverReason.HOST), context)
    }

    /**
     * A runtime with its own conversation that becomes active without a handover (the team changed
     * in the editor) and missed another member's replies hears them as the recent messages. Null when
     * it is owed context already or missed no reply.
     */
    fun catchUp(record: ChatRecord, member: TeamMember): ChatRecord? {
        val runtime = member.runtimeId
        if (record.conversations[runtime] == null || owedTo(record, runtime) != null) return null
        val unseen = unseen(record, runtime)
        if (unseen.none { it is ChatEntry.Assistant && it.author.runtimeId != runtime }) return null
        val context = recentMessages(unseen) ?: return null
        return handover(record, entry(currentMember(record.entries), member, HandoverMethod.RECENT_MESSAGES, HandoverReason.HOST), context)
    }

    /**
     * A send that changes the current member's model within its runtime is a switch too, noted as
     * `same-conversation`. Null when the member is unchanged, or on another runtime.
     */
    fun modelChange(record: ChatRecord, member: TeamMember): ChatRecord? {
        val current = currentMember(record.entries) ?: return null
        if (current.runtimeId != member.runtimeId || current.modelId == member.modelId) return null
        return handover(record, entry(current, member, HandoverMethod.SAME_CONVERSATION, HandoverReason.HOST), null)
    }

    /**
     * [member]'s runtime lost its conversation (or was given another persona) during the turn for the
     * user entry [userEntryId]: forget the conversation, owe the runtime what it was owed together with
     * the recent messages before that entry, and note it just before the entry. [before] are the
     * entries as they were before this turn's own notes, which name the member it starts over from.
     */
    fun startOver(record: ChatRecord, member: TeamMember, userEntryId: String, before: List<ChatEntry>): ChatRecord {
        val runtime = member.runtimeId
        val at = record.entries.indexOfFirst { it.id == userEntryId }.takeIf { it >= 0 } ?: record.entries.size
        val earlier = record.entries.take(at)
        val forgotten = record.copy(conversations = record.conversations - runtime)
        val recent = recentMessages(unseen(forgotten.copy(entries = earlier), runtime))
        val context = listOfNotNull(owedTo(record, runtime), recent).joinToString("\n\n").takeIf { it.isNotEmpty() }?.take(PENDING_CONTEXT_LIMIT)
        val note = entry(currentMember(before), member, if (recent == null) HandoverMethod.FRESH else HandoverMethod.RECENT_MESSAGES, HandoverReason.HOST)
        val entries = (earlier + note + record.entries.drop(at)).takeLast(ChatLimits.HISTORY)
        return forgotten.copy(entries = entries, pendingContext = owe(record, runtime, context))
    }

    /**
     * A retry by [member] where the conversation's current member is another one: noted by the
     * handover rules, without a summary (same-conversation, up-to-date, recent-messages or fresh).
     * Null when [member] is the current member already.
     */
    fun switchTo(record: ChatRecord, member: TeamMember): ChatRecord? {
        val current = currentMember(record.entries)
        if (current == member) return null
        val plan = plan(record, current, member, fromCanSummarize = false) as HandoverPlan.Record
        return handover(record, entry(current, member, plan.method, HandoverReason.HOST), plan.context)
    }

    /** The handover text [runtime] is owed for its next turn, if any. */
    fun owedTo(record: ChatRecord, runtime: RuntimeId): String? = record.pendingContext[runtime]

    /** [runtime] accepted a turn carrying what it was owed, so it is owed nothing now. */
    fun consumed(record: ChatRecord, runtime: RuntimeId): ChatRecord = record.copy(pendingContext = record.pendingContext - runtime)

    /**
     * The messages [runtime] has not seen: all of them without a conversation of its own; otherwise
     * those after its last reply, or after the last handover it was given once it has taken what that
     * handover owed it.
     */
    fun unseen(record: ChatRecord, runtime: RuntimeId): List<ChatEntry> {
        val entries = record.entries
        fun turns(from: Int) = entries.drop(from).filter { it is ChatEntry.User || it is ChatEntry.Assistant }
        if (record.conversations[runtime] == null) return turns(0)
        val replied = entries.indexOfLast { it is ChatEntry.Assistant && it.author.runtimeId == runtime }
        val handed = if (owedTo(record, runtime) != null) -1 else entries.indexOfLast { it is ChatEntry.Handover && it.to.runtimeId == runtime }
        return turns(maxOf(replied, handed) + 1)
    }

    /** The member that wrote the last reply, or null when nobody has replied. */
    fun lastAuthor(entries: List<ChatEntry>): TeamMember? = entries.filterIsInstance<ChatEntry.Assistant>().lastOrNull()?.author

    /** The conversation's current member: the `to` of the last handover, else the author of the last reply. */
    fun currentMember(entries: List<ChatEntry>): TeamMember? =
        entries.filterIsInstance<ChatEntry.Handover>().lastOrNull()?.to ?: lastAuthor(entries)

    /** [entries] for a summary, oldest first, within [TRANSCRIPT_LIMIT]. */
    fun transcript(entries: List<ChatEntry>): String = bounded(entries, Int.MAX_VALUE, TRANSCRIPT_LIMIT).joinToString("\n")

    /**
     * The last [HandoverLimits.RECENT_MESSAGES] messages within [HandoverLimits.RECENT_CHARS], oldest
     * dropped first and a cut message marked, as a context block; null when there is nothing to hand over.
     */
    fun recentMessages(entries: List<ChatEntry>): String? {
        val lines = bounded(entries, HandoverLimits.RECENT_MESSAGES, HandoverLimits.RECENT_CHARS).ifEmpty { return null }
        return block("the most recent messages", lines.joinToString("\n"))
    }

    /** A summary as the incoming runtime receives it. */
    fun summaryContext(from: TeamMember, summary: String): String = block("written by ${label(from)}", summary)

    /** The handover record, which is also its note in the conversation. Every caller says who decided on it. */
    fun entry(from: TeamMember?, to: TeamMember, method: HandoverMethod, reason: HandoverReason, summary: String? = null,
        id: String = UUID.randomUUID().toString(), at: Instant = Instant.now()) =
        ChatEntry.Handover(id, at.truncatedTo(ChronoUnit.MILLIS).toString(), from, to, method, reason, summary?.takeIf { method == HandoverMethod.SUMMARY })

    /** "Claude (sonnet)", "Codex (its default model)": how a member is named to another runtime. */
    fun label(member: TeamMember): String =
        "${member.runtimeId.copy.product} (${if (member.modelId == RUNTIME_DEFAULT_MODEL) "its default model" else member.modelId})"

    private fun owe(record: ChatRecord, runtime: RuntimeId, context: String?): Map<RuntimeId, String> =
        if (context == null) record.pendingContext else record.pendingContext + (runtime to context)

    private fun block(source: String, text: String) = "Earlier in this conversation ($source):\n<<<\n$text\n>>>"

    /** What a message said, or null for a handover note. */
    private fun said(entry: ChatEntry): String? = when (entry) {
        is ChatEntry.User -> entry.text
        is ChatEntry.Assistant -> entry.text
        is ChatEntry.Handover -> null
    }

    private fun speaker(entry: ChatEntry) = if (entry is ChatEntry.User) "Person: " else "Companion: "

    /**
     * The newest messages that fit, oldest first; a message that never said anything (a reply stopped
     * before it began) tells the next member nothing. The oldest one that does not fit whole keeps
     * its speaker and its end, marked as shortened.
     */
    private fun bounded(entries: List<ChatEntry>, count: Int, chars: Int): List<String> {
        val kept = ArrayDeque<String>()
        var room = chars
        for (entry in entries.filterNot { said(it).isNullOrBlank() }.takeLast(count).asReversed()) {
            val line = speaker(entry) + said(entry)
            if (line.length <= room) { kept.addFirst(line); room -= line.length; continue }
            val head = speaker(entry) + SHORTENED
            if (room - head.length >= MIN_CUT) kept.addFirst(head + said(entry)!!.takeLast(room - head.length))
            break
        }
        return kept.toList()
    }

    /** Marks a message whose beginning was cut to fit. */
    const val SHORTENED = "[shortened] …"

    /** A cut message keeps at least this much of its end, or is left out. */
    private const val MIN_CUT = 200
}
