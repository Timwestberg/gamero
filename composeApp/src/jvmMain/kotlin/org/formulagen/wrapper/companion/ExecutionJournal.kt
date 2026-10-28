package org.formulagen.wrapper.companion

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * What runs one request. The fields added after the first release are left out of a record
 * while they hold their default, so a record without them reads the same in an older companion.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExecutionRequest(
    val id: String,
    val hostId: String,
    val prompt: String,
    val context: String = "",
    /** Generated memory notes the server chose for a task, given to the provider in their own section. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val recalledMemory: String? = null,
    /** Set only for a memory job: the provider runs [prompt] as it is, with no character section, on this model and effort. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val extraction: ProviderChoice? = null,
) {
    companion object {
        /** The longest prompt a provider is given: a Hovel follow-up's whole conversation (event-contracts `HOVEL_LIMITS.providerInput`), or a memory job's input (`HOVEL_MEMORY_LIMITS.jobInput`). */
        const val MAX_PROMPT_CHARS = 131_072
        const val MAX_CONTEXT_CHARS = 16_384
        /** event-contracts `HOVEL_MEMORY_LIMITS.recalledMemory`. */
        const val MAX_RECALLED_MEMORY_CHARS = 12_288
    }
}

/** The model and reasoning effort a memory job runs on, fixed by the server when it made the job. */
@Serializable
data class ProviderChoice(val model: String, val effort: String) {
    /** Within event-contracts `hovelMemoryExtractionModel` and `hovelMemoryExtractionEffort`, so a model is never read as a flag. */
    fun isValid() = model.length <= MAX_MODEL_CHARS && MODEL.matches(model) && effort in EFFORTS

    companion object {
        /** Leaves the choice to Codex. */
        const val RUNTIME_DEFAULT = "runtime-default"
        const val MAX_MODEL_CHARS = 256
        val EFFORTS = setOf(RUNTIME_DEFAULT, "minimal", "low", "medium", "high", "xhigh")
        private val MODEL = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")
    }
}

@Serializable
enum class ExecutionPhase { PREPARED, LAUNCHING, SUCCEEDED, FAILED, RECONCILIATION_REQUIRED }

@Serializable
data class ExecutionRecord(
    val request: ExecutionRequest,
    val claimId: String,
    val phase: ExecutionPhase = ExecutionPhase.PREPARED,
    val result: String? = null,
)

/** One process owns a host journal. Ambiguous launches are retained, never reset. */
class ExecutionJournal(private val directory: Path, private val hostId: String) : AutoCloseable {
    private val json = Json { encodeDefaults = true }
    private val lockChannel: FileChannel
    private val processLock: java.nio.channels.FileLock
    private var closed = false

    init {
        UUID.fromString(hostId)
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        lockChannel = FileChannel.open(directory.resolve("host.lock"), CREATE, WRITE)
        try {
            processLock = lockChannel.tryLock() ?: error("This computer already has an active companion")
        } catch (error: Exception) {
            lockChannel.close()
            throw error
        }
    }

    @Synchronized
    fun prepare(request: ExecutionRequest): ExecutionRecord {
        checkOpen()
        require(request.hostId == hostId) { "Task belongs to another computer" }
        UUID.fromString(request.id)
        require(request.prompt.isNotBlank() && request.prompt.length <= ExecutionRequest.MAX_PROMPT_CHARS &&
            request.context.length <= ExecutionRequest.MAX_CONTEXT_CHARS)
        request.recalledMemory?.let { require(it.isNotEmpty() && it.length <= ExecutionRequest.MAX_RECALLED_MEMORY_CHARS) }
        request.extraction?.let {
            require(it.isValid()) { "Memory job model or effort is invalid" }
            require(request.context.isEmpty() && request.recalledMemory == null) { "A memory job runs its input alone" }
        }
        read(request.id)?.let {
            require(it.request == request) { "Task identity has changed" }
            return it
        }
        return ExecutionRecord(request, UUID.randomUUID().toString()).also(::write)
    }

    @Synchronized
    fun read(id: String): ExecutionRecord? {
        checkOpen()
        val path = path(id)
        if (!Files.exists(path)) return null
        require(Files.size(path) <= 32L * 1024 * 1024) { "Journal record is too large; preserve it for reconciliation" }
        val record = json.decodeFromString<ExecutionRecord>(Files.readString(path))
        require(record.request.id == id && record.request.hostId == hostId) { "Journal ownership differs" }
        UUID.fromString(record.claimId)
        return record
    }

    @Synchronized
    fun begin(record: ExecutionRecord): ExecutionRecord {
        checkOpen()
        require(read(record.request.id) == record && record.phase == ExecutionPhase.PREPARED)
        return record.copy(phase = ExecutionPhase.LAUNCHING).also(::write)
    }

    @Synchronized
    fun complete(record: ExecutionRecord, succeeded: Boolean, result: String): ExecutionRecord {
        checkOpen()
        require(read(record.request.id) == record && record.phase == ExecutionPhase.LAUNCHING)
        require(result.length <= 8 * 1024 * 1024) { "Result exceeds the journal limit; launch remains ambiguous" }
        return record.copy(phase = if (succeeded) ExecutionPhase.SUCCEEDED else ExecutionPhase.FAILED, result = result).also(::write)
    }

    @Synchronized
    fun reconcile(record: ExecutionRecord): ExecutionRecord {
        checkOpen()
        require(read(record.request.id) == record && record.phase == ExecutionPhase.LAUNCHING)
        return record.copy(phase = ExecutionPhase.RECONCILIATION_REQUIRED,
            result = "Execution may have started. Inspect this computer before resolving; automatic retry is disabled.").also(::write)
    }

    /** Keep complete output locally; make truncation explicit in the server-bound preview. */
    fun preview(record: ExecutionRecord, limit: Int = 65_536): String {
        require(limit >= 128)
        val text = record.result.orEmpty()
        if (text.length <= limit) return text
        val suffix = "\n[Truncated preview; full output is retained in this computer's execution journal.]"
        return text.take(limit - suffix.length) + suffix
    }

    private fun path(id: String): Path {
        require(UUID.fromString(id).toString() == id) { "Task ID must be a canonical UUID" }
        return directory.resolve("$id.json")
    }

    private fun write(record: ExecutionRecord) {
        val encoded = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        require(encoded.size <= 32 * 1024 * 1024) { "Encoded journal record exceeds the storage limit" }
        val target = path(record.request.id)
        val temporary = Files.createTempFile(directory, ".receipt-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            FileChannel.open(temporary, WRITE).use { file ->
                val bytes = ByteBuffer.wrap(encoded)
                while (bytes.hasRemaining()) file.write(bytes)
                file.force(true)
            }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            FileChannel.open(directory, READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun checkOpen() {
        check(!closed && processLock.isValid) { "Execution journal is closed" }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { processLock.release() } finally { lockChannel.close() }
    }
}
