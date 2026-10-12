package org.formulagen.wrapper.companion

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

@Serializable
data class ExecutionRequest(val id: String, val hostId: String, val prompt: String, val context: String = "")

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
        require(request.hostId == hostId) { "Task belongs to another computer" }
        UUID.fromString(request.id)
        require(request.prompt.isNotBlank() && request.prompt.length <= 16_384 && request.context.length <= 16_384)
        read(request.id)?.let {
            require(it.request == request) { "Task identity has changed" }
            return it
        }
        return ExecutionRecord(request, UUID.randomUUID().toString()).also(::write)
    }

    @Synchronized
    fun read(id: String): ExecutionRecord? {
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
        require(read(record.request.id) == record && record.phase == ExecutionPhase.PREPARED)
        return record.copy(phase = ExecutionPhase.LAUNCHING).also(::write)
    }

    @Synchronized
    fun complete(record: ExecutionRecord, succeeded: Boolean, result: String): ExecutionRecord {
        require(read(record.request.id) == record && record.phase == ExecutionPhase.LAUNCHING)
        require(result.length <= 8 * 1024 * 1024) { "Result exceeds the journal limit; launch remains ambiguous" }
        return record.copy(phase = if (succeeded) ExecutionPhase.SUCCEEDED else ExecutionPhase.FAILED, result = result).also(::write)
    }

    @Synchronized
    fun reconcile(record: ExecutionRecord): ExecutionRecord {
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

    override fun close() {
        processLock.release()
        lockChannel.close()
    }
}
