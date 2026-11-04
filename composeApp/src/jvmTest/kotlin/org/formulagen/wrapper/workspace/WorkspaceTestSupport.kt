package org.formulagen.wrapper.workspace

import java.awt.EventQueue
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

/** Room a test leaves after its folder for Unix socket paths: a subfolder and a socket's name. */
private const val SOCKET_NAME_ROOM = 32

/** A temporary folder short enough for Unix sockets inside it: the system's, else one under `/tmp`. */
internal fun shortTempFolder(prefix: String = "hv"): Path {
    val folder = createTempDirectory(prefix)
    if (folder.toString().toByteArray().size + SOCKET_NAME_ROOM <= EditorSockets.MAX_SOCKET_PATH_BYTES) return folder
    folder.toFile().delete()
    return createTempDirectory(Path.of("/tmp"), prefix)
}

/** Collects a session's output as text and its exit code. */
internal class CollectingViewer : SessionViewer {
    private val bytes = ByteArrayOutputStream()
    val exit = CompletableFuture<Int>()

    override fun output(bytes: ByteArray, length: Int) { synchronized(this.bytes) { this.bytes.write(bytes, 0, length) } }
    override fun exited(code: Int) { exit.complete(code) }

    fun text(): String = synchronized(bytes) { bytes.toByteArray().decodeToString() }

    /** Wait until the output matches [pattern]; the match, or a failure naming what arrived. */
    fun await(pattern: Regex, timeoutMs: Long = 10_000): MatchResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pattern.find(text())?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("No match for $pattern in:\n${text()}")
    }

    fun await(text: String, timeoutMs: Long = 10_000) = await(Regex(Regex.escape(text)), timeoutMs)
}

/**
 * Answers the queries a terminal answers, as JediTerm does in the app: primary device attributes
 * and the cursor position. fish 4 waits for them at startup and drops what is typed meanwhile.
 */
internal class TerminalReplies(private val keys: Attachment) : SessionViewer {
    override fun output(bytes: ByteArray, length: Int) {
        val text = String(bytes, 0, length, Charsets.ISO_8859_1)
        repeat(Regex("\u001b\\[0?c").findAll(text).count()) { keys.input("\u001b[?62;22c".toByteArray()) }
        repeat(Regex("\u001b\\[6n").findAll(text).count()) { keys.input("\u001b[1;1R".toByteArray()) }
    }
    override fun exited(code: Int) {}
}

/** The one fake [Session]: it records keys and sizes, answers [busy] (or throws), and counts asks and closes. */
internal class FakeSession(override val title: String = "Shell") : Session {
    val input = CopyOnWriteArrayList<String>()
    val sizes = CopyOnWriteArrayList<TermSize>()
    val closed = CountDownLatch(1)
    val busyCalls = AtomicInteger()
    /** What [busy] answers. */
    @Volatile var busy: Busy? = null
    /** [busy] throws instead of answering. */
    @Volatile var busyFails = false
    /** The last viewer attached. */
    @Volatile var viewer: SessionViewer? = null
    /** An attachment was closed. */
    @Volatile var detached = false
    /** [attach] throws, as a terminal that cannot connect would. */
    @Volatile var attachFails = false

    override fun busy(): Busy? {
        busyCalls.incrementAndGet()
        return if (busyFails) throw IllegalStateException("busy boom") else busy
    }

    override fun attach(viewer: SessionViewer): Attachment {
        if (attachFails) throw IllegalStateException("attach boom")
        this.viewer = viewer
        return object : Attachment {
            override fun input(bytes: ByteArray) { input.add(bytes.decodeToString()) }
            override fun resize(size: TermSize) { sizes.add(size) }
            override fun close() { detached = true }
        }
    }

    override fun close() { closed.countDown() }

    /** Whether [close] has been called. */
    fun isClosed(): Boolean = closed.count == 0L
}

/** A [FolderTrust] in memory, never the real file: [all] trusts every folder, else only those in [folders]. */
internal class MemoryFolderTrust(private val all: Boolean = false) : FolderTrust {
    val folders = CopyOnWriteArraySet<Path>()
    override fun trusted(folder: Path): Boolean = all || folder in folders
    override fun trust(folder: Path) { folders.add(folder) }
    override fun forget() { folders.clear() }
}

/** [read]'s value, computed on the AWT thread; what it throws is rethrown here. */
internal fun <T> onAwtNow(read: () -> T): T {
    var value: Result<T>? = null
    EventQueue.invokeAndWait { value = runCatching(read) }
    return value!!.getOrThrow()
}

/** [session]'s busy state once [expected] holds, or the last one after [timeoutMs]. */
internal fun awaitBusy(session: Session, timeoutMs: Long = 10_000, expected: (Busy?) -> Boolean): Busy? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var busy = session.busy()
    while (!expected(busy) && System.currentTimeMillis() < deadline) { Thread.sleep(50); busy = session.busy() }
    return busy
}
