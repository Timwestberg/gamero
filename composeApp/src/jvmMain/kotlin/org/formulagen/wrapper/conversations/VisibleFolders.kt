package org.formulagen.wrapper.conversations

import java.awt.Desktop
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.storage.Artifacts
import org.formulagen.wrapper.storage.privateFolder
import org.formulagen.wrapper.storage.readBounded
import org.formulagen.wrapper.storage.writeDurably

private const val TAG = "companion-folders"
private const val LEDGER = "companion-folders.json"
private const val MAX_LEDGER_BYTES = 8_000_000L
private const val MAX_MARKER_BYTES = 4_096L

/** The ledger is Hovel's own file: every field is written, and a field this build does not know means a newer one wrote it. */
private val ledgerJson = Json { encodeDefaults = true }

/** A marker is read from a folder the person can change, so a field this build does not know is passed over. */
private val markerJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * The companions' folders under [root], which the person opens in Finder: one per companion, and in it one per
 * conversation with its outputs as files and an index of who made them. This is the only code that writes there.
 *
 * It mirrors the conversation records and never reads its own files back: each output is written once, and what the
 * person then edits, moves or deletes is theirs. What it has written is kept per account in the ledger under
 * [ledgerRoot], beside the conversations. A ledger that cannot be read is never replaced: the mirror stops for that
 * account and says so in the log.
 *
 * The one thing it takes away is the folder of a conversation or companion the account deleted ([gone]), and only to
 * the Trash. The exception is `scratch/`, which is the agents' and not the person's: it is emptied when its run
 * completes, and swept of what is older than a week.
 */
