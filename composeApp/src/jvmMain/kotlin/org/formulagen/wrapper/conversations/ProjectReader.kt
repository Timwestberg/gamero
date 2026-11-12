package org.formulagen.wrapper.conversations

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import org.formulagen.wrapper.brain.CoordinatorDecision

/*
 * Reading a project's files for the coordinator, which has no file tools of its own. The design is in
 * docs/companion-folders/companion-folders-step2.ARCH.md.
 */

/** One message's allowance of reading. Starting values, to be tuned from use. */
internal object ReadLimits {
    /** How many times the coordinator may ask before it must answer or delegate. */
    const val ROUNDS = 2
    /** The text one message may be read, over all its rounds. */
    const val CHARS = 32_000
    const val LIST_ENTRIES = 200
    const val SEARCH_MATCHES = 40
    const val SEARCH_LINE_CHARS = 240
    /** A search stops looking after this many files, or this long, so a huge project cannot hold a message. */
    const val SEARCH_FILES = 1_500
    const val SEARCH_MILLIS = 3_000L
    /** A file larger than this is not searched, and only its start is ever read. */
    const val FILE_BYTES = 200_000L
}

/**
 * What a round of reading gave: [text] for the coordinator's next request, and for the person [read], what was read
 * by name, and [refused], what was not.
 */
internal data class ReadResult(val text: String, val read: List<String>, val refused: List<String>) {
    /** What the conversation says was read: "Read README.md and 2 more." Null when nothing was. */
    fun line(): String? = when (read.size) {
        0 -> null
        1 -> "Read ${read[0]}."
        2 -> "Read ${read[0]} and ${read[1]}."
        else -> "Read ${read[0]} and ${read.size - 1} more."
    }?.take(LINE_CHARS)

    private companion object { const val LINE_CHARS = 240 }
}

/**
 * Reads for one message, within its allowance ([ReadLimits]). Every path goes through [guard]; what it refuses is
 * named with its reason and never read. It only reads: nothing here writes, runs or follows a link out of a folder.
 */
internal class ProjectReader(private val guard: PathGuard, private val now: () -> Long = System::currentTimeMillis) {
    private var rounds = 0
    private var chars = 0

    /** Whether the message's allowance is used up: the coordinator then answers or delegates. */
    val spent: Boolean get() = rounds >= ReadLimits.ROUNDS || chars >= ReadLimits.CHARS

    /** Whether there is a folder to read at all. */
    val possible: Boolean get() = guard.roots.isNotEmpty()

    fun answer(request: CoordinatorDecision.Read): ReadResult {
        rounds++
        val read = ArrayList<String>()
        val refused = ArrayList<String>()
        val parts = ArrayList<String>()
        fun allowed(asked: String): PathGuard.Checked.Allowed? = when (val checked = guard.check(asked)) {
            is PathGuard.Checked.Allowed -> checked
            is PathGuard.Checked.Refused -> { refused += "${checked.shown} (${checked.reason.said})"; null }
        }
        for (asked in request.files.orEmpty()) {
            val file = allowed(asked) ?: continue
            if (chars >= ReadLimits.CHARS) { refused += "${file.shown} (this message's reading allowance is used up)"; continue }
            val text = file(file.path)
            if (text == null) { refused += "${file.shown} (${if (Files.isDirectory(file.path)) "a folder: list it instead" else "not a text file"})"; continue }
            val kept = text.first.take(ReadLimits.CHARS - chars)
            chars += kept.length
            val whole = !text.second && kept.length == text.first.length
            parts += "File ${file.shown}, ${if (whole) "in full" else "its first ${kept.length} characters"}:\n<<<\n$kept\n>>>"
            read += file.shown
        }
        for (asked in request.list.orEmpty()) {
            val folder = allowed(asked) ?: continue
            val listing = listing(folder.path)
            if (listing == null) { refused += "${folder.shown} (not a folder)"; continue }
            chars += listing.length
            parts += "Folder ${folder.shown}: $listing"
            read += if (folder.shown == ".") "the folder's list" else "${folder.shown}/"
        }
        request.search?.let { wanted ->
            val found = search(wanted)
            chars += found.length
            parts += found
            read += "a search for \"${wanted.take(SEARCH_SHOWN)}\""
        }
        if (refused.isNotEmpty()) parts += "Not read: ${refused.joinToString("; ")}."
        if (spent) parts += "This message's reading allowance is now used up. Answer from what you have, or delegate."
        return ReadResult(parts.joinToString("\n\n"), read, refused)
    }

