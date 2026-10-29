# Fluid, consistent Ember voice

> **Status:** Tim approved this plan. The decisions in the next section are what the implementation follows. This file is still only the plan. It contains no implementation code.
>
> Drafts: #53 synthesis (base `main`), #54 streaming (base #53), #56 Kokoro chat (base #54; Mac chat no longer uses Pocket), #55 iOS FluidAudio (base #53, merges after #56). Merge #52, then #53, #54, #56, then #55.

**Goal:** Make Ember's spoken replies one continuous voice across a conversation, with sentence audio that flows instead of restarting every few dozen characters.

**Architecture:** Keep the one `SherpaPocketEngine` and its cached speaker embedding for the life of the desktop chat. Change `:voice` so a reply is split only on sentence boundaries, pieces are joined without a silence insert, and a truncation retry uses the same seed. Play the first sentence through a streaming audio line while the next sentence synthesizes. Manual stop already exists; microphone barge-in does not.

**Tech Stack:** Kotlin (`:voice`, `composeApp` desktop), sherpa-onnx 2.0.0 PocketTTS, JavaSound (`SourceDataLine` replacing `Clip`), optional FluidAudio on iOS. The shared numbers live in server-platform `event-contracts` (draft PR 180).

**Evidence:** Investigation on `main` at `cbdee5f`. Live speech is `DesktopSpeech` (`composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/chat/DesktopSpeech.kt`). A 3-turn probe on this Linux VM (int8 PocketTTS, CC0 `0a67_enhanced-24k.wav`, 1 step, temperature 0.3, seed 1) cut two of the three replies mid-sentence, inserted one exact 40 ms zero run in every reply, and moved pitch from 122 Hz to 145 Hz to 137 Hz across three sentences that shared the same embedding. The same sentence at seed 1 repeated (spectral cosine 1.0). Seed 2 of that sentence was 144 Hz.

## Global constraints

- Voices stay CC0 or otherwise permissive. Refuse any path containing `expresso` or `test_wavs`. Never use those clips.
- No secrets or tokens in code, logs, or the plan's later patches.
- Voice audio never goes into Godot or over the pet socket. Spoken text may mention those names. The samples stay in the voice player.
- Synthesis rate is 24 kHz. The contract reference file is 32 kHz, 16-bit mono PCM, 10 s, resampled once when the engine is created.
- Seed stays 1 for every piece and every retry. Temperature stays 0.3 unless Tim changes it in the contract PR.
- Do not rewrite the committed Linux bench JSON files. New measurements get a new results file.
- Leave the PRs as drafts. Tim's owner merges.
- sherpa-onnx 2.0.0 has no public session that carries the language-model state across `generate` calls. Continuity comes from longer pieces and from not restarting the sampler's seed, not from a hidden context API.

---

## Contract values (server-platform #180)

Update the shared voice config in `event-contracts` in a draft PR before the gamero implementation merges. Gamero's `VoiceConfig` defaults and the bench `config/bench.conf` follow that PR. Do not edit server-platform from the gamero plan PR.

| Field | Today | Planned | Why |
|---|---|---|---|
| `numSteps` | 1 | 5, fallback 3 | One Euler step lets noise move pitch between sentences. 5 is the stability target. Tim chose 3 as the fallback if a Mac cannot stay in real time. The module does not switch steps by itself. |
| `temperature` | 0.3 | 0.3 | Unchanged. Seed 1 already makes the same text repeat. |
| `seed` | 1 | 1, including retries | A seed change is a different draw. Seed 2 of one sentence moved 122 Hz to 144 Hz. |
| `firstPieceTargetChars` | 40 | unused for cutting | The 40-character window cuts inside a sentence (`SpeechText.kt`). The splitter keeps whole sentences. |
| `laterPieceMaxChars` | 140 | unused for packing | Do not pack or split mid-sentence at 140. |
| `silenceMs` | 40 | 0 | The 40 ms gap is inserted zeros (`Speech.kt`). Playback crossfades instead. |
| Frame ceiling `0.8 / 4 / 12` | `ceil(max(12, 4 + 0.8 * chars))` | keep, as a safety cap | Still bounds a runaway. It is not a duration target. |
| `shortPiece` 24 / 36 | pieces of 24 characters or fewer get at least 36 frames | keep | This floor is what stopped a short sentence from being clipped. Do not drop it in the fluency change. |
| Retry | seed + 1, keep the shorter take | same seed; raise the cap only when the take hit `max_frames` | Same-seed regeneration of an overlong take is a repeat (measured max sample delta 0.003). It cannot pick a shorter voice. See the open question. |
| Expected audio | `max(0.8 s, chars/15)` on the normalized piece | unchanged | Still the definition of "overlong" (`2×` that). |

