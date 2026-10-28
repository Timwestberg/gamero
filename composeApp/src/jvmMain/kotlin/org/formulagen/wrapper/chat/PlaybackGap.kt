package org.formulagen.wrapper.chat

/**
 * One spoken sentence. [synthMs] is how long the engine takes to produce it.
 * [audioMs] is how long the samples take to play.
 */
internal data class SentenceTiming(val synthMs: Double, val audioMs: Double)

/**
 * Silence a listener hears between the end of one sentence and the start of the
 * next, beyond any silence already in the samples. [lineBufferMs] is how much
 * audio [javax.sound.sampled.SourceDataLine.write] can queue before it blocks.
 * With [lookahead], the next sentence is synthesized while the current one is
 * still being written to the line.
 */
internal fun wallClockGaps(
    sentences: List<SentenceTiming>,
    lineBufferMs: Double,
    lookahead: Boolean,
): List<Double> {
    if (sentences.size < 2 || lineBufferMs <= 0.0) return emptyList()
    val clock = PlayClock(lineBufferMs)
    val starts = DoubleArray(sentences.size)
    if (!lookahead) {
        clock.elapse(sentences[0].synthMs)
        for (index in sentences.indices) {
            if (index > 0) clock.elapse(sentences[index].synthMs)
            starts[index] = clock.queued
            clock.submit(sentences[index].audioMs)
        }
    } else {
        val ready = DoubleArray(sentences.size)
        var synthesized = 0.0
        for (index in sentences.indices) {
            synthesized += sentences[index].synthMs
            ready[index] = synthesized
        }
        clock.elapse(ready[0])
        var next = 0
        val total = sentences.sumOf { it.audioMs }
        while (clock.played + 1e-6 < total) {
            if (next < sentences.size && ready[next] <= clock.wall + 1e-9) {
                starts[next] = clock.queued
                clock.submit(sentences[next].audioMs)
                next += 1
            } else if (next < sentences.size) {
                clock.elapse(ready[next] - clock.wall)
            } else {
                clock.elapse(total - clock.played)
            }
        }
    }
    clock.elapse((clock.queued - clock.played).coerceAtLeast(0.0))
    val gaps = ArrayList<Double>(sentences.size - 1)
    for (index in 0 until sentences.size - 1) {
        val boundary = starts[index] + sentences[index].audioMs
        val ended = clock.wallWhenReached(boundary)
        val nextStarted = clock.wallWhenAdvancedPast(boundary)
        gaps.add((nextStarted - ended).coerceAtLeast(0.0))
    }
    return gaps
}

private class PlayClock(private val bufferMs: Double) {
    var wall = 0.0
    var played = 0.0
    var queued = 0.0
    private val points = ArrayList<DoubleArray>()

    init {
        points.add(doubleArrayOf(0.0, 0.0))
    }

    fun elapse(dt: Double) {
        if (dt <= 1e-9) return
        val available = (queued - played).coerceAtLeast(0.0)
        val playing = minOf(dt, available)
        if (playing > 1e-9) {
            played += playing
            wall += playing
            points.add(doubleArrayOf(wall, played))
        }
        val stall = dt - playing
        if (stall > 1e-9) {
            wall += stall
            points.add(doubleArrayOf(wall, played))
        }
    }

    fun submit(audioMs: Double) {
        var left = audioMs
        while (left > 1e-9) {
            val free = bufferMs - (queued - played)
            if (free > 1e-9) {
                val take = minOf(left, free)
                queued += take
                left -= take
            } else {
                val buffered = (queued - played).coerceAtLeast(0.0)
                if (buffered <= 1e-9) {
                    wall += left
                    points.add(doubleArrayOf(wall, played))
                    left = 0.0
                } else {
                    elapse(minOf(left, buffered))
                }
            }
        }
    }

    fun wallWhenReached(audioPos: Double): Double = wallAt(audioPos, strictPast = false)

    fun wallWhenAdvancedPast(audioPos: Double): Double = wallAt(audioPos, strictPast = true)

    private fun wallAt(audioPos: Double, strictPast: Boolean): Double {
        var previousWall = points[0][0]
        var previousPlayed = points[0][1]
        for (index in 1 until points.size) {
            val nextWall = points[index][0]
            val nextPlayed = points[index][1]
            val advanced = nextPlayed - previousPlayed
            if (advanced > 1e-9) {
                val contains = if (strictPast) audioPos < nextPlayed && audioPos >= previousPlayed else audioPos <= nextPlayed && audioPos >= previousPlayed
                if (contains) {
                    val frac = (audioPos - previousPlayed) / advanced
                    return previousWall + frac * (nextWall - previousWall)
                }
            }
            previousWall = nextWall
            previousPlayed = nextPlayed
        }
        return wall
    }
}
