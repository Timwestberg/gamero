package org.formulagen.wrapper.workspace

import com.jediterm.core.util.TermSize as JediTermSize
import com.jediterm.terminal.TtyConnector
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

private const val TAG = "workspace-terminal"

/** Output chunks (up to [PTY_READ_BYTES] each) waiting for the emulator. A full queue holds the reader, and so the process. */
private const val QUEUED_CHUNKS = 64

/**
 * Adapts a [Session]'s pushed output to the pull-based [TtyConnector] JediTerm reads from.
 *
 * The session's reader thread hands over bytes ([output]); JediTerm's emulator thread pulls
 * characters ([read]). Decoding happens on the emulator thread with one stateful UTF-8 decoder, so
 * a character split across two reads stays whole and malformed bytes become U+FFFD. Keys and
 * resizes go to the session's [Attachment]. [onExit] runs once, on the thread that reports the exit.
 */
internal class SessionTtyConnector(private val name: String, private val onExit: (Int) -> Unit = {}) : TtyConnector, SessionViewer {
    private val chunks = ArrayBlockingQueue<ByteArray>(QUEUED_CHUNKS)
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    /** The start of a character whose remaining bytes have not arrived yet. Emulator thread only. */
    private var carried: ByteBuffer = EMPTY_BYTES
    /** Decoded characters JediTerm has not read yet. Emulator thread only. */
    private var decoded: CharBuffer = EMPTY_CHARS
    /** The decoder has been flushed: reading has ended for good. Emulator thread only. */
    private var flushed = false
    private val ended = CountDownLatch(1)
    @Volatile private var exitCode = 0
    private val closed = AtomicBoolean(false)
    @Volatile private var attachment: Attachment? = null

    /** Attach to [session]. Output from now on reaches JediTerm. */
    fun connect(session: Session) {
        attachment = session.attach(this)
    }

    /** Waits while the queue is full: the back-pressure [SessionViewer.output] allows. */
    override fun output(bytes: ByteArray, length: Int) {
        val copy = bytes.copyOf(length)
        while (!closed.get()) {
            if (chunks.offer(copy, POLL_MS, MILLISECONDS)) return
        }
    }

    override fun exited(code: Int) {
        if (ended.count == 0L) return
        exitCode = code
        ended.countDown()
        try { onExit(code) } catch (error: Throwable) {
            rethrowIfFatal(error)
            log(LogLevel.Error, TAG, "The exit handler of $name failed", error)
        }
    }

    /** Decoded characters, blocking until some arrive; -1 once the session has ended and everything was read. */
    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        while (true) {
            if (decoded.hasRemaining()) {
                val count = minOf(length, decoded.remaining())
                decoded.get(buf, offset, count)
                return count
            }
            // A flushed decoder takes no more input: a chunk that arrives after the end is dropped.
            if (flushed) return -1
            val chunk = nextChunk()
            if (chunk != null) decode(chunk, endOfInput = false)
            else { flushed = true; decode(ByteArray(0), endOfInput = true) }
        }
    }

    /** The next chunk, or null once the session has exited (or the connector closed) and nothing is left. */
    private fun nextChunk(): ByteArray? {
        while (true) {
            chunks.poll()?.let { return it }
            if (closed.get() || ended.count == 0L) return null
            chunks.poll(POLL_MS, MILLISECONDS)?.let { return it }
        }
    }

    private fun decode(chunk: ByteArray, endOfInput: Boolean) {
        val input = if (carried.hasRemaining()) {
            ByteBuffer.allocate(carried.remaining() + chunk.size).put(carried).put(chunk).flip()
        } else ByteBuffer.wrap(chunk)
        // UTF-8 never decodes to more characters than bytes, plus one replacement for a cut-off end.
        val output = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, output, endOfInput)
        if (endOfInput) decoder.flush(output)
        carried = if (input.hasRemaining()) ByteBuffer.allocate(input.remaining()).put(input).flip() else EMPTY_BYTES
        decoded = output.flip()
    }

    override fun write(bytes: ByteArray) {
        try { attachment?.input(bytes) } catch (error: Throwable) {
            rethrowIfFatal(error)
            log(LogLevel.Error, TAG, "Could not send keys to $name", error)
        }
    }

    override fun write(string: String) = write(string.toByteArray(Charsets.UTF_8))

    override fun resize(termSize: JediTermSize) {
        try { attachment?.resize(TermSize(termSize.columns, termSize.rows)) } catch (error: Throwable) {
            rethrowIfFatal(error)
            log(LogLevel.Error, TAG, "Could not resize $name", error)
        }
    }

    override fun isConnected(): Boolean = !closed.get() && ended.count > 0

    override fun waitFor(): Int {
        ended.await()
        return exitCode
    }

    override fun ready(): Boolean = decoded.hasRemaining() || chunks.isNotEmpty()

    override fun getName(): String = name

    /** Detach from the session; JediTerm calls this when its emulator stops. The session keeps running. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { attachment?.close() } catch (error: Throwable) {
            rethrowIfFatal(error)
            log(LogLevel.Error, TAG, "Could not detach $name", error)
        }
        attachment = null
    }

    private companion object {
        const val POLL_MS = 50L
        val EMPTY_BYTES: ByteBuffer = ByteBuffer.allocate(0)
        val EMPTY_CHARS: CharBuffer = CharBuffer.allocate(0)
    }
}
