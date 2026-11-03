package org.formulagen.wrapper.workspace

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.runtimes.WorkPaths
import org.formulagen.wrapper.storage.privateFolder
import org.formulagen.wrapper.storage.readBounded
import org.formulagen.wrapper.storage.writeDurably
import org.formulagen.wrapper.util.loggingDaemonThreads
import org.formulagen.wrapper.util.shortened

/*
 * Which files each working thread edited, written by the Mac as the edits happen, for the workspace's View changes.
 * The page's activity keeps only the latest reply's last steps; this keeps every reply's edited files, within the
 * player's history limits. Only edits a runtime reports as finished reach it: files changed by a command never do.
 */

/** Receives each file a working reply edits, as its runtime reports it. Null: no file named. */
internal fun interface EditListener {
    fun edited(path: Path?)

    companion object {
        /** Keeps nothing: a working turn without an edit record. */
        val NONE = EditListener {}
    }
}

/** Report the file a runtime named as text. Blank text, or text that is no path, is an edit without a file. */
internal fun EditListener.reportEdit(raw: String?) = edited(raw?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() })

/** One thread's record, in workspace/edits/<projectId>/<threadId>.json. */
@Serializable
internal data class ThreadEdits(
    val threadId: String,
    val title: String,                  // for "also edited by"
    val files: List<String>,            // relative to the working folder, unique, in first-edit order
    val gaps: Set<EditGap> = emptySet(),
    val updatedAt: Long,
    val version: Int = 1,
)

/** Why a record may not list every file its thread edited. */
@Serializable
internal enum class EditGap {
    @SerialName("file-limit") FILE_LIMIT,             // reached the player's files-per-thread setting
    @SerialName("unnamed-edit") UNNAMED_EDIT,         // the runtime reported an edit without a file
    @SerialName("earlier-replies") EARLIER_REPLIES,   // the thread had replies before its record existed
}

/**
 * One account's thread edit records, under `<providerRoot>/<accountId>/workspace/edits/<projectId>/<threadId>.json`
 * in private folders. [thread] opens a working turn's record and returns its [EditRecorder].
 *
 * One writer thread does all the work: it resolves reported paths, keeps the records in memory, and writes each one
 * whole through [write] (`writeDurably`), so runtime threads never wait on the disk and nothing it does throws into
 * them. A record is written when it is opened, and when its files or gaps change once the edits reported together
 * are applied (a multi-file change is one write). A failed write is logged, and the next change writes the whole
 * record again. [limits] gives the account's history limits, read at each opening and
 * each new file; until profile sync exists they are the defaults.
 */