Crossfade length (10 ms) and the jitter buffer (200 ms) are player behavior. They do not belong in the synthesis contract.

## Pull request order

All of these stay drafts.

1. **server-platform contract draft.** Update `event-contracts` voice config and the corpus notes to the table above. This agent cannot read that private repo. Someone with access opens or updates draft #180 (or a successor if #180 has merged). Gamero does not vendor a copy of the private schema.
2. **gamero draft A, synthesis policy.** `:voice` splitter, join, retry, and `numSteps` default, plus the bench config comments. No desktop player change. Linux can prove this PR.
3. **gamero draft B, stacked on A.** Replace the JavaSound `Clip` with a streaming line, overlap the next sentence, and make `stopSpeaking` drop queued audio during that stream.
4. **gamero draft C, stacked on A (independent of B).** iOS FluidAudio keeps one `PocketTtsManager` and one cloned voice for the conversation. Linux cannot compile or run it.

A is the only PR required for the voice-identity and gap metrics. B is required for time-to-first-audio. C is required only for the iOS backend.

## Files

| Unit | Files | Responsibility |
|---|---|---|
| Splitter | `voice/src/commonMain/kotlin/org/formulagen/wrapper/voice/SpeechText.kt` | Sentences only, after the existing number, time, and filename normalization. |
| Join and retry | `voice/src/commonMain/kotlin/org/formulagen/wrapper/voice/Speech.kt` | No zero insert. Crossfade helper. Same-seed cap retry. |
| Defaults | `voice/src/commonMain/kotlin/org/formulagen/wrapper/voice/VoiceConfig.kt` | `numSteps = 5`, `silenceMs = 0`. Ceiling and short floor stay. |
| Checks | `voice/src/commonMain/kotlin/org/formulagen/wrapper/voice/VoiceSelfCheck.kt`, `voice/src/commonTest/.../VoiceLogicTest.kt` | Logic checks. No model. |
| Bench mirror | `spikes/ember-voice/bench/src/Chunker.kt`, `Bench.kt`, `config/bench.conf`, `SCHEMA.md` | Keep the bench splitter in step with `:voice`. Do not overwrite committed result JSON. |
| Desktop player | `composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/chat/DesktopSpeech.kt` | Streaming line, 200 ms start, overlap, stop drops the queue. |
| Desktop tests | `composeApp/src/jvmTest/kotlin/org/formulagen/wrapper/chat/DesktopChatTest.kt` | Fake engine and fake line. |
| iOS session | `voice/ios/EmberVoiceFluid.swift`, `voice/src/iosMain/kotlin/org/formulagen/wrapper/voice/IosEngine.kt` | One manager and one clone per engine lifetime. |
| Probe | `spikes/ember-voice/bench` self-check plus a small metrics main already patterned by the investigation probe | F0, spectrum, gaps, RTF. Not a committed WAV. |

`SherpaPocketEngine` stays one instance per `DesktopSpeech`. It already loads the reference once (`SherpaPocketEngine.kt` init) and the JVM cache capacity is already 50. Do not create an engine per reply.

---

### Task 1: Sentence pieces and no silence insert

**Files:** `SpeechText.kt`, `Speech.kt`, `VoiceSelfCheck.kt`, bench `Chunker.kt` and its self-check.

**Interfaces:**
- Consumes: `normalizeForSpeech`, existing sentence split on `.` `!` `?`.
- Produces: `speechPieces(text, firstTargetChars, laterMaxChars)` returns one string per sentence. The two integer arguments remain so callers compile, and they no longer cut or pack. `concat(parts, gapSamples, crossfadeSamples)` joins pieces. `gapSamples` for the live config is 0. Crossfade is 10 ms (`sampleRate / 100`) only when either edge's peak absolute amplitude over that window is at least 0.02. Otherwise abut the pieces. A fade mixes out the tail of the previous piece and in the head of the next. Output length is `sum(lengths) - fade` when a fade runs, else the sum.

