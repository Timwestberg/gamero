package org.formulagen.wrapper.workspace

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.runtimes.EditListener
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

/** One thread's record, in workspace/edits/<projectId>/<threadId>.json. */
@Serializable
internal data class ThreadEdits(
    val threadId: String,
    val title: String,                  // for "also edited by"
    val files: List<String>,            // relative to the working folder, unique, in first-edit order
    @Serializable(with = EditGaps::class)
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

/** A record's gaps by their serial names. A name this build does not know, a later build's gap, is dropped. */
private object EditGaps : KSerializer<Set<EditGap>> {
    private val names = ListSerializer(String.serializer())
    private val gaps = EditGap.serializer().descriptor
    private val byName = EditGap.entries.associateBy { gaps.getElementName(it.ordinal) }

    override val descriptor: SerialDescriptor = names.descriptor
    override fun serialize(encoder: Encoder, value: Set<EditGap>) = names.serialize(encoder, value.map { gaps.getElementName(it.ordinal) })
    override fun deserialize(decoder: Decoder): Set<EditGap> = names.deserialize(decoder).mapNotNullTo(LinkedHashSet(), byName::get)
}

/**
 * One account's thread edit records, under `<providerRoot>/<accountId>/workspace/edits/<projectId>/<threadId>.json`
 * in private folders. [thread] opens a working turn's record and returns the [EditListener] its runtime reports to.
 *
 * One writer thread does all the work: it resolves reported paths, keeps the records in memory, and writes each one
 * whole through [write] (`writeDurably`), so runtime threads never wait on the disk and nothing it does throws into
 * them. A record is written when it is opened, and when its files or gaps change once the edits reported together
 * are applied (a multi-file change is one write). A failed write is logged and the record stays pending, so the next
 * flush writes it whole again: after any later task, and at [close]. [limits] gives the account's history limits, read
 * at each opening and each new file; until profile sync exists they are the defaults.
 *
 * A record a later build wrote (its `version` above 1) is never written over or removed: its thread records nothing
 * here. [readFile] is how every record file is read.
 */
internal class ThreadEditStore(
    providerRoot: Path,
    accountId: String,
    private val limits: () -> WorkspaceLimits = { WorkspaceLimits.DEFAULTS },
    private val now: () -> Long = System::currentTimeMillis,
    private val write: (Path, ByteArray) -> Unit = ::writeDurably,
    private val readFile: (Path, Long) -> ByteArray = ::readBounded,
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
    /** Records changed since they were last written, or whose last write failed. Touched only on [writer]. */
    private val pending = LinkedHashSet<Entry>()
    /** Threads whose record a later build wrote, each logged once. Touched only on [writer]. */
    private val newer = HashSet<Key>()

    /**
     * Before a working turn in [threadId]: create its record, or refresh the one it has (its [title] and `updatedAt`),
     * then trim [projectId] to the threads-per-project limit, oldest `updatedAt` first. A new record for a thread with
     * [earlierReplies] gets [EditGap.EARLIER_REPLIES]. Edits are resolved against the working [folder]. Throws only for
     * ids that are not record ids.
     */
    fun thread(projectId: String, threadId: String, title: String, earlierReplies: Boolean, folder: Path): EditListener {
        require(SharedRules.isRecordId(projectId) && SharedRules.isRecordId(threadId)) { "Edit records are keyed by record ids" }
        val key = Key(projectId, threadId)
        later { open(key, title.shortened(CompanionLimits.THREAD_TITLE), earlierReplies) }
        return EditRecorder(key, folder)
    }

    /** [threadId]'s record as last written, or null: none, a later build's, or one that cannot be read (larger than the guard, or damaged). */
    fun read(projectId: String, threadId: String): ThreadEdits? {
        require(SharedRules.isRecordId(projectId) && SharedRules.isRecordId(threadId)) { "Edit records are keyed by record ids" }
        return (stored(Key(projectId, threadId)) as? Stored.Current)?.record
    }

    /** Finish the edits already queued, write every record still pending (briefly), then take no more. */
    override fun close() {
        // One more flush after everything queued, so a record whose last write failed is tried again.
        later {}
        writer.shutdown()
        if (!writer.awaitTermination(CLOSE_SECONDS, TimeUnit.SECONDS)) log(LogLevel.Warn, TAG, "Thread edit records were still being written at close")
    }

    /**
     * One working turn's listener. What its runtime reports is queued for the writer, which keeps a path only inside
     * the working folder, by [WorkPaths.inside].
     */
    private inner class EditRecorder(private val key: Key, folder: Path) : EditListener {
        /** Resolved on the writer, at the first edit. */
        private val paths by lazy { WorkPaths(folder) }

        // The folder itself is no file.
        override fun edited(path: Path?) = later { add(key, path?.let { paths.inside(it)?.takeIf { file -> file != "." } ?: return@later }) }
    }

    // ---- On the writer only ----------------------------------------------------------------------

    private fun open(key: Key, title: String, earlierReplies: Boolean) {
        val at = now()
        val entry = entry(key) ?: run {
            if (key in newer) return
            Entry(key, title, emptyList(), if (earlierReplies) setOf(EditGap.EARLIER_REPLIES) else emptySet(), at).also(::remember)
        }
        entry.title = title
        entry.updatedAt = at
        save(entry)
        trim(key)
    }

    /** One reported edit: [file] relative to the folder, or null for an edit without a file. */
    private fun add(key: Key, file: String?) {
        // No record: the thread's was trimmed while it ran, so it starts again at its next turn; or a later build wrote it.
        val entry = entry(key) ?: return
        val changed = when {
            file == null -> entry.gaps.add(EditGap.UNNAMED_EDIT)
            file in entry.files -> false
            entry.files.size >= limits().threadFiles || entry.bytes + cost(file) > FILES_BUDGET -> entry.gaps.add(EditGap.FILE_LIMIT)
            else -> { entry.files += file; entry.bytes += cost(file); true }
        }
        if (!changed) return
        entry.updatedAt = now()
        pending += entry
    }

    /** Write [entry] whole. A failure keeps it pending, so the next flush writes it again. */
    private fun save(entry: Entry) {
        try {
            val bytes = editsJson.encodeToString(ThreadEdits.serializer(), entry.record()).toByteArray()
            privateFolder(workspace); privateFolder(base)
            write(privateFolder(base.resolve(entry.key.projectId)).resolve("${entry.key.threadId}.json"), bytes)
            pending -= entry
        } catch (error: Exception) {
            pending += entry
            log(LogLevel.Warn, TAG, "A thread's edit record could not be written; the next flush writes it again", error)
        }
    }

    /** Delete [keep]'s project's oldest records, by `updatedAt`, beyond the threads-per-project limit. [keep] stays. */
    private fun trim(keep: Key) {
        val limit = limits().projectThreads
        val threads = recordIds(base.resolve(keep.projectId)) + keep.threadId
        if (threads.size <= limit) return
        val updated = (threads - keep.threadId).mapNotNull { threadId -> updatedAt(Key(keep.projectId, threadId))?.let { threadId to it } }
        val oldest = updated.sortedWith(compareBy({ it.second }, { it.first })).take(threads.size - limit).map { it.first }
        for (threadId in oldest) {
            val key = Key(keep.projectId, threadId)
            entries.remove(key)?.let(pending::remove)
            runCatching { Files.deleteIfExists(file(key)) }.onFailure { log(LogLevel.Warn, TAG, "An old thread edit record could not be removed", it) }
        }
    }

    /**
     * When [key]'s record last changed, read once for a trim: [Long.MIN_VALUE] for one that cannot be read, so it goes
     * first, and null for a later build's, which stays.
     */
    private fun updatedAt(key: Key): Long? = entries[key]?.updatedAt ?: when (val stored = if (key in newer) Stored.Newer else stored(key)) {
        is Stored.Current -> stored.record.updatedAt
        Stored.Newer -> null
        null -> Long.MIN_VALUE
    }

    /** [key]'s record: in memory, or read back from disk. Null when it has none, or when a later build wrote it ([newer]). */
    private fun entry(key: Key): Entry? {
        entries.remove(key)?.let { entries[key] = it; return it }
        if (key in newer) return null
        return when (val stored = stored(key)) {
            is Stored.Current -> stored.record.let { Entry(key, it.title, it.files, it.gaps, it.updatedAt) }.also(::remember)
            Stored.Newer -> {
                newer += key
                log(LogLevel.Warn, TAG, "A thread's edit record is from a newer Hovel; this one records nothing for that thread")
                null
            }
            null -> null
        }
    }

    /** Keep [entry] in memory, dropping the least recently used records that are safely on disk. */
    private fun remember(entry: Entry) {
        entries[entry.key] = entry
        val excess = entries.size - ENTRY_MEMORY
        if (excess > 0) entries.entries.filter { it.value !in pending }.take(excess).forEach { entries.remove(it.key) }
    }

    // ---- Anywhere ----------------------------------------------------------------------------------

    /** Run [task] on the writer, and write what is pending once nothing else waits; once closed, nothing runs. Never throws. */
    private fun later(task: () -> Unit) {
        try {
            writer.execute {
                try { task() } catch (error: Exception) { log(LogLevel.Warn, TAG, "A thread edit could not be recorded", error) }
                if (queue.isEmpty()) pending.toList().forEach(::save)
            }
        } catch (_: RejectedExecutionException) { /* Closed: the record keeps what it has. */ }
    }

    /**
     * What [key]'s file holds: a record this build keeps, a later build's, or null for none or one that cannot be read.
     * The version is read before the rest, so a later build's record is known even when its shape is not.
     */
    private fun stored(key: Key): Stored? {
        val file = file(key)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return null
        return try {
            val json = editsJson.parseToJsonElement(readFile(file, WorkspaceGuards.EDIT_RECORD_BYTES.toLong()).decodeToString())
            val version = ((json as? JsonObject)?.get("version") as? JsonPrimitive)?.longOrNull
            if (version != null && version > 1) Stored.Newer
            else editsJson.decodeFromJsonElement(ThreadEdits.serializer(), json).takeIf { it.version == 1 && it.threadId == key.threadId }?.let(Stored::Current)
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

    /** A record file as this build reads it. */
    private sealed interface Stored {
        /** A record this build reads and writes. */
        data class Current(val record: ThreadEdits) : Stored
        /** A later build's record: never written over or removed here. */
        data object Newer : Stored
    }

    /** A record as the writer keeps it. */
    private class Entry(val key: Key, var title: String, files: List<String>, gaps: Set<EditGap>, var updatedAt: Long) {
        val files = LinkedHashSet(files)
        val gaps: MutableSet<EditGap> = gaps.toMutableSet()
        var bytes = files.sumOf(::cost)

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

        /** Records from a later build may hold keys this one does not know. */
        val editsJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /** One file's bytes in the record's list: its JSON string and a separator. */
        fun cost(file: String): Int = editsJson.encodeToString(String.serializer(), file).toByteArray().size + 1
    }
}
