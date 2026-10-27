package org.formulagen.wrapper.progression.embedded

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.EOFException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the pinned engine's local surface channel, separate from progression. */
internal class MacEmbeddedGame(private val scope: CoroutineScope) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val outgoing = Channel<ByteArray>(256)
    private val mutableContext = MutableStateFlow<Long?>(null)
    val contextId = mutableContext.asStateFlow()
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    val launchArguments = listOf("--embedded", "--remote-debug", "tcp://127.0.0.1:${server.localPort}", "--resolution", "2560x1600")
    private val job = scope.launch(Dispatchers.IO) {
        try {
            server.soTimeout = 25000
            server.accept().use { client ->
                socket = client
                if (closed) return@use
                client.tcpNoDelay = true
                client.soTimeout = 25000
                coroutineScope {
                    val sender = launch(Dispatchers.IO) {
                        for (packet in outgoing) client.getOutputStream().write(packet)
                    }
                    try {
                        val input = client.getInputStream()
                        while (isActive) {
                            val header = input.readNBytes(4)
                            if (header.size != 4) throw EOFException("Game surface disconnected")
                            val length = GodotEmbedCodec.buffer(4).put(header).flip().int
                            require(length in 8..GodotEmbedCodec.MAX_PACKET) { "Invalid surface packet size" }
                            val payload = input.readNBytes(length)
                            require(payload.size == length) { "Incomplete surface packet" }
                            GodotEmbedCodec.contextId(payload)?.let {
                                client.soTimeout = 0
                                mutableContext.value = it
                            }
                        }
                    } finally { sender.cancel() }
                }
            }
        } catch (error: Exception) {
            if (!closed && error !is CancellationException) mutableFailure.value = error.message ?: "Game surface failed"
        }
    }

    /** Resize in render pixels; the engine normalizes its Core Animation layer. */
    fun resize(width: Int, height: Int) {
        if (width > 0 && height > 0) send("embed:window_size", GodotEmbedCodec.Size(width, height))
    }

    /** Forward an already-encoded keyboard or mouse event. */
    fun input(event: ByteArray) = send("embed:event", event)

    /** Release held keys and buttons before changing input ownership. */
    fun releaseInput() = send("embed:win_event", 1)

    /** Queue without blocking the UI; overflow is a visible failed session. */
    private fun send(name: String, argument: Any) {
        if (!closed && outgoing.trySend(GodotEmbedCodec.message(name, listOf(argument))).isFailure) {
            mutableFailure.value = "Game input channel stalled"
            close()
        }
    }

    /** Unblock reads and accept before cancelling the owning coroutine. */
    override fun close() {
        closed = true
        socket?.close()
        server.close()
        outgoing.close()
        job.cancel()
    }
}
