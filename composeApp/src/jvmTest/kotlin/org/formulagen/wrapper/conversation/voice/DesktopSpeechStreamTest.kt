package org.formulagen.wrapper.conversation.voice

import org.formulagen.wrapper.voice.PieceAudio
import org.formulagen.wrapper.voice.PieceEngine
import org.formulagen.wrapper.voice.VoiceConfig
import org.formulagen.wrapper.voice.FILLER_LINES
import org.formulagen.wrapper.voice.SpeechCancelled
import org.formulagen.wrapper.conversation.VoiceState
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopSpeechStreamTest {
    @Test
    fun theEngineLoadsOnlyOnTheVoiceWorker() {
        val loadedOn = CopyOnWriteArrayList<String>()
        val spoken = CountDownLatch(1)
        val engine = object : PieceEngine {
            override val sampleRate: Int = 24000
            override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
                spoken.countDown()
                return PieceAudio(FloatArray(480), 24000, 1.0, 1.0)
            }
            override fun release() {}
        }
        val line = object : PcmLine {
            override fun start() {}
            override fun write(pcm: ByteArray) {}
            override fun drain() {}
            override fun flush() {}
            override fun stop() {}
            override fun close() {}
        }
        DesktopSpeech(VoiceConfig(), { line }, { loadedOn += Thread.currentThread().name; engine }).use { speech ->
            // Warm and speak from the caller's thread, as Talk's arm and a reply do: the load happens on the worker.
            speech.warm()
            speech.speak("Hello there.")
            assertTrue(spoken.await(2, TimeUnit.SECONDS))
        }
        assertEquals(listOf(VOICE_WORKER), loadedOn, "loaded once, on the voice worker")
    }

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
        val line = object : PcmLine {
            override fun start() {
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
            // The next sentence is already being synthesized; playback did not wait for it to finish.
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS), "the next sentence was synthesized while the first was starting")
            releaseSecond.countDown()
        }
    }

    @Test
    fun theNextSentenceIsSynthesizedWhileTheFirstOneIsStillBeingWritten() {
        val calls = AtomicInteger()
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val writingFirst = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
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
        val line = object : PcmLine {
            override fun start() {}
            override fun write(pcm: ByteArray) {
                if (writingFirst.count == 1L) {
                    writingFirst.countDown()
                    assertTrue(releaseWrite.await(2, TimeUnit.SECONDS))
                }
            }
            override fun drain() {}
            override fun flush() {}
            override fun stop() { releaseWrite.countDown() }
            override fun close() { releaseWrite.countDown() }
        }
        DesktopSpeech(VoiceConfig(), { line }, { engine }).use { speech ->
            speech.speak("Hello there. This is the second sentence.")
            assertTrue(writingFirst.await(2, TimeUnit.SECONDS), "the first sentence reached the speaker")
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS), "the next sentence started while the first write was still blocked")
            releaseWrite.countDown()
            releaseSecond.countDown()
        }
    }

    @Test
    fun anEnqueuedSentenceStartsBeforeTheNextOneIsSynthesized() {
        val calls = AtomicInteger()
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val engine = object : PieceEngine {
            override val sampleRate: Int = 24000
            override fun usesFrameCeiling(): Boolean = false
            override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
                val n = calls.incrementAndGet()
                if (n >= 2) {
                    secondEntered.countDown()
                    assertTrue(releaseSecond.await(2, TimeUnit.SECONDS))
                }
                return PieceAudio(FloatArray(4800), 24000, 1.0, 1.0)
            }
            override fun release() {}
        }
        val started = CountDownLatch(1)
        val line = object : PcmLine {
            override fun start() { started.countDown() }
            override fun write(pcm: ByteArray) {}
            override fun drain() {}
            override fun flush() {}
            override fun stop() {}
            override fun close() {}
        }
        DesktopSpeech(VoiceConfig(), { line }, { engine }).use { speech ->
            assertTrue(speech.enqueue("Hello there."))
            assertTrue(started.await(2, TimeUnit.SECONDS), "first sentence reached the speaker")
            assertTrue(speech.enqueueToSpeakerMs in 0..280, "enqueue to speaker ${speech.enqueueToSpeakerMs}")
            println("STAGE enqueue_to_speaker_ms=${speech.enqueueToSpeakerMs}")
            speech.enqueue("This is the next sentence.")
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS), "the next sentence was synthesized while the first was playing")
            releaseSecond.countDown()
            speech.endStream()
        }
    }

    /** A line that records what reached the speaker, in order, and how many were open at once. */
    private class RecordingLine(private val log: CopyOnWriteArrayList<String>, private val open: AtomicInteger, private val mostOpen: AtomicInteger) : PcmLine {
        init { mostOpen.accumulateAndGet(open.incrementAndGet(), ::maxOf) }
        override fun start() { log += "start" }
        override fun write(pcm: ByteArray) { if (pcm.isNotEmpty()) log += "write" }
        override fun drain() { log += "drain" }
        override fun flush() {}
        override fun stop() {}
        override fun close() { if (log.lastOrNull() != "close") open.decrementAndGet(); log += "close" }
    }

    private fun countingEngine(calls: CopyOnWriteArrayList<String>) = object : PieceEngine {
        override val sampleRate: Int = 24000
        override fun usesFrameCeiling(): Boolean = false
        override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
            calls += text
            return PieceAudio(FloatArray(4800), 24000, 1.0, 1.0)
        }
        override fun release() {}
    }

    @Test
    fun appendedSentencesPlayInOrderAndTheVoiceGoesIdleByItself() {
        val synthesized = CopyOnWriteArrayList<String>()
        val log = CopyOnWriteArrayList<String>()
        val open = AtomicInteger()
        val mostOpen = AtomicInteger()
        DesktopSpeech(VoiceConfig(), { RecordingLine(log, open, mostOpen) }, { countingEngine(synthesized) }).use { speech ->
            val revision = speech.playbackRevision()
            assertTrue(speech.append("Hello there."))
            assertTrue(speech.append("This is the second sentence."))
            assertTrue(speech.status().state != VoiceState.IDLE, "speaking from the first append on")
            // No endStream: the stream ends once both sentences have been played out.
            assertTrue(waitFor { speech.status().state == VoiceState.IDLE })
            assertEquals(2, synthesized.size)
            assertEquals(2, log.count { it == "write" })
            assertTrue(log.indexOf("drain") > log.lastIndexOf("write"), "the device played everything out before the voice went idle: $log")
            assertEquals(revision, speech.playbackRevision(), "append does not take the speaker from its owner")
            // A sentence that arrives after the stream ended starts a new one.
            assertTrue(speech.append("And one more thing."))
            assertTrue(waitFor { synthesized.size == 3 && speech.status().state == VoiceState.IDLE })
            assertEquals(3, log.count { it == "write" })
            assertEquals(1, mostOpen.get(), "sentences never overlap on two lines")
        }
    }

    @Test
    fun aSentenceAppendedWhileTheLastOnePlaysOutContinuesTheSameStream() {
        val synthesized = CopyOnWriteArrayList<String>()
        val draining = CountDownLatch(1)
        val releaseDrain = CountDownLatch(1)
        val starts = AtomicInteger()
        val writes = AtomicInteger()
        val line = object : PcmLine {
            override fun start() { starts.incrementAndGet() }
            override fun write(pcm: ByteArray) { if (pcm.isNotEmpty()) writes.incrementAndGet() }
            override fun drain() {
                if (draining.count == 1L) {
                    draining.countDown()
                    assertTrue(releaseDrain.await(2, TimeUnit.SECONDS))
                }
            }
            override fun flush() {}
            override fun stop() { releaseDrain.countDown() }
            override fun close() { releaseDrain.countDown() }
        }
        DesktopSpeech(VoiceConfig(), { line }, { countingEngine(synthesized) }).use { speech ->
            assertTrue(speech.append("Hello there."))
            assertTrue(draining.await(2, TimeUnit.SECONDS), "the first sentence is playing out")
            assertTrue(speech.status().state != VoiceState.IDLE, "still speaking while the device plays out")
            assertTrue(speech.append("This arrived just in time."))
            assertTrue(waitFor { synthesized.size == 2 })
            releaseDrain.countDown()
            assertTrue(waitFor { writes.get() == 2 && speech.status().state == VoiceState.IDLE })
            assertEquals(1, starts.get(), "the late sentence went out on the line that was already open")
        }
    }

    @Test
    fun stopDropsAppendedSentencesAndALaterAppendSpeaksAgain() {
        val synthesized = CopyOnWriteArrayList<String>()
        val inWrite = CountDownLatch(1)
        val flushed = java.util.concurrent.atomic.AtomicBoolean(false)
        val line = object : PcmLine {
            override fun start() {}
            override fun write(pcm: ByteArray) {
                inWrite.countDown()
                while (!flushed.get()) Thread.sleep(5)
            }
            override fun drain() {}
            override fun flush() { flushed.set(true) }
            override fun stop() {}
            override fun close() {}
        }
        DesktopSpeech(VoiceConfig(), { line }, { countingEngine(synthesized) }).use { speech ->
            speech.append("Hello there friend.")
            speech.append("This one is never heard.")
            assertTrue(inWrite.await(2, TimeUnit.SECONDS))
            speech.stop()
            assertEquals(VoiceState.IDLE, speech.status().state)
            assertTrue(speech.append("A fresh start."))
            assertTrue(waitFor { synthesized.lastOrNull() == "A fresh start." && speech.status().state == VoiceState.IDLE })
        }
    }

    @Test
    fun appendSpeaksWordsNotMarkdownAndSkipsWhatHasNone() {
        val synthesized = CopyOnWriteArrayList<String>()
        DesktopSpeech(VoiceConfig(), { SilentLine() }, { countingEngine(synthesized) }).use { speech ->
            assertTrue(speech.append("**Done.** See [the plan](https://example.com/plan)."))
            assertTrue(waitFor { speech.status().state == VoiceState.IDLE && synthesized.isNotEmpty() })
            assertTrue(synthesized.none { '*' in it || "https" in it }, synthesized.toString())
            assertEquals(false, speech.append("   "))
            assertEquals(VoiceState.IDLE, speech.status().state)
        }
        DesktopSpeech(null).use { speech -> assertEquals(false, speech.append("Hello."), "no voice installed") }
    }

    @Test
    fun stopFlushesWithinFiftyMilliseconds() {
        val inWrite = CountDownLatch(1)
        val flushed = java.util.concurrent.atomic.AtomicBoolean(false)
        val engine = object : PieceEngine {
            override val sampleRate: Int = 24000
            override fun usesFrameCeiling(): Boolean = false
            override fun synthesize(text: String, maxFrames: Int, seed: Int) = PieceAudio(FloatArray(4800), 24000, 1.0, 1.0)
            override fun release() {}
        }
        val line = object : PcmLine {
            override fun start() {}
            override fun write(pcm: ByteArray) {
                inWrite.countDown()
                while (!flushed.get()) Thread.sleep(5)
            }
            override fun drain() {}
            override fun flush() { flushed.set(true) }
            override fun stop() {}
            override fun close() {}
        }
        DesktopSpeech(VoiceConfig(), { line }, { engine }).use { speech ->
            speech.enqueue("Hello there friend.")
            assertTrue(inWrite.await(2, TimeUnit.SECONDS))
            val startedNs = System.nanoTime()
            speech.stop()
            val flushMs = (System.nanoTime() - startedNs) / 1_000_000
            assertTrue(flushMs <= 50, "flush took ${flushMs}ms")
            println("STAGE barge_flush_ms=$flushMs")
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
        val writes = AtomicInteger()
        val line = object : PcmLine {
            override fun start() {
                started.countDown()
                hold.await(2, TimeUnit.SECONDS)
            }
            override fun write(pcm: ByteArray) { writes.incrementAndGet() }
            override fun drain() {}
            override fun flush() {}
            override fun stop() { hold.countDown() }
            override fun close() { hold.countDown() }
        }
        DesktopSpeech(VoiceConfig(), { line }, { engine }).use { speech ->
            speech.speak("Hello there. This is the second sentence.")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            speech.stop()
            Thread.sleep(300)
            assertEquals(0, writes.get(), "stop drops audio that has not been written")
        }
    }

    @Test
    fun aLoadingEngineNeverHoldsUpTheSpeakerAndAVoiceChosenMeanwhileWins() {
        val root = Files.createTempDirectory("kokoro-load")
        val model = root.resolve("model")
        Files.createDirectories(model)
        for (name in listOf("model.onnx", "voices-blends.bin", "tokens.txt", "lexicon-us-en.txt")) {
            Files.writeString(model.resolve(name), "x")
        }
        val env = mapOf(
            "HOVEL_KOKORO_MODEL" to model.toString(),
            "HOVEL_KOKORO_VOICE_FILE" to root.resolve("choice.txt").toString(),
        )
        val config = checkNotNull(desktopVoiceConfig(env, engineLinked = true)).copy(fillersEnabled = false)
        val loading = CountDownLatch(1)
        val loaded = CountDownLatch(1)
        val opened = CopyOnWriteArrayList<String>()
        val released = CopyOnWriteArrayList<String>()
        val seen = CopyOnWriteArrayList<String>()
        fun engine(active: VoiceConfig) = object : PieceEngine {
            override val sampleRate: Int = 24000
            override fun usesFrameCeiling(): Boolean = false
            override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
                seen.add(active.kokoroVoiceId)
                return PieceAudio(FloatArray(4800), 24000, 1.0, 1.0)
            }
            override fun release() { released.add(active.kokoroVoiceId) }
        }
        try {
            DesktopSpeech(config, { SilentLine() }, { active ->
                opened.add(active.kokoroVoiceId)
                if (opened.size == 1) {
                    loading.countDown()
                    assertTrue(loaded.await(5, TimeUnit.SECONDS))
                }
                engine(active)
            }).use { speech ->
                speech.warm()
                assertTrue(loading.await(2, TimeUnit.SECONDS), "the first load started")
                // What the window's thread asks of the speaker answers while the model loads.
                val answered = CountDownLatch(1)
                Thread {
                    speech.status()
                    speech.stop()
                    speech.chooseVoice("heart60-adam40")
                    answered.countDown()
                }.start()
                assertTrue(answered.await(1, TimeUnit.SECONDS), "nothing waited on the load")
                loaded.countDown()
                speech.speak("Hello there.")
                assertTrue(waitFor { seen.isNotEmpty() })
                assertEquals(listOf("heart60-adam40"), seen, "the voice chosen during the load speaks")
                assertEquals(listOf("adam70-michael30", "heart60-adam40"), opened)
                assertEquals(listOf("adam70-michael30"), released, "the load for the old voice was let go")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun kokoroChoicePersistsAcrossSpeaksAndTheNextLaunch() {
        val root = Files.createTempDirectory("kokoro-choice")
        val model = root.resolve("model")
        Files.createDirectories(model)
        for (name in listOf("model.onnx", "voices-blends.bin", "tokens.txt", "lexicon-us-en.txt")) {
            Files.writeString(model.resolve(name), "x")
        }
        val choice = root.resolve("choice.txt")
        val env = mapOf(
            "HOVEL_KOKORO_MODEL" to model.toString(),
            "HOVEL_KOKORO_VOICE_FILE" to choice.toString(),
        )
        val config = checkNotNull(desktopVoiceConfig(env, engineLinked = true))
        val seen = CopyOnWriteArrayList<String>()
        val opens = AtomicInteger()
        fun engine(active: VoiceConfig) = object : PieceEngine {
            override val sampleRate: Int = 24000
            override fun usesFrameCeiling(): Boolean = false
            override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
                seen.add(active.kokoroVoiceId)
                return PieceAudio(FloatArray(4800), 24000, 1.0, 1.0)
            }
            override fun release() {}
        }
        try {
            DesktopSpeech(config, { SilentLine() }, { opens.incrementAndGet(); engine(it) }).use { speech ->
                assertEquals(listOf("Sam", "Jamie", "Riley", "Harper", "Quinn", "Avery", "Emery", "Rowan", "Sage"), speech.voices().map { it.name })
                speech.speak("Hello there.")
                assertTrue(waitFor { seen.size == 1 })
                speech.speak("Hello again.")
                assertTrue(waitFor { seen.size == 2 })
                assertEquals(listOf("adam70-michael30", "adam70-michael30"), seen)
                assertEquals(1, opens.get())
                speech.chooseVoice("heart60-adam40")
                assertEquals("heart60-adam40", speech.currentVoiceId())
                val beforeHarper = seen.size
                speech.speak("Third sentence here.")
                assertTrue(waitFor { seen.size > beforeHarper })
                assertEquals("heart60-adam40", seen.last())
                assertEquals(2, opens.get())
                assertFailsWith<IllegalArgumentException> { speech.chooseVoice("expresso") }
            }
            val restarted = checkNotNull(desktopVoiceConfig(env, engineLinked = true))
            assertEquals("heart60-adam40", restarted.kokoroVoiceId)
            writeKokoroVoiceChoice(choice, "adam60-george40")
            val edited = checkNotNull(desktopVoiceConfig(env, engineLinked = true))
            assertEquals("adam60-george40", edited.kokoroVoiceId)
            val pinned = checkNotNull(desktopVoiceConfig(env + ("HOVEL_KOKORO_VOICE" to "adam55-bella45"), engineLinked = true))
            assertEquals("adam55-bella45", pinned.kokoroVoiceId)
            assertTrue(pinned.kokoroVoiceFromEnv)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun stopFlushesACachedClip() {
        val started = CountDownLatch(1)
        val flushed = CountDownLatch(1)
        val line = object : PcmLine {
            override fun start() { started.countDown() }
            override fun write(pcm: ByteArray) { Thread.sleep(1_000) }
            override fun drain() {}
            override fun flush() { flushed.countDown() }
            override fun stop() {}
            override fun close() {}
        }
        DesktopSpeech(VoiceConfig(fillersEnabled = false), { line }).use { speech ->
            speech.playClip(ByteArray(4800), 24000) {}
            assertTrue(started.await(2, TimeUnit.SECONDS), "clip reached the speaker")
            speech.stop()
            assertTrue(flushed.await(2, TimeUnit.SECONDS), "stop flushed the filler")
        }
    }

    @Test
    fun aCancelledWarmKeepsGoingForTheSelectedVoice() {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val engine = object : PieceEngine {
            override val sampleRate = 24000
            override fun synthesize(text: String, maxFrames: Int, seed: Int) = PieceAudio(floatArrayOf(0.2f), 24000, 1.0, 1.0)
            override fun synthesizeLine(text: String, voiceId: String): PieceAudio {
                if (voiceId == "heart60-adam40" && cancelled.compareAndSet(false, true)) {
                    throw SpeechCancelled()
                }
                return PieceAudio(floatArrayOf(0.2f), 24000, 1.0, 1.0)
            }
            override fun release() {}
        }
        val config = VoiceConfig(backend = VoiceConfig.KOKORO, kokoroVoiceId = "heart60-adam40", fillersEnabled = true)
        DesktopSpeech(config, { SilentLine() }, { engine }).use { speech ->
            speech.warmFillers(FILLER_LINES)
            assertTrue(waitFor { cancelled.get() })
            speech.warmFillers(FILLER_LINES)
            assertTrue(waitFor { speech.fillerPcm("heart60-adam40", "Hmm,") != null })
            assertTrue(speech.fillerPcm("heart60-adam40", "Let me think,") != null)
            assertTrue(speech.fillerPcm("heart60-adam40", "Okay, so") != null)
        }
    }

    @Test
    fun tenSpeaksThatAreCancelledReturnTheVoiceToIdle() {
        val engine = object : PieceEngine {
            override val sampleRate = 24000
            override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
                if (text.startsWith("fail")) throw SpeechCancelled()
                return PieceAudio(FloatArray(400), 24000, 1.0, 1.0)
            }
            override fun release() {}
        }
        val updates = CopyOnWriteArrayList<VoiceState>()
        DesktopSpeech(VoiceConfig(backend = VoiceConfig.KOKORO, fillersEnabled = false), { SilentLine() }, { engine }).use { speech ->
            speech.onVoice { updates += speech.status().state }
            repeat(10) {
                speech.speak("Hello there.")
                assertTrue(waitFor { speech.status().state == VoiceState.IDLE })
                speech.speak("fail this take")
                assertTrue(waitFor { speech.status().state == VoiceState.IDLE })
                speech.stop()
                assertEquals(VoiceState.IDLE, speech.status().state)
            }
        }
        assertTrue(updates.isNotEmpty())
    }
}

private class SilentLine : PcmLine {
    override fun start() {}
    override fun write(pcm: ByteArray) {}
    override fun drain() {}
    override fun flush() {}
    override fun stop() {}
    override fun close() {}
}

private fun waitFor(check: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (System.nanoTime() < deadline) {
        if (check()) return true
        Thread.sleep(10)
    }
    return check()
}
