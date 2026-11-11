package org.formulagen.wrapper.conversations

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.providers.copy
import org.formulagen.wrapper.storage.Artifacts

/*
 * A companion's visible folder on this Mac: what it holds and how its names are made. The design is in
 * docs/companion-folders/companion-folders.ARCH.md. `VisibleFolders` is the only code that touches the disk.
 */

/** Who a visible folder belongs to, in [CompanionFolderRules.MARKER] inside it. A folder is found by this file, never by its name. */
@Serializable
internal data class FolderMarker(
    val version: Int = 1,
    val accountId: String,
    val companionId: String,
    /** Null on a companion's folder; set on a conversation's. */
    val conversationId: String? = null,
)

/**
 * What Hovel has written to the visible folders, per account, at `<root>/<accountId>/companion-folders.json`.
 * The conversation records stay the truth about outputs; this is only the mirror's memory.
 */
@Serializable
internal data class FolderLedger(
    val version: Int = 1,
    /** By companion id. */
    val companions: Map<String, CompanionFolder> = emptyMap(),
    /** By conversation id. */
    val conversations: Map<String, ConversationFolder> = emptyMap(),
)

/**
 * [name] is the folder as it was last seen, and [from] the companion's name it was made from, which tells a rename
 * in Hovel from a rename by hand. A folder the person renamed is [pinned]: Hovel keeps their name. [limitBytes] is
 * the space the person lets this companion's folder take on this Mac before the page says so; null is the default
 * ([StorageLimits.DEFAULT_BYTES]).
 */
@Serializable
internal data class CompanionFolder(val name: String, val from: String, val pinned: Boolean = false, val limitBytes: Long? = null)

/** A conversation's folder, named as [CompanionFolder] is, from the conversation's title. */
@Serializable
internal data class ConversationFolder(
    val companionId: String,
    val name: String,
    val from: String,
    val pinned: Boolean = false,
    /** The day its first output was made, `yyyy-MM-dd`. It never changes. */
    val startedOn: String,
    /** In the order written. An entry stays when the person deletes its file, so the file is not written again. */
    val outputs: List<MirroredOutput> = emptyList(),
)

/** One output as `index.md` lists it. [at] is an ISO-8601 instant. */
@Serializable
internal data class MirroredOutput(
    val artifactId: String,
    val file: String,
    val madeBy: String,
    val runtimeId: RuntimeId,
    val modelId: String,
    val at: String,
)

/** What a claim says the folders are called now. */
internal data class FolderNames(
    val accountId: String,
    val companionId: String,
    val companion: String,
    val conversationId: String,
    val conversation: String,
)

/** An output a conversation's record holds, with who made it and when it was stored. */
internal data class RecordedOutput(
    val artifact: ArtifactRef,
    val madeBy: String,
    val runtimeId: RuntimeId,
    val modelId: String,
    val at: Instant,
)

/** Whether a conversation has a visible folder, and whether it holds every output. */
internal enum class FolderState {
    /** No folder yet: the conversation has produced no output, or this Mac keeps no visible folders. */
    NONE,
    READY,
    /** The last sync could not write something there; it is tried again at the conversation's next message. */
    BEHIND,
}

/** The conversations and companions this Mac keeps a visible folder for, or those of them the account no longer has. */
internal data class FolderIds(val companionIds: Set<String> = emptySet(), val conversationIds: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = companionIds.isEmpty() && conversationIds.isEmpty()
}

/**
 * A companion's folder and the space it takes. Bytes are a thousand to the kilobyte, as Finder counts them. The
 * values mirror the contract's `STORAGE_LIMITS`; the counting bounds are this Mac's own.
 */
internal object StorageLimits {
    const val DEFAULT_BYTES = 1_000_000_000L
    const val MIN_BYTES = 100_000_000L
    const val MAX_BYTES = 1_000_000_000_000L
    /** The most conversations one answer lists, and one clean-up names. */
    const val CONVERSATIONS = 200
    /** A folder with more entries than this, or one that takes longer to count, is reported as at least what was counted. */
    const val ENTRIES = 200_000
    const val MILLIS = 2_000L
}

/** One conversation's folder: what it takes, and when a file in it last changed. */
internal data class ConversationUsage(val conversationId: String, val usedBytes: Long, val changedAt: Instant)

