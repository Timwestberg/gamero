package org.formulagen.wrapper.voice

/** Pure checks. No model and no native library. */
fun runVoiceSelfCheck() {
    check(frameBudget(5, 0.0, 0.9, 0, 24, 24) == 24) { "short floor" }
    check(frameBudget(18, 0.0, 0.9, 0, 24, 24) == 24) { "18 chars" }
    check(frameBudget(40, 0.0, 0.9, 0, 24, 24) == 36) { "40 chars on the slope" }
    check(frameBudget(5, 4.0, 0.8, 12, 24, 36) == 36) { "chosen short floor" }
    check(frameBudget(18, 4.0, 0.8, 12, 24, 36) == 36) { "18 chars get 36" }
    check(frameBudget(36, 4.0, 0.8, 12, 24, 36) == 33) { "36 chars is 4+0.8*36" }
    check(frameBudget(39, 4.0, 0.8, 12, 24, 36) == 36) { "39 chars is ceil(35.2)" }
    check(frameBudget(28, 4.0, 0.8, 12, 24, 36) == 27) { "ceil(4 + 0.8 * 28) is 27" }
    check(frameBudget(40, 0.0, 0.0, 0, 24, 0) == 0) { "cap stays off" }
    val passed = speechPieces("Done! Your build passed.", 40, 140)
    check(passed == listOf("Done!", "Your build passed.")) { "build pieces $passed" }
    check(VoiceConfig().framesFor("Done!".length) == 36) { "config frames" }
    check(VoiceConfig().framesFor("Your build passed.".length) == 36) { "second piece frames" }
    val numbers = speechPieces("PetBridge.kt returned error 503 at 2:45 PM after 30 seconds.", 40, 140)
    check(numbers.any { it.contains("five hundred three") }) { "503 $numbers" }
    check(numbers.any { it.contains("two forty-five") }) { "time $numbers" }
    check(normalizeForSpeech("PetBridge") == "PetBridge") { "bare camel" }

    val events = ArrayList<String>()
    val fake = object : PieceEngine {
        override val sampleRate: Int = 24000
        override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
            return PieceAudio(FloatArray(2400) { 0.1f }, 24000, 12.0)
        }
        override fun release() {}
    }
    val spoken = EmberVoice(fake, VoiceConfig()).speak("Done! Your build passed.")
    check(spoken.events.first().name == SPEECH_START) { "start" }
    check(spoken.events.last().name == SPEECH_END) { "end" }
    check(spoken.events.count { it.name == SPEECH_LEVEL } == 2) { "levels" }
    check(spoken.ttfaMs == 12.0) { "ttfa from the engine callback" }
    check(spoken.samples.size == 2400 + 2400 + 24000 * 40 / 1000) { "40 ms gap is in the audio" }
    check(spoken.audioSeconds == spoken.samples.size / 24000.0) { "measured audio includes the gap" }
    events.addAll(spoken.events.map { it.name })
    val heard = ArrayList<String>()
    EmberVoice(fake, VoiceConfig()).speak("Done! Your build passed.") { heard.add(it.name) }
    check(heard == events) { "listener hears the same events" }

    var calls = 0
    val retryEngine = object : PieceEngine {
        override val sampleRate: Int = 24000
        override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
            calls += 1
            check(text == "Done!") { "normalized piece $text" }
            return if (calls == 1) {
                check(seed == 1) { "first seed" }
                PieceAudio(FloatArray(24000 * 10), 24000, 100.0, 120.0)
            } else {
                check(seed == 2) { "retry seed" }
                PieceAudio(FloatArray(24000), 24000, 20.0, 30.0)
            }
        }
        override fun release() {}
    }
    val retried = EmberVoice(retryEngine, VoiceConfig()).speak("Done!")
    check(calls == 2) { "runaway retries once" }
    check(retried.samples.size == 24000) { "shorter take is kept" }
    check(retried.ttfaMs == 150.0) { "buffered retry counts both takes" }

    val cached = selectTier(
        DeviceProfile("phone"),
        listOf(TierRule("phone", true), TierRule("old", false)),
        TierCache(ROUTE_SERVER, VoiceConfig().version),
        VoiceConfig(),
    ) { BenchmarkSample(1.0, 9.0) }
    check(cached.cached && cached.route == ROUTE_SERVER) { "cache" }
    val below = selectTier(
        DeviceProfile("old"),
        listOf(TierRule("phone", true), TierRule("old", false)),
        TierCache(null, null),
        VoiceConfig(),
    ) { throw IllegalStateException("benchmark should not run") }
    check(below.route == ROUTE_SERVER && !below.cached) { "floor" }
    val slow = selectTier(
        DeviceProfile("phone"),
        listOf(TierRule("phone", true)),
        TierCache(null, null),
        VoiceConfig(),
    ) { BenchmarkSample(800.0, 2.0) }
    check(slow.route == ROUTE_SERVER) { "slow benchmark" }
    val fast = selectTier(
        DeviceProfile("phone"),
        listOf(TierRule("phone", true)),
        TierCache(null, null),
        VoiceConfig(),
    ) { BenchmarkSample(400.0, 2.0) }
    check(fast.route == ROUTE_ON_DEVICE) { "fast benchmark" }

    val long = PieceAudio(FloatArray(24000 * 10), 24000, 1.0)
    check(ranLong(VoiceConfig(), "Done!", long)) { "runaway" }
    val short = PieceAudio(FloatArray(24000), 24000, 1.0)
    check(!ranLong(VoiceConfig(), "Done!", short)) { "not a runaway" }

    val oneSecond = prepareReferenceClip(pcm16Wav(24000, 1, 24000) { 1000 })
    check(oneSecond.samples.size == 24000) { "1s stays 24000" }
    check(oneSecond.sampleRate == 24000) { "rate" }
    val ten = prepareReferenceClip(pcm16Wav(32000, 1, 320000) { 1000 })
    check(ten.samples.size == 240000 && ten.sourceRate == 32000) { "32 kHz 10s -> 24 kHz" }
    val stereo = prepareReferenceClip(pcm16Wav(24000, 2, 24000) { index -> if (index % 2 == 0) 0 else 2000 })
    check(stereo.sourceChannels == 2 && stereo.samples.size == 24000) { "stereo mix" }
    val roundTrip = prepareReferenceClip(referenceClipWavBytes(oneSecond))
    check(roundTrip.samples.size == oneSecond.samples.size) { "wav round trip" }
    val spokenNumber = speechPieces("503", 40, 140)
    check(spokenNumber == listOf("five hundred three")) { "503 spoken $spokenNumber" }
    check(expectedAudioSeconds(spokenNumber[0].length) == 18 / 15.0) { "expected uses the normalized length" }
    check(compareVersions("2.0.0-rc1", "2.0.0") < 0) { "pre-release is lower than the release" }
    check(compareVersions("2.0.0", "2.0.0-rc1") > 0) { "release is higher than its pre-release" }
    check(compareVersions("2.0.0+build", "2.0.0") == 0) { "build metadata is ignored" }
    checkFails("expresso") { refuseNonCommercialVoice("voices/expresso/ex03.wav") }
    checkFails("test_wavs") { refuseNonCommercialVoice("models/test_wavs/bria.wav") }
    checkFails("huge") { prepareReferenceClip(ByteArray(64 * 1024 * 1024 + 1)) }
    val truncated = ByteArray(44)
    "RIFF".forEachIndexed { index, ch -> truncated[index] = ch.code.toByte() }
    "WAVE".forEachIndexed { index, ch -> truncated[8 + index] = ch.code.toByte() }
    "data".forEachIndexed { index, ch -> truncated[12 + index] = ch.code.toByte() }
    truncated[16] = 0xff.toByte()
    truncated[17] = 0xff.toByte()
    truncated[18] = 0xff.toByte()
    truncated[19] = 0xff.toByte()
    checkFails("truncated") { prepareReferenceClip(truncated) }
    checkFails("short") { prepareReferenceClip(pcm16Wav(24000, 1, 12000) { 1 }) }
    checkFails("long") { prepareReferenceClip(pcm16Wav(24000, 1, 24000 * 11) { 1 }) }
    checkFails("not a wav") { prepareReferenceClip(ByteArray(64) { 0 }) }
}

