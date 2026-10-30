package org.formulagen.wrapper.progression

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import org.formulagen.wrapper.companion.terminateProcessTreeInBackground

/**
 * A game a desktop host starts: the [engine] script that resolves the pinned Godot, the [project] it runs and the
 * [scene] it opens. Only a [presentation] game talks to the host over the companion presentation socket.
 */
data class GodotGame(val engine: Path, val project: Path, val scene: String, val presentation: Boolean) {
    companion object {
        /** The companions game's scene in the Tower project. */
        const val COMPANIONS_SCENE = "res://companions/main.tscn"

        /** The companions game in [project], a Tower project, run by the project's own engine script. */
        fun companions(project: Path) = GodotGame(project.resolve("scripts/godot.sh"), project, COMPANIONS_SCENE, presentation = true)
    }
}

/**
 * Private desktop adapter: runs one [game] as a child process and, for a presentation game, its loopback
 * presentation session. The child game alone owns its progression save.
 */
class DesktopCompanionHost(
    private val game: GodotGame,
    private val scope: CoroutineScope,
    val controller: PresentationController,
    private val environment: Map<String, String> = System.getenv(),
    private val gameArguments: List<String> = emptyList(),
) : AutoCloseable {
    private val session = UUID.randomUUID().toString()
    /** Only a presentation game gets a socket, so only it can ever connect. */
    private val server = if (game.presentation) ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) else null
    private val outgoing = Channel<HostMessage>(Channel.UNLIMITED)
    private val input = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val controllerInput = input.asSharedFlow()
    private val mutableExit = MutableStateFlow<Int?>(null)
    val exitCode = mutableExit.asStateFlow()
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    private var process: Process? = null
    private val lifecycle = Any()
    private var closed = false
    @Volatile private var socket: Socket? = null
    private var job: Job? = null

    /** Start accepting before launching the game so the first handshake cannot race. */
    fun start() {
        job = scope.launch {
            server?.let { server ->
                launch(Dispatchers.IO) {
                    while (isActive && !server.isClosed) {
                        try { serve(server.accept()) }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            if (!server.isClosed) withContext(Dispatchers.Main) { controller.disconnected() }
                        }
                    }
                }
            }
            try {
                val scenario = environment["HOVEL_PRESENTATION_SCENARIO"]
                val command = buildList {
                    add(game.engine.toString())
                    addAll(gameArguments)
                    if (environment["HOVEL_PRESENTATION_HEADLESS"] == "1") add("--headless")
                    addAll(listOf("--path", game.project.toString()))
                    if (scenario != null) addAll(listOf("-s", scenario)) else add(game.scene)
                }
                val started = withContext(Dispatchers.IO) {
                    synchronized(lifecycle) {
                        check(!closed) { "Host already closed" }
                        ProcessBuilder(command).directory(game.project.toFile()).inheritIO().apply {
                            environment().putAll(this@DesktopCompanionHost.environment)
                            server?.let { server ->
                                environment()["HOVEL_PRESENTATION_PORT"] = server.localPort.toString()
                                environment()["HOVEL_PRESENTATION_SESSION"] = session
                            }
                        }.start().also { process = it }
                    }
                }
                val code = withContext(Dispatchers.IO) { started.waitFor() }
                mutableExit.value = code
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                System.err.println("Hovel companion host could not start: ${error.message}")
                controller.disconnected()
                mutableExit.value = 1
            }
        }
    }

    /** Submit a command through the same envelope used for retry and reconnect. */
    fun send(command: PresentationCommand) { outgoing.trySend(HostMessage("command", session, command = command)) }

    /** Authenticate one child and exchange bounded frames with heartbeat timeouts. */
    private suspend fun serve(client: Socket) = coroutineScope {
        client.use {
            client.soTimeout = 5000
            client.tcpNoDelay = true
            val reader = BufferedInputStream(client.getInputStream())
            val hello = PresentationProtocol.decode(readLine(reader), session)
            require(hello.kind == "hello")
            socket = client
            withContext(Dispatchers.Main) { mutableConnected.value = true }
            val writer = client.getOutputStream()
            /** Serialize writes on the single sender coroutine. */
            fun write(message: HostMessage) {
                writer.write((PresentationProtocol.json.encodeToString(message) + "\n").toByteArray(Charsets.UTF_8))
                writer.flush()
            }
            write(HostMessage("welcome", session))
            val sender = launch(Dispatchers.IO) {
                while (isActive) {
                    val message = withTimeoutOrNull(1000) { outgoing.receive() } ?: HostMessage("ping", session)
                    write(message)
                }
            }
            try {
                while (isActive) {
                    val message = PresentationProtocol.decode(readLine(reader), session)
                    withContext(Dispatchers.Main) {
                        if (message.kind == "input") {
                            if (environment["HOVEL_TRACE_CONTROLLER"] == "1") println("HOVEL_CONTROLLER_INPUT ${message.input}")
                            input.tryEmit(message.input)
                        }
                        else controller.receive(message)
                    }
                }
            } finally { sender.cancel(); socket = null; withContext(Dispatchers.Main) { mutableConnected.value = false } }
        }
    }

    /** A disconnected or oversized frame cannot allocate unbounded memory. */
    private fun readLine(reader: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < PresentationProtocol.MAX_MESSAGE_BYTES) {
            val value = reader.read()
            if (value < 0) throw EOFException("Game disconnected")
            if (value == 10) return bytes.toString(Charsets.UTF_8)
            bytes.write(value)
        }
        error("Presentation message is too large")
    }

    /**
     * Release the game and session socket. Callers are often on the UI thread, so the game's
     * process tree is signalled now and force-killed after the grace period in the background.
     */
    override fun close() {
        val running = synchronized(lifecycle) {
            closed = true
            process
        }
        running?.let { terminateProcessTreeInBackground(it, "hovel-game-stop") }
        socket?.close()
        server?.close()
        job?.cancel()
    }
}
