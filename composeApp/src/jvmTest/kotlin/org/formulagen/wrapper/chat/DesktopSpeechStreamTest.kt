package org.formulagen.wrapper.chat

import org.formulagen.wrapper.voice.PieceAudio
import org.formulagen.wrapper.voice.PieceEngine
import org.formulagen.wrapper.voice.VoiceConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSpeechStreamTest {
    @Test
    fun firstSentenceStartsBeforeTheNextOneIsSynthesized() {
        val calls = AtomicInteger()
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val engine = object : PieceEngine {
            override val sampleRate: Int = 24000
            override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
                val n = calls.incrementAndGet()
                if (n == 2) {
                    secondEntered.countDown()
                    assertTrue(releaseSecond.await(2, TimeUnit.SECONDS))
                }
                return PieceAudio(FloatArray(4800), 24000, 1.0, 1.0)
            }
            override fun release() {}
        }
        val started = CountDownLatch(1)
        val callsAtStart = AtomicInteger()
        val line = object : PcmLine {
            override fun start() {
                callsAtStart.set(calls.get())
                started.countDown()
            }
            override fun write(pcm: ByteArray) {}
            override fun drain() {}
            override fun flush() {}
            override fun stop() {}
            override fun close() {}
        }
        DesktopSpeech(VoiceConfig(), { line }, { engine }).use { speech ->
            speech.speak("Hello there. This is the second sentence.")
            assertTrue(started.await(2, TimeUnit.SECONDS), "playback started")
            assertEquals(1, callsAtStart.get(), "playback started before the next sentence")
            releaseSecond.countDown()
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun stopDropsTheQueuedSentence() {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val engine = object : PieceEngine {
            override val sampleRate: Int = 24000
            override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
                calls.incrementAndGet()
                return PieceAudio(FloatArray(4800), 24000, 1.0, 1.0)
            }
            override fun release() {}
        }
        val line = object : PcmLine {
            override fun start() {
                started.countDown()
                hold.await(2, TimeUnit.SECONDS)
            }
            override fun write(pcm: ByteArray) {}
            override fun drain() {}
            override fun flush() {}
            override fun stop() { hold.countDown() }
            override fun close() { hold.countDown() }
        }
        DesktopSpeech(VoiceConfig(), { line }, { engine }).use { speech ->
            speech.speak("Hello there. This is the second sentence.")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            speech.stop()
            Thread.sleep(200)
            assertEquals(1, calls.get())
        }
    }
}
