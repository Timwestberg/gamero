package org.formulagen.wrapper.chat

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.DuplexChoice
import org.formulagen.wrapper.voice.EmberAudio
import org.formulagen.wrapper.voice.EndpointDecision
import org.formulagen.wrapper.voice.HEARING_MIN_SPEECH_MS
import org.formulagen.wrapper.voice.HEARING_SAMPLE_RATE
import org.formulagen.wrapper.voice.TurnCommitter
import org.formulagen.wrapper.voice.chunkRms
import org.formulagen.wrapper.voice.HearingDuplex
import org.formulagen.wrapper.voice.HearingEngine
import org.formulagen.wrapper.voice.HearingResult

internal interface MicCapture : AutoCloseable {
    /** Samples written into [into]. `0` means wait and try again. Negative means the microphone closed. */
    fun read(into: FloatArray): Int
}

/**
 * What a talk loop asks of the microphone: a dictation hold ([down], [up]) or a hands-free session
 * ([startSession] to [endSession]), and the session's controls. [DesktopHearing] is the Mac's; a
 * [SharedHearing] user is one caller's share of it.
 */
internal interface Hearing : AutoCloseable {
    /** Start a dictation hold. False when a capture is already running. */
    fun down(onPartial: (String) -> Unit, onFailed: (String) -> Unit): Boolean

    /** End the hold and deliver its last decode. False when it had already been delivered. May block. */
    fun up(onResult: (HearingResult) -> Unit): Boolean

    /** Start a hands-free session. False when a capture is already running. */
    fun startSession(
        silenceMs: Int,
        onPartial: (String) -> Unit,
        onUtterance: (HearingResult) -> Unit,
        onFailed: (String) -> Unit,
        onFrame: (voicedMs: Int, chunkMs: Int, vadProb: Float) -> Unit = { _, _, _ -> },
    ): Boolean

    fun setPaused(value: Boolean)

    /** Drop the in-progress transcript and start again from the last 300 ms. */
    fun restartUtterance()

    fun noteEmberSpeaking(text: String)

    fun noteEmberSilent(text: String)

    fun endSession()

    /** Wait up to [timeoutMs] for a capture told to stop to let go of the microphone. Call outside any lock. */
    fun awaitStopped(timeoutMs: Long)

    /** Whether another user of a shared microphone holds it now. */
    val busyElsewhere: Boolean get() = false
}

/**
 * One hold: open the microphone, feed [engine], and finish on release.
 * The microphone thread never takes the chat lock. [up] may block for the last decode.
 */