/**
 * What a companion's folder takes on this Mac: all of it ([usedBytes], the person's own files there too), the part in
 * scratch folders, and each conversation's folder, the one changed longest ago first. [partial]: the folder was too
 * large to count to the end, so every size is at least this. Passing [limitBytes] changes nothing but what the page says.
 */
internal data class FolderUsage(val usedBytes: Long, val limitBytes: Long, val scratchBytes: Long, val partial: Boolean,
    val conversations: List<ConversationUsage>)

/** How long a scratch file is kept. A starting value, to be tuned from use. */
internal object ScratchLimits {
    /** A file a run did not clear, because the run never completed, goes after this many days. */
    const val KEPT_DAYS = 7L
}

/** The visible folders as the conversation engine sees them. */
internal fun interface CompanionFolders {
    /**
     * Brings one conversation's visible folder up to date: names it as [names] says, writes every one of [outputs]
     * not yet written ([bytes] reads one), and rewrites its index and its copy of the shared [notes]. It is safe to
     * repeat, and it never throws: what could not be written is tried again at the next call.
     */
    fun sync(names: FolderNames, outputs: List<RecordedOutput>, notes: List<TeamNote>, bytes: (ArtifactRef) -> ByteArray)

    /** Whether [conversationId] has a visible folder, and whether it is up to date. */
    fun state(accountId: String, conversationId: String): FolderState = FolderState.NONE

    /** Shows [conversationId]'s folder in Finder. False when it has none. */
    fun show(accountId: String, conversationId: String): Boolean = false

    /** Every conversation and companion of [accountId] a folder is kept for. */
    fun kept(accountId: String): FolderIds = FolderIds()

    /**
     * The account no longer has [gone]: each of their folders is moved to the Trash, where the person can still get it
     * back, and forgotten. A folder is moved only when its marker says it is that conversation's or companion's.
     */
    fun gone(accountId: String, gone: FolderIds) {}

    /**
     * The conversation's scratch folder, where its working agents leave files for each other, made when a working
     * turn first needs it. Null when this Mac keeps no visible folders, or the folder cannot be made: the turn then
     * works without one.
     */
    fun scratch(names: FolderNames): Path? = null

    /** Empties the conversation's scratch folder: the run that used it has completed. */
    fun emptyScratch(accountId: String, conversationId: String) {}

    /** Deletes what has lain in any of [accountId]'s scratch folders for more than [ScratchLimits.KEPT_DAYS] days. */
    fun sweepScratch(accountId: String) {}

    /** What [companionId]'s folder takes on this Mac. Null when it has no folder here, or it cannot be counted. */
    fun usage(accountId: String, companionId: String): FolderUsage? = null

    /** Sets the space [companionId]'s folder may take before the page says so, and answers its usage. Null when it has no folder here. */
    fun setLimit(accountId: String, companionId: String, limitBytes: Long): FolderUsage? = null

    /**
     * The person's clean-up of [companionId]'s folder: with [scratch], every scratch folder in it is emptied, and the
     * folder of each of [conversationIds] is moved to the Trash. The conversations stay in the account, and a folder
     * that went comes back only when its conversation has something new to put in it. Answers the usage afterwards.
     */
    fun clean(accountId: String, companionId: String, conversationIds: Set<String>, scratch: Boolean): FolderUsage? = null

    companion object {
        /** A Mac that keeps no visible folders, and every test that does not ask for them. */
        val NONE = CompanionFolders { _, _, _, _ -> }
    }
}

/** How the visible folders are named and what their index says. Pure: nothing here touches the disk. */
internal object CompanionFolderRules {
    const val MARKER = ".hovel.json"
    const val CONVERSATIONS = "conversations"
    const val OUTPUTS = "outputs"
    const val INDEX = "index.md"
    const val NOTES = "notes.md"
    const val SCRATCH = "scratch"

    /** Moves the root, for a test rig: a test account must never write into the person's real folders. */
    const val ROOT_OVERRIDE = "HOVEL_COMPANION_FOLDERS"

    /** The longest name of a folder or a file, before its extension. */
    const val NAME_LIMIT = 80

    /** How far into an output its first heading is looked for. */
    private const val HEADING_LINES = 40

    private val HEADING = Regex("^ {0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val WHITESPACE = Regex("\\s+")
    private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** `~/Hovel`, or where [ROOT_OVERRIDE] says. */
    fun root(environment: Map<String, String>, home: Path): Path =
        environment[ROOT_OVERRIDE]?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() } ?: home.resolve("Hovel")

