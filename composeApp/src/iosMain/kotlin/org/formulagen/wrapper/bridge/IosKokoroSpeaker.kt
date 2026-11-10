@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
package org.formulagen.wrapper.bridge

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.voice.EmberVoice
import org.formulagen.wrapper.voice.VoiceConfig
import org.formulagen.wrapper.voice.kokoroFilesMissing
import org.formulagen.wrapper.voice.kokoroLinked
import org.formulagen.wrapper.voice.openPieceEngine
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.posix.memcpy
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch

private const val TAG = "IosKokoroSpeaker"

/** The folder of the app bundle that holds Kokoro's files (`scripts/ios-voice.sh` puts them there). */
private const val BUNDLE_FOLDER = "kokoro"

/** Overrides [KOKORO_THREADS] at launch, to find what a phone runs fastest. */
private const val THREADS_ENV = "GAMERO_KOKORO_THREADS"

/**
 * How many threads Kokoro generates on. An iPhone has two fast cores and four slow ones; the Mac's four threads would
 * put half the work on slow cores, where the fast two then wait for them.
 */
private const val KOKORO_THREADS = 2

/** Loading the model and generating the first sentence come before the first sound, once per launch. */
private const val KOKORO_START_BUDGET_MS = 30_000L

/** The pause between two sentences, as on the Mac ([VoiceConfig.resolvedSentenceGapMs]). */
private const val SENTENCE_GAP_MS = 160

/** A stretch of silence that marks an utterance's end: when it has played, everything before it has. */
private const val END_MARK_MS = 20

private const val MS_PER_SECOND = 1000

/**
 * The phone's speaker: Kokoro where this build links it and the app bundle carries its model, Apple's voice
 * otherwise. Kokoro falls back to Apple's voice for good if its model cannot load.
 */
internal fun iosSpeaker(): IosUtteranceSpeaker {
    val apple = AppleSpeaker()
    val dir = NSBundle.mainBundle.resourcePath?.let { "$it/$BUNDLE_FOLDER" }
    if (dir == null || !kokoroLinked() || kokoroFilesMissing(dir).isNotEmpty()) {
        log(LogLevel.Info, TAG, "Kokoro is not in this build; the companion speaks with the system voice")
        return apple
    }
    val threads = (NSProcessInfo.processInfo.environment[THREADS_ENV] as? String)?.toIntOrNull() ?: KOKORO_THREADS
    return IosKokoroSpeaker(dir, threads.coerceIn(1, NSProcessInfo.processInfo.activeProcessorCount.toInt()), apple)
}

