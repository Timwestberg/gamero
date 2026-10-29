package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import org.formulagen.wrapper.bridge.ChatVoice
import org.formulagen.wrapper.bridge.VoiceState
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.EmberVoice
import org.formulagen.wrapper.voice.KokoroVoice
import org.formulagen.wrapper.voice.PieceEngine
import org.formulagen.wrapper.voice.VoiceConfig
import org.formulagen.wrapper.voice.kokoroCatalog
import org.formulagen.wrapper.voice.kokoroVoice
import org.formulagen.wrapper.voice.kokoroVoices
import org.formulagen.wrapper.voice.openPieceEngine
import org.formulagen.wrapper.voice.pocketEngineLinked

/** Chat owns when to speak; an audio failure must not discard its text. */
internal interface ChatSpeech : AutoCloseable {
    fun status(): ChatVoice
    fun speak(text: String)
    fun stop()
    fun kokoroActive(): Boolean = false
    fun voiceChoices(): List<VoiceChoice> = emptyList()
    fun currentVoiceId(): String = ""
    fun previewVoice(id: String) {}
}

/**
 * One serialized PocketTTS engine, with independently cancellable desktop playback.
 * [openEngine] loads the native runtime on first use; tests replace it.
 */
internal class DesktopSpeech(
    config: VoiceConfig?,
    private val openLine: (Int) -> PcmLine = ::openSpeaker,
    private val openEngine: (VoiceConfig) -> PieceEngine = ::openPieceEngine,
) : ChatSpeech {
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-voice"))
    private var voiceConfig: VoiceConfig? = config
    private var engine: EmberVoice? = null
    private var line: PcmLine? = null
    /** Audio waiting for the speaker. Synthesis keeps filling this while a sentence is still playing. */
    private var playQueue: LinkedBlockingQueue<PlayChunk>? = null
    private val playbackEnd = PlayChunk(ByteArray(0), 0)
    private var generation = 0
    private var closed = false
    private val notInstalled = ChatVoice(VoiceState.UNAVAILABLE, "Local voice is not installed. Text chat is available.")
    /** Set once the native runtime fails to link; a later attempt would only fail again. */
    private var unavailable: ChatVoice? = if (voiceConfig == null) notInstalled else null
    private var state = unavailable ?: ChatVoice()

    override fun status(): ChatVoice = synchronized(gate) { state }

    /** Kokoro voices a person can pick. The id is what gets stored. */
    internal fun voices(): List<KokoroVoice> = kokoroVoices()

    override fun kokoroActive(): Boolean = synchronized(gate) { voiceConfig?.backend == VoiceConfig.KOKORO }

    override fun voiceChoices(): List<VoiceChoice> =
        if (kokoroActive()) kokoroVoices().map { VoiceChoice(it.id, it.name) } else emptyList()

    override fun currentVoiceId(): String = synchronized(gate) {
        voiceConfig?.kokoroVoiceId?.ifEmpty { null } ?: kokoroCatalog().defaultId
    }

    /** Switch now, remember it, and speak one short line in the new voice. */
    override fun previewVoice(id: String) {
        chooseVoice(id)
        val name = kokoroVoice(id)?.name ?: return
        speak(voicePreviewLine(name))
    }

    /**
     * Remember [id] for the next sentence and for the next launch.
     * The speaking engine is rebuilt on the voice worker after the current take stops.
     */
    internal fun chooseVoice(id: String) = synchronized(gate) {
        val voice = kokoroVoice(id) ?: throw IllegalArgumentException("Unknown Kokoro voice '$id'.")
        val current = voiceConfig ?: throw IllegalStateException("Local voice is not installed.")
        if (current.backend != VoiceConfig.KOKORO) {
            throw IllegalStateException("Voice choice applies to the Kokoro chat engine.")
        }
        if (current.kokoroVoiceFile.isNotEmpty()) writeKokoroVoiceChoice(Path.of(current.kokoroVoiceFile), voice.id)
        if (current.kokoroVoiceId == voice.id) return@synchronized
        voiceConfig = current.copy(kokoroVoiceId = voice.id)
        stopLocked()
        val previous = engine
        engine = null
        worker.execute { runCatching { previous?.release() } }
    }

    /** Synthesize on the voice worker; cancelled audio is never submitted to the speaker. */
    override fun speak(text: String) = synchronized(gate) {
        if (closed) return@synchronized
        stopLocked()
        if (voiceConfig == null || unavailable != null) return@synchronized
        val spoken = speechText(text)
        if (spoken.isBlank()) return@synchronized
        val ticket = generation
        state = ChatVoice(VoiceState.PREPARING)
        worker.execute {
            var stale: EmberVoice? = null
            val settings = synchronized(gate) {
                if (closed || ticket != generation) return@execute
                stale = refreshKokoroChoiceLocked()
                voiceConfig
            }
            stale?.let { runCatching { it.release() } }
            if (settings == null) return@execute
            try {
                val voice = synchronized(gate) {
                    if (closed || ticket != generation) return@execute
                    val active = voiceConfig ?: return@execute
                    engine ?: EmberVoice(openEngine(active), active).also { engine = it }
                }
                // The speaker thread blocks in the device buffer. Synthesis stays on this
                // worker, so the next sentence is generated while the current one plays.
                val queue = LinkedBlockingQueue<PlayChunk>()
                synchronized(gate) { playQueue = queue }
                val playbackFinished = CountDownLatch(1)
                val playback = loggingDaemonThreads("hovel-voice-play").newThread {
                    try {
                        playQueued(queue, ticket, settings, text)
                    } finally {
                        playbackFinished.countDown()
                    }
                }
                var playerStarted = false
                try {
                    playback.start()
                    playerStarted = true
                    voice.speakStreaming(spoken) { samples, rate ->
                        if (synchronized(gate) { closed || ticket != generation }) return@speakStreaming false
                        queue.put(PlayChunk(pcm16(samples), rate))
                        synchronized(gate) { !closed && ticket == generation }
                    }
                } finally {
                    queue.offer(playbackEnd)
                    // Stop closes the line, which unblocks a drain. Waiting out a long reply is correct.
                    if (playerStarted) playbackFinished.await() else playbackFinished.countDown()
                    synchronized(gate) { if (playQueue === queue) playQueue = null }
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
    /** A voice file edited between sentences is picked up here. An env pin stays put. */
    private fun refreshKokoroChoiceLocked(): EmberVoice? {
        val current = voiceConfig ?: return null
        if (current.backend != VoiceConfig.KOKORO || current.kokoroVoiceFromEnv) return null
        if (current.kokoroVoiceFile.isEmpty()) return null
        val id = readKokoroVoiceChoice(Path.of(current.kokoroVoiceFile)) ?: return null
        if (kokoroVoice(id) == null || id == current.kokoroVoiceId) return null
        voiceConfig = current.copy(kokoroVoiceId = id)
        val previous = engine
        engine = null
        return previous
    }

    private fun stopLocked() {
        generation++
        engine?.cancel()
        playQueue?.offer(playbackEnd)
        line?.let { runCatching { it.flush(); it.stop(); it.close() } }
        line = null
        state = unavailable ?: ChatVoice()
    }

    /**
     * Writes queued sentences to the speaker. Blocks while the device buffer is full,
     * which is what lets the synthesizer stay a sentence ahead.
     */
    private fun playQueued(
        queue: LinkedBlockingQueue<PlayChunk>,
        ticket: Int,
        settings: VoiceConfig,
        text: String,
    ) {
        var player: PcmLine? = null
        var started = false
        var pending = ByteArray(0)
        fun stopped() = synchronized(gate) { closed || ticket != generation }
        try {
            while (!stopped()) {
                val chunk = queue.take()
                if (chunk === playbackEnd || stopped()) break
                val current = player ?: openLine(chunk.rate).also {
                    player = it
                    synchronized(gate) { line = it }
                }
                if (!started) {
                    pending += chunk.pcm
                    val queuedMs = if (chunk.rate > 0) pending.size / 2 * 1000 / chunk.rate else 0
                    // Once per reply. Later sentences are already being synthesized on the worker.
                    if (queuedMs >= settings.startAfterMs || chunk.pcm.isNotEmpty()) {
                        if (stopped()) break
                        current.start()
                        started = true
                        synchronized(gate) {
                            if (ticket == generation) {
                                state = ChatVoice(
                                    VoiceState.SPEAKING,
                                    if (text.length > MAX_SPOKEN_CHARS) {
                                        "Reading the beginning of this reply. The full reply is shown above."
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        if (stopped()) break
                        current.write(pending)
                        pending = ByteArray(0)
                    }
                } else {
                    current.write(chunk.pcm)
                }
            }
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
        } finally {
            val live = synchronized(gate) { ticket == generation && !closed }
            if (live) runCatching { player?.drain() }
            runCatching { player?.stop() }
            runCatching { player?.close() }
            synchronized(gate) { if (line === player) line = null }
        }
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

private class PlayChunk(val pcm: ByteArray, val rate: Int)

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
    // A full second, so a short sentence is not stuck behind a tiny device buffer.
    // Synthesis no longer waits on this write; the player thread does.
    line.open(format, sampleRate * 2)
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
    if (!engineLinked) {
        log(LogLevel.Warn, "voice", "Spoken replies are off. Run scripts/fetch-voice.sh, then rebuild.")
        return null
    }
    val backend = environment["HOVEL_VOICE_BACKEND"]?.trim()?.takeIf { it.isNotEmpty() } ?: VoiceConfig.KOKORO
    return when (backend) {
        VoiceConfig.KOKORO -> kokoroDesktopConfig(environment)
        VoiceConfig.SHERPA -> pocketDesktopConfig(environment)
        else -> {
            log(LogLevel.Warn, "voice", "Spoken replies are off. HOVEL_VOICE_BACKEND must be kokoro or sherpa.")
            null
        }
    }
}

private val KOKORO_FILES = listOf("model.onnx", "voices-blends.bin", "tokens.txt", "lexicon-us-en.txt")

private fun kokoroDesktopConfig(environment: Map<String, String>): VoiceConfig? {
    val directory = environment["HOVEL_KOKORO_MODEL"]?.let(Path::of)
    if (directory == null || !Files.isDirectory(directory) || KOKORO_FILES.any { !Files.isRegularFile(directory.resolve(it)) }) {
        log(LogLevel.Warn, "voice", "Spoken replies are off. Run scripts/fetch-voice.sh, then rebuild.")
        return null
    }
    val choice = kokoroChoiceFile(environment)
    val fromEnv = environment["HOVEL_KOKORO_VOICE"]?.trim()?.takeIf { it.isNotEmpty() && kokoroVoice(it) != null }
    return VoiceConfig(
        backend = VoiceConfig.KOKORO,
        numThreads = VoiceConfig.KOKORO_THREADS,
        kokoroModelDir = directory.toAbsolutePath().toString(),
        kokoroVoiceId = resolveKokoroVoiceId(environment, choice),
        kokoroVoiceFile = choice.toAbsolutePath().toString(),
        kokoroVoiceFromEnv = fromEnv != null,
    )
}

private fun pocketDesktopConfig(environment: Map<String, String>): VoiceConfig? {
    val directory = environment["HOVEL_VOICE_MODEL"]?.let(Path::of)
    val voice = environment["HOVEL_VOICE_REFERENCE"]?.let(Path::of)
    if (directory == null || voice == null || !Files.isDirectory(directory) || !Files.isRegularFile(voice)) {
        log(LogLevel.Warn, "voice", "Pocket voice is off. Set HOVEL_VOICE_MODEL and HOVEL_VOICE_REFERENCE.")
        return null
    }
    return VoiceConfig(
        backend = VoiceConfig.SHERPA,
        modelDir = directory.toAbsolutePath().toString(),
        voiceWav = voice.toAbsolutePath().toString(),
    )
}

internal fun kokoroChoiceFile(environment: Map<String, String>): Path {
    environment["HOVEL_KOKORO_VOICE_FILE"]?.let { return Path.of(it) }
    val home = environment["HOME"] ?: System.getProperty("user.home")
    return Path.of(home, ".gamero", "kokoro-voice")
}

internal fun readKokoroVoiceChoice(file: Path): String? {
    if (!Files.isRegularFile(file)) return null
    return Files.readString(file).lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun writeKokoroVoiceChoice(file: Path, id: String) {
    val parent = file.parent
    if (parent != null) Files.createDirectories(parent)
    Files.writeString(file, id + "\n")
}

internal fun resolveKokoroVoiceId(environment: Map<String, String>, choiceFile: Path?): String {
    val fromEnv = environment["HOVEL_KOKORO_VOICE"]?.trim()?.takeIf { it.isNotEmpty() }
    if (fromEnv != null && kokoroVoice(fromEnv) != null) return fromEnv
    val fromFile = choiceFile?.let(::readKokoroVoiceChoice)
    if (fromFile != null && kokoroVoice(fromFile) != null) return fromFile
    return kokoroCatalog().defaultId
}
