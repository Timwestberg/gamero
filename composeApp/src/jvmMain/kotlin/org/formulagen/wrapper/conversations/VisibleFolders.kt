package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
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
 */
internal class VisibleFolders(
    private val root: Path,
    private val ledgerRoot: Path,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = Instant::now,
) : CompanionFolders {
    /** A folder as it stands after [settle]. */
    private class Settled(val dir: Path, val name: String, val pinned: Boolean)

    @Synchronized
    override fun sync(names: FolderNames, outputs: List<RecordedOutput>, bytes: (ArtifactRef) -> ByteArray) {
        try {
            bring(names, outputs, bytes)
        } catch (error: Exception) {
            log(LogLevel.Warn, TAG, "A conversation's visible folder is behind; it is tried again at its next message", error)
        }
    }

    private fun bring(names: FolderNames, outputs: List<RecordedOutput>, bytes: (ArtifactRef) -> ByteArray) {
        require(SharedRules.isRecordId(names.accountId) && SharedRules.isRecordId(names.companionId) && SharedRules.isRecordId(names.conversationId))
        val ledger = read(names.accountId)
        val known = ledger.conversations[names.conversationId]
        val written = known?.outputs.orEmpty()
        val missing = outputs.filter { output -> written.none { it.artifactId == output.artifact.id } }.sortedBy { it.at }
        // A folder appears with its first file: a conversation that has produced nothing leaves nothing on disk.
        if (known == null && missing.isEmpty()) return

        val companionLast = ledger.companions[names.companionId]
        val companion = settle(
            parent = root,
            marker = FolderMarker(accountId = names.accountId, companionId = names.companionId),
            lastName = companionLast?.name, lastFrom = companionLast?.from, pinned = companionLast?.pinned == true,
            from = names.companion, wanted = CompanionFolderRules.safeName(names.companion, "Companion"),
        )
        // The day the conversation's first output was made, which for one older than the folders is not today.
        val startedOn = known?.startedOn ?: (missing.firstOrNull()?.at ?: now()).atZone(zone).toLocalDate().toString()
        val conversation = settle(
            parent = companion.dir.resolve(CompanionFolderRules.CONVERSATIONS),
            marker = FolderMarker(accountId = names.accountId, companionId = names.companionId, conversationId = names.conversationId),
            lastName = known?.name, lastFrom = known?.from, pinned = known?.pinned == true,
            from = names.conversation, wanted = CompanionFolderRules.conversationName(startedOn, names.conversation),
        )

        val (all, failure) = write(conversation.dir.resolve(CompanionFolderRules.OUTPUTS), written, missing, bytes)
        val folder = ConversationFolder(names.companionId, conversation.name, names.conversation, conversation.pinned, startedOn, all)
        replaceIfChanged(conversation.dir.resolve(CompanionFolderRules.INDEX), CompanionFolderRules.index(names, folder, zone).toByteArray())
        val next = ledger.copy(
            companions = ledger.companions + (names.companionId to CompanionFolder(companion.name, names.companion, companion.pinned)),
            conversations = ledger.conversations + (names.conversationId to folder),
        )
        if (next != ledger) writeDurably(ledgerFile(names.accountId), ledgerJson.encodeToString(FolderLedger.serializer(), next).toByteArray())
        failure?.let { throw it }
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

    private fun read(accountId: String): FolderLedger {
        val file = ledgerFile(accountId)
        if (!Files.exists(file)) return FolderLedger()
        return ledgerJson.decodeFromString(FolderLedger.serializer(), readBounded(file, MAX_LEDGER_BYTES).decodeToString()).also { ledger ->
            require(ledger.version == 1) { "Unknown companion folders ledger version" }
            require(ledger.companions.keys.all(SharedRules::isRecordId) && ledger.conversations.keys.all(SharedRules::isRecordId))
        }
    }
}
