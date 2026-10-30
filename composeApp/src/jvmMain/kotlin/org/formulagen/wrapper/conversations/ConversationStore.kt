package org.formulagen.wrapper.conversations

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.ActivityStep
import org.formulagen.wrapper.bridge.BrainArtifact
import org.formulagen.wrapper.bridge.BrainAttemptOutcome
import org.formulagen.wrapper.bridge.BrainAttemptState
import org.formulagen.wrapper.bridge.BrainLimits
import org.formulagen.wrapper.bridge.BrainRoleSnapshot
import org.formulagen.wrapper.bridge.BrainSource
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.bridge.trimEcma
import org.formulagen.wrapper.brain.BrainRecord
import org.formulagen.wrapper.storage.Artifacts
import org.formulagen.wrapper.storage.privateFolder
import org.formulagen.wrapper.storage.readBounded
import org.formulagen.wrapper.storage.sha256
import org.formulagen.wrapper.storage.writeDurably
import org.formulagen.wrapper.chat.NativeConversation

/**
 * What one conversation keeps on this Mac. The server holds everything the person sees; this holds
 * what only the Mac may: the provider conversations (the coordinator's in [brain], each thread's in
 * [threadContexts]), the workflow run machine ([brain]'s run), and each thread as the Mac last stored
 * it, so its next upsert carries everything.
 */
@Serializable
internal data class ConversationRecord(
    val version: Int = 1,
    /** The run machine, its waiting reason, and the coordinator's native conversations by `roleId:runtimeId`. */
    val brain: BrainRecord = BrainRecord(),
    /** The message that last drove [brain]'s run: the person's retry of it resumes the run instead of starting another. */
    val runMessageId: String? = null,
    /** Each thread's specialist conversation. Retries, returns and forwarded replies of one task share it. */
    val threadContexts: Map<String, NativeConversation> = emptyMap(),
    /** The thread each attempt of the run belongs to. */
    val attemptThreads: Map<String, String> = emptyMap(),
    /** Each thread as this Mac last stored it. */
    val threads: Map<String, ThreadState> = emptyMap(),
    /**
     * Checkpoints made and not yet stored, in order: a checkpoint that ran out of retries waits here, and is sent
     * at the start of the conversation's next run and before a message settles, under the claim of the time.
     */
    val outbox: List<MessageCheckpoint> = emptyList(),
)

/** A thread's visible state, as its next `thread.upserted` sends it. */
@Serializable
internal data class ThreadState(
    val title: String,
    val parentMessageId: String,
    val revision: Long,
    val role: BrainRoleSnapshot,
    val stepId: String?,
    val state: BrainAttemptState,
    val outcome: BrainAttemptOutcome? = null,
    val activity: List<ActivityStep> = emptyList(),
    val outputs: List<ArtifactRef> = emptyList(),
) {
    /** This thread as the contract's `conversationThread`. */
    fun wire(conversationId: String, id: String) =
        ConversationThread(activity, conversationId, id, outcome, outputs, parentMessageId, revision, role, state, stepId, title)
}

/** Where a claimed message is on its way from the claim to its settle. */
@Serializable
internal enum class ClaimPhase {
    /** The claim id is journaled; the server may or may not have committed the claim. Claim again with the same id. */
    @SerialName("claiming") CLAIMING,
    /** The server claimed [ClaimEntry.messageId] for it; nothing launched. Claim again with the same id. */
    @SerialName("claimed") CLAIMED,
    /** A turn may have started: a crash from here on settles `reconciliation-required` and never runs it again. */
    @SerialName("launching") LAUNCHING,
    /** The outcome is decided; only its settle remains to deliver. */
    @SerialName("settling") SETTLING;

    /** Claimed or being claimed, and nothing launched: the claim is made again with its id and may still run. */
    val unrun: Boolean get() = this == CLAIMING || this == CLAIMED
}

/** One claim's journal record: the conversation, the claimed message and its revision, and the outcome once decided. */
@Serializable
internal data class ClaimEntry(
    val claimId: String,
    val conversationId: String,
    val phase: ClaimPhase,
    val messageId: String? = null,
    val revision: Long? = null,
    val outcome: MessageState? = null,
)

/**
 * The account's conversations on this Mac, at `<root>/<accountId>/conversations/<id>/`: each one's
 * [ConversationRecord], its claim journal, its artifacts (outputs, and inputs whose bytes stay
 * here), and the messages to stop as soon as they are claimed ([stopOnClaim]). Folders and files are
 * private and every record is replaced atomically; reading never creates anything. [open] takes the account's runner lock, so only one Hovel runs this account's conversations,
 * and loads the journal into memory: [pendingClaims] and [claims] answer from it, and every change is
 * written through.
 */
internal class ConversationStore(root: Path, val accountId: String) : AutoCloseable {
    private val base: Path
    private var lock: Pair<FileChannel, FileLock>? = null
    private val gates = ConcurrentHashMap<String, Any>()
    private val pending = ConcurrentHashMap<String, ClaimEntry>()