internal class DesktopHearing(
    private val engine: HearingEngine,
    private val openMic: () -> MicCapture,
) : Hearing {
    private val gate = Any()
    private val stop = AtomicBoolean(false)
    private val delivered = AtomicBoolean(true)
    private var thread: Thread? = null
    private var mic: MicCapture? = null
    private var failed: String? = null

    override fun down(onPartial: (String) -> Unit, onFailed: (String) -> Unit): Boolean {
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
    override fun up(onResult: (HearingResult) -> Unit): Boolean {
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

    private val paused = AtomicBoolean(false)
    private val restart = AtomicBoolean(false)
    private val feedPreRoll = AtomicBoolean(false)
    private val emberSpeaking = AtomicBoolean(false)
    private val emberHotUntilNs = AtomicLong(0)
    private val emberWords = AtomicReference("")
    private var committer = TurnCommitter()

    /**
     * Hands-free session. The microphone stays open until [endSession].
     * While paused, captured audio is discarded so Ember is not transcribed.
     */
    override fun startSession(
        silenceMs: Int,
        onPartial: (String) -> Unit,
        onUtterance: (HearingResult) -> Unit,
        onFailed: (String) -> Unit,
        onFrame: (voicedMs: Int, chunkMs: Int, vadProb: Float) -> Unit,
    ): Boolean {
        synchronized(gate) {
            if (thread != null) return false
            stop.set(false)
            failed = null
            delivered.set(false)
            paused.set(false)
            restart.set(true)
            committer = TurnCommitter(silenceMs = silenceMs)
            val preRoll = PreRoll(HEARING_SAMPLE_RATE * 300 / 1000)
            val debugLevels = System.getenv("HOVEL_HEARING_DEBUG")?.trim()?.lowercase() in setOf("1", "true", "yes")
            val worker = Thread({
                var capture: MicCapture? = null
                var wasSpeech = false
                var lastPartial = ""
                var pendingPartial: String? = null
                var lastPartialLogNs = 0L
                var debugMs = 0
                try {
                    capture = openMic()
                    synchronized(gate) { mic = capture }
                    log(LogLevel.Info, "hearing", "state=listening")
                    val buf = FloatArray(1600)
                    while (!stop.get()) {
                        val n = capture.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        val chunk = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                        if (paused.get()) continue
                        preRoll.add(chunk)
                        if (restart.compareAndSet(true, false)) {
                            runCatching { engine.startUtterance() }
                            committer.resetUtterance()
                            if (feedPreRoll.compareAndSet(true, false)) {
                                val prior = preRoll.copy()
                                if (prior.isNotEmpty()) engine.accept(prior)
                            } else {
                                engine.accept(chunk)
                            }
                        } else {
                            engine.accept(chunk)
                        }
                        val voicedMs = engine.voicedMillisOfLastAccept()
                        val chunkMs = n * 1000 / HEARING_SAMPLE_RATE
                        val rms = chunkRms(chunk)
                        val prob = engine.lastVadProbability()
                        onFrame(voicedMs, chunkMs, prob)
                        val text = engine.partial()
                        val hot = emberSpeaking.get() || System.nanoTime() < emberHotUntilNs.get()
                        val event = committer.observe(voicedMs > 0, chunkMs, rms, text, prob, hot, emberWords.get())
                        if (event.speech != wasSpeech) {
                            if (event.speech) log(LogLevel.Info, "hearing", "vad start prob=$prob rms=$rms")
                            else log(LogLevel.Info, "hearing", "vad end prob=$prob rms=$rms")
                            wasSpeech = event.speech
                        }
                        if (text != lastPartial) pendingPartial = text
                        val now = System.nanoTime()
                        if (pendingPartial != null && (lastPartialLogNs == 0L || now - lastPartialLogNs >= 200_000_000L)) {
                            log(LogLevel.Info, "hearing", "partial chars=${pendingPartial!!.length} text=\"${pendingPartial!!.take(80)}\"")
                            lastPartial = pendingPartial!!
                            pendingPartial = null
                            lastPartialLogNs = now
                        }
                        if (debugLevels) {
                            debugMs += chunkMs
                            if (debugMs >= 1000) {
                                log(LogLevel.Info, "hearing", "debug rms=$rms vad=$prob")
                                debugMs = 0
                            }
                        }
                        if (event.decision == EndpointDecision.LISTEN) {
                            onPartial(text)
                            continue
                        }
                        val result = runCatching { engine.finish() }.getOrElse { HearingResult("", 0, 0.0) }
                        val send = event.decision == EndpointDecision.SEND && result.text.isNotBlank()
                        if (send) {
                            log(LogLevel.Info, "hearing", "endpoint reason=${event.reason?.name?.lowercase()} text=\"${result.text.take(80)}\"")
                            log(LogLevel.Info, "hearing", "send chars=${result.text.length}")
                            log(LogLevel.Info, "hearing", "state=listening")
                            onUtterance(result)
                        } else if (event.discard != null) {
                            log(LogLevel.Info, "hearing", "discard reason=${event.discard} text=\"${result.text.take(80)}\" vadMs=${event.vadSpeechMs} prob=${event.maxProb}")
                        } else {
                            log(LogLevel.Info, "hearing", "endpoint drop chars=${result.text.length}")
                        }
                        onPartial("")
                        restart.set(true)
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

    override fun setPaused(value: Boolean) {
        paused.set(value)
        if (!value) restart.set(true)
    }

    /** Drop the in-progress transcript and start again from the last 300 ms. */
    override fun restartUtterance() {
        feedPreRoll.set(true)
        restart.set(true)
    }

    /** Clear the recognizer without replaying the last audio. Used when Ember starts or stops. */
    fun dropUtterance() {
        feedPreRoll.set(false)
        restart.set(true)
    }

    override fun noteEmberSpeaking(text: String) {
        emberSpeaking.set(true)
        if (text.isNotBlank()) emberWords.set(text)
        dropUtterance()
    }

    override fun noteEmberSilent(text: String) {
        emberSpeaking.set(false)
        if (text.isNotBlank()) emberWords.set(text)
        emberHotUntilNs.set(System.nanoTime() + 500_000_000L)
        dropUtterance()
    }

    fun isPaused(): Boolean = paused.get()

    override fun endSession() {
        stop.set(true)
        delivered.set(true)
        paused.set(true)
        synchronized(gate) { runCatching { mic?.close() } }
    }

    /**
     * Wait up to [timeoutMs] for a capture that was told to stop to let go of the microphone, so the next one can open
     * it. A capture still running is left alone. Call outside any lock: it joins.
     */
    override fun awaitStopped(timeoutMs: Long) {
        val running = synchronized(gate) { thread }
        if (stop.get()) running?.join(timeoutMs)
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

private class PreRoll(private val capacity: Int) {
    private val buf = FloatArray(capacity.coerceAtLeast(1))
    private var count = 0
    private var at = 0

    fun add(samples: FloatArray) {
        for (sample in samples) {
            buf[at] = sample
            at = (at + 1) % buf.size
            if (count < buf.size) count++
        }
    }

    fun copy(): FloatArray {
        val out = FloatArray(count)
        val start = (at - count + buf.size) % buf.size
        for (index in 0 until count) out[index] = buf[(start + index) % buf.size]
        return out
    }
}

/**
 * Picks the voice-processing graph when [choice] is full duplex and the helper starts.
 * A failure rewrites [choice] to half duplex before the microphone opens.
 */
internal class HearingRoute(
    var choice: DuplexChoice,
    private val environment: Map<String, String>,
) {
    @Volatile private var graphUp = false

    fun arm(): DuplexChoice {
        if (choice.mode != HearingDuplex.FULL || graphUp) return choice
        if (!EmberAudio.tryLoad(environment)) {
            choice = DuplexChoice(HearingDuplex.HALF, "aec-unavailable")
            log(LogLevel.Info, "hearing", "duplex=half reason=aec-unavailable")
            return choice
        }
        val code = EmberAudio.startCode(HEARING_SAMPLE_RATE)
        if (code != EmberAudio.START_OK) {
            choice = DuplexChoice(HearingDuplex.HALF, "graph-failed")
            log(LogLevel.Info, "hearing", "duplex=half reason=graph-failed code=$code detail=${EmberAudio.lastError()}")
            return choice
        }
        graphUp = true
        return choice
    }

    fun openMic(): MicCapture = if (graphUp) GraphMic(::release) else openTargetMic()

    fun openLine(rate: Int): PcmLine = if (graphUp) GraphLine(rate) else openSpeaker(rate)

    fun release() {
        if (!graphUp) return
        graphUp = false
        EmberAudio.stop()
    }
}

private class GraphMic(private val onClose: () -> Unit) : MicCapture {
    override fun read(into: FloatArray): Int {
        val n = EmberAudio.read(into)
        if (n == 0) Thread.sleep(5)
        return n
    }
    override fun close() = onClose()
}

private class GraphLine(private val rate: Int) : PcmLine {
    override fun start() {}
    override fun write(pcm: ByteArray) {
        if (pcm.size < 2) return
        val shorts = ShortArray(pcm.size / 2)
        for (index in shorts.indices) {
            val lo = pcm[index * 2].toInt() and 0xff
            val hi = pcm[index * 2 + 1].toInt()
            shorts[index] = ((hi shl 8) or lo).toShort()
        }
        EmberAudio.play(shorts, rate)
    }
    override fun drain() {}
    override fun flush() = EmberAudio.flush()
    override fun stop() {}
    override fun close() {}
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
