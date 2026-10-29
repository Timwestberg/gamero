package org.formulagen.wrapper.brain

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.NativeConversation

/** A committed save: replaying [id] with the same [digest] rebuilds its document at [revision] from the replayed request. */
@Serializable internal data class SaveReceipt(val id: String, val digest: String, val revision: Long)
@Serializable internal data class ConfigRecord(val version: Int = 1, val document: BrainDocument, val receipts: List<SaveReceipt>)

/**
 * What the controller does next with a run: [PLAN] asks the coordinator what to do with the request, [EXECUTE] runs the
 * queued attempt, [EVALUATE] has the coordinator grade its result, [ADVANCE] start or skip the next workflow step, and
 * [FINISH] answer once every step is done. [THREAD_REPLY] is a conversation's reply in a thread, which the coordinator
 * forwards, answers or turns into a new task ([THREAD_REPLY_DECISIONS]); it never belongs to a run.
 */
@Serializable
internal enum class BrainPhase {
    @SerialName("plan") PLAN,
    @SerialName("execute") EXECUTE,
    @SerialName("evaluate") EVALUATE,
    @SerialName("advance") ADVANCE,
    @SerialName("finish") FINISH,
    @SerialName("thread-reply") THREAD_REPLY,
}

/**
 * One request's run with the configuration pinned when it began. [nextStep] is the next workflow step; [invalidated]
 * attempts belong to a pass a return made stale. [contexts] are the specialists' native conversations in this run.
 */
@Serializable
internal data class RunRecord(
    val summary: BrainRun,
    val configuration: BrainConfiguration,
    val workspace: String?,
    val request: String,
    val inputs: List<String>,
    val attempts: List<BrainAttempt> = emptyList(),
    val artifacts: List<BrainArtifact> = emptyList(),
    val nextStep: Int = 0,
    val phase: BrainPhase = BrainPhase.PLAN,
    val task: String = "",
    val returnsAllowance: Int = BrainLimits.RETURNS,
    val dispatchesAllowance: Int = BrainLimits.DISPATCHES,
    val contexts: Map<String, NativeConversation> = emptyMap(),
    val invalidated: List<String> = emptyList(),
)

/** A companion's coordinator conversation, its current run and the request receipts that keep retries single. */
@Serializable
internal data class BrainRecord(
    val version: Int = 1,
    val messages: List<BrainEntry> = emptyList(),
    val run: RunRecord? = null,
    val waiting: BrainWaiting? = null,
    val receipts: Map<String, String> = emptyMap(),
    val staged: List<BrainArtifact> = emptyList(),
    val coordinatorContexts: Map<String, NativeConversation> = emptyMap(),
    val notice: String? = null,
)

