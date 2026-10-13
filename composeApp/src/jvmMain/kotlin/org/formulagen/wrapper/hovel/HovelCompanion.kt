package org.formulagen.wrapper.hovel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

@Serializable
data class HostRegistration(val id: String, val ownerId: String, val name: String)
data class ConnectionApproval(val ownerId: String, val answer: CompletableDeferred<Boolean>)

/** Product adapter owns Hovel semantics; provider credentials never enter this class. */
class HovelCompanion(
    private val appUrl: String,
    private val directory: Path,
    private val executable: Path,
    private val workspace: Path,
    private val credentials: HostCredentials = MacHostCredentials(),
) : AutoCloseable {
    var status by mutableStateOf("Connect this Mac from Hovel. Sign in with your ChatGPT subscription in the configured Codex CLI.")
        private set
    var approval by mutableStateOf<ConnectionApproval?>(null)
        private set
    private val lifecycle = Any()
    private var closed = false
    private var generation = 0L
    private var session: HovelHostSession? = null
    private var registration: HostRegistration? = null
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val origin = URI(appUrl).let { URI(it.scheme, null, it.host, it.port, null, null, null).toASCIIString() }
    private val json = Json

    suspend fun dispatch(method: String, args: JsonObject): JsonObject {
        if (method == "capabilities") {
            require(args.isEmpty())
            return buildJsonObject { put("version", 1); put("connectComputer", true); put("cloud", false); put("sandbox", "read-only") }
        }
        val authorizedGeneration = synchronized(lifecycle) { check(!closed); generation }
        check(mutex.tryLock()) { "Another native operation is pending" }
        try {
            return when (method) {
                "prepareConnection" -> {
                    require(args.keys == setOf("ownerId"))
                    val owner = args.getValue("ownerId").jsonPrimitive.content
                    require(UUID.fromString(owner).toString() == owner)
                    val answer = CompletableDeferred<Boolean>()
                    withContext(Dispatchers.Main) {
                        synchronized(lifecycle) {
                            check(!closed && generation == authorizedGeneration)
                            approval = ConnectionApproval(owner, answer)
                        }
                    }
                    try { check(answer.await()) { "Connection declined" } }
                    finally { withContext(NonCancellable + Dispatchers.Main) { approval = null } }
                    currentCoroutineContext().ensureActive()
                    val registered = runInterruptible { prepareRegistration(owner) }
                    val context = currentCoroutineContext()
                    synchronized(lifecycle) {
                        context.ensureActive()
                        check(!closed && generation == authorizedGeneration)
                        registration = registered
                    }
                    status = "Approve registration in Hovel to start this computer"
                    val token = runInterruptible { credentials.read(registered.id) }
                    buildJsonObject {
                        put("id", registered.id); put("name", registered.name)
                        put("tokenHash", sha256(token))
                    }
                }
                "activateConnection" -> {
                    require(args.keys == setOf("ownerId", "hostId"))
                    val registered = synchronized(lifecycle) { registration } ?: error("Native approval is required")
                    require(args.getValue("ownerId").jsonPrimitive.content == registered.ownerId &&
                        args.getValue("hostId").jsonPrimitive.content == registered.id)
                    val token = runInterruptible { credentials.read(registered.id) }
                    val context = currentCoroutineContext()
                    synchronized(lifecycle) {
                        context.ensureActive()
                        check(!closed && generation == authorizedGeneration && registration == registered) { "Native authorization expired" }
                        if (session == null) {
                            val transport = HovelHttpTransport(URI("$origin/api/hovel/v1/hovel"), registered.ownerId, registered.id, token)
                            val journal = ExecutionJournal(directory.resolve("journal-${registered.id}"), registered.id)
                            try {
                                val provider = CodexCliProvider(executable, workspace, directory.resolve("output-${registered.id}"))
                                session = HovelHostSession(registered.id, journal, provider, transport) { status = it }.also { it.start() }
                            } catch (error: Exception) { journal.close(); throw error }
                        }
                    }
                    buildJsonObject { put("id", registered.id); put("connected", true) }
                }
                else -> error("Unsupported native method")
            }
        } catch (error: Exception) {
            if (error !is CancellationException) status = "Connection failed. Check the native configuration, Keychain approval and server."
            throw error
        } finally { mutex.unlock() }
    }

    internal fun prepareRegistration(owner: String): HostRegistration {
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        java.nio.channels.FileChannel.open(directory.resolve("registration.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock() ?: error("Another companion owns registration")
            lock.use { return prepareLocked(owner) }
        }
    }

    private fun prepareLocked(owner: String): HostRegistration {
        val file = directory.resolve("registration.json")
        if (Files.exists(file)) {
            require(Files.size(file) <= 4096)
            val existing = json.decodeFromString<HostRegistration>(Files.readString(file))
            require(existing.ownerId == owner) { "This native profile belongs to another account" }
            credentials.read(existing.id)
            return existing
        }
        val created = HostRegistration(UUID.randomUUID().toString(), owner, "My Mac")
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        credentials.save(created.id, token)
        val temp = Files.createTempFile(directory, ".registration-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(json.encodeToString(created).toByteArray())
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temp, file, ATOMIC_MOVE)
            java.nio.channels.FileChannel.open(directory, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temp) }
        return created
    }

    fun invalidatePending() = synchronized(lifecycle) {
        generation++
        registration = null
        approval?.answer?.cancel()
    }

    override fun close() {
        val active = synchronized(lifecycle) {
            if (closed) return
            closed = true
            invalidatePending()
            session.also { session = null }
        }
        active?.close()
    }

    companion object {
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
