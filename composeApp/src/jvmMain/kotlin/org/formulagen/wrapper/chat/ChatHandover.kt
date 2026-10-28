package org.formulagen.wrapper.chat

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.formulagen.wrapper.bridge.ChatEntry
import org.formulagen.wrapper.bridge.HandoverLimits
import org.formulagen.wrapper.bridge.HandoverMethod
import org.formulagen.wrapper.bridge.HandoverReason
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.bridge.TeamMember

/** What a handover will do, decided from the record before any runtime runs. */
internal sealed interface HandoverPlan {
    /** Record [method] now; [context] is owed to the incoming runtime's next turn, when there is any. */
    data class Record(val method: HandoverMethod, val context: String?) : HandoverPlan

    /** Ask the outgoing runtime to summarize [transcript]; if it cannot, the incoming one gets [fallback], the recent messages. */
    data class Summarize(val transcript: String, val fallback: String) : HandoverPlan
}

/**
 * How one member hands the conversation to the next (`chat.handover`, and a runtime joining or
 * starting over). Pure: [DesktopChat] runs the summary and records the entry. Everything a runtime
 * is handed is delimited data, never instructions.
 */
internal object Handovers {
    /** Longest transcript an outgoing member is asked to summarize; the oldest messages drop first. */
    const val TRANSCRIPT_LIMIT = 24_000

    /**
     * The same runtime keeps its own conversation. Another runtime gets a summary when [from] can
     * write one ([fromCanSummarize]: its runtime is on this Mac and has spoken in this conversation),
     * the recent messages otherwise, and nothing when it has missed nothing.
     */
    fun plan(record: ChatRecord, from: TeamMember?, to: TeamMember, fromCanSummarize: Boolean): HandoverPlan {
        if (from != null && from.runtimeId == to.runtimeId) return HandoverPlan.Record(HandoverMethod.SAME_CONVERSATION, null)
        val unseen = unseen(record, to.runtimeId)
        val recent = recentMessages(unseen) ?: return HandoverPlan.Record(HandoverMethod.FRESH, null)
        return if (from != null && fromCanSummarize) HandoverPlan.Summarize(transcript(unseen), recent)
        else HandoverPlan.Record(HandoverMethod.RECENT_MESSAGES, recent)
    }

    /** The messages [runtime] has not seen: those after its last reply while it keeps its own conversation, otherwise all of them. */
    fun unseen(record: ChatRecord, runtime: RuntimeId): List<ChatEntry> {
        val turns = record.entries.filter { it is ChatEntry.User || it is ChatEntry.Assistant }
        if (record.conversations[runtime] == null) return turns
        return turns.drop(turns.indexOfLast { it is ChatEntry.Assistant && it.author.runtimeId == runtime } + 1)
    }

    /** The member that wrote the last reply, or null when nobody has replied. */
    fun lastAuthor(entries: List<ChatEntry>): TeamMember? = entries.filterIsInstance<ChatEntry.Assistant>().lastOrNull()?.author

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

    /** The handover record, which is also its note in the conversation. */
    fun entry(id: String, from: TeamMember?, to: TeamMember, method: HandoverMethod, summary: String? = null, at: Instant = Instant.now()) =
        ChatEntry.Handover(id, at.truncatedTo(ChronoUnit.MILLIS).toString(), from, to, method, HandoverReason.MANUAL,
            summary?.takeIf { method == HandoverMethod.SUMMARY })

    /** "Claude (sonnet)", "Codex (its default model)": how a member is named to another runtime. */
    fun label(member: TeamMember): String =
        "${member.runtimeId.product} (${if (member.modelId == RUNTIME_DEFAULT_MODEL) "its default model" else member.modelId})"

    private fun block(source: String, text: String) = "Earlier in this conversation ($source):\n<<<\n$text\n>>>"

    private fun line(entry: ChatEntry): String = when (entry) {
        is ChatEntry.User -> "Person: ${entry.text}"
        is ChatEntry.Assistant -> "Companion: ${entry.text}"
        is ChatEntry.Handover -> ""
    }

    /** The newest messages that fit, oldest first. The oldest one that does not fit whole keeps its end, marked as shortened. */
    private fun bounded(entries: List<ChatEntry>, count: Int, chars: Int): List<String> {
        val kept = ArrayDeque<String>()
        var room = chars
        for (entry in entries.filterNot { it is ChatEntry.Handover }.takeLast(count).asReversed()) {
            val line = line(entry)
            if (line.length <= room) { kept.addFirst(line); room -= line.length; continue }
            if (room >= MIN_CUT) kept.addFirst("[shortened] …" + line.takeLast(room - 13))
            break
        }
        return kept.toList()
    }

    /** A cut message keeps at least this much of its end, or is left out. */
    private const val MIN_CUT = 200
}
