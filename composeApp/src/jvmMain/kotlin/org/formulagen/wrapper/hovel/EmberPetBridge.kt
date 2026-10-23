package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val EMBER_DISCOVERY_NAME = "ember-pet.json"

/**
 * Loopback WebSocket the Ember pet connects to. The per-launch token is the only secret
 * this bridge stores, and it lives in the discovery file so the pet can find the socket.
 * Host credentials and Codex login never enter the socket or that file.
 */
class EmberPetBridge(
    private val directory: Path,
    private val sink: EmberMessageSink = UnsupportedEmberMessageSink(),
    private val petExecutable: Path? = null,
    private val token: String = newEmberToken(),
    private val pid: Long = ProcessHandle.current().pid(),
) : AutoCloseable {
    val canLaunch: Boolean get() = petExecutable?.let(Files::isExecutable) == true
    private val lock = Any()
    private var snapshot = EmberCompanionSnapshot(online = true)
    private var published = emberView(snapshot)
    private var socketUrl: String? = null
    private var server: EmberSocket? = null
    private var pet: Process? = null
    private var started = false
    private val closed = AtomicBoolean(false)
    private val hook = Thread({ runCatching { close() } }, "ember-pet-bridge")

    fun start() {
        val socket = EmberSocket(token, this)
        socket.isDaemon = true
        socket.start()
        val bound = socket.awaitBound()
        synchronized(lock) {
            if (closed.get() || started) {
                socket.stopQuietly()
                return
            }
            if (!bound || !socket.boundToLoopback()) {
                socket.stopQuietly()
                note("Ember pet bridge did not stay on 127.0.0.1")
                return
            }
            val url = "ws://127.0.0.1:${socket.port}/?token=$token"
            try {
                writeDiscovery(url)
            } catch (_: Exception) {
                socket.stopQuietly()
                note("Ember pet bridge could not publish its discovery file")
                return
            }
            server = socket
            socketUrl = url
            started = true
        }
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
    }

    fun update(next: EmberCompanionSnapshot) {
        val view = emberView(next)
        val send = synchronized(lock) {
            if (closed.get() || !started) return
            snapshot = next
            if (published == view) null else view.also { published = it }
        } ?: return
        broadcast(send)
    }

    /** Start the pet, if [petExecutable] is set, with `EMBER_PET_WS` and without API-key variables. */
    fun launchPet() {
        val executable = petExecutable ?: return
        if (!Files.isExecutable(executable)) return
        val url = synchronized(lock) { if (closed.get()) null else socketUrl } ?: return
        synchronized(lock) {
            if (closed.get()) return
            if (pet?.isAlive == true) return
            pet = runCatching { startEmberPet(executable, url) }.getOrElse {
                note("Ember pet did not start")
                null
            }
        }
    }

    internal fun sendCurrent(conn: WebSocket) {
        val view = synchronized(lock) { published }
        send(conn, view)
    }

    internal fun onPetFrame(conn: WebSocket, raw: String) {
        val reply = emberHandleClientMessage(raw, sink, ::note) ?: return
        runCatching { conn.send(reply) }
    }

    internal fun noteConnectionProblem() {
        note("Ember pet connection closed because of a socket error")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val socket: EmberSocket?
        val child: Process?
        val blocked = synchronized(lock) {
            snapshot = snapshot.copy(online = false, tasks = emptyList())
            published = emberView(snapshot)
            socket = server
            child = pet
            server = null
            pet = null
            started = false
            published
        }
        if (socket != null) {
            val state = emberStateJson(blocked.state)
            for (conn in socket.connections) runCatching { conn.send(state) }
            socket.stopQuietly()
        }
        runCatching { deleteDiscoveryIfOurs() }
        child?.destroy()
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }

    private fun broadcast(view: EmberView) {
        val socket = synchronized(lock) { server } ?: return
        for (conn in socket.connections) send(conn, view)
    }

    private fun send(conn: WebSocket, view: EmberView) {
        runCatching {
            conn.send(emberStateJson(view.state))
            view.say?.let { conn.send(emberSayJson(it)) }
        }
    }

    private fun writeDiscovery(url: String) {
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val file = directory.resolve(EMBER_DISCOVERY_NAME)
        val body = buildJsonObject {
            put("url", url)
            put("pid", pid)
            put("version", 1)
        }.toString().toByteArray(Charsets.UTF_8)
        val temp = Files.createTempFile(
            directory, ".ember-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
        try {
            FileChannel.open(temp, WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(body)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun deleteDiscoveryIfOurs() {
        val file = directory.resolve(EMBER_DISCOVERY_NAME)
        val parsed = runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrNull() ?: return
        val filePid = runCatching { parsed["pid"]?.jsonPrimitive?.long }.getOrNull() ?: return
        val url = parsed["url"]?.jsonPrimitive?.contentOrNull ?: return
        if (filePid == pid && url.contains("token=$token")) Files.deleteIfExists(file)
    }

    private fun note(message: String) {
        log(LogLevel.Warn, "ember-pet", message)
    }
}

internal fun newEmberToken(): String =
    ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

/** The URL is an environment value, never an argument, so it does not show up in the process list. */
internal fun startEmberPet(
    executable: Path,
    socketUrl: String,
    parent: MutableMap<String, String> = ProcessBuilder().environment(),
): Process {
    applyEmberPetEnvironment(parent, socketUrl)
    val builder = ProcessBuilder(executable.toAbsolutePath().normalize().toString())
    val env = builder.environment()
    env.clear()
    env.putAll(parent)
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    return builder.start()
}

private class EmberSocket(
    private val token: String,
    private val bridge: EmberPetBridge,
) : WebSocketServer(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)) {
    private val bound = CountDownLatch(1)
    private val failed = AtomicBoolean(false)

    fun awaitBound(): Boolean = bound.await(5, TimeUnit.SECONDS) && !failed.get() && port > 0

    fun boundToLoopback(): Boolean {
        val address = address?.address ?: return false
        return address.isLoopbackAddress && address.hostAddress == "127.0.0.1"
    }

    fun stopQuietly() {
        runCatching { stop(500) }
    }

    override fun onStart() {
        bound.countDown()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val remote = conn.remoteSocketAddress
        if (remote !is InetSocketAddress || remote.address?.isLoopbackAddress != true) {
            conn.close(CloseFrame.POLICY_VALIDATION)
            return
        }
        bridge.sendCurrent(conn)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit

    override fun onMessage(conn: WebSocket, message: String) {
        bridge.onPetFrame(conn, message)
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        bridge.noteConnectionProblem()
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn == null) failed.set(true) else bridge.noteConnectionProblem()
        bound.countDown()
    }

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket,
        draft: Draft,
        request: ClientHandshake,
    ): ServerHandshakeBuilder {
        val remote = conn.remoteSocketAddress
        if (remote !is InetSocketAddress || remote.address?.isLoopbackAddress != true ||
            !emberTokenAccepted(request.resourceDescriptor ?: "", token)
        ) {
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "forbidden")
        }
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }
}
