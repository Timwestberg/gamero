package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import org.formulagen.wrapper.bridge.ChatVoice
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.EmberVoice
import org.formulagen.wrapper.voice.VoiceConfig
import org.formulagen.wrapper.voice.openPieceEngine
import org.formulagen.wrapper.voice.pocketEngineLinked

/** Chat owns when to speak; an audio failure must not discard its text. */
internal interface ChatSpeech : AutoCloseable {
    fun status(): ChatVoice
    fun speak(text: String)
    fun stop()
}

/** One serialized PocketTTS engine, with independently cancellable desktop playback. */
internal class DesktopSpeech(private val config: VoiceConfig?) : ChatSpeech {
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "hovel-voice").apply { isDaemon = true } }
    private var engine: EmberVoice? = null
    private var clip: Clip? = null
    private var generation = 0
    private var closed = false
    private val unavailable = ChatVoice("unavailable", "Local voice is not installed. Text chat is available.")
    private var state = if (config == null) unavailable else ChatVoice()

    override fun status(): ChatVoice = synchronized(gate) { state }

    /** Synthesize on the voice worker; cancelled audio is never submitted to the speaker. */
    override fun speak(text: String) = synchronized(gate) {
        if (closed) return@synchronized
        stopLocked()
        if (config == null) { state = unavailable; return@synchronized }
        val spoken = speechText(text)
        if (spoken.isBlank()) return@synchronized
        val ticket = generation
        state = ChatVoice("preparing")
        worker.execute {
            try {
                synchronized(gate) { if (closed || ticket != generation) return@execute }
                val voice = engine ?: EmberVoice(openPieceEngine(config), config).also { engine = it }
                val audio = voice.speak(spoken)
                val pcm = pcm16(audio.samples)
                val player = AudioSystem.getClip()
                try {
                    player.open(AudioFormat(audio.sampleRate.toFloat(), 16, 1, true, false), pcm, 0, pcm.size)
                    synchronized(gate) {
                        if (closed || ticket != generation) return@execute
                        clip = player; state = ChatVoice("speaking", if (text.length > MAX_SPOKEN_CHARS) "Reading the beginning of this reply. The full reply is shown above." else null)
                        player.start()
                    }
                    val deadline = System.nanoTime() + (player.microsecondLength + 5_000_000L) * 1000
                    while (synchronized(gate) { !closed && ticket == generation } && player.microsecondPosition < player.microsecondLength) {
                        check(System.nanoTime() < deadline) { "Audio device stalled" }
                        Thread.sleep(20)
                    }
                } finally { player.stop(); player.close() }
                synchronized(gate) { if (ticket == generation) { clip = null; state = ChatVoice() } }
            } catch (_: Exception) {
                synchronized(gate) {
                    if (ticket == generation && !closed) { clip = null; state = ChatVoice("error", "Voice playback failed. Your text reply is still available.") }
                }
            }
        }
    }

    /** Stop the device immediately, even while an earlier synthesis is finishing. */
    override fun stop() = synchronized(gate) { stopLocked() }
    private fun stopLocked() {
        generation++
        clip?.let { runCatching { it.stop(); it.close() } }; clip = null
        state = if (config == null) unavailable else ChatVoice()
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