    /**
     * [text] as a name Finder shows as written: `/` and `:` become `-`, control characters go, runs of whitespace
     * become one space, and a leading dot goes so the name is never hidden. Cut to [limit]; [fallback] when nothing is left.
     */
    fun safeName(text: String, fallback: String, limit: Int = NAME_LIMIT): String {
        val cleaned = text.map { if (it == '/' || it == ':') '-' else it }.filterNot { it.isISOControl() && !it.isWhitespace() }.joinToString("")
        val trimmed = WHITESPACE.replace(cleaned, " ").trim().trimStart('.').trim()
        return trimmed.take(limit).trim().trimEnd('.').trim().ifEmpty { fallback }
    }

    /** [wanted], or [wanted] with ` 2`, ` 3` before [extension] until none of [taken] has it. Names differing only by case are the same file. */
    fun freeName(wanted: String, taken: Collection<String>, extension: String = ""): String {
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        if ((wanted + extension).lowercase() !in used) return wanted + extension
        var number = 2
        while ("$wanted $number$extension".lowercase() in used) number++
        return "$wanted $number$extension"
    }

    /** The first heading of [markdown], outside a code fence and within its first lines, as plain text. Null when it has none. */
    fun firstHeading(markdown: String): String? {
        var fenced = false
        for (line in markdown.lineSequence().take(HEADING_LINES)) {
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) { fenced = !fenced; continue }
            if (fenced) continue
            val heading = HEADING.matchEntire(line)?.groupValues?.get(1) ?: continue
            val plain = LINK.replace(heading) { it.groupValues[1] }.filterNot { it == '*' || it == '_' || it == '`' }.trim()
            if (plain.isNotEmpty()) return plain
        }
        return null
    }

    /** `.md` for the text an agent writes, which is Markdown either way; `.bin` for anything else. */
    fun extension(mediaType: String): String = if (mediaType in Artifacts.TEXT) ".md" else ".bin"

    /**
     * An output's file name without its extension: its place in the conversation as two digits, then its first heading,
     * or [title] (the specialist's or step's name) when it has none.
     */
    fun outputName(order: Int, title: String, text: String?): String {
        val number = order.toString().padStart(2, '0')
        return "$number " + safeName(text?.let(::firstHeading) ?: title, "Output", NAME_LIMIT - number.length - 1)
    }

    /** A conversation's folder: the day of its first output, then its title. */
    fun conversationName(startedOn: String, title: String): String =
        "$startedOn " + safeName(title, "Conversation", NAME_LIMIT - startedOn.length - 1)

    /** `index.md`: each output in the order it was made, who made it and with what. Hovel rewrites it at every sync. */
    fun index(names: FolderNames, folder: ConversationFolder, zone: ZoneId): String = buildString {
        append("# ").append(names.conversation.ifBlank { "Conversation" }).append("\n\n")
        append("What ").append(names.companion).append("'s agents produced in this conversation, oldest first. ")
        append("Hovel writes this file. An edit here is replaced.\n\n")
        if (folder.outputs.isEmpty()) { append("Nothing yet.\n"); return@buildString }
        append("| # | File | Made by | Provider | Model | When |\n| --- | --- | --- | --- | --- | --- |\n")
        folder.outputs.forEachIndexed { index, output ->
            val link = "$OUTPUTS/" + linkPath(output.file)
            val at = runCatching { WHEN.format(Instant.parse(output.at).atZone(zone)) }.getOrDefault(output.at)
            append("| ").append((index + 1).toString().padStart(2, '0'))
            append(" | [").append(cell(output.file)).append("](").append(link).append(")")
            append(" | ").append(cell(output.madeBy))
            append(" | ").append(cell(output.runtimeId.copy.product))
            append(" | ").append(cell(output.modelId))
            append(" | ").append(at).append(" |\n")
        }
    }

    /** [file] as a Markdown link's target: the characters that would end the link or the table cell are escaped. */
    private fun linkPath(file: String): String = buildString {
        for (ch in file) append(when (ch) {
            '%' -> "%25"; ' ' -> "%20"; '|' -> "%7C"; '(' -> "%28"; ')' -> "%29"; '#' -> "%23"; '?' -> "%3F"
            else -> ch.toString()
        })
    }

    /** [text] as it can stand in a table cell. */
    private fun cell(text: String) = text.replace("|", "\\|").replace("\n", " ")
}