/**
 * Kokoro 82M, the Mac's voice, on the phone. Each utterance goes through the Mac's own pipeline ([EmberVoice]: the
 * same normalizing, lexicons, voices and silence trimming), one at a time on a background thread, and its sound is
 * handed to an [AVAudioPlayerNode] as it is made, so the next sentence is generated while this one plays.
 *
 * The model (about 330 MB) loads at the first utterance, or at [warm], and stays until iOS warns that memory is
 * short while nothing is being said. If it cannot load, [fallback] speaks from then on.
 *
 * [say], [flush] and the reports are on the main thread; [generation] tells the background thread that what it is
 * working on was flushed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class IosKokoroSpeaker(
    private val modelDir: String,
    private val threads: Int,
    private val fallback: IosUtteranceSpeaker,
) : IosUtteranceSpeaker {
    /** One utterance in line. [sound] is true once any of it was handed to the player. */
    private class Spoken(val generation: Int, val text: String, val started: () -> Unit, val ended: () -> Unit) {
        var sound = false
        var reported = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val synthesis = Dispatchers.Default.limitedParallelism(1)
    private val work = Channel<Spoken>(Channel.UNLIMITED)
    private val generation = AtomicInt(0)
    private val player = KokoroPlayer()
    /** The utterances not yet heard to their end, oldest first. Main thread. */
    private val line = ArrayDeque<Spoken>()
    /** False once the model could not load: [fallback] speaks. Main thread. */
    private var usable = true
    /** The loaded voice. Written on the synthesis thread; read on the main thread only to cancel. */
    @Volatile
    private var voice: EmberVoice? = null
    private val memoryWarning = NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationDidReceiveMemoryWarningNotification, null, NSOperationQueue.mainQueue,
    ) { if (line.isEmpty()) scope.launch(synthesis) { unload() } }

    init {
        scope.launch(synthesis) { for (spoken in work) generate(spoken) }
    }

    override val startBudgetMs: Long get() = if (usable) KOKORO_START_BUDGET_MS else fallback.startBudgetMs

    override fun say(text: String, started: () -> Unit, ended: () -> Unit) {
        if (!usable) {
            fallback.say(text, started, ended)
            return
        }
        val spoken = Spoken(generation.load(), text, started, ended)
        line += spoken
        work.trySend(spoken)
    }

    override fun flush() {
        generation.incrementAndFetch()
        voice?.cancel()
        line.clear()
        player.flush()
        fallback.flush()
    }

    override fun warm() {
        if (usable) scope.launch(synthesis) { runCatching { load() }.onFailure(::unusable) }
    }

    override fun rest() {
        player.rest()
        fallback.rest()
    }

    override fun close() {
        flush()
        NSNotificationCenter.defaultCenter.removeObserver(memoryWarning)
        work.close()
        scope.launch(synthesis) { unload() }.invokeOnCompletion { scope.cancel() }
        player.rest()
        fallback.close()
    }

    /** Synthesis thread: one utterance, handed to the player sentence by sentence. */
    private fun generate(spoken: Spoken) {
        if (stale(spoken)) return
        val engine = try {
            load()
        } catch (failure: Throwable) {
            unusable(failure)
            return
        }
        var first = true
        try {
            val result = engine.speakStreaming(spoken.text) { samples, rate ->
                if (stale(spoken)) return@speakStreaming false
                // Sentences of one utterance already carry their gap; the gap before an utterance is added here.
                val lead = if (first && player.holdsSound(spoken.generation)) rate * SENTENCE_GAP_MS / MS_PER_SECOND else 0
                player.play(spoken.generation, samples, rate, lead)
                if (first) {
                    first = false
                    scope.launch { if (!stale(spoken)) { spoken.sound = true; reportStart() } }
                }
                true
            }
            if (result.audioSeconds > 0.0) {
                log(LogLevel.Info, TAG, "Kokoro: ${tenths(result.audioSeconds)} s of speech in ${tenths(result.totalMs / MS_PER_SECOND)} s, ${tenths(result.rtf)}x real time, on $threads threads")
            }
        } catch (failure: Throwable) {
            // This one sentence is skipped; the reply goes on.
            log(LogLevel.Warn, TAG, "Kokoro could not say a sentence: ${failure.message}")
        }
        if (stale(spoken)) return
        player.mark(spoken.generation, engine.sampleRate) { scope.launch { finished(spoken) } }
    }

    private fun stale(spoken: Spoken) = spoken.generation != generation.load()

    /** Main thread: the oldest utterance in line starts once it has sound. */
    private fun reportStart() {
        val head = line.firstOrNull() ?: return
        if (head.sound && !head.reported) {
            head.reported = true
            head.started()
        }
    }

    /** Main thread: [spoken]'s last sound has played. */
    private fun finished(spoken: Spoken) {
        if (stale(spoken) || !line.remove(spoken)) return
        spoken.ended()
        reportStart()
    }

    /** Synthesis thread: the voice, loading it on first use. */
    private fun load(): KokoroVoice {
        voice?.let { return KokoroVoice(it, loadedRate) }
        val config = VoiceConfig(backend = VoiceConfig.KOKORO, kokoroModelDir = modelDir, numThreads = threads)
        val engine = openPieceEngine(config)
        loadedRate = engine.sampleRate
        log(LogLevel.Info, TAG, "Kokoro is loaded: ${config.version}")
        return KokoroVoice(EmberVoice(engine, config).also { voice = it }, loadedRate)
    }

    private var loadedRate = 0

    /** Synthesis thread: gives the model's memory back. The next utterance loads it again. */
    private fun unload() {
        val loaded = voice ?: return
        voice = null
        runCatching { loaded.release() }
        log(LogLevel.Info, TAG, "Kokoro was unloaded")
    }

    /** The model cannot speak on this phone: everything in line, and everything after it, goes to [fallback]. */
    private fun unusable(failure: Throwable) {
        log(LogLevel.Warn, TAG, "Kokoro could not load, so the system voice speaks: ${failure.message}")
        scope.launch {
            if (!usable) return@launch
            usable = false
            val waiting = line.toList()
            line.clear()
            generation.incrementAndFetch()
            player.flush()
            for (spoken in waiting) fallback.say(spoken.text, spoken.started, spoken.ended)
        }
    }

    private fun tenths(value: Double): String {
        val scaled = kotlin.math.round(value * 10).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }

    /** The loaded voice with the rate its sound comes at. */
    private class KokoroVoice(private val ember: EmberVoice, val sampleRate: Int) {
        fun speakStreaming(text: String, onSentence: (FloatArray, Int) -> Boolean) = ember.speakStreaming(text, null, onSentence)
    }
}