/** Private atomic storage; corrupt or unknown records are never overwritten. */
internal class BrainStore(private val root: Path) {
    private fun directory(profile: String, companionId: String): Path {
        require(SharedRules.isProfileId(profile) && BrainRules.uuid(companionId))
        return root.resolve(profile).resolve("companion-brains").resolve(UUID.fromString(companionId).toString())
    }
    fun read(profile: String, companionId: String): BrainDocument? = locked(profile, companionId) { readConfig(it)?.document }
    /** Whether the companion has a saved brain, readable or not: a corrupt one still keeps the legacy chat closed. */
    fun converted(profile: String, companionId: String) = Files.exists(directory(profile, companionId).resolve(CONFIGURATION))
    private fun readConfig(directory: Path): ConfigRecord? {
        val file = directory.resolve(CONFIGURATION)
        if (!Files.exists(file)) return null
        require(Files.size(file) <= CONFIG_RECORD_BYTES)
        val json = brainJson.parseToJsonElement(Files.readString(file)).jsonObject
        return brainJson.decodeFromJsonElement<ConfigRecord>(withSlimReceipts(json)).also { record ->
            require(record.version == 1 && record.document.revision > 0 && BrainRules.configuration(record.document.configuration))
            require(record.receipts.size <= SAVE_RECEIPTS)
        }
    }
    /** Compare, commit and receipt are protected by one cross-window/process lock. */
    fun save(profile: String, companionId: String, revision: Long, mutationId: String, configuration: BrainConfiguration): BrainSaveResult =
        locked(profile, companionId) { directory ->
            require(BrainRules.configuration(configuration))
            val current = readConfig(directory)
            val digest = digest("$revision:${brainWire(configuration)}".toByteArray())
            val prior = current?.receipts?.find { it.id == mutationId }
            if (prior != null) {
                // The digest covers the expected revision and configuration, so the replayed request is the saved document.
                require(prior.digest == digest) { "Mutation ID reused with different content" }
                return@locked saved(BrainDocument(prior.revision, configuration))
            }
            if ((current?.document?.revision ?: 0) != revision) return@locked BrainSaveResult.Conflict(current?.document?.revision ?: 0)
            require(revision < BrainLimits.REVISION)
            val document = BrainDocument(revision + 1, configuration)
            val receipts = (current?.receipts.orEmpty() + SaveReceipt(mutationId, digest, document.revision)).takeLast(SAVE_RECEIPTS)
            val record = ConfigRecord(document = document, receipts = receipts)
            val content = brainJson.encodeToString(ConfigRecord.serializer(), record)
            require(content.toByteArray().size <= CONFIG_RECORD_BYTES)
            atomic(directory.resolve(CONFIGURATION), content)
            saved(document)
        }
    private fun saved(document: BrainDocument) = BrainSaveResult.Saved(document, emptyList())
    fun readRecord(profile: String, companionId: String): BrainRecord = locked(profile, companionId) { directory ->
        val file = directory.resolve("conversation.json")
        if (!Files.exists(file)) BrainRecord() else {
            require(Files.size(file) <= RECORD_BYTES)
            brainJson.decodeFromString<BrainRecord>(Files.readString(file)).also { record ->
                require(record.version == 1 && record.run?.let { BrainRules.configuration(it.configuration) } != false)
            }
        }
    }
    fun writeRecord(profile: String, companionId: String, record: BrainRecord) = locked(profile, companionId) { directory ->
        val content = brainJson.encodeToString(BrainRecord.serializer(), record)
        require(content.toByteArray().size <= RECORD_BYTES) { "Conversation storage limit reached" }
        atomic(directory.resolve("conversation.json"), content)
    }
    fun archive(profile: String, companionId: String, run: RunRecord) = locked(profile, companionId) { directory ->
        require(BrainRules.uuid(run.summary.id))
        atomic(directory.resolve("run-${run.summary.id}.json"), brainJson.encodeToString(RunRecord.serializer(), run))
    }
    fun archived(profile: String, companionId: String, runId: String): RunRecord? = locked(profile, companionId) { directory ->
        require(BrainRules.uuid(runId))
        val file = directory.resolve("run-$runId.json")
        if (!Files.exists(file)) return@locked null
        require(Files.size(file) <= RECORD_BYTES)
        brainJson.decodeFromString<RunRecord>(Files.readString(file))
    }
    /** An immutable blob is durable before its reference is published. */
    fun artifact(
        profile: String,
        companionId: String,
        title: String,
        source: BrainSource,
        bytes: ByteArray,
        mediaType: String,
    ): BrainArtifact = locked(profile, companionId) { directory ->
        require(bytes.size <= ARTIFACT_BYTES)
        val id = UUID.randomUUID().toString()
        val folder = directory.resolve("artifacts"); privateDirectory(folder)
        val file = folder.resolve(id)
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        FileChannel.open(file, WRITE).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        FileChannel.open(folder, READ).use { it.force(true) }
        val label = title.trimEcma().take(BrainLimits.LABEL).trimEcma().ifEmpty { "Attached file" }
        val preview = if (mediaType == BINARY) "Attached file" else bytes.toString(Charsets.UTF_8).take(BrainLimits.PREVIEW)
        BrainArtifact(id, label, digest(bytes), source, mediaType, preview)
    }
    fun bytes(profile: String, companionId: String, artifact: BrainArtifact): ByteArray {
        require(BrainRules.uuid(artifact.id))
        val file = directory(profile, companionId).resolve("artifacts").resolve(artifact.id)
        require(Files.size(file) <= ARTIFACT_BYTES)
        return Files.readAllBytes(file).also { require(digest(it) == artifact.digest) }
    }
    fun artifactPath(profile: String, companionId: String, artifact: BrainArtifact): Path {
        bytes(profile, companionId, artifact)
        return directory(profile, companionId).resolve("artifacts").resolve(artifact.id)
    }
    private fun <T> locked(profile: String, companionId: String, action: (Path) -> T): T {
        val directory = directory(profile, companionId)
        return synchronized(locks.computeIfAbsent(directory.toAbsolutePath()) { Any() }) {
            privateDirectory(directory)
            val lock = directory.resolve(".lock")
            FileChannel.open(lock, CREATE, WRITE).use { channel ->
                Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-------"))
                channel.lock().use { action(directory) }
            }
        }
    }
    companion object {
        /** The largest input file or output an artifact holds. */
        const val ARTIFACT_BYTES = 8 * 1024 * 1024
        /** The media type of an artifact that is not UTF-8 text. */
        const val BINARY = "application/octet-stream"
        private const val CONFIGURATION = "configuration.json"
        /** The most save receipts a configuration keeps. */
        private const val SAVE_RECEIPTS = 32
        /** The conversation record, and each archived run. */
        private const val RECORD_BYTES = 16_000_000
        // Earlier records kept a whole document in each of their thirty-two receipts; they must still load.
        private const val CONFIG_RECORD_BYTES = 8_000_000
        private val locks = ConcurrentHashMap<Path, Any>()
        /** Receipts once held the whole saved document; only its revision is kept. */
        private fun withSlimReceipts(record: JsonObject): JsonObject {
            val receipts = record["receipts"]?.jsonArray ?: return record
            return JsonObject(record + ("receipts" to JsonArray(receipts.map { receipt ->
                val fields = receipt.jsonObject
                val document = fields["document"]?.jsonObject ?: return@map fields
                JsonObject(fields - "document" + ("revision" to document.getValue("revision")))
            })))
        }
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun privateDirectory(path: Path) {
            Files.createDirectories(path)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }
        private fun atomic(file: Path, content: String) {
            privateDirectory(file.parent)
            val owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            val temp = Files.createTempFile(file.parent, ".brain-", ".tmp", owner)
            try {
                FileChannel.open(temp, WRITE).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(content.toByteArray())
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
                FileChannel.open(file.parent, READ).use { it.force(true) }
            } finally { Files.deleteIfExists(temp) }
        }
    }
}
