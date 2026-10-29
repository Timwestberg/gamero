package org.formulagen.wrapper.chat

import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import org.formulagen.wrapper.voice.HEARING_SAMPLE_RATE
import org.formulagen.wrapper.voice.HearingEngine
import org.formulagen.wrapper.voice.HearingResult

internal interface MicCapture : AutoCloseable {
    /** Samples written into [into]. `0` means wait and try again. Negative means the microphone closed. */
    fun read(into: FloatArray): Int
}

/**
 * One hold: open the microphone, feed [engine], and finish on release.
 * The microphone thread never takes the chat lock. [up] may block for the last decode.
 */
internal class DesktopHearing(
    private val engine: HearingEngine,
    private val openMic: () -> MicCapture,
) : AutoCloseable {
    private val gate = Any()
    private val stop = AtomicBoolean(false)
    private val delivered = AtomicBoolean(true)
    private var thread: Thread? = null
    private var mic: MicCapture? = null
    private var failed: String? = null

    fun down(onPartial: (String) -> Unit, onFailed: (String) -> Unit): Boolean {
        synchronized(gate) {
            if (thread != null) return false
            stop.set(false)
            failed = null
            delivered.set(false)
            val worker = Thread({
                var capture: MicCapture? = null
                try {
                    engine.startUtterance()
                    capture = openMic()
                    synchronized(gate) { mic = capture }
                    val buf = FloatArray(1600)
                    while (!stop.get()) {
                        val n = capture.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        val chunk = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                        engine.accept(chunk)
                        onPartial(engine.partial())
                    }
                } catch (error: Throwable) {
                    if (error is VirtualMachineError) throw error
                    failed = error.message ?: "The microphone did not open."
                    onFailed(failed!!)
                } finally {
                    runCatching { capture?.close() }
                    synchronized(gate) {
                        if (mic === capture) mic = null
                        if (thread === Thread.currentThread()) thread = null
                    }
                }
            }, "hovel-hearing")
            worker.isDaemon = true
            thread = worker
            worker.start()
            return true
        }
    }

    /** @return false when this release had already been delivered, so the caller must still clear its hold. */
    fun up(onResult: (HearingResult) -> Unit): Boolean {
        if (!delivered.compareAndSet(false, true)) return false
        val running = synchronized(gate) {
            stop.set(true)
            runCatching { mic?.close() }
            thread
        }
        running?.join(3_000)
        val error = synchronized(gate) { failed }
        if (error != null) return true
        val result = runCatching { engine.finish() }.getOrElse { HearingResult("", 0, 0.0) }
        onResult(result)
        return true
    }

    /** Stop the microphone without a transcript. Safe to call while holding another lock: it does not join. */
    fun cancel() {
        stop.set(true)
    }

    override fun close() {
        stop.set(true)
        val running = synchronized(gate) {
            runCatching { mic?.close() }
            thread.also { thread = null }
        }
        running?.join(1_000)
        runCatching { engine.close() }
    }
}

internal fun openTargetMic(): MicCapture {
    val format = AudioFormat(HEARING_SAMPLE_RATE.toFloat(), 16, 1, true, false)
    val line = AudioSystem.getTargetDataLine(format)
    line.open(format, HEARING_SAMPLE_RATE * 2)
    line.start()
    return TargetMic(line)
}

private class TargetMic(private val line: TargetDataLine) : MicCapture {
    private val bytes = ByteArray(3200)

    override fun read(into: FloatArray): Int {
        val want = minOf(bytes.size, into.size * 2)
        val n = line.read(bytes, 0, want - (want and 1))
        if (n <= 0) return 0
        val frames = n / 2
        var frame = 0
        while (frame < frames && frame < into.size) {
            val lo = bytes[frame * 2].toInt() and 0xff
            val hi = bytes[frame * 2 + 1].toInt()
            into[frame] = ((hi shl 8) or lo).toShort() / 32768f
            frame += 1
        }
        return frame
    }

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.close() }
    }
}