- [ ] Add failing checks. `speechPieces("I finished the task. Three files changed and all tests are green.", 40, 140)` stays two sentences. `speechPieces("Should I rerun the suite, or is this ready for you to merge?", 40, 140)` is one piece. `speechPieces("I refactored the session code, moved the socket into the bridge, and the build is green.", 40, 140)` is one piece. Two non-silent pieces joined with gap 0 and a hot edge contain no run of exact zeros longer than 5 ms. Two pieces that already end and start below 0.02 are concatenated with no overlap.
- [ ] Run `voice/jvm-check.sh` (or `./gradlew :voice:jvmTest` when the sherpa jar is absent and the stub factory is on the classpath). Expect the new checks to fail.
- [ ] Change the splitter and the join. Leave normalization as it is.
- [ ] Re-run the same check. Expect pass.
- [ ] Mirror the splitter in the bench `Chunker.kt` and extend `./run.sh --self-check` with the same three sentences. Expect `self-check: ok`.

### Task 2: Same-seed truncation retry

**Files:** `Speech.kt`, `VoiceSelfCheck.kt`.

**Interfaces:**
- Consumes: `PieceEngine.synthesize(text, maxFrames, seed)`, `VoiceConfig.framesFor`, `expectedAudioSeconds`, `maxDurationRatio`.
- Produces: `fun retryFrames(firstCap: Int): Int` = `min(firstCap * 2, 500)`. The second call uses `config.seed`, never `seed + 1`. Retry only when `maxFrames > 0` and the take's sample count is at least `maxFrames * frameSamples` (1920 samples at 24 kHz, one 80 ms frame) minus one frame, which means the ceiling stopped the take. Keep the second take. An overlong take that ended on its own (sample count well under the ceiling) is kept as-is and is not regenerated.

- [ ] Failing fake-engine check: first call with seed 1 returns audio whose length equals the ceiling; second call must be observed with seed 1 and `retryFrames`; the spoken samples are the second take. A second case returns audio longer than `2 × expected` but shorter than the ceiling; `synthesize` is called once.
- [ ] Implement that rule and delete the seed+1 branch.
- [ ] Re-run `:voice:jvmTest`. Expect pass.

### Task 3: Five flow steps, with a recorded fallback

**Files:** `VoiceConfig.kt`, `spikes/ember-voice/bench/config/bench.conf`, `SCHEMA.md`.

**Interfaces:**
- Consumes: `GenerationConfig.numSteps`, already taken from `VoiceConfig.numSteps`.
- Produces: default `numSteps = 5`. The recorded fallback is 3. Do not change the shipped default unless Tim's Mac RTF check says to.

- [ ] Update the default and the bench config comment. Add a self-check that `VoiceConfig().numSteps == 5` and `silenceMs == 0`.
- [ ] Run the logic self-check. Expect pass.
- [ ] Do not retune the Mac spike's historical 5-step / 0.7 measurement config. That spike is a different program and its recorded tables depend on the old harness settings.

### Task 4: Stream the first sentence

**Files:** `DesktopSpeech.kt`, `DesktopChatTest.kt`.

**Interfaces:**
- Consumes: `EmberVoice` must expose sentence audio as it is produced. Add `fun speakStreaming(text: String, onSentence: (FloatArray, Int) -> Boolean): SpeakResult`. Returning `false` from `onSentence` cancels the rest. The existing `speak` keeps working by collecting every sentence, so current callers compile.
- Produces: `DesktopSpeech.speak` synthesizes sentence 1 on the voice worker, then starts a `SourceDataLine` opened at 24 kHz, 16-bit, mono, little-endian. If the mixer refuses 24 kHz, open the line's default rate and resample with the existing linear resampler in `ReferenceClip.kt`. Wait until 200 ms of sentence-1 audio is queued or the sentence has ended, whichever comes first, then start the line. Synthesize sentence N+1 on the voice worker while the line drains sentence N. One `generate` at a time. The engine busy flag stays held for the whole reply.

