package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import org.formulagen.wrapper.bridge.ChatVoice
import org.formulagen.wrapper.bridge.VoiceState
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.EmberVoice
import org.formulagen.wrapper.voice.PieceEngine
import org.formulagen.wrapper.voice.VoiceConfig
import org.formulagen.wrapper.voice.openPieceEngine
import org.formulagen.wrapper.voice.pocketEngineLinked

/** Chat owns when to speak; an audio failure must not discard its text. */
internal interface ChatSpeech : AutoCloseable {
    fun status(): ChatVoice
    fun speak(text: String)
    fun stop()
}

/**
 * One serialized PocketTTS engine, with independently cancellable desktop playback.
 * [openEngine] loads the native runtime on first use; tests replace it.
 */
internal class DesktopSpeech(
    private val config: VoiceConfig?,
    private val openLine: (Int) -> PcmLine = ::openSpeaker,
    private val openEngine: (VoiceConfig) -> PieceEngine = ::openPieceEngine,
) : ChatSpeech {
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-voice"))
    private var engine: EmberVoice? = null
    private var line: PcmLine? = null
    private var generation = 0
    private var closed = false
    private val notInstalled = ChatVoice(VoiceState.UNAVAILABLE, "Local voice is not installed. Text chat is available.")
    /** Set once the native runtime fails to link; a later attempt would only fail again. */
    private var unavailable: ChatVoice? = if (config == null) notInstalled else null
    private var state = unavailable ?: ChatVoice()

    override fun status(): ChatVoice = synchronized(gate) { state }

    /** Synthesize on the voice worker; cancelled audio is never submitted to the speaker. */
    override fun speak(text: String) = synchronized(gate) {
        if (closed) return@synchronized
        stopLocked()
        val settings = config
        if (settings == null || unavailable != null) return@synchronized
        val spoken = speechText(text)
        if (spoken.isBlank()) return@synchronized
        val ticket = generation
        state = ChatVoice(VoiceState.PREPARING)
        worker.execute {
            try {
                synchronized(gate) { if (closed || ticket != generation) return@execute }
                val voice = engine ?: EmberVoice(openEngine(settings), settings).also { engine = it }
                var player: PcmLine? = null
                var started = false
                var pending = ByteArray(0)
                try {
                    voice.speakStreaming(spoken) { samples, rate ->
                        if (synchronized(gate) { closed || ticket != generation }) return@speakStreaming false
                        val current = player ?: openLine(rate).also { player = it; synchronized(gate) { line = it } }
                        val pcm = pcm16(samples)
                        if (!started) {
                            pending = pending + pcm
                            val queuedMs = if (rate > 0) pending.size / 2 * 1000 / rate else 0
                            // The sentence has finished synthesizing. Start once 200 ms is queued,
                            // or immediately when the sentence is shorter than that.
                            if (queuedMs >= settings.startAfterMs || pcm.isNotEmpty()) {
                                if (synchronized(gate) { closed || ticket != generation }) return@speakStreaming false
                                current.start()
                                started = true
                                synchronized(gate) {
                                    state = ChatVoice(
                                        VoiceState.SPEAKING,
                                        if (text.length > MAX_SPOKEN_CHARS) {
                                            "Reading the beginning of this reply. The full reply is shown above."
                                        } else {
                                            null
                                        },
                                    )
                                }
                                current.write(pending)
                                pending = ByteArray(0)
                            }
                        } else {
                            current.write(pcm)
                        }
                        synchronized(gate) { !closed && ticket == generation }
                    }
                } finally {
                    player?.let { runCatching { it.drain(); it.stop(); it.close() } }
                }
                synchronized(gate) { if (ticket == generation) { line = null; state = ChatVoice() } }
            } catch (error: Throwable) {
                // UnsatisfiedLinkError or ExceptionInInitializerError: the native runtime cannot load on this Mac.
                val linkage = error is LinkageError
                synchronized(gate) {
                    if (linkage) unavailable = ChatVoice(VoiceState.UNAVAILABLE, "Local voice could not start on this Mac. Text chat is available.")
                    if (ticket == generation) line = null
                    if (!closed && (linkage || ticket == generation)) {
                        state = unavailable ?: ChatVoice(VoiceState.ERROR, "Voice playback failed. Your text reply is still available.")
                    }
                }
                if (error is VirtualMachineError) throw error
            }
        }
    }

    /** Stop the device immediately, even while an earlier synthesis is finishing. */
    override fun stop() = synchronized(gate) { stopLocked() }
    private fun stopLocked() {
        generation++
        engine?.cancel()
        line?.let { runCatching { it.flush(); it.stop(); it.close() } }
        line = null
        state = unavailable ?: ChatVoice()
    }

    /** Release only after the serialized engine has finished any in-flight synthesis. */
    override fun close() = synchronized(gate) {
        if (closed) return@synchronized
        stopLocked(); closed = true
        worker.execute { runCatching { engine?.release() }; engine = null }
        worker.shutdown()
    }
}

private const val MAX_SPOKEN_CHARS = 1200

/** Keep code blocks and long machine output out of spoken replies. */
internal fun speechText(text: String): String = text.replace(Regex("```[\\s\\S]*?```"), "Code is shown in the chat.")
    .replace(Regex("\\[([^]]+)\\]\\([^)]+\\)"), "$1").replace("`", "").replace("**", "")
    .take(MAX_SPOKEN_CHARS).trim()

/** Clamp invalid samples before writing little-endian signed PCM. */
internal fun pcm16(samples: FloatArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
    samples.forEachIndexed { index, sample ->
        val value = ((if (sample.isFinite()) sample else 0f).coerceIn(-1f, 1f) * 32767).toInt()
        bytes[index * 2] = value.toByte(); bytes[index * 2 + 1] = (value shr 8).toByte()
    }
}

internal interface PcmLine {
    fun start()
    fun write(pcm: ByteArray)
    fun drain()
    fun flush()
    fun stop()
    fun close()
}

internal fun openSpeaker(sampleRate: Int): PcmLine {
    val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
    val line = AudioSystem.getSourceDataLine(format)
    line.open(format)
    return SourcePcmLine(line)
}

private class SourcePcmLine(private val line: SourceDataLine) : PcmLine {
    override fun start() = line.start()
    override fun write(pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            val wrote = line.write(pcm, offset, pcm.size - offset)
            if (wrote <= 0) break
            offset += wrote
        }
    }
    override fun drain() = line.drain()
    override fun flush() = line.flush()
    override fun stop() = line.stop()
    override fun close() = line.close()
}

/** Host configuration supplies model paths; no path or reference clip comes from the page. */
internal fun desktopVoiceConfig(environment: Map<String, String>, engineLinked: Boolean = pocketEngineLinked()): VoiceConfig? {
    val directory = environment["HOVEL_VOICE_MODEL"]?.let(Path::of)
    val voice = environment["HOVEL_VOICE_REFERENCE"]?.let(Path::of)
    if (!engineLinked || directory == null || voice == null || !Files.isDirectory(directory) || !Files.isRegularFile(voice)) {
        log(LogLevel.Warn, "voice", "Spoken replies are off. Run scripts/fetch-voice.sh, then rebuild.")
        return null
    }
    return VoiceConfig(modelDir = directory.toAbsolutePath().toString(), voiceWav = voice.toAbsolutePath().toString())
}
