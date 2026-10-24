package org.formulagen.wrapper.voice

import kotlin.math.ceil
import kotlin.math.max

/**
 * PocketTTS frame ceiling for one piece. 0 means leave the engine default alone.
 * A positive ceiling is max(floor, intercept + slope * chars). A piece of at most
 * [shortChars] characters is raised to [shortMinFrames] when that floor is higher.
 * 36 frames is 2.88 s at 80 ms per frame.
 */
fun frameBudget(
    chars: Int,
    intercept: Double,
    slope: Double,
    floor: Int,
    shortChars: Int,
    shortMinFrames: Int,
): Int {
    val curveOn = slope > 0.0 || intercept > 0.0 || floor > 0
    val shortOn = shortChars > 0 && chars <= shortChars && shortMinFrames > 0
    if (!curveOn && !shortOn) return 0
    var frames = 0
    if (curveOn) {
        val linear = intercept + slope * chars.toDouble()
        frames = ceil(max(floor.toDouble(), linear)).toInt()
        if (frames < 4) frames = 4
    }
    if (shortOn && shortMinFrames > frames) frames = shortMinFrames
    return frames
}

/** Expected speech length. A piece longer than [ratio] times this is a runaway. */
fun expectedAudioSeconds(chars: Int, charsPerSecond: Double = 15.0, minSeconds: Double = 0.8): Double {
    val fromChars = chars / charsPerSecond
    return if (fromChars < minSeconds) minSeconds else fromChars
}