private fun checkFails(label: String, block: () -> Unit) {
    try {
        block()
        throw IllegalStateException("$label should have failed")
    } catch (e: VoiceClipException) {
        check(e.message?.isNotBlank() == true) { "$label message" }
    }
}

private fun pcm16Wav(rate: Int, channels: Int, frames: Int, sample: (Int) -> Int): ByteArray {
    val dataBytes = frames * channels * 2
    val out = ByteArray(44 + dataBytes)
    fun put(at: Int, text: String) {
        text.forEachIndexed { index, ch -> out[at + index] = ch.code.toByte() }
    }
    fun put16(at: Int, value: Int) {
        out[at] = (value and 0xff).toByte()
        out[at + 1] = ((value shr 8) and 0xff).toByte()
    }
    fun put32(at: Int, value: Int) {
        out[at] = (value and 0xff).toByte()
        out[at + 1] = ((value shr 8) and 0xff).toByte()
        out[at + 2] = ((value shr 16) and 0xff).toByte()
        out[at + 3] = ((value shr 24) and 0xff).toByte()
    }
    put(0, "RIFF")
    put32(4, 36 + dataBytes)
    put(8, "WAVE")
    put(12, "fmt ")
    put32(16, 16)
    put16(20, 1)
    put16(22, channels)
    put32(24, rate)
    put32(28, rate * channels * 2)
    put16(32, channels * 2)
    put16(34, 16)
    put(36, "data")
    put32(40, dataBytes)
    var i = 0
    while (i < frames * channels) {
        val value = sample(i)
        out[44 + i * 2] = (value and 0xff).toByte()
        out[45 + i * 2] = ((value shr 8) and 0xff).toByte()
        i += 1
    }
    return out
}