- [ ] Failing test with a fake engine that blocks on sentence 2 and a fake line that records `start` time. `start` happens after sentence 1 is delivered and before sentence 2 returns. A second test feeds two silent sentences and asserts the line never sees a 40 ms zero insert from the joiner.
- [ ] Replace `AudioSystem.getClip()` in `DesktopSpeech.kt:62-64`. Keep the generation counter in `stopLocked`.
- [ ] Run `./gradlew :composeApp:jvmTest`. Expect pass, including the existing `stopSpeaking` test.

### Task 5: Barge-in against what exists today

**What exists:** Desktop `chat.stopSpeaking` (`ChatCapability.kt` method `stopSpeaking`, `DesktopChat.stopSpeaking` at `DesktopChat.kt:290-291`) sets `session.speak = false` and calls `speech.stop()`. `stopLocked` (`DesktopSpeech.kt:94`) bumps `generation`, stops the clip, and drops it. There is no microphone capture, no voice-activity detector, and no speech-to-text anywhere under `composeApp/` or `voice/`. Android and iOS mark `chat.stopSpeaking` unsupported in the host-bridge parity fixture.

**This plan's barge-in:** wire the existing stop into the streaming player. Do not build a mic, VAD, or STT stack here.

**Interfaces:**
- Consumes: `generation` ticket already in `DesktopSpeech`.
- Produces: `stop` sets an `AtomicBoolean` that the sherpa progress callback observes and returns 0, so the in-flight `generate` stops. Audio already queued in the line is drained with `flush` and the line stops. `onSentence` returns false, so no later sentence is synthesized. `chat.stopSpeaking` stays the only public trigger.

- [ ] Failing test: start a two-sentence fake speak, call `stop()` after sentence 1 is handed to the line, assert sentence 2 is not synthesized and the line's `stop`/`flush` ran.
- [ ] Implement the cancel flag. Do not add a `TargetDataLine`.
- [ ] Run `:composeApp:jvmTest`. Expect pass.

A later project can feed `stopSpeaking` from a VAD on the input device. That project needs a capture permission, a detector, and a decision about whether the captured audio is transcribed. It is out of scope until Tim asks for it.

### Task 6: Metrics on a 3-turn conversation

**Files:** a bench entry point next to `spikes/ember-voice/bench/src/Bench.kt`, invoked as `./run.sh --fluency` (name fixed in the implementation). It writes `results/fluency-linux.json`, which is gitignored like the other uncommitted results. It does not write the committed baseline files.

**Script, fixed text:**

1. `I finished the task. Three files changed and all tests are green.`
2. `I refactored the session code, moved the socket into the bridge, and the build is green.`
3. `Should I rerun the suite, or is this ready for you to merge?`

Plus one repeat of sentence `Hello, this is Ember.` at seed 1.

**Metrics and pass bars:**

| Metric | How | Pass |
|---|---|---|
| Piece boundaries | Print `speechPieces` for the three turns | Turns 2 and 3 are one piece each. Turn 1 is two sentences. No piece boundary falls inside a sentence. |
| Zero runs | Scan the joined PCM for exact zeros | No run longer than 5 ms between pieces. The old 40 ms run is gone. |
| Same-text stability | Spectral cosine of mean log-magnitude, first 3 s, FFT 1024 | Repeat of `Hello, this is Ember.` cosine ≥ 0.99. |
| Cross-sentence similarity | Same spectrum, pairwise across the three spoken turns | Report the minimum. Target ≥ 0.75. If 5 steps miss it, record the number and stop. Do not silently return to 1 step. |
| F0 spread | Autocorrelation pitch on a 1 s window from the middle of each sentence | Report min, max, and range in Hz. Target range ≤ 15 Hz. The 1-step baseline on this VM was 122–145 Hz (range 23). |
| Time to first audio | Clock from `speak` to the first line write | Less than or equal to sentence-1 synthesis plus 200 ms. Not the sum of the reply. |
| RTF | Audio seconds / synthesis seconds for the three turns together | Report it. On a Mac-class machine, if RTF < 1.0 at 5 steps, ship the fallback of 3, remeasure F0 and cosine, and put both numbers in the PR. |
| Retry seed | Log the seed of every `synthesize` | Every call is seed 1. |

- [ ] Run `./run.sh --fluency` on Linux after Task 3. Commit the summary numbers into the PR description, not a new baseline JSON.
- [ ] Mac RTF is a manual step on Tim's machine. Linux RTF is not the fallback trigger. This VM was faster than real time even at 1 step (RTF about 2.9–3.7 for the chopped replies) and is not Mac-class.

