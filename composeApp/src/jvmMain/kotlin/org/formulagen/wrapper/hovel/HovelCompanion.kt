package org.formulagen.wrapper.hovel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.BridgeBusyException
import org.formulagen.wrapper.bridge.BridgeDeclinedException
import org.formulagen.wrapper.bridge.Capability
import org.formulagen.wrapper.bridge.CapabilityPack
import org.formulagen.wrapper.bridge.CompanionCapability
import org.formulagen.wrapper.bridge.CompanionMethods
import org.formulagen.wrapper.bridge.CompanionRegistration
import org.formulagen.wrapper.closeInReverse
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

/**
 * A registered Mac's connection to its account: the [registration], the [transport] that alone holds
 * the host token, where status lines go, the [features] its heartbeat declares, [providerState] (the task
 * lane's Codex readiness, null without a task lane), and [heartbeat], which hands each heartbeat's answer to
 * the Codex lanes.
 */
class HostConnection(
    val registration: HostRegistration,
    val transport: HovelHttpTransport,
    val status: (String) -> Unit,
    val features: List<String>,
    val providerState: () -> String?,
    val heartbeat: (JsonObject) -> Unit,
)

/**
 * Product adapter owns Hovel semantics; provider credentials never enter this class. Once this Mac
 * is registered it runs from launch ([resume]) with no page involved: [conversations] starts the
 * conversation runner for the account; Codex ([executable], when installed) runs memory jobs, and
 * Hovel tasks in [workspace] when one is configured, until step E removes tasks.
 */
class HovelCompanion(
    private val appUrl: String,
    private val directory: Path,
    private val executable: Path?,
    private val workspace: Path? = null,
    private val credentials: HostCredentials = MacHostCredentials(),
    private val conversations: (HostConnection) -> AutoCloseable = { AutoCloseable {} },
) : AutoCloseable {
    var status by mutableStateOf("Connect this Mac from Hovel to run your companion conversations here.")
        private set
    var approval by mutableStateOf<ConnectionApproval?>(null)
        private set

    /** Where this Mac runs Hovel tasks: the configured workspace, when Codex is installed to run them. */
    val taskWorkspace: Path? get() = workspace.takeIf { executable != null }
    private val lifecycle = Any()
    private var closed = false
    private var generation = 0L
    private var session: AutoCloseable? = null
    private var registration: HostRegistration? = null
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val origin = appOrigin(appUrl)
    private val json = Json

    suspend fun dispatch(method: String, args: JsonObject): JsonObject {
        val authorizedGeneration = synchronized(lifecycle) { check(!closed); generation }
        if (!mutex.tryLock()) throw BridgeBusyException()
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
                    try { if (!answer.await()) throw BridgeDeclinedException("Connection declined") }
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
                        if (session == null) session = startSession(registered, token)
                    }
                    buildJsonObject { put("id", registered.id); put("connected", true) }
                }
                else -> error("Unsupported native method")
            }
        } catch (error: Exception) {
            // A navigation cancels with no message; a call that ran out of time still tells the person.
            when (error) {
                is TimeoutCancellationException -> status = "Connection timed out. Start again from Hovel."
                !is CancellationException -> status = "Connection failed. Check the native configuration, Keychain approval and server."
            }
            throw error
        } finally { mutex.unlock() }
    }

    /**
     * At launch: a Mac registered before connects again on its own, from `registration.json` and the
     * Keychain token, with no page involved. Nothing happens before the one-time registration. Blocks
     * on Keychain, so callers run it off the UI thread.
     */
    fun resume() {
        val file = directory.resolve("registration.json")
        if (!Files.exists(file)) return
        try {
            require(Files.size(file) <= 4096)
            val registered = json.decodeFromString<HostRegistration>(Files.readString(file))
            val token = credentials.read(registered.id)
            synchronized(lifecycle) { if (!closed && session == null) session = startSession(registered, token) }
        } catch (error: Exception) {
            status = "Connect this Mac from Hovel again to run your companion conversations here."
        }
    }

    /**
     * The registered Mac's session: with Codex, its lanes (memory jobs, and Hovel tasks in [workspace]); and the
     * conversation runner. Called holding [lifecycle].
     */
    private fun startSession(registered: HostRegistration, token: String): AutoCloseable {
        val transport = HovelHttpTransport(URI("$origin/api/hovel/v1/hovel"), registered.ownerId, registered.id, token)
        val journals = mutableListOf<ExecutionJournal>()
        var lanes: HovelHostSession? = null
        try {
            if (executable != null) {
                // Tasks run on the player's own Codex login, read-only in the configured workspace; so do memory jobs.
                val tasks = workspace?.let {
                    ExecutionJournal(directory.resolve("journal-${registered.id}"), registered.id).also(journals::add) to
                        CodexCliProvider(executable, it, directory.resolve("output-${registered.id}"))
                }
                // Memory jobs keep their own journal and output, so one may run beside a task.
                val memoryJobs = ExecutionJournal(directory.resolve("memory-journal-${registered.id}"), registered.id).also(journals::add)
                // Extraction reads only its input: an empty private folder, never the player's project and its AGENTS.md.
                val memoryWorkspace = privateDirectory(directory.resolve("memory-workspace-${registered.id}"))
                val memoryProvider = CodexCliProvider(executable, memoryWorkspace, directory.resolve("memory-output-${registered.id}"))
                lanes = HovelHostSession(registered.id, tasks?.first, tasks?.second, memoryJobs, memoryProvider,
                    tasks?.second?.let { it::readiness } ?: memoryProvider::readiness, transport) { status = it }.also { it.start() }
            }
            val session = lanes
            val features = session?.features ?: hovelHostFeatures(tasks = false, memory = false)
            val runner = conversations(HostConnection(registered, transport, { status = it }, features,
                { session?.taskProviderState() }, { response -> session?.heartbeat(response) }))
            return AutoCloseable { closeInReverse(listOfNotNull(session, runner)) }
        } catch (error: Exception) {
            lanes?.close() ?: journals.forEach { runCatching { it.close() } }
            throw error
        }
    }

    private fun privateDirectory(path: Path): Path = Files.createDirectories(path).also {
        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
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

/** Hovel's companion as a `host-bridge.v2` capability. Validation and single-flight stay in [HovelCompanion.dispatch]. */
class HovelCompanionCapability(private val companion: HovelCompanion) : CompanionCapability {
    override suspend fun prepareConnection(ownerId: String): CompanionRegistration {
        val result = companion.dispatch("prepareConnection", buildJsonObject { put("ownerId", ownerId) })
        return CompanionRegistration(
            id = result.getValue("id").jsonPrimitive.content,
            name = result.getValue("name").jsonPrimitive.content,
            tokenHash = result.getValue("tokenHash").jsonPrimitive.content,
        )
    }

    override suspend fun activateConnection(ownerId: String, hostId: String): JsonObject =
        companion.dispatch("activateConnection", buildJsonObject { put("ownerId", ownerId); put("hostId", hostId) })
}