    init {
        require(SharedRules.isRecordId(accountId)) { "Conversations are keyed by an account id" }
        base = root.resolve(accountId).resolve("conversations")
    }

    /** Takes the runner lock and loads the journal; fails when another Hovel holds the lock. */
    @Synchronized fun open(): ConversationStore = apply {
        if (lock != null) return@apply
        privateFolder(base)
        val channel = FileChannel.open(base.resolve(".runner.lock"), CREATE, WRITE)
        val held = try { channel.tryLock() } catch (error: Exception) { channel.close(); throw error }
        if (held == null) { channel.close(); error("Another Hovel runs this account's conversations") }
        lock = channel to held
        loadJournal()
        pruneStopMarks()
    }

    @Synchronized override fun close() {
        lock?.let { (channel, held) -> try { held.release() } finally { channel.close() } }
        lock = null
    }

    fun read(conversationId: String): ConversationRecord = gated(conversationId) {
        val file = folder(conversationId).resolve(RECORD)
        if (!Files.exists(file)) ConversationRecord()
        else brainJson.decodeFromString(ConversationRecord.serializer(), readBounded(file, RECORD_BYTES).decodeToString())
            .also { require(it.version == 1) { "Unknown conversation record version" } }
    }

    fun write(conversationId: String, record: ConversationRecord) = gated(conversationId) {
        val bytes = brainJson.encodeToString(ConversationRecord.serializer(), record).toByteArray()
        require(bytes.size <= RECORD_BYTES) { "Conversation storage limit reached" }
        writeDurably(privateFolder(folder(conversationId)).resolve(RECORD), bytes)
    }

    /** Every claim journaled and not yet settled, in every conversation. */
    fun pendingClaims(): List<ClaimEntry> = pending.values.toList()

    /** [conversationId]'s pending claims. */
    fun claims(conversationId: String): List<ClaimEntry> = pending.values.filter { it.conversationId == conversationId }

    /** Journal [entry], replacing its earlier record. */
    fun journal(entry: ClaimEntry): ClaimEntry = gated(entry.conversationId) {
        require(SharedRules.isRecordId(entry.claimId))
        val file = privateFolder(folder(entry.conversationId).resolve(JOURNAL)).resolve("${entry.claimId}.json")
        writeDurably(file, brainJson.encodeToString(ClaimEntry.serializer(), entry).toByteArray())
        pending[entry.claimId] = entry
        entry
    }

    /**
     * Stop [messageId] as soon as this Mac claims it: a heard voice turn given up on before its claim landed. Durable,
     * so the turn never runs, even when Hovel restarts before the claim. The project runs only on this Mac, so this
     * Mac's own record is the whole rule.
     */
    fun stopOnClaim(conversationId: String, messageId: String) = gated(conversationId) {
        require(SharedRules.isRecordId(messageId))
        writeDurably(privateFolder(folder(conversationId).resolve(STOPS)).resolve("$messageId.json"), STOP_MARK)
    }

    /** Whether [messageId] is to stop as soon as it is claimed. */
    fun stopsOnClaim(conversationId: String, messageId: String): Boolean = gated(conversationId) {
        require(SharedRules.isRecordId(messageId))
        Files.exists(folder(conversationId).resolve(STOPS).resolve("$messageId.json"), NOFOLLOW_LINKS)
    }

    /** [messageId]'s stop is journaled as its outcome: the mark has done its work. */
    fun clearStopOnClaim(conversationId: String, messageId: String) = gated(conversationId) {
        require(SharedRules.isRecordId(messageId))
        Files.deleteIfExists(folder(conversationId).resolve(STOPS).resolve("$messageId.json"))
        Unit
    }

    /** The claim is settled or never happened: forget it. */
    fun forget(entry: ClaimEntry) = gated(entry.conversationId) {
        require(SharedRules.isRecordId(entry.claimId))
        Files.deleteIfExists(folder(entry.conversationId).resolve(JOURNAL).resolve("${entry.claimId}.json"))
        pending.remove(entry.claimId)
        Unit
    }

    /**
     * Store [bytes] as an immutable artifact, durable before its reference is used. [id] keeps an
     * id the server already gave (an input); otherwise a new one is made.
     */
    fun artifact(conversationId: String, title: String, source: BrainSource, bytes: ByteArray, mediaType: String,
        id: String = UUID.randomUUID().toString()): BrainArtifact = gated(conversationId) {
        require(bytes.size <= Artifacts.MAX_BYTES && SharedRules.isRecordId(id))
        writeDurably(privateFolder(privateFolder(folder(conversationId)).resolve(ARTIFACTS)).resolve(id), bytes)
        val label = title.trimEcma().take(BrainLimits.LABEL).trimEcma().ifEmpty { "Attached file" }
        val preview = if (mediaType == Artifacts.BINARY) "Attached file" else bytes.toString(Charsets.UTF_8).take(BrainLimits.PREVIEW)
        BrainArtifact(id, label, sha256(bytes), source, mediaType, preview)
    }

