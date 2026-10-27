package org.formulagen.wrapper.voice

/**
 * A PocketTTS reference clip. The shared contract file is 32 kHz, 16-bit
 * mono PCM, 10 s. Any PCM or float WAV is mixed to mono and resampled to
 * 24 kHz, which is the synthesis rate. Shorter than a second is not enough speech to clone.
 * Longer than 10 seconds is past the length this sherpa-onnx build keeps
 * (`max_reference_audio_len` 10). The CC0 default clip is exactly 10 seconds,
 * so that length is allowed.
 */
const val REFERENCE_SAMPLE_RATE = 24000
const val REFERENCE_MIN_SECONDS = 1.0
const val REFERENCE_MAX_SECONDS = 10.0

class VoiceClipException(message: String) : IllegalArgumentException(message)

private const val MAX_WAV_BYTES = 64 * 1024 * 1024

/** Expresso and the model's test_wavs clips are CC-BY-NC. */
fun refuseNonCommercialVoice(path: String) {
    val lowered = path.replace('\\', '/').lowercase()
    if (lowered.contains("expresso") || lowered.contains("test_wavs")) {
        throw VoiceClipException(
            "voice must be CC0 or another permissive license. " +
                "Expresso and test_wavs are CC-BY-NC and cannot be used ($path).",
        )
    }
}

data class PreparedClip(
    val samples: FloatArray,
    val sampleRate: Int,
    val seconds: Double,
    val sourceRate: Int,
    val sourceChannels: Int,
)

fun prepareReferenceClip(wav: ByteArray): PreparedClip {
    val decoded = decodeWav(wav)
    val mono = mixToMono(decoded.samples, decoded.channels)
    val atRate = if (decoded.sampleRate == REFERENCE_SAMPLE_RATE) {
        mono
    } else {
        resampleLinear(mono, decoded.sampleRate, REFERENCE_SAMPLE_RATE)
    }
    if (atRate.isEmpty()) throw VoiceClipException("voice clip has no samples")
    val seconds = atRate.size / REFERENCE_SAMPLE_RATE.toDouble()
    if (seconds < REFERENCE_MIN_SECONDS) {
        throw VoiceClipException(
            "voice clip is too short (${fmtSeconds(seconds)} s). " +
                "Record at least ${fmtSeconds(REFERENCE_MIN_SECONDS)} s of speech.",
        )
    }
    if (seconds > REFERENCE_MAX_SECONDS + 0.0005) {
        throw VoiceClipException(
            "voice clip is too long (${fmtSeconds(seconds)} s). " +
                "Keep it at or under ${fmtSeconds(REFERENCE_MAX_SECONDS)} s.",
        )
    }
    return PreparedClip(atRate, REFERENCE_SAMPLE_RATE, seconds, decoded.sampleRate, decoded.channels)
}

/** 16-bit mono WAV at 24 kHz, which is what sherpa-onnx's wave reader accepts. */
fun referenceClipWavBytes(clip: PreparedClip): ByteArray {
    val dataBytes = clip.samples.size * 2
    val out = ByteArray(44 + dataBytes)
    putTag(out, 0, "RIFF")
    put32(out, 4, 36 + dataBytes)
    putTag(out, 8, "WAVE")
    putTag(out, 12, "fmt ")
    put32(out, 16, 16)
    put16(out, 20, 1)
    put16(out, 22, 1)
    put32(out, 24, clip.sampleRate)
    put32(out, 28, clip.sampleRate * 2)
    put16(out, 32, 2)
    put16(out, 34, 16)
    putTag(out, 36, "data")
    put32(out, 40, dataBytes)
    var i = 0
    while (i < clip.samples.size) {
        val scaled = (clip.samples[i] * 32767.0f).toInt().coerceIn(-32768, 32767)
        out[44 + i * 2] = (scaled and 0xff).toByte()
        out[45 + i * 2] = ((scaled shr 8) and 0xff).toByte()
        i += 1
    }
    return out
}

private class DecodedWav(val sampleRate: Int, val channels: Int, val samples: FloatArray)

