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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.NativeConversation

@Serializable internal data class SaveReceipt(val id: String, val digest: String, val document: BrainDocument)
@Serializable internal data class ConfigRecord(val version: Int = 1, val document: BrainDocument, val receipts: List<SaveReceipt>)
@Serializable internal data class RunRecord(val summary: BrainRun, val configuration: BrainConfiguration, val workspace: String?, val request: String,
    val inputs: List<String>, val attempts: List<BrainAttempt> = emptyList(), val artifacts: List<BrainArtifact> = emptyList(), val nextStep: Int = 0,
    val phase: String = "plan", val task: String = "", val returnsAllowance: Int = BrainLimits.RETURNS, val dispatchesAllowance: Int = BrainLimits.DISPATCHES,
    val contexts: Map<String, NativeConversation> = emptyMap(), val invalidated: List<String> = emptyList())
@Serializable internal data class BrainRecord(val version: Int = 1, val messages: List<BrainEntry> = emptyList(), val run: RunRecord? = null,
    val waiting: BrainWaiting? = null, val receipts: Map<String, String> = emptyMap(), val staged: List<BrainArtifact> = emptyList(),
    val coordinatorContexts: Map<String, NativeConversation> = emptyMap(), val notice: String? = null)

/** Private atomic storage; corrupt or unknown records are never overwritten. */
internal class BrainStore(private val root: Path) {
    private fun directory(profile: String, companionId: String): Path {
        require(SharedRules.isProfileId(profile) && BrainRules.uuid(companionId))
        return root.resolve(profile).resolve("companion-brains").resolve(UUID.fromString(companionId).toString())
    }
    fun read(profile: String, companionId: String): BrainDocument? = locked(profile, companionId) { readConfig(it)?.document }
    fun converted(profile: String, companionId: String) = read(profile, companionId) != null
    private fun readConfig(directory: Path): ConfigRecord? {
        val file = directory.resolve("configuration.json")
        if (!Files.exists(file)) return null
        require(Files.size(file) <= CONFIG_RECORD_BYTES)
        return brainJson.decodeFromString<ConfigRecord>(Files.readString(file)).also { record ->
            require(record.version == 1 && record.document.revision > 0 && BrainRules.configuration(record.document.configuration))
            require(record.receipts.size <= 32)
        }
    }
    /** Compare, commit and receipt are protected by one cross-window/process lock. */
    fun save(profile: String, companionId: String, revision: Long, mutationId: String, configuration: BrainConfiguration): JsonElement = locked(profile, companionId) { directory ->
        require(BrainRules.configuration(configuration))
        val current = readConfig(directory)
        val digest = digest("$revision:${brainWire(configuration)}".toByteArray())
        val prior = current?.receipts?.find { it.id == mutationId }
        if (prior != null) {
            require(prior.digest == digest) { "Mutation ID reused with different content" }
            return@locked saved(prior.document)
        }
        if ((current?.document?.revision ?: 0) != revision) return@locked buildJsonObject { put("status", "conflict"); put("currentRevision", current?.document?.revision ?: 0) }
        require(revision < 9_007_199_254_740_991L)
        val document = BrainDocument(revision + 1, configuration)
        val record = ConfigRecord(document = document, receipts = ((current?.receipts ?: emptyList()) + SaveReceipt(mutationId, digest, document)).takeLast(32))
        val content = brainJson.encodeToString(ConfigRecord.serializer(), record)
        require(content.toByteArray().size <= CONFIG_RECORD_BYTES)
        atomic(directory.resolve("configuration.json"), content)
        saved(document)
    }
    private fun saved(document: BrainDocument) = buildJsonObject { put("status", "saved"); put("document", brainWire(document)); put("issues", JsonArray(emptyList())) }
    fun readRecord(profile: String, companionId: String): BrainRecord = locked(profile, companionId) { directory ->
        val file = directory.resolve("conversation.json")
        if (!Files.exists(file)) BrainRecord() else {
            require(Files.size(file) <= 16_000_000)
            brainJson.decodeFromString<BrainRecord>(Files.readString(file)).also { require(it.version == 1); it.run?.let { run -> require(BrainRules.configuration(run.configuration)) } }
        }
    }
    fun writeRecord(profile: String, companionId: String, record: BrainRecord) = locked(profile, companionId) { directory ->
        val content = brainJson.encodeToString(BrainRecord.serializer(), record)
        require(content.toByteArray().size <= 16_000_000) { "Conversation storage limit reached" }
        atomic(directory.resolve("conversation.json"), content)
    }
    fun archive(profile: String, companionId: String, run: RunRecord) = locked(profile, companionId) { directory ->
        require(BrainRules.uuid(run.summary.id))
        atomic(directory.resolve("run-${run.summary.id}.json"), brainJson.encodeToString(RunRecord.serializer(), run))
    }
    fun archived(profile: String, companionId: String, runId: String): RunRecord? = locked(profile, companionId) { directory ->
        require(BrainRules.uuid(runId))
        val file = directory.resolve("run-$runId.json")
        if (Files.exists(file)) { require(Files.size(file) <= 16_000_000); brainJson.decodeFromString<RunRecord>(Files.readString(file)) } else null
    }
    /** An immutable blob is durable before its reference is published. */
    fun artifact(profile: String, companionId: String, title: String, source: BrainSource, bytes: ByteArray, mediaType: String): BrainArtifact = locked(profile, companionId) { directory ->
        require(bytes.size <= 8 * 1024 * 1024)
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
        BrainArtifact(id, label, digest(bytes), source, mediaType, if (mediaType == "application/octet-stream") "Attached file" else bytes.toString(Charsets.UTF_8).take(512))
    }
    fun bytes(profile: String, companionId: String, artifact: BrainArtifact): ByteArray {
        require(BrainRules.uuid(artifact.id))
        val file = directory(profile, companionId).resolve("artifacts").resolve(artifact.id)
        require(Files.size(file) <= 8 * 1024 * 1024)
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
        // Thirty-two replay receipts plus the current config, including UTF-8 expansion.
        private const val CONFIG_RECORD_BYTES = 8_000_000
        private val locks = ConcurrentHashMap<Path, Any>()
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun privateDirectory(path: Path) { Files.createDirectories(path); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
        private fun atomic(file: Path, content: String) {
            privateDirectory(file.parent)
            val temp = Files.createTempFile(file.parent, ".brain-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
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