### Task 7: One iOS voice clone per conversation

**Files:** `EmberVoiceFluid.swift`, `IosEngine.kt`.

**Interfaces:**
- Consumes: `FluidAudioPieceEngine`, which today calls `ember_voice_fluid_speak` per piece.
- Produces: `ember_voice_fluid_create` still builds one native box per engine. That box creates `PocketTtsManager`, calls `initialize()`, and `cloneVoice(from:)` once. Later `speak` calls only `makeSession(voiceData:temperature:seed:)` with the stored clone and seed 1. `release` cleans the manager. The bundled named voice (`alba`) stays unused.

- [ ] This cannot be compiled or run on the Linux agent. Review the diff against the FluidAudio session API (`initialize`, `cloneVoice(from:)`, `makeSession(voiceData:temperature:seed:)`, `enqueue`, `finish`, `frames`, `cancel`).
- [ ] `voice/ios-bench/run.sh --self-check` still passes (device-name matching only). Do not invent device timings.

## Risks

- **Same-seed retry does not change an overlong take.** The measurement showed seed 1 repeats. Raising the frame cap only helps when the cap itself stopped the audio. Task 2 follows that. If Tim wanted a second draw that can be shorter, that requires a different seed, which is the voice change we are removing.
- **Five steps may miss the old 500 ms TTFA bar** on a Mac. Streaming makes TTFA the first sentence only, which is the right number to compare. A full-reply `Clip` TTFA is no longer the product metric.
- **JavaSound may refuse 24 kHz.** Task 4 resamples to the line's rate. A bad resampler will sound dull. Use the existing linear resampler only as the fallback, and log the rate actually opened.
- **`generate` cannot be preempted except by the progress callback returning 0.** Barge-in latency is the rest of the current frame loop, not zero. Do not call `generate` on two threads. The engine lock stays one speaker.
- **The short-piece floor and the ceiling stay.** Removing them would re-open the "your buil" clip from the earlier bench. WER is not re-run in this plan unless the ceiling constants change.
- **Bench history.** Old result files were measured with the 40-character chunker, 40 ms gaps, and 1 step. Leave them. A fluency run is a new file.
- **iOS is unverified.** Draft C can merge only as code inspection plus the script self-check.
- **No input audio path.** Shipping a "barge-in" that listens would be a new permission and a new subsystem. Task 5 only makes the existing stop button actually cut a stream.

## Decisions (Tim)

1. Retry only when the frame ceiling truncates the take. Same seed. Raised cap is `min(firstCap * 2, 500)`. An overlong take that stopped on its own is kept.
2. A sentence longer than 400 characters is split at a word boundary. Shorter sentences stay whole.
3. Fallback step count is 3, not 2. Tim runs the Mac RTF check. The code does not switch steps by itself.
4. Pass bars stand: cross-sentence spectral cosine ≥ 0.75, F0 range ≤ 15 Hz, no zero run over 5 ms, time-to-first-audio ≤ first-sentence synthesis + 200 ms. Report the numbers either way.
5. Barge-in for this plan is `chat.stopSpeaking` cutting the stream. Microphone, VAD, and speech-to-text stay out.
6. Crossfade only when an edge is at least 0.02. Quiet edges are abutted.
7. #180 merged. The follow-on contract draft is server-platform #192 (`ember-voice.v2`). Gamero's field names follow that draft. Kokoro chat (#56) adds engine id, blend id, per-voice speed, and the lexicon hash, which #192 still needs.

## Sizing

This is three small code surfaces plus one player rewrite. No new model, no new native library on the desktop.

- Tasks 1–3 touch the Kotlin splitter, the join, the retry, and defaults. The tests are pure and run on Linux. This is the fluency fix for gaps and for seed changes.
- Task 4 is the risky edit: one desktop class changes from "synthesize everything, then `Clip`" to a line plus a 200 ms start. The fake-line test is what proves overlap without a speaker.
- Task 5 is a small addition on that player, reusing `stopSpeaking`. It is not a listening feature.
- Task 6 is a measurement run, Linux automatically and Mac manually.
- Task 7 is a short Swift lifetime change. It is untestable in CI here.

Contract PR first, then gamero draft A, then drafts B and C on top of A.