internal class VisibleFolders(
    private val root: Path,
    private val ledgerRoot: Path,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Opens a folder in Finder. */
    private val reveal: (Path) -> Unit = { Desktop.getDesktop().open(it.toFile()) },
    /** Moves a folder to the Trash; false when the Mac could not. */
    private val trash: (Path) -> Boolean = { Desktop.getDesktop().moveToTrash(it.toFile()) },
    private val now: () -> Instant = Instant::now,
) : CompanionFolders {
    /** The conversations whose last sync could not write everything. Kept until the app quits: a sync clears it. */
    private val behind = ConcurrentHashMap.newKeySet<String>()

    /** A folder as it stands after [settle]. */
    private class Settled(val dir: Path, val name: String, val pinned: Boolean)

    @Synchronized
    override fun sync(names: FolderNames, outputs: List<RecordedOutput>, notes: List<TeamNote>, bytes: (ArtifactRef) -> ByteArray) {
        try {
            bring(names, outputs, notes, bytes)
            behind -= names.conversationId
        } catch (error: Exception) {
            behind += names.conversationId
            log(LogLevel.Warn, TAG, "A conversation's visible folder is behind; it is tried again at its next message", error)
        }
    }

    @Synchronized
    override fun state(accountId: String, conversationId: String): FolderState = try {
        when {
            read(accountId).conversations[conversationId] == null -> if (conversationId in behind) FolderState.BEHIND else FolderState.NONE
            conversationId in behind -> FolderState.BEHIND
            else -> FolderState.READY
        }
    } catch (_: Exception) {
        // The ledger cannot be read, so nothing is being written.
        FolderState.BEHIND
    }

    @Synchronized
    override fun show(accountId: String, conversationId: String): Boolean = try {
        val dir = conversationFolder(accountId, read(accountId), conversationId)
        if (dir != null) reveal(dir)
        dir != null
    } catch (error: Exception) {
        log(LogLevel.Warn, TAG, "A conversation's visible folder could not be shown", error)
        false
    }

    @Synchronized
    override fun kept(accountId: String): FolderIds = try {
        read(accountId).let { FolderIds(it.companions.keys, it.conversations.keys) }
    } catch (_: Exception) {
        FolderIds()
    }

    @Synchronized
    override fun gone(accountId: String, gone: FolderIds) {
        if (gone.isEmpty) return
        try {
            var ledger = read(accountId)
            // A conversation first: when its companion goes too, the companion's folder then takes what is left.
            for (id in gone.conversationIds) {
                val folder = ledger.conversations[id] ?: continue
                val dir = conversationFolder(accountId, ledger, id)
                if (dir != null && !discard(dir)) continue
                ledger = ledger.copy(conversations = ledger.conversations - id)
                behind -= id
                log(LogLevel.Info, TAG, "A deleted conversation's folder \"${folder.name}\" " + if (dir == null) "was already gone" else "was moved to the Trash")
            }
            for (id in gone.companionIds) {
                val folder = ledger.companions[id] ?: continue
                val dir = find(root, FolderMarker(accountId = accountId, companionId = id), folder.name)
                if (dir != null && !discard(dir)) continue
                val theirs = ledger.conversations.filterValues { it.companionId == id }.keys
                ledger = ledger.copy(companions = ledger.companions - id, conversations = ledger.conversations - theirs)
                behind -= theirs
                log(LogLevel.Info, TAG, "A deleted companion's folder \"${folder.name}\" " + if (dir == null) "was already gone" else "was moved to the Trash")
            }
            writeLedger(accountId, ledger)
        } catch (error: Exception) {
            log(LogLevel.Warn, TAG, "The folders of deleted conversations could not be moved to the Trash; it is tried again at the next connect", error)
        }
    }

    /** Moves [dir] to the Trash. False, and logged, when the Mac could not: the folder stays and is tried again. */
    private fun discard(dir: Path): Boolean =
        runCatching { trash(dir) }.getOrDefault(false).also { moved ->
            if (!moved) log(LogLevel.Warn, TAG, "A folder could not be moved to the Trash and is left where it is")
        }

    /** [conversationId]'s folder where it is now, found by its marker; null when it has none or the person removed it. */
    private fun conversationFolder(accountId: String, ledger: FolderLedger, conversationId: String): Path? {
        val conversation = ledger.conversations[conversationId] ?: return null
        val companion = find(root, FolderMarker(accountId = accountId, companionId = conversation.companionId), ledger.companions[conversation.companionId]?.name)
            ?: return null
        return find(companion.resolve(CompanionFolderRules.CONVERSATIONS),
            FolderMarker(accountId = accountId, companionId = conversation.companionId, conversationId = conversationId), conversation.name)
    }

    private fun bring(names: FolderNames, outputs: List<RecordedOutput>, notes: List<TeamNote>, bytes: (ArtifactRef) -> ByteArray) {
        require(SharedRules.isRecordId(names.accountId) && SharedRules.isRecordId(names.companionId) && SharedRules.isRecordId(names.conversationId))
        val ledger = read(names.accountId)
        val known = ledger.conversations[names.conversationId]
        val written = known?.outputs.orEmpty()
        val missing = outputs.filter { output -> written.none { it.artifactId == output.artifact.id } }.sortedBy { it.at }
        // A folder appears with its first file: a conversation that has produced nothing leaves nothing on disk.
        if (known == null && missing.isEmpty()) return

        // The day the conversation's first output was made, which for one older than the folders is not today.
        val (companion, conversation, startedOn) = home(names, ledger, missing.firstOrNull()?.at)

        val (all, failure) = write(conversation.dir.resolve(CompanionFolderRules.OUTPUTS), written, missing, bytes)
        val folder = ConversationFolder(names.companionId, conversation.name, names.conversation, conversation.pinned, startedOn, all)
        replaceIfChanged(conversation.dir.resolve(CompanionFolderRules.INDEX), CompanionFolderRules.index(names, folder, zone).toByteArray())
        // The notes file is Hovel's copy of the log: with no notes left, as after a memory cleanup, the copy goes too.
        val notesFile = conversation.dir.resolve(CompanionFolderRules.NOTES)
        if (notes.isEmpty()) Files.deleteIfExists(notesFile)
        else replaceIfChanged(notesFile, TeamNotes.markdown(names.conversation, notes, zone).toByteArray())
        val next = ledger.copy(
            companions = ledger.companions + (names.companionId to CompanionFolder(companion.name, names.companion, companion.pinned)),
            conversations = ledger.conversations + (names.conversationId to folder),
        )
        if (next != ledger) writeLedger(names.accountId, next)
        failure?.let { throw it }
    }

    /** A conversation's folder and its companion's, as they stand after [home], and the day the conversation's folder is dated. */
    private data class Home(val companion: Settled, val conversation: Settled, val startedOn: String)

    /**
     * The companion's folder and the conversation's in it, made or renamed as [names] says. A conversation with no
     * folder yet is dated [first], the time of its first output, or today when it has none.
     */
    private fun home(names: FolderNames, ledger: FolderLedger, first: Instant?): Home {
        val known = ledger.conversations[names.conversationId]
        val companionLast = ledger.companions[names.companionId]
        val companion = settle(
            parent = root,
            marker = FolderMarker(accountId = names.accountId, companionId = names.companionId),
            lastName = companionLast?.name, lastFrom = companionLast?.from, pinned = companionLast?.pinned == true,
            from = names.companion, wanted = CompanionFolderRules.safeName(names.companion, "Companion"),
        )
        val startedOn = known?.startedOn ?: (first ?: now()).atZone(zone).toLocalDate().toString()
        val conversation = settle(
            parent = companion.dir.resolve(CompanionFolderRules.CONVERSATIONS),
            marker = FolderMarker(accountId = names.accountId, companionId = names.companionId, conversationId = names.conversationId),
            lastName = known?.name, lastFrom = known?.from, pinned = known?.pinned == true,
            from = names.conversation, wanted = CompanionFolderRules.conversationName(startedOn, names.conversation),
        )
        return Home(companion, conversation, startedOn)
    }

    @Synchronized
    override fun scratch(names: FolderNames): Path? = try {
        require(SharedRules.isRecordId(names.accountId) && SharedRules.isRecordId(names.companionId) && SharedRules.isRecordId(names.conversationId))
        val ledger = read(names.accountId)
        val (companion, conversation, startedOn) = home(names, ledger, null)
        val dir = conversation.dir.resolve(CompanionFolderRules.SCRATCH)
        // A link there is not Hovel's folder: an agent is never given write access through one.
        check(!Files.exists(dir, NOFOLLOW_LINKS) || Files.isDirectory(dir, NOFOLLOW_LINKS)) { "The scratch folder is not a folder" }
        privateFolder(dir)
        val folder = ConversationFolder(names.companionId, conversation.name, names.conversation, conversation.pinned, startedOn,
            ledger.conversations[names.conversationId]?.outputs.orEmpty())
        val next = ledger.copy(
            companions = ledger.companions + (names.companionId to CompanionFolder(companion.name, names.companion, companion.pinned)),
            conversations = ledger.conversations + (names.conversationId to folder),
        )
        if (next != ledger) writeLedger(names.accountId, next)
        dir.toAbsolutePath().normalize()
    } catch (error: Exception) {
        log(LogLevel.Warn, TAG, "A conversation's scratch folder could not be made; its working turn goes on without one", error)
        null
    }

    @Synchronized
    override fun emptyScratch(accountId: String, conversationId: String) {
        try {
            scratchOf(accountId, read(accountId), conversationId)?.let { prune(it, cutoff = null) }
        } catch (error: Exception) {
            log(LogLevel.Warn, TAG, "A conversation's scratch folder could not be emptied; the sweep at the next connect tries again", error)
        }
    }

    @Synchronized
    override fun sweepScratch(accountId: String) {
        try {
            val ledger = read(accountId)
            val cutoff = now().minus(ScratchLimits.KEPT_DAYS, ChronoUnit.DAYS)
            for (id in ledger.conversations.keys) {
                runCatching { scratchOf(accountId, ledger, id)?.let { prune(it, cutoff) } }
                    .onFailure { log(LogLevel.Warn, TAG, "A scratch folder could not be swept", it) }
            }
        } catch (error: Exception) {
            log(LogLevel.Warn, TAG, "The scratch folders could not be swept", error)
        }
    }

    /** [conversationId]'s scratch folder, when it has one that is a real folder. */
    private fun scratchOf(accountId: String, ledger: FolderLedger, conversationId: String): Path? =
        conversationFolder(accountId, ledger, conversationId)?.resolve(CompanionFolderRules.SCRATCH)?.takeIf { Files.isDirectory(it, NOFOLLOW_LINKS) }

    /**
     * Deletes what is in [dir], and with a [cutoff] only what was last changed before it. A folder inside goes when
     * nothing is left in it; [dir] itself stays. A link is deleted as a link, never followed.
     */
    private fun prune(dir: Path, cutoff: Instant?) {
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cutoff == null || attrs.lastModifiedTime().toInstant().isBefore(cutoff)) Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(folder: Path, failure: java.io.IOException?): FileVisitResult {
                if (folder != dir && Files.list(folder).use { !it.findAny().isPresent }) Files.deleteIfExists(folder)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Writes each of [missing] into [dir] after [written], oldest first, and answers the whole list with what stopped
     * it, if anything did. It stops at the first output it cannot write, so the numbers stay in the order made.
     */
    private fun write(dir: Path, written: List<MirroredOutput>, missing: List<RecordedOutput>, bytes: (ArtifactRef) -> ByteArray): Pair<List<MirroredOutput>, Exception?> {
        val all = written.toMutableList()
        for (output in missing) {
            try {
                val data = bytes(output.artifact)
                val text = if (output.artifact.mediaType in Artifacts.TEXT) data.toString(Charsets.UTF_8) else null
                val name = CompanionFolderRules.outputName(all.size + 1, output.artifact.title, text)
                val extension = CompanionFolderRules.extension(output.artifact.mediaType)
                privateFolder(dir)
                val there = dir.resolve(name + extension)
                // A stop between the file and the ledger leaves the file with no entry: the same bytes under the same name are this output.
                val file = if (same(there, data)) name + extension else CompanionFolderRules.freeName(name, names(dir), extension).also { writeDurably(dir.resolve(it), data) }
                all += MirroredOutput(output.artifact.id, file, output.madeBy, output.runtimeId, output.modelId, output.at.toString())
            } catch (error: Exception) {
                return all to error
            }
        }
        return all to null
    }

    /**
     * The folder that [marker] names under [parent], named as it should be now. A folder is found by its marker, so
     * one the person renamed is still theirs, and keeps their name from then on. One renamed in Hovel ([from] is no
     * longer [lastFrom]) is renamed to [wanted]. With none, because it is new or the person moved or deleted it, a
     * fresh one is made.
     */
    private fun settle(parent: Path, marker: FolderMarker, lastName: String?, lastFrom: String?, pinned: Boolean, from: String, wanted: String): Settled {
        val found = find(parent, marker, lastName)
        if (found == null) {
            if (lastName != null) log(LogLevel.Info, TAG, "A visible folder was moved or deleted by hand; a new one is made beside it")
            val name = CompanionFolderRules.freeName(wanted, names(parent))
            val dir = privateFolder(privateFolder(parent).resolve(name))
            writeDurably(dir.resolve(CompanionFolderRules.MARKER), markerJson.encodeToString(FolderMarker.serializer(), marker).toByteArray())
            return Settled(dir, name, pinned = false)
        }
        val actual = found.fileName.toString()
        if (lastName != null && actual != lastName) return Settled(found, actual, pinned = true)
        if (pinned || lastFrom == null || lastFrom == from) return Settled(found, actual, pinned)
        val name = CompanionFolderRules.freeName(wanted, names(parent) - actual)
        if (name == actual) return Settled(found, actual, pinned = false)
        return Settled(Files.move(found, parent.resolve(name)), name, pinned = false)
    }

    /** The folder under [parent] whose marker is [marker]: the one called [hint] when that is it, else whichever has it. */
    private fun find(parent: Path, marker: FolderMarker, hint: String?): Path? {
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return null
        hint?.let { parent.resolve(it) }?.takeIf { marked(it, marker) }?.let { return it }
        return Files.list(parent).use { children -> children.filter { marked(it, marker) }.findFirst().orElse(null) }
    }

    private fun marked(dir: Path, marker: FolderMarker): Boolean {
        val file = dir.resolve(CompanionFolderRules.MARKER)
        if (!Files.isDirectory(dir, NOFOLLOW_LINKS) || !Files.isRegularFile(file, NOFOLLOW_LINKS)) return false
        return runCatching { markerJson.decodeFromString(FolderMarker.serializer(), readBounded(file, MAX_MARKER_BYTES).decodeToString()) }.getOrNull() == marker
    }

    private fun names(dir: Path): Set<String> =
        if (!Files.isDirectory(dir)) emptySet() else Files.list(dir).use { children -> children.map { it.fileName.toString() }.toList().toSet() }

    private fun same(file: Path, data: ByteArray): Boolean =
        Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) == data.size.toLong() && Files.readAllBytes(file).contentEquals(data)

    private fun replaceIfChanged(file: Path, data: ByteArray) {
        if (!same(file, data)) writeDurably(file, data)
    }

    private fun ledgerFile(accountId: String): Path = ledgerRoot.resolve(accountId).resolve(LEDGER)

    private fun writeLedger(accountId: String, ledger: FolderLedger) =
        writeDurably(ledgerFile(accountId), ledgerJson.encodeToString(FolderLedger.serializer(), ledger).toByteArray())

    private fun read(accountId: String): FolderLedger {
        val file = ledgerFile(accountId)
        if (!Files.exists(file)) return FolderLedger()
        return ledgerJson.decodeFromString(FolderLedger.serializer(), readBounded(file, MAX_LEDGER_BYTES).decodeToString()).also { ledger ->
            require(ledger.version == 1) { "Unknown companion folders ledger version" }
            require(ledger.companions.keys.all(SharedRules::isRecordId) && ledger.conversations.keys.all(SharedRules::isRecordId))
        }
    }
}
