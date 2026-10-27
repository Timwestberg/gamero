package org.formulagen.wrapper.voice

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoiceConcurrencyTest {
    @Test
    fun secondSpeakFailsWhileTheFirstIsRunning() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = object : PieceEngine {
            override val sampleRate: Int = 24000
            override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
                started.countDown()
                release.await()
                return PieceAudio(FloatArray(240), 24000, 1.0, 1.0)
            }
            override fun release() {}
        }
        val voice = EmberVoice(engine)
        val worker = Thread { voice.speak("Done!") }
        worker.start()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val error = assertFailsWith<IllegalStateException> { voice.speak("Done!") }
        assertEquals(true, error.message?.contains("already speaking"))
        val releaseError = assertFailsWith<IllegalStateException> { voice.release() }
        assertEquals(true, releaseError.message?.contains("speaking"))
        release.countDown()
        worker.join(5000)
        assertEquals(false, worker.isAlive)
        voice.speak("Done!")
    }
}
