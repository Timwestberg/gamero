package org.formulagen.wrapper.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackGapTest {
    @Test fun lookaheadRemovesTheStallWhenTheNextSentenceFinishesDuringPlayback() {
        val sentences = listOf(
            SentenceTiming(synthMs = 400.0, audioMs = 1000.0),
            SentenceTiming(synthMs = 400.0, audioMs = 1000.0),
        )
        val before = wallClockGaps(sentences, lineBufferMs = 200.0, lookahead = false)
        val after = wallClockGaps(sentences, lineBufferMs = 200.0, lookahead = true)
        assertEquals(200.0, before.single(), 1.0)
        assertEquals(0.0, after.single(), 1.0)
    }

    @Test fun aShortSentenceStillUnderrunsByLessWhenTheNextOneIsAlreadyStarted() {
        val sentences = listOf(
            SentenceTiming(synthMs = 300.0, audioMs = 300.0),
            SentenceTiming(synthMs = 500.0, audioMs = 800.0),
        )
        val before = wallClockGaps(sentences, lineBufferMs = 100.0, lookahead = false)
        val after = wallClockGaps(sentences, lineBufferMs = 100.0, lookahead = true)
        // Sequential: write keeps 100 ms queued, then the 500 ms synthesize starts. Playback ends 100 ms later.
        assertEquals(400.0, before.single(), 1.0)
        // Lookahead: synthesize starts as playback starts, so the stall is 500 - 300.
        assertEquals(200.0, after.single(), 1.0)
        assertTrue(after.single() < before.single())
    }

    @Test fun measuredReplyDoesNotStallOnceTheNextSentenceIsSynthesizedAhead() {
        // Kokoro sid 50, 2 threads, this machine. Audio is the generated length.
        val reply = listOf(
            SentenceTiming(synthMs = 461.0, audioMs = 1350.0),
            SentenceTiming(synthMs = 419.0, audioMs = 1294.0),
            SentenceTiming(synthMs = 406.0, audioMs = 1274.0),
            SentenceTiming(synthMs = 450.0, audioMs = 1442.0),
        )
        // JDK's SourceDataLine.open(format) buffer is about 500 ms. A 200 ms buffer is the
        // small end of what CoreAudio exposes when the default is not honored.
        val beforeHalfSecond = wallClockGaps(reply, lineBufferMs = 500.0, lookahead = false)
        val beforeSmallBuffer = wallClockGaps(reply, lineBufferMs = 200.0, lookahead = false)
        val after = wallClockGaps(reply, lineBufferMs = 200.0, lookahead = true)
        assertEquals(listOf(0.0, 0.0, 0.0), beforeHalfSecond.map { kotlin.math.round(it) })
        assertEquals(listOf(219.0, 206.0, 250.0), beforeSmallBuffer.map { kotlin.math.round(it) })
        assertEquals(listOf(0.0, 0.0, 0.0), after.map { kotlin.math.round(it) })

        val hello = listOf(
            SentenceTiming(synthMs = 300.0, audioMs = 756.0),
            SentenceTiming(synthMs = 525.0, audioMs = 1689.0),
        )
        assertEquals(25.0, kotlin.math.round(wallClockGaps(hello, 500.0, lookahead = false).single()))
        assertEquals(325.0, kotlin.math.round(wallClockGaps(hello, 200.0, lookahead = false).single()))
        assertEquals(0.0, kotlin.math.round(wallClockGaps(hello, 200.0, lookahead = true).single()))

        // Same reply with 4 threads, which is what Mac chat now uses.
        val reply4 = listOf(
            SentenceTiming(synthMs = 290.0, audioMs = 1350.0),
            SentenceTiming(synthMs = 264.0, audioMs = 1294.0),
            SentenceTiming(synthMs = 256.0, audioMs = 1274.0),
            SentenceTiming(synthMs = 285.0, audioMs = 1442.0),
        )
        assertEquals(listOf(64.0, 56.0, 85.0), wallClockGaps(reply4, 200.0, lookahead = false).map { kotlin.math.round(it) })
        assertEquals(listOf(0.0, 0.0, 0.0), wallClockGaps(reply4, 200.0, lookahead = true).map { kotlin.math.round(it) })
    }
}