private fun decodeWav(bytes: ByteArray): DecodedWav {
    if (bytes.size > MAX_WAV_BYTES) throw VoiceClipException("wav is too large")
    if (bytes.size < 44) throw VoiceClipException("wav is too small")
    if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") throw VoiceClipException("not a wav file")
    var pos = 12
    var rate = 0
    var channels = 0
    var bits = 0
    var format = 0
    var data: ByteArray? = null
    while (pos + 8 <= bytes.size) {
        val id = tag(bytes, pos)
        val size = le32(bytes, pos + 4)
        val start = pos + 8
        if (size < 0 || size > MAX_WAV_BYTES || start.toLong() + size.toLong() > bytes.size) {
            throw VoiceClipException("truncated wav chunk $id")
        }
        if (id == "fmt ") {
            if (size < 16) throw VoiceClipException("short fmt chunk")
            format = le16(bytes, start)
            channels = le16(bytes, start + 2)
            rate = le32(bytes, start + 4)
            bits = le16(bytes, start + 14)
            if (format == 0xFFFE) {
                if (size < 40) throw VoiceClipException("short extensible fmt chunk")
                format = le16(bytes, start + 24)
            }
        } else if (id == "data") {
            data = bytes.copyOfRange(start, start + size)
        }
        pos = start + size + (size and 1)
    }
    val audio = data ?: throw VoiceClipException("wav has no data chunk")
    if (channels < 1 || rate <= 0) throw VoiceClipException("wav header has no channels or sample rate")
    val frameBytes = channels * (bits / 8)
    if (frameBytes < 1 || bits !in listOf(8, 16, 24, 32)) {
        throw VoiceClipException("need 8, 16, 24, or 32-bit PCM or float wav, found $bits-bit format $format")
    }
    if (format != 1 && format != 3) {
        throw VoiceClipException("need PCM or float wav, found format $format")
    }
    if (format == 3 && bits != 32) throw VoiceClipException("float wav needs 32 bits, found $bits")
    val frames = audio.size / frameBytes
    val samples = FloatArray(frames * channels)
    var frame = 0
    while (frame < frames) {
        var ch = 0
        while (ch < channels) {
            val at = frame * frameBytes + ch * (bits / 8)
            samples[frame * channels + ch] = when {
                format == 3 -> float32(audio, at)
                bits == 8 -> ((audio[at].toInt() and 0xff) - 128) / 128f
                bits == 16 -> le16s(audio, at) / 32768f
                bits == 24 -> le24s(audio, at) / 8388608f
                else -> le32(audio, at) / 2147483648f
            }
            ch += 1
        }
        frame += 1
    }
    return DecodedWav(rate, channels, samples)
}

private fun mixToMono(interleaved: FloatArray, channels: Int): FloatArray {
    if (channels == 1) return interleaved
    val frames = interleaved.size / channels
    val mono = FloatArray(frames)
    var frame = 0
    while (frame < frames) {
        var sum = 0f
        var ch = 0
        while (ch < channels) {
            sum += interleaved[frame * channels + ch]
            ch += 1
        }
        mono[frame] = sum / channels
        frame += 1
    }
    return mono
}

internal fun resampleLinear(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
    if (input.isEmpty() || inRate == outRate) return input
    val outLen = (input.size.toLong() * outRate / inRate).toInt().coerceAtLeast(1)
    if (input.size == 1 || outLen == 1) return FloatArray(outLen) { input[0] }
    val out = FloatArray(outLen)
    val scale = (input.size - 1).toDouble() / (outLen - 1).toDouble()
    var i = 0
    while (i < outLen) {
        val pos = i * scale
        val left = pos.toInt().coerceIn(0, input.size - 2)
        val frac = (pos - left).toFloat()
        out[i] = input[left] * (1f - frac) + input[left + 1] * frac
        i += 1
    }
    return out
}

private fun fmtSeconds(value: Double): String {
    val rounded = kotlin.math.round(value * 100.0) / 100.0
    val text = rounded.toString()
    return if (text.endsWith(".0")) text.dropLast(2) else text
}

private fun tag(bytes: ByteArray, at: Int): String {
    return bytes.decodeToString(at, at + 4)
}

private fun le16(bytes: ByteArray, at: Int): Int {
    return (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
}

private fun le16s(bytes: ByteArray, at: Int): Int {
    val value = le16(bytes, at)
    return if (value >= 32768) value - 65536 else value
}

private fun le24s(bytes: ByteArray, at: Int): Int {
    val value = (bytes[at].toInt() and 0xff) or
        ((bytes[at + 1].toInt() and 0xff) shl 8) or
        ((bytes[at + 2].toInt() and 0xff) shl 16)
    return if (value >= 8388608) value - 16777216 else value
}

private fun le32(bytes: ByteArray, at: Int): Int {
    return (bytes[at].toInt() and 0xff) or
        ((bytes[at + 1].toInt() and 0xff) shl 8) or
        ((bytes[at + 2].toInt() and 0xff) shl 16) or
        ((bytes[at + 3].toInt() and 0xff) shl 24)
}

private fun float32(bytes: ByteArray, at: Int): Float {
    return Float.fromBits(le32(bytes, at))
}

private fun putTag(out: ByteArray, at: Int, text: String) {
    text.forEachIndexed { index, ch -> out[at + index] = ch.code.toByte() }
}

private fun put16(out: ByteArray, at: Int, value: Int) {
    out[at] = (value and 0xff).toByte()
    out[at + 1] = ((value shr 8) and 0xff).toByte()
}

private fun put32(out: ByteArray, at: Int, value: Int) {
    out[at] = (value and 0xff).toByte()
    out[at + 1] = ((value shr 8) and 0xff).toByte()
    out[at + 2] = ((value shr 16) and 0xff).toByte()
    out[at + 3] = ((value shr 24) and 0xff).toByte()
}
