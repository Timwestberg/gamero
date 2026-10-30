package org.formulagen.wrapper.conversation.voice

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import org.formulagen.wrapper.conversation.ChatVoice
import org.formulagen.wrapper.conversation.VoiceState
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.EmberVoice
import org.formulagen.wrapper.voice.FILLER_LINES
import org.formulagen.wrapper.voice.FillerPcm
import org.formulagen.wrapper.voice.HEARING_ENDPOINT_SILENCE_MS
import org.formulagen.wrapper.voice.HEARING_RESUME_GUARD_MS
import org.formulagen.wrapper.voice.KokoroVoice
import org.formulagen.wrapper.voice.PieceEngine
import org.formulagen.wrapper.voice.SpeechCancelled
import org.formulagen.wrapper.voice.VoiceConfig
import org.formulagen.wrapper.voice.kokoroCatalog
import org.formulagen.wrapper.voice.kokoroVoice
import org.formulagen.wrapper.voice.kokoroVoices
import org.formulagen.wrapper.voice.openPieceEngine
import org.formulagen.wrapper.voice.pocketEngineLinked
import org.formulagen.wrapper.voice.readFillerSettings
import org.formulagen.wrapper.util.loggingDaemonThreads

/** Chat owns when to speak; an audio failure must not discard its text. */
internal interface ChatSpeech : AutoCloseable {
    fun status(): ChatVoice
    fun speak(text: String)
    fun stop()
    fun kokoroActive(): Boolean = false
    fun voiceChoices(): List<VoiceChoice> = emptyList()
    fun currentVoiceId(): String = ""
    fun previewVoice(id: String) {}
    /** Called after the spoken state changes, and never while this speech object holds its own lock. */
    fun onVoice(listener: () -> Unit) {}
    /** Load the voice ahead of the first sentence, off the caller's thread, whatever the filler choice. */
    fun warm() {}
    fun setFillersEnabled(enabled: Boolean) {}
    /** Synthesize [lines] for every Kokoro blend and keep the PCM. A later play does not call the model. */
    fun warmFillers(lines: List<String>) {}
    fun fillerPcm(voiceId: String, line: String): FillerPcm? = null
    /** Play a cached clip. [onDone] runs after the device finishes or is flushed, off the caller thread. */
    fun playClip(pcm: ByteArray, sampleRate: Int, onDone: () -> Unit = {}) {}
    /**
     * Append one sentence to the live reply. False when this speaker only speaks a finished reply.
     * Does not flush and does not bump the playback generation.
     */
    fun enqueue(piece: String): Boolean = false
    /** The live reply has no more sentences. Playback drains what is already queued. */
    fun endStream() {}
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
    /** The voice worker's thread, the only one that loads an engine ([loadedEngine]). A worker that died is replaced. */
    @Volatile private var workerThread: Thread? = null
    private val worker = Executors.newSingleThreadExecutor { task ->
        loggingDaemonThreads(VOICE_WORKER).newThread(task).also { workerThread = it }
    }
    private var voiceConfig: VoiceConfig? = config
    private var engine: EmberVoice? = null
    /** Bumped whenever [engine] is dropped for another voice, so a load that started before keeps nothing. */
    private var engineEpoch = 0
    private var line: PcmLine? = null
    /** Audio waiting for the speaker. Synthesis keeps filling this while a sentence is still playing. */
    private var playQueue: LinkedBlockingQueue<PlayChunk>? = null
    private val playbackEnd = PlayChunk(ByteArray(0), 0)
    private var generation = 0
    private var streamTicket = -1
    private var streamIndex = 0
    private var enqueueStartedNs = 0L
    /** Milliseconds from the first [enqueue] of a reply to the speaker starting. -1 until then. */
    @Volatile var enqueueToSpeakerMs: Long = -1
    private var closed = false
    private val fillerCache = HashMap<String, Map<String, FillerPcm>>()
    private var onVoice: (() -> Unit)? = null
    private val notInstalled = ChatVoice(VoiceState.UNAVAILABLE, "Local voice is not installed. Text chat is available.")
    /** Set once the native runtime fails to link; a later attempt would only fail again. */
    private var unavailable: ChatVoice? = if (voiceConfig == null) notInstalled else null
    private var state = unavailable ?: ChatVoice()

    override fun status(): ChatVoice = synchronized(gate) { state }

    override fun onVoice(listener: () -> Unit) {
        synchronized(gate) { onVoice = listener }
    }

    /** Load the engine on the voice worker, so Talk's first sentence does not wait for it. A failure waits for the first speak. */
    override fun warm() = synchronized(gate) {
        if (closed || voiceConfig == null || unavailable != null) return@synchronized
        runCatching {
            worker.execute {
                runCatching { loadedEngine() }.onFailure { if (it is VirtualMachineError) throw it }
            }
        }
        Unit
    }

