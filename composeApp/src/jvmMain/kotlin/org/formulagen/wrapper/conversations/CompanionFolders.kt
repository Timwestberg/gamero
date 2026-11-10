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
 * in Hovel from a rename by hand. A folder the person renamed is [pinned]: Hovel keeps their name.
 */
@Serializable
internal data class CompanionFolder(val name: String, val from: String, val pinned: Boolean = false)

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

/** The visible folders as the conversation engine sees them. */
internal fun interface CompanionFolders {
    /**
     * Brings one conversation's visible folder up to date: names it as [names] says, writes every one of [outputs]
     * not yet written ([bytes] reads one), and rewrites its index. It is safe to repeat, and it never throws: what
     * could not be written is tried again at the next call.
     */
    fun sync(names: FolderNames, outputs: List<RecordedOutput>, bytes: (ArtifactRef) -> ByteArray)

    companion object {
        /** A Mac that keeps no visible folders, and every test that does not ask for them. */
        val NONE = CompanionFolders { _, _, _ -> }
    }
}

/** How the visible folders are named and what their index says. Pure: nothing here touches the disk. */
internal object CompanionFolderRules {
    const val MARKER = ".hovel.json"
    const val CONVERSATIONS = "conversations"
    const val OUTPUTS = "outputs"
    const val INDEX = "index.md"

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
