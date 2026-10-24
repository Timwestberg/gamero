package org.formulagen.wrapper.voice

/** Pure checks. No model and no native library. */
fun runVoiceSelfCheck() {
    check(frameBudget(5, 0.0, 0.9, 0, 24, 24) == 24) { "short floor" }
    check(frameBudget(18, 0.0, 0.9, 0, 24, 24) == 24) { "18 chars" }
    check(frameBudget(40, 0.0, 0.9, 0, 24, 24) == 36) { "40 chars on the slope" }
    check(frameBudget(5, 4.0, 0.8, 12, 24, 36) == 36) { "chosen short floor" }
    check(frameBudget(18, 4.0, 0.8, 12, 24, 36) == 36) { "18 chars get 36" }
    check(frameBudget(36, 4.0, 0.8, 12, 24, 36) == 33) { "36 chars is 4+0.8*36" }
    check(frameBudget(39, 4.0, 0.8, 12, 24, 36) == 36) { "39 chars is max(12, 35.2) -> 36" }
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
    check(spoken.samples.isNotEmpty()) { "audio" }
    events.addAll(spoken.events.map { it.name })

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
}