internal class ThreadEditStore(
    providerRoot: Path,
    accountId: String,
    private val limits: () -> WorkspaceLimits = { WorkspaceLimits.DEFAULTS },
    private val now: () -> Long = System::currentTimeMillis,
    private val write: (Path, ByteArray) -> Unit = ::writeDurably,
) : AutoCloseable {
    private val workspace: Path
    private val base: Path

    init {
        require(SharedRules.isRecordId(accountId)) { "Edit records are keyed by an account id" }
        workspace = providerRoot.resolve(accountId).resolve("workspace")
        base = workspace.resolve("edits")
    }

    private val queue = LinkedBlockingQueue<Runnable>()
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue, loggingDaemonThreads("hovel-thread-edits"))

    /** Records in memory, least recently used first. Touched only on [writer]. */
    private val entries = LinkedHashMap<Key, Entry>()
    /** Records changed since they were last written, written once the queue is empty. Touched only on [writer]. */
    private val pending = LinkedHashSet<Entry>()

    /**
     * Before a working turn in [threadId]: create its record, or refresh the one it has (its [title] and `updatedAt`),
     * then trim [projectId] to the threads-per-project limit, oldest `updatedAt` first. A new record for a thread with
     * [earlierReplies] (a provider conversation already) gets [EditGap.EARLIER_REPLIES]. Edits are resolved against
     * the working [folder]. Throws only for ids that are not record ids.
     */
    fun thread(projectId: String, threadId: String, title: String, earlierReplies: Boolean, folder: Path): EditRecorder {
        require(SharedRules.isRecordId(projectId) && SharedRules.isRecordId(threadId)) { "Edit records are keyed by record ids" }
        later { open(Key(projectId, threadId), title.shortened(CompanionLimits.THREAD_TITLE), earlierReplies) }
        return EditRecorder(projectId, threadId, folder)
    }

    /** [threadId]'s record as last written, or null: none, or one that cannot be read (larger than the guard, or damaged). */
    fun read(projectId: String, threadId: String): ThreadEdits? {
        require(SharedRules.isRecordId(projectId) && SharedRules.isRecordId(threadId)) { "Edit records are keyed by record ids" }
        return readRecord(Key(projectId, threadId))
    }

    /** Finish the edits already queued and write them (briefly), then take no more. */
    override fun close() {
        writer.shutdown()
        if (!writer.awaitTermination(CLOSE_SECONDS, TimeUnit.SECONDS)) log(LogLevel.Warn, TAG, "Thread edit records were still being written at close")
    }

    /**
     * One working turn's listener. What its runtime reports is queued for the writer, which keeps a path only inside
     * the working folder: as given, or as the file system resolves it (`/tmp` is `/private/tmp` on a Mac).
     */
    inner class EditRecorder internal constructor(val projectId: String, val threadId: String, folder: Path) : EditListener {
        private val key = Key(projectId, threadId)
        /** Resolved on the writer, at the first edit. */
        private val paths by lazy { WorkPaths(folder) }

        override fun edited(path: Path?) = later { add(key, path?.let { relative(paths, it) ?: return@later }) }
    }

    // ---- On the writer only ----------------------------------------------------------------------

    private fun open(key: Key, title: String, earlierReplies: Boolean) {
        val at = now()
        val entry = entry(key) ?: Entry(key, title, emptyList(), if (earlierReplies) setOf(EditGap.EARLIER_REPLIES) else emptySet(), at)
            .also { remember(it) }
        entry.title = title
        entry.updatedAt = at
        save(entry)
        trim(key)
    }

    /** One reported edit: [file] relative to the folder, or null for an edit without a file. */
    private fun add(key: Key, file: String?) {
        // No record: the thread's was trimmed while it ran, so it starts again at its next turn.
        val entry = entry(key) ?: return
        val changed = when {
            file == null -> entry.gaps.add(EditGap.UNNAMED_EDIT)
            file in entry.files -> false
            entry.files.size >= threadFiles() || entry.bytes + cost(file) > FILES_BUDGET -> entry.gaps.add(EditGap.FILE_LIMIT)
            else -> { entry.files += file; entry.bytes += cost(file); true }
        }
        if (!changed) return
        entry.updatedAt = now()
        pending += entry
    }

    /** Write [entry] whole. A failure leaves it unsaved in memory, so the next change writes it again. */
    private fun save(entry: Entry) {
        pending -= entry
        try {
            val bytes = editsJson.encodeToString(ThreadEdits.serializer(), entry.record()).toByteArray()
            privateFolder(workspace); privateFolder(base)
            write(privateFolder(base.resolve(entry.key.projectId)).resolve("${entry.key.threadId}.json"), bytes)
            entry.unsaved = false
        } catch (error: Exception) {
            entry.unsaved = true
            log(LogLevel.Warn, TAG, "A thread's edit record could not be written; its next change writes it again", error)
        }
    }

    /** Delete [keep]'s project's oldest records, by `updatedAt`, beyond the threads-per-project limit. [keep] stays. */
    private fun trim(keep: Key) {
        val limit = WorkspaceSettingsLimits.PROJECT_THREADS.resolve(runCatching { limits().projectThreads }.getOrNull())
        val threads = recordIds(base.resolve(keep.projectId)) + keep.threadId
        if (threads.size <= limit) return
        // A record that cannot be read counts as the oldest.
        val updated = { threadId: String -> Key(keep.projectId, threadId).let { entries[it]?.updatedAt ?: readRecord(it)?.updatedAt ?: Long.MIN_VALUE } }
        val oldest = (threads - keep.threadId).sortedWith(compareBy<String>(updated, { it })).take(threads.size - limit)
        for (threadId in oldest) {
            val key = Key(keep.projectId, threadId)
            entries.remove(key)?.let(pending::remove)
            runCatching { Files.deleteIfExists(file(key)) }.onFailure { log(LogLevel.Warn, TAG, "An old thread edit record could not be removed", it) }
        }
    }

    /** [key]'s record: in memory, or read back from disk. Null when it has none. */
    private fun entry(key: Key): Entry? =
        entries.remove(key)?.also { entries[key] = it } ?: readRecord(key)?.let { Entry(key, it.title, it.files, it.gaps, it.updatedAt) }?.also(::remember)

    /** Keep [entry] in memory, dropping the least recently used records that are safely on disk. */
    private fun remember(entry: Entry) {
        entries[entry.key] = entry
        val excess = entries.size - ENTRY_MEMORY
        if (excess > 0) entries.entries.filter { it.value !in pending && !it.value.unsaved }.take(excess).forEach { entries.remove(it.key) }
    }

    private fun threadFiles() = WorkspaceSettingsLimits.THREAD_FILES.resolve(runCatching { limits().threadFiles }.getOrNull())

    // ---- Anywhere ----------------------------------------------------------------------------------

    /** Run [task] on the writer, and write what changed once nothing else waits; once closed, nothing runs. Never throws. */
    private fun later(task: () -> Unit) {
        try {
            writer.execute {
                try { task() } catch (error: Exception) { log(LogLevel.Warn, TAG, "A thread edit could not be recorded", error) }
                if (queue.isEmpty()) pending.toList().forEach(::save)
            }
        } catch (_: RejectedExecutionException) { /* Closed: the record keeps what it has. */ }
    }

    private fun readRecord(key: Key): ThreadEdits? {
        val file = file(key)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return null
        return try {
            editsJson.decodeFromString(ThreadEdits.serializer(), readBounded(file, WorkspaceGuards.EDIT_RECORD_BYTES.toLong()).decodeToString())
                .takeIf { it.version == 1 && it.threadId == key.threadId }
        } catch (error: Exception) {
            log(LogLevel.Warn, TAG, "A thread edit record could not be read", error)
            null
        }
    }

    private fun recordIds(folder: Path): Set<String> {
        if (!Files.isDirectory(folder, NOFOLLOW_LINKS)) return emptySet()
        return Files.list(folder).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.filter(SharedRules::isRecordId).toList()
        }.toSet()
    }

    private fun file(key: Key): Path = base.resolve(key.projectId).resolve("${key.threadId}.json")

    /** Both ids were checked as record ids before a key is made. */
    private data class Key(val projectId: String, val threadId: String)

    /** A record as the writer keeps it. [unsaved]: the last write failed. */
    private class Entry(val key: Key, var title: String, files: List<String>, gaps: Set<EditGap>, var updatedAt: Long) {
        val files = LinkedHashSet(files)
        val gaps: MutableSet<EditGap> = gaps.toMutableSet()
        var bytes = files.sumOf(::cost)
        var unsaved = false

        fun record() = ThreadEdits(key.threadId, title, files.toList(), gaps.sorted().toSet(), updatedAt)
    }

    private companion object {
        const val TAG = "workspace"
        /** Records kept in memory; others are read back when their thread edits again. */
        const val ENTRY_MEMORY = 64
        const val CLOSE_SECONDS = 2L
        /**
         * The bytes a record's file list may take, so a record always reads back within
         * [WorkspaceGuards.EDIT_RECORD_BYTES]: the rest of a record (ids, a 120-character title, gaps) fits in 16 KiB.
         * A thread of long paths reaches it before the file limit, and gets [EditGap.FILE_LIMIT] the same way.
         */
        const val FILES_BUDGET = WorkspaceGuards.EDIT_RECORD_BYTES - 16 * 1024

        val editsJson = Json { encodeDefaults = true }

        /** One file's bytes in the record's list: its JSON string and a separator. */
        fun cost(file: String): Int = editsJson.encodeToString(String.serializer(), file).toByteArray().size + 1

        /**
         * [path] relative to [paths]' folder, or null outside it: as given, then as the file system resolves it, so
         * a path through a link to the folder (or from the folder's real path to its link) is still inside. The folder
         * itself is no file.
         */
        fun relative(paths: WorkPaths, path: Path): String? =
            (paths.relative(path) ?: onDisk(paths.absolute(path))?.let(paths::relative))?.takeIf { it != "." }

        /** [path] with links resolved: its own real path, or its nearest existing folder's with the rest added (a deleted file). */
        fun onDisk(path: Path): Path? {
            var existing: Path? = path
            while (existing != null && !Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.parent
            val found = existing ?: return null
            return runCatching { found.toRealPath().resolve(found.relativize(path)).normalize() }.getOrNull()
        }
    }
}