    /**
     * The engine, loaded if there is none. It runs only on the voice worker (checked), so a model load never blocks a
     * caller; and the load runs outside [gate], so a caller on the UI thread ([status], [stop], [enqueue]) never waits on
     * one either. A load the voice changed under, or that finished after [close], is released; the next voice is loaded
     * instead. Null once closed or with no voice installed.
     */
    private fun loadedEngine(): EmberVoice? {
        check(Thread.currentThread() === workerThread) { "The voice engine loads only on the voice worker" }
        while (true) {
            val (active, epoch) = synchronized(gate) {
                if (closed) return null
                engine?.let { return it }
                (voiceConfig ?: return null) to engineEpoch
            }
            val loaded = EmberVoice(openEngine(active), active)
            val kept = synchronized(gate) {
                if (!closed && engine == null && epoch == engineEpoch) loaded.also { engine = it } else null
            }
            if (kept != null) return kept
            runCatching { loaded.release() }
        }
    }

    override fun setFillersEnabled(enabled: Boolean) = synchronized(gate) {
        val current = voiceConfig ?: return@synchronized
        voiceConfig = current.copy(fillersEnabled = enabled)
        if (enabled) warmFillers(FILLER_LINES)
    }

    /** Caller holds [gate]. The listener runs later, so it can take the chat lock safely. */
    private fun touchState(next: ChatVoice) {
        if (state == next) return
        state = next
        val listener = onVoice ?: return
        runCatching { worker.execute { listener() } }
    }

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

    override fun warmFillers(lines: List<String>) = synchronized(gate) {
        val settings = voiceConfig ?: return@synchronized
        if (closed || !settings.fillersEnabled || settings.backend != VoiceConfig.KOKORO || lines.isEmpty()) return@synchronized
        if (kokoroVoices().all { fillerCache[it.id]?.keys?.containsAll(lines) == true }) return@synchronized
        worker.execute { warmFillersOnWorker(lines) }
    }

    override fun fillerPcm(voiceId: String, line: String): FillerPcm? = synchronized(gate) {
        fillerCache[voiceId]?.get(line)
    }

    override fun playClip(pcm: ByteArray, sampleRate: Int, onDone: () -> Unit) = synchronized(gate) {
        if (closed || pcm.isEmpty() || sampleRate <= 0) {
            onDone()
            return@synchronized
        }
        stopLocked()
        val ticket = generation
        touchState(ChatVoice(VoiceState.SPEAKING))
        worker.execute {
            var player: PcmLine? = null
            try {
                if (synchronized(gate) { closed || ticket != generation }) return@execute
                val opened = openLine(sampleRate)
                player = opened
                val live = synchronized(gate) {
                    if (closed || ticket != generation) false else {
                        line = opened
                        true
                    }
                }
                if (!live) return@execute
                opened.start()
                opened.write(pcm)
                if (synchronized(gate) { ticket == generation && !closed }) runCatching { opened.drain() }
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
            } finally {
                runCatching { player?.stop() }
                runCatching { player?.close() }
                synchronized(gate) {
                    if (line === player) line = null
                    if (ticket == generation && !closed) touchState(unavailable ?: ChatVoice())
                }
                onDone()
            }
        }
    }

    private fun warmFillersOnWorker(lines: List<String>) {
        val voice = loadedEngine() ?: return
        val selected = synchronized(gate) {
            voiceConfig?.kokoroVoiceId?.ifEmpty { null } ?: kokoroCatalog().defaultId
        }
        val ids = listOf(selected) + kokoroVoices().map { it.id }.filter { it != selected }
        for (id in ids) {
            if (synchronized(gate) { closed || fillerCache[id]?.keys?.containsAll(lines) == true }) continue
            val clips = LinkedHashMap<String, FillerPcm>()
            try {
                for (line in lines) {
                    val audio = voice.synthesizeLine(line, id)
                    if (audio.samples.isNotEmpty()) clips[line] = FillerPcm(pcm16(audio.samples), audio.sampleRate)
                }
            } catch (cancelled: SpeechCancelled) {
                if (clips.isNotEmpty()) synchronized(gate) { fillerCache[id] = (fillerCache[id] ?: emptyMap()) + clips }
                return
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                log(LogLevel.Warn, "hearing", "filler warm failed voice=$id ${error.javaClass.simpleName}")
                return
            }
            synchronized(gate) { fillerCache[id] = clips }
        }
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
        engineEpoch++
        worker.execute { runCatching { previous?.release() } }
        warmFillers(FILLER_LINES)
    }