/**
 * Plays Kokoro's sound: one player node on an audio engine, fed mono 32-bit float buffers. The engine runs only
 * while there is something to say. Buffers may be handed over from any thread.
 */
private class KokoroPlayer {
    private val engine = AVAudioEngine()
    private val node = AVAudioPlayerNode()
    private var format: AVAudioFormat? = null
    /** The flush generation whose sound the node holds, or -1 when it holds none. */
    private val holding = AtomicInt(-1)

    /** True when sound of [generation] is already in line, so what follows needs a pause before it. */
    fun holdsSound(generation: Int): Boolean = holding.load() == generation

    /** Plays [samples] after what the node holds, [leadSilence] samples of silence first. */
    fun play(generation: Int, samples: FloatArray, sampleRate: Int, leadSilence: Int) {
        if (samples.isEmpty() || !start(sampleRate)) return
        val buffer = buffer(leadSilence + samples.size) ?: return
        val channel = buffer.floatChannelData?.get(0) ?: return
        samples.usePinned { pinned ->
            memcpy(channel + leadSilence, pinned.addressOf(0), (samples.size * Float.SIZE_BYTES).toULong())
        }
        holding.store(generation)
        node.scheduleBuffer(buffer, completionHandler = null)
    }

    /** Runs [played] once everything handed over so far has been heard. */
    fun mark(generation: Int, sampleRate: Int, played: () -> Unit) {
        val rate = if (sampleRate > 0) sampleRate else DEFAULT_RATE
        val buffer = if (start(rate)) buffer(rate * END_MARK_MS / MS_PER_SECOND) else null
        if (buffer == null) {
            played()
            return
        }
        node.scheduleBuffer(buffer, AVAudioPlayerNodeCompletionDataPlayedBack) { _ ->
            holding.compareAndSet(generation, -1)
            played()
        }
    }

    /** Silences the node and drops what it holds. Their marks still report; the speaker ignores a stale one. */
    fun flush() {
        holding.store(-1)
        if (engine.running) node.stop()
    }

    fun rest() {
        if (engine.running) engine.stop()
    }

    /** A silent buffer of [frames] samples at the player's format. */
    private fun buffer(frames: Int): AVAudioPCMBuffer? {
        val current = format ?: return null
        val buffer = AVAudioPCMBuffer(pCMFormat = current, frameCapacity = frames.toUInt())
        buffer.frameLength = frames.toUInt()
        return buffer
    }

    /** Makes sure the engine runs and the node plays at [sampleRate]. False when iOS will not start the engine. */
    private fun start(sampleRate: Int): Boolean {
        if (format == null) {
            val mono = AVAudioFormat(AVAudioPCMFormatFloat32, sampleRate.toDouble(), 1u, false)
            engine.attachNode(node)
            engine.connect(node, engine.mainMixerNode, mono)
            format = mono
        }
        if (!engine.running) {
            engine.prepare()
            if (!engine.startAndReturnError(null)) {
                log(LogLevel.Warn, TAG, "The audio engine did not start; Kokoro's sound was dropped")
                return false
            }
        }
        if (!node.playing) node.play()
        return true
    }

    private companion object {
        const val DEFAULT_RATE = 24_000
    }
}