    /** A text file's start and whether more follows; null for a folder, a binary file or one that cannot be read. */
    private fun file(path: Path): Pair<String, Boolean>? = runCatching {
        if (!Files.isRegularFile(path)) return null
        val size = Files.size(path)
        val bytes = Files.newInputStream(path).use { it.readNBytes(minOf(size, ReadLimits.FILE_BYTES).toInt()) }
        decode(bytes, cut = size > bytes.size)?.let { it to (size > bytes.size) }
    }.getOrNull()

    /** [bytes] as UTF-8 text, or null when they are not text. A [cut] read may end inside a character, which is dropped. */
    private fun decode(bytes: ByteArray, cut: Boolean): String? {
        if (bytes.any { it == 0.toByte() }) return null
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        for (drop in 0..if (cut) 3 else 0) {
            runCatching { return decoder.reset().decode(ByteBuffer.wrap(bytes, 0, bytes.size - drop)).toString() }
        }
        return null
    }

    /** A folder's entries by name, folders marked with `/`, without what is skipped or linked; null when it is no folder. */
    private fun listing(path: Path): String? = runCatching {
        if (!Files.isDirectory(path)) return null
        val (names, more) = entries(path)
        if (names.isEmpty()) "empty." else names.joinToString(", ") + if (more) ", and more" else ""
    }.getOrNull()

    private fun entries(dir: Path): Pair<List<String>, Boolean> {
        val names = ArrayList<String>()
        var more = false
        Files.newDirectoryStream(dir).use { stream ->
            for (entry in stream) {
                val name = entry.fileName.toString()
                val shown = when {
                    Files.isSymbolicLink(entry) -> null
                    Files.isDirectory(entry, NOFOLLOW_LINKS) -> "$name/".takeUnless { SkipList.folder(name) }
                    Files.isRegularFile(entry, NOFOLLOW_LINKS) -> name.takeUnless { SkipList.file(name) }
                    else -> null
                } ?: continue
                if (names.size == ReadLimits.LIST_ENTRIES) { more = true; break }
                names += shown
            }
        }
        return names.sorted() to more
    }

    /** The lines that hold [wanted], whatever its case, in the folders' text files: nearest the top first, and bounded. */
    private fun search(wanted: String): String {
        val needle = wanted.trim().lowercase()
        val matches = ArrayList<String>()
        val deadline = now() + ReadLimits.SEARCH_MILLIS
        var files = 0
        var stopped = false
        search@ for (root in guard.roots) {
            val waiting = ArrayDeque(listOf(root.real))
            while (waiting.isNotEmpty()) {
                val dir = waiting.removeFirst()
                val (names, _) = runCatching { entries(dir) }.getOrNull() ?: continue
                for (name in names) {
                    if (name.endsWith("/")) { waiting.addLast(dir.resolve(name.dropLast(1))); continue }
                    if (files++ >= ReadLimits.SEARCH_FILES || now() > deadline) { stopped = true; break@search }
                    val path = dir.resolve(name)
                    val text = runCatching { if (Files.size(path) > ReadLimits.FILE_BYTES) null else file(path)?.first }.getOrNull() ?: continue
                    text.lineSequence().forEachIndexed { index, line ->
                        if (matches.size < ReadLimits.SEARCH_MATCHES && line.lowercase().contains(needle)) {
                            matches += "${guard.shown(root, path)}:${index + 1}: ${line.trim().take(ReadLimits.SEARCH_LINE_CHARS)}"
                        }
                    }
                    if (matches.size >= ReadLimits.SEARCH_MATCHES) { stopped = true; break@search }
                }
            }
        }
        val head = "Search for \"$wanted\": " + when {
            matches.isEmpty() -> "no line holds it" + if (stopped) " in the files looked at; the search stopped early." else "."
            stopped -> "the first ${matches.size} lines; the search stopped early, so there may be more."
            else -> "${matches.size} ${if (matches.size == 1) "line" else "lines"}."
        }
        return if (matches.isEmpty()) head else head + "\n<<<\n" + matches.joinToString("\n") + "\n>>>"
    }

    private companion object { const val SEARCH_SHOWN = 40 }
}
