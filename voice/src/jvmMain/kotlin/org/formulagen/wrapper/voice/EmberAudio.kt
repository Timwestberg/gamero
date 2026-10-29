package org.formulagen.wrapper.voice

import java.io.File

/**
 * The Mac voice-processing graph, loaded from [HOVEL_EMBER_AUDIO].
 * Playback written here is the far-end reference. [read] is the 16 kHz tap after cancellation.
 * A missing library is a normal outcome on Linux and on a Mac that has not built the helper yet.
 */
object EmberAudio {
    const val START_OK = 0
    const val START_FAILED = -1
    const val START_MIC_DENIED = -2
    const val START_VOICE_PROCESSING = -3

    private var loaded = false
    private var linked = false

    fun tryLoad(environment: Map<String, String>): Boolean {
        if (loaded) return linked
        loaded = true
        val path = environment["HOVEL_EMBER_AUDIO"]?.trim().orEmpty()
        if (path.isEmpty() || !File(path).isFile) return false
        linked = try {
            System.load(path)
            true
        } catch (error: UnsatisfiedLinkError) {
            false
        }
        return linked
    }

    /** [START_OK], [START_MIC_DENIED], [START_VOICE_PROCESSING], or [START_FAILED]. */
    fun startCode(sampleRate: Int): Int = if (linked) nativeStart(sampleRate) else START_FAILED

    fun start(sampleRate: Int): Boolean = startCode(sampleRate) == START_OK

    fun lastError(): String = if (linked) nativeLastError() else ""

    fun read(dst: FloatArray): Int = if (linked) nativeRead(dst) else -1

    fun play(pcm: ShortArray, sampleRate: Int): Boolean = linked && nativePlay(pcm, sampleRate) == 0

    fun flush() {
        if (linked) nativeFlush()
    }

    fun stop() {
        if (linked) nativeStop()
    }

    private external fun nativeStart(sampleRate: Int): Int
    private external fun nativeRead(dst: FloatArray): Int
    private external fun nativePlay(pcm: ShortArray, sampleRate: Int): Int
    private external fun nativeFlush()
    private external fun nativeStop()
    private external fun nativeVoiceProcessing(): Int
    private external fun nativeLastError(): String
}
