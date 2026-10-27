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

/** Private desktop adapter; the child game alone owns its progression save. */
class DesktopCompanionHost(
    private val project: Path,
    private val scope: CoroutineScope,
    val controller: PresentationController,
    private val environment: Map<String, String> = System.getenv(),
    private val gameArguments: List<String> = emptyList(),
) : AutoCloseable {
    private val session = UUID.randomUUID().toString()
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val outgoing = Channel<HostMessage>(Channel.UNLIMITED)
    private val input = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val controllerInput = input.asSharedFlow()
    private val mutableExit = MutableStateFlow<Int?>(null)
    val exitCode = mutableExit.asStateFlow()
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    private var game: Process? = null
    private val lifecycle = Any()
    private var closed = false
    @Volatile private var socket: Socket? = null
    private var job: Job? = null

    /** Start accepting before launching the game so the first handshake cannot race. */
    fun start() {
        job = scope.launch {
            launch(Dispatchers.IO) {
                while (isActive && !server.isClosed) {
                    try { serve(server.accept()) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (!server.isClosed) withContext(Dispatchers.Main) { controller.disconnected() }
                    }
                }
            }
            try {
                val scenario = environment["HOVEL_PRESENTATION_SCENARIO"]
                val command = buildList {
                    add(project.resolve("scripts/godot.sh").toString())
                    addAll(gameArguments)
                    if (environment["HOVEL_PRESENTATION_HEADLESS"] == "1") add("--headless")
                    addAll(listOf("--path", project.toString()))
                    if (scenario != null) addAll(listOf("-s", scenario)) else add("res://companions/main.tscn")
                }
                val process = withContext(Dispatchers.IO) {
                    synchronized(lifecycle) {
                        check(!closed) { "Host already closed" }
                        ProcessBuilder(command).directory(project.toFile()).inheritIO().apply {
                            environment().putAll(this@DesktopCompanionHost.environment)
                            environment()["HOVEL_PRESENTATION_PORT"] = server.localPort.toString()
                            environment()["HOVEL_PRESENTATION_SESSION"] = session
                        }.start().also { game = it }
                    }
                }
                val code = withContext(Dispatchers.IO) { process.waitFor() }
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
        val process = synchronized(lifecycle) {
            closed = true
            game
        }
        process?.let { terminateProcessTreeInBackground(it, "hovel-game-stop") }
        socket?.close()
        server.close()
        job?.cancel()
    }
}