    /** Synthesize on the voice worker; cancelled audio is never submitted to the speaker. */
    override fun speak(text: String) = synchronized(gate) {
        if (closed) return@synchronized
        stopLocked()
        if (voiceConfig == null || unavailable != null) return@synchronized
        val spoken = speechText(text)
        if (spoken.isBlank()) return@synchronized
        val ticket = generation
        touchState(ChatVoice(VoiceState.PREPARING))
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
                if (synchronized(gate) { closed || ticket != generation }) return@execute
                val voice = loadedEngine() ?: return@execute
                if (synchronized(gate) { closed || ticket != generation }) return@execute
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
                synchronized(gate) { if (ticket == generation) { line = null; touchState(ChatVoice()) } }
            } catch (error: Throwable) {
                // A cancel bumps the generation and has already returned the button to idle.
                // Any other failure must not leave the row on Preparing or Speaking.
                val linkage = error is LinkageError
                val cancelled = error is SpeechCancelled
                synchronized(gate) {
                    if (linkage) unavailable = ChatVoice(VoiceState.UNAVAILABLE, "Local voice could not start on this Mac. Text chat is available.")
                    if (ticket == generation) line = null
                    if (!closed && ticket == generation) {
                        touchState(when {
                            linkage -> unavailable ?: ChatVoice()
                            cancelled -> ChatVoice()
                            else -> unavailable ?: ChatVoice(VoiceState.ERROR, "Voice playback failed. Your text reply is still available.")
                        })
                    }
                }
                if (error is VirtualMachineError) throw error
            }
        }
    }

    /** Stop the device immediately, even while an earlier synthesis is finishing. */
    override fun stop() = synchronized(gate) { stopLocked() }

    override fun enqueue(piece: String): Boolean {
        return synchronized(gate) {
            if (closed || piece.isBlank() || voiceConfig == null || unavailable != null) return@synchronized false
            ensureStreamLocked()
            val ticket = generation
            val queue = playQueue ?: return@synchronized false
            val settings = voiceConfig ?: return@synchronized false
            val index = streamIndex
            streamIndex += 1
            if (index == 0) enqueueStartedNs = System.nanoTime()
            worker.execute { streamPiece(piece, ticket, queue, settings, index) }
            true
        }
    }

    override fun endStream() {
        synchronized(gate) {
            val queue = playQueue ?: return
            val ticket = generation
            if (streamTicket != ticket) return
            worker.execute {
                if (synchronized(gate) { ticket == generation && playQueue === queue }) queue.offer(playbackEnd)
            }
        }
    }

    /**
     * Caller holds [gate]. A stream already open for this generation is left alone. A new one picks up a voice chosen
     * meanwhile in the voice file, by another speaker included; the old engine is released after any synthesis queued.
     */
    private fun ensureStreamLocked() {
        if (playQueue != null && streamTicket == generation) return
        refreshKokoroChoiceLocked()?.let { stale -> runCatching { worker.execute { runCatching { stale.release() } } } }
        val ticket = generation
        streamTicket = ticket
        streamIndex = 0
        enqueueToSpeakerMs = -1
        val queue = LinkedBlockingQueue<PlayChunk>()
        playQueue = queue
        val settings = voiceConfig ?: return
        touchState(ChatVoice(VoiceState.PREPARING))
        val playback = loggingDaemonThreads("hovel-voice-play").newThread {
            playQueued(queue, ticket, settings, "")
        }
        try {
            playback.start()
        } catch (error: Throwable) {
            playQueue = null
            streamTicket = -1
            touchState(ChatVoice())
            if (error is VirtualMachineError) throw error
        }
    }

    private fun streamPiece(
        piece: String,
        ticket: Int,
        queue: LinkedBlockingQueue<PlayChunk>,
        settings: VoiceConfig,
        index: Int,
    ) {
        if (synchronized(gate) { closed || ticket != generation }) return
        try {
            val voice = loadedEngine() ?: return
            if (synchronized(gate) { closed || ticket != generation }) return
            voice.speakStreaming(piece) { samples, rate ->
                if (synchronized(gate) { closed || ticket != generation }) return@speakStreaming false
                if (index > 0 && settings.resolvedSentenceGapMs > 0 && rate > 0) {
                    queue.put(PlayChunk(ByteArray(rate * settings.resolvedSentenceGapMs / 1000 * 2), rate))
                }
                queue.put(PlayChunk(pcm16(samples), rate))
                true
            }
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            val cancelled = error is SpeechCancelled
            synchronized(gate) {
                if (!cancelled && ticket == generation && !closed) {
                    touchState(unavailable ?: ChatVoice(VoiceState.ERROR, "Voice playback failed. Your text reply is still available."))
                }
            }
        }
    }
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
        engineEpoch++
        return previous
    }

    private fun stopLocked() {
        generation++
        engine?.cancel()
        playQueue?.offer(playbackEnd)
        line?.let { runCatching { it.flush(); it.stop(); it.close() } }
        line = null
        touchState(unavailable ?: ChatVoice())
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
                        val startedNs = enqueueStartedNs
                        if (startedNs != 0L) enqueueToSpeakerMs = (System.nanoTime() - startedNs) / 1_000_000
                        synchronized(gate) {
                            if (ticket == generation) {
                                touchState(ChatVoice(
                                    VoiceState.SPEAKING,
                                    if (text.length > MAX_SPOKEN_CHARS) {
                                        "Reading the beginning of this reply. The full reply is shown above."
                                    } else {
                                        null
                                    },
                                ))
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
            synchronized(gate) {
                if (line === player) line = null
                if (ticket == generation && streamTicket == ticket && playQueue === queue && !closed) {
                    playQueue = null
                    streamTicket = -1
                    touchState(unavailable ?: ChatVoice())
                }
            }
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

/** The voice worker's thread name. */
internal const val VOICE_WORKER = "hovel-voice"

/** Keep code blocks, list markers, and long machine output out of spoken replies. */
internal fun speechText(text: String): String = textBeforeUnclosedFence(text)
    .replace(Regex("```[\\s\\S]*?```"), "Code is shown in the chat.")
    .replace(Regex("\\[([^]]+)\\]\\([^)]+\\)"), "$1")
    .replace(Regex("(?m)^[ \\t]{0,3}#{1,6}[ \\t]+"), "")
    .replace(Regex("(?m)^[ \\t]{0,3}(?:[-*+]|\\d+[.)])[ \\t]+"), "")
    .replace("`", "")
    .replace("**", "")
    .replace(Regex("[ \\t]*\\n+[ \\t]*"), " ")
    .take(MAX_SPOKEN_CHARS)
    .trim()

/** An opening fence with no close is not spoken, so a half-written block stays in the bubble only. */
internal fun textBeforeUnclosedFence(raw: String): String {
    var fences = 0
    var lastOpen = -1
    var index = 0
    while (index <= raw.length - 3) {
        if (raw.startsWith("```", index)) {
            fences += 1
            if (fences % 2 == 1) lastOpen = index
            index += 3
        } else {
            index += 1
        }
    }
    return if (fences % 2 == 1 && lastOpen >= 0) raw.substring(0, lastOpen) else raw
}

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
    val fillers = readFillerSettings(environment, readSavedFillers(fillerChoiceFile(environment)))
    return VoiceConfig(
        backend = VoiceConfig.KOKORO,
        numThreads = VoiceConfig.KOKORO_THREADS,
        kokoroModelDir = directory.toAbsolutePath().toString(),
        kokoroVoiceId = resolveKokoroVoiceId(environment, choice),
        kokoroVoiceFile = choice.toAbsolutePath().toString(),
        kokoroVoiceFromEnv = fromEnv != null,
        fillersEnabled = fillers.enabled,
        fillerThresholdMs = fillers.thresholdMs,
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

internal fun fillerChoiceFile(environment: Map<String, String>): Path {
    environment["HOVEL_VOICE_FILLERS_FILE"]?.let { return Path.of(it) }
    val home = environment["HOME"] ?: System.getProperty("user.home")
    return Path.of(home, ".gamero", "voice-fillers")
}

/** Missing file means off. `on` or `1` is on. */
internal fun readSavedFillers(file: Path): Boolean {
    if (!Files.isRegularFile(file)) return false
    val word = Files.readString(file).lineSequence().firstOrNull()?.trim()?.lowercase()
    return word == "on" || word == "1" || word == "true"
}

internal fun writeSavedFillers(file: Path, enabled: Boolean) {
    val parent = file.parent
    if (parent != null) Files.createDirectories(parent)
    Files.writeString(file, if (enabled) "on\n" else "off\n")
}

/** Trailing quiet that ends a hands-free turn. Clamped so a bad env value cannot stick the mic. */
internal fun hearingSilenceMs(environment: Map<String, String>): Int =
    environment["HOVEL_HEARING_SILENCE_MS"]?.toIntOrNull()?.coerceIn(200, 5_000) ?: HEARING_ENDPOINT_SILENCE_MS

/** Quiet after Ember stops before the microphone opens again. */
internal fun hearingGuardMs(environment: Map<String, String>): Int =
    environment["HOVEL_HEARING_GUARD_MS"]?.toIntOrNull()?.coerceIn(0, 2_000) ?: HEARING_RESUME_GUARD_MS

internal fun resolveKokoroVoiceId(environment: Map<String, String>, choiceFile: Path?): String {
    val fromEnv = environment["HOVEL_KOKORO_VOICE"]?.trim()?.takeIf { it.isNotEmpty() }
    if (fromEnv != null && kokoroVoice(fromEnv) != null) return fromEnv
    val fromFile = choiceFile?.let(::readKokoroVoiceChoice)
    if (fromFile != null && kokoroVoice(fromFile) != null) return fromFile
    return kokoroCatalog().defaultId
}