    /** An artifact's bytes, checked against its digest. */
    fun bytes(conversationId: String, artifact: BrainArtifact): ByteArray = gated(conversationId) {
        require(SharedRules.isRecordId(artifact.id))
        readBounded(folder(conversationId).resolve(ARTIFACTS).resolve(artifact.id), Artifacts.MAX_BYTES.toLong())
            .also { require(sha256(it) == artifact.digest) { "Artifact changed on disk" } }
    }

    /** Where a working specialist may open [artifact] itself. */
    fun artifactPath(conversationId: String, artifact: BrainArtifact): Path {
        bytes(conversationId, artifact)
        return folder(conversationId).resolve(ARTIFACTS).resolve(artifact.id)
    }

    /** The journal as it is on disk. An entry that cannot be read is skipped, and logged once; it stays on disk for a person to inspect. */
    private fun loadJournal() {
        pending.clear()
        if (!Files.isDirectory(base, NOFOLLOW_LINKS)) return
        val folders = Files.list(base).use { it.toList() }
            .filter { Files.isDirectory(it, NOFOLLOW_LINKS) && SharedRules.isRecordId(it.fileName.toString()) }
        for (folder in folders) {
            val journal = folder.resolve(JOURNAL)
            if (!Files.isDirectory(journal, NOFOLLOW_LINKS)) continue
            for (file in Files.list(journal).use { it.toList() }.filter { it.fileName.toString().endsWith(".json") }) {
                val entry = runCatching {
                    brainJson.decodeFromString(ClaimEntry.serializer(), readBounded(file, ENTRY_BYTES).decodeToString()).also {
                        require(it.conversationId == folder.fileName.toString() && "${it.claimId}.json" == file.fileName.toString()) {
                            "Journal ownership differs"
                        }
                    }
                }.onFailure { log(LogLevel.Warn, "conversations", "Skipped an unreadable claim journal entry: $file", it) }.getOrNull()
                if (entry != null) pending[entry.claimId] = entry
            }
        }
    }

    /**
     * Drop stop marks older than a queued message lives ([CompanionLimits.QUEUED_MESSAGE_MS]): the account has expired
     * those messages, so no claim comes. A mark whose message has a claim journaled stays until that claim settles. One
     * that cannot be read or removed waits for the next launch.
     */
    private fun pruneStopMarks(now: Long = System.currentTimeMillis()) {
        if (!Files.isDirectory(base, NOFOLLOW_LINKS)) return
        val claimed = pending.values.mapNotNullTo(HashSet()) { it.messageId }
        val folders = Files.list(base).use { it.toList() }
            .filter { Files.isDirectory(it, NOFOLLOW_LINKS) && SharedRules.isRecordId(it.fileName.toString()) }
        for (folder in folders) {
            val marks = folder.resolve(STOPS)
            if (!Files.isDirectory(marks, NOFOLLOW_LINKS)) continue
            for (mark in Files.list(marks).use { it.toList() }) {
                if (mark.fileName.toString().removeSuffix(".json") in claimed) continue
                runCatching {
                    if (now - Files.getLastModifiedTime(mark, NOFOLLOW_LINKS).toMillis() > CompanionLimits.QUEUED_MESSAGE_MS) {
                        Files.deleteIfExists(mark)
                    }
                }.onFailure { log(LogLevel.Warn, "conversations", "A stale stop mark was kept: $mark", it) }
            }
        }
    }

    private fun folder(conversationId: String): Path {
        require(SharedRules.isRecordId(conversationId)) { "Invalid conversation" }
        return base.resolve(conversationId)
    }

    private fun <T> gated(conversationId: String, action: () -> T): T =
        synchronized(gates.computeIfAbsent(conversationId) { Any() }) { action() }

    companion object {
        private const val RECORD = "conversation.json"
        private const val JOURNAL = "journal"
        private const val ARTIFACTS = "artifacts"
        private const val STOPS = "stop-on-claim"
        private val STOP_MARK = "{}".toByteArray()
        private const val RECORD_BYTES = 16_000_000L
        private const val ENTRY_BYTES = 16_384L

        /**
         * Every provider conversation id [profile]'s conversations hold, coordinators and threads alike,
         * read from disk, so Grok's pruning keeps them. Ids of other runtimes are harmless there. Null
         * when any record cannot be read, and then nothing may be pruned.
         */
        fun bindingHandles(root: Path, profile: String, limit: Int = 4_096): Set<String>? {
            if (!SharedRules.isRecordId(profile)) return emptySet()
            val base = root.resolve(profile).resolve("conversations")
            if (Files.notExists(base, NOFOLLOW_LINKS)) return emptySet()
            return runCatching {
                val folders = Files.list(base).use { it.toList() }.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
                check(folders.size <= limit)
                folders.flatMapTo(HashSet()) { folder ->
                    val file = folder.resolve(RECORD)
                    if (!Files.exists(file)) return@flatMapTo emptyList()
                    val text = readBounded(file, RECORD_BYTES).decodeToString()
                    val record = brainJson.decodeFromString(ConversationRecord.serializer(), text)
                    (record.brain.coordinatorContexts.values + record.threadContexts.values).map { it.id }
                }
            }.getOrNull()
        }
    }
}
