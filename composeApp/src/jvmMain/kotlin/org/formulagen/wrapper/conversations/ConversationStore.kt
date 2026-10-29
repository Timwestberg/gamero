package org.formulagen.wrapper.conversations

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.ActivityStep
import org.formulagen.wrapper.bridge.BrainArtifact
import org.formulagen.wrapper.bridge.BrainAttemptOutcome
import org.formulagen.wrapper.bridge.BrainAttemptState
import org.formulagen.wrapper.bridge.BrainLimits
import org.formulagen.wrapper.bridge.BrainRoleSnapshot
import org.formulagen.wrapper.bridge.BrainSource
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.bridge.trimEcma
import org.formulagen.wrapper.brain.BrainRecord
import org.formulagen.wrapper.brain.BrainStore
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
    @SerialName("settling") SETTLING,
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
 * [ConversationRecord], its claim journal and its artifacts (outputs, and inputs whose bytes stay
 * here). Folders and files are private and every record is replaced atomically. [open] takes the
 * account's runner lock, so only one Hovel runs this account's conversations.
 */
internal class ConversationStore(root: Path, val accountId: String) : AutoCloseable {
    private val base: Path
    private var lock: Pair<FileChannel, FileLock>? = null
    private val gates = ConcurrentHashMap<String, Any>()

    init {
        require(SharedRules.isRecordId(accountId)) { "Conversations are keyed by an account id" }
        base = root.resolve(accountId).resolve("conversations")
    }

    /** Takes the runner lock; fails when another Hovel holds it. */
    @Synchronized fun open(): ConversationStore = apply {
        if (lock != null) return@apply
        privateFolder(base)
        val channel = FileChannel.open(base.resolve(".runner.lock"), CREATE, WRITE)
        val held = try { channel.tryLock() } catch (error: Exception) { channel.close(); throw error }
        if (held == null) { channel.close(); error("Another Hovel runs this account's conversations") }
        lock = channel to held
    }

    @Synchronized override fun close() {
        lock?.let { (channel, held) -> try { held.release() } finally { channel.close() } }
        lock = null
    }

    fun read(conversationId: String): ConversationRecord = locked(conversationId) { folder ->
        val file = folder.resolve(RECORD)
        if (!Files.exists(file)) ConversationRecord()
        else brainJson.decodeFromString(ConversationRecord.serializer(), readBounded(file, RECORD_BYTES).decodeToString())
            .also { require(it.version == 1) { "Unknown conversation record version" } }
    }

    fun write(conversationId: String, record: ConversationRecord) = locked(conversationId) { folder ->
        val bytes = brainJson.encodeToString(ConversationRecord.serializer(), record).toByteArray()
        require(bytes.size <= RECORD_BYTES) { "Conversation storage limit reached" }
        writeDurably(folder.resolve(RECORD), bytes)
    }

    /** Read, change and write [conversationId]'s record as one step. */
    fun update(conversationId: String, transform: (ConversationRecord) -> ConversationRecord): ConversationRecord =
        synchronized(gate(conversationId)) { transform(read(conversationId)).also { write(conversationId, it) } }

    /** Every claim journaled and not yet settled, in every conversation. */
    fun pendingClaims(): List<ClaimEntry> {
        if (!Files.isDirectory(base, NOFOLLOW_LINKS)) return emptyList()
        return Files.list(base).use { folders -> folders.toList() }
            .filter { Files.isDirectory(it, NOFOLLOW_LINKS) && SharedRules.isRecordId(it.fileName.toString()) }
            .flatMap { claims(it.fileName.toString()) }
    }

    /** [conversationId]'s pending claims. */
    fun claims(conversationId: String): List<ClaimEntry> = locked(conversationId) { folder ->
        val journal = folder.resolve(JOURNAL)
        if (!Files.isDirectory(journal, NOFOLLOW_LINKS)) return@locked emptyList()
        Files.list(journal).use { files -> files.toList() }.filter { it.fileName.toString().endsWith(".json") }.map { file ->
            brainJson.decodeFromString(ClaimEntry.serializer(), readBounded(file, ENTRY_BYTES).decodeToString()).also {
                require(it.conversationId == conversationId && "${it.claimId}.json" == file.fileName.toString()) { "Journal ownership differs" }
            }
        }
    }

    /** Journal [entry], replacing its earlier record. */
    fun journal(entry: ClaimEntry): ClaimEntry = locked(entry.conversationId) { folder ->
        require(SharedRules.isRecordId(entry.claimId))
        writeDurably(folder.resolve(JOURNAL).resolve("${entry.claimId}.json"), brainJson.encodeToString(ClaimEntry.serializer(), entry).toByteArray())
        entry
    }

    /** The claim is settled or never happened: forget it. */
    fun forget(entry: ClaimEntry) = locked(entry.conversationId) { folder ->
        require(SharedRules.isRecordId(entry.claimId))
        Files.deleteIfExists(folder.resolve(JOURNAL).resolve("${entry.claimId}.json"))
        Unit
    }

    /**
     * Store [bytes] as an immutable artifact, durable before its reference is used. [id] keeps an
     * id the server already gave (an input); otherwise a new one is made.
     */
    fun artifact(conversationId: String, title: String, source: BrainSource, bytes: ByteArray, mediaType: String,
        id: String = UUID.randomUUID().toString()): BrainArtifact = locked(conversationId) { folder ->
        require(bytes.size <= BrainStore.ARTIFACT_BYTES && SharedRules.isRecordId(id))
        writeDurably(privateFolder(folder.resolve(ARTIFACTS)).resolve(id), bytes)
        val label = title.trimEcma().take(BrainLimits.LABEL).trimEcma().ifEmpty { "Attached file" }
        val preview = if (mediaType == BrainStore.BINARY) "Attached file" else bytes.toString(Charsets.UTF_8).take(BrainLimits.PREVIEW)
        BrainArtifact(id, label, digest(bytes), source, mediaType, preview)
    }

    /** An artifact's bytes, checked against its digest. */
    fun bytes(conversationId: String, artifact: BrainArtifact): ByteArray = locked(conversationId) { folder ->
        require(SharedRules.isRecordId(artifact.id))
        readBounded(folder.resolve(ARTIFACTS).resolve(artifact.id), BrainStore.ARTIFACT_BYTES.toLong())
            .also { require(digest(it) == artifact.digest) { "Artifact changed on disk" } }
    }

    /** Where a working specialist may open [artifact] itself. */
    fun artifactPath(conversationId: String, artifact: BrainArtifact): Path {
        bytes(conversationId, artifact)
        return folder(conversationId).resolve(ARTIFACTS).resolve(artifact.id)
    }

    private fun folder(conversationId: String): Path {
        require(SharedRules.isRecordId(conversationId)) { "Invalid conversation" }
        return base.resolve(conversationId)
    }

    private fun gate(conversationId: String) = gates.computeIfAbsent(conversationId) { Any() }

    private fun <T> locked(conversationId: String, action: (Path) -> T): T =
        synchronized(gate(conversationId)) { action(privateFolder(folder(conversationId))) }

    companion object {
        private const val RECORD = "conversation.json"
        private const val JOURNAL = "journal"
        private const val ARTIFACTS = "artifacts"
        private const val RECORD_BYTES = 16_000_000L
        private const val ENTRY_BYTES = 16_384L

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
                    val record = brainJson.decodeFromString(ConversationRecord.serializer(), readBounded(file, RECORD_BYTES).decodeToString())
                    (record.brain.coordinatorContexts.values + record.threadContexts.values).map { it.id }
                }
            }.getOrNull()
        }
    }
}
