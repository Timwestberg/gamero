# :voice

Mac chat speaks with Kokoro 82M v1.0. PocketTTS stays behind the same `PieceEngine` seam for a later "speak in my voice" clip (`HOVEL_VOICE_BACKEND=sherpa`). The iPhone app speaks with the same Kokoro when its build links sherpa-onnx (`scripts/ios-voice.sh`, see `docs/ios.md`). Android still uses Pocket.

`scripts/fetch-voice.sh` downloads both. Kokoro's pinned files are `model.onnx`, upstream `voices.bin`, `tokens.txt`, and `lexicon-us-en.txt`. `voice/kokoro/pack-blends.py` writes the seven chat blends into a derived `voices-blends.bin` (speaker slots 45–51, which hold Mandarin voices upstream). That file is not committed. The voice a person picks is one of:

| Id | Name | Speed | Blend |
| --- | --- | --- | --- |
| `heart70-nicole30` | Sage | 1.10 | Heart 70 / Nicole 30, the default |
| `heart70-bella30` | Quinn | 1.05 | Heart 70 / Bella 30 |
| `heart60-bella20-nicole20` | Harper | 1.10 | Heart 60 / Bella 20 / Nicole 20 |
| `fenrir90-heart10` | Rowan | 1.00 | Fenrir 90 / Heart 10 |
| `fenrir85-nicole15` | Sam | 1.00 | Fenrir 85 / Nicole 15 |
| `fable70-heart30` | Emery | 1.05 | Fable 70 / Heart 30, British |
| `fable85-emma15` | Jamie | 1.05 | Fable 85 / Emma 15, British |

These are the voices Tim marked natural in two listening rounds on 2026-11-10. What the rounds showed, for the next person who adds a voice:

- One voice leads each mix at 60% or more. Two male voices mixed 70/30 sounded computerized, and so did a male and a female voice at 60/40.
- No mix uses `am_adam`. Upstream grades it F+, and the three voices led by it (the first Sam, Jamie and Riley) were the first to be called computerized.
- Faster reads as more natural: the Heart-led voices went from okay at 1.00 to natural at 1.05 and 1.10. `bm_fable` is a slow talker, so its mixes run at 1.05.
- The raw voices differ in loudness by about 5 dB (the Fenrir-led ones are louder). Nothing evens that out yet.

A new blend is one object in `voice/kokoro/blends.json`, the same object in `KOKORO_BLENDS_JSON` and `web/hearing/src/kokoro-voices.js`, and a free slot. Run `pack-blends.py` with `derivedVoicesSha256` emptied to print the new hash, then pin it in those three files, `web/hearing/src/models.js` and `VoiceConversationTest`. Give it a name the voice can say (`KokoroLexiconTest` checks). tool-webkit keeps its own copy of the pins for the browser voice.

While Ember's chat is open, the Mac window shows a Voice row with these names, and the composer's options hold the same choice on the Mac and the iPhone. Choosing a voice speaks "Hi, I'm …" and the next sentence uses it. The Mac saves the id to `~/.gamero/kokoro-voice`; `HOVEL_KOKORO_VOICE` overrides that file for one launch. A saved id that is no longer a voice falls back to the default.

A word missing from `lexicon-us-en.txt` used to be skipped. Regular inflections of a listed stem are written to `lexicon-chat.txt` beside it, and any word that is still unknown is spelled letter by letter so it is not dropped.

Hovel's own words are a third lexicon, `lexicon-hovel.txt`, loaded last: the voice names upstream lacks (Quinn, Avery, Jamie) and a few products (iPhone, iPad, GitHub, todo, wifi, Gamero), each with its plural. Without it a voice spelled its own name in "Hi, I'm Quinn." The words and their phones are `KOKORO_PRODUCT_WORDS` in `KokoroLexicon.kt`; put a pronunciation together from words the lexicon already holds, so every phone has a token. The Mac writes the file beside the model and the iPhone writes it to its caches. `KokoroLexiconTest` checks that every voice can say its own name. The browser's voice does not load this file yet, so it still spells these words.

Speech is normalized before it is split into sentences: versions (`Sonnet 5.5`, `2.0.1`), units, URLs, and filenames become words. A token the normalizer does not know is spelled. It is not dropped. A mark with no sound is never named: a quotation mark or an emoji is dropped, a dash, a bracket or an ellipsis becomes the pause it stands for (a comma), and a curly apostrophe becomes the plain one so a contraction stays one word (`SpeechPunctuationTest`). `openingClause` cuts the first clause off a long sentence, for a caller that wants the first sound sooner. Each sentence is one Kokoro generate. Playback is the existing stream: the first sentence can start while the next one is still generating. Kokoro's padding at a period is trimmed, then `sentenceGapMs` (160) is the only pause inserted between sentences. The next sentence is synthesized while the current one is still playing, on `KOKORO_THREADS` (4) threads. `startAfterMs` waits once, before the first sentence of a reply.

On-device PocketTTS remains in this module for the cloned-voice path. The Mac companion uses the JVM backend through `DesktopSpeech`.

## What it does

`EmberVoice.speak` sends one piece per sentence. A sentence longer than 400 characters is split at a word boundary. Each piece is capped with `ceil(max(12, 4 + 0.8 * chars))`, so `4 + 0.8 * 28` is 27 frames, and a piece of 24 characters or fewer gets at least 36 frames. `max_char_in_sentence` is at least the piece length, so sherpa-onnx's own splitter does not cut the piece again. Expected audio is `max(0.8 s, normalized chars / 15)`. A take is generated again only when it fills that frame cap, with the same seed and twice the cap. Pocket inserts no silence; a join whose edge is at least 0.02 is crossfaded for 10 ms. Kokoro trims edges quieter than 0.02 and inserts `sentenceGapMs` instead. Five flow steps are the default. If a Mac cannot stay in real time, the fallback is 3. A second `speak` or `release` while a speak is running throws. The listener events are `speech_start`, one `speech_level` per committed piece (RMS), and `speech_end`.

sherpa-onnx 2.0.0 finishes the language model for a piece before the first audio callback. This module does not pretend that callback is a frame streamed while the model is still generating. The first callback is still the TTFA the benchmark records.

Defaults follow ember-voice.v2: `numSteps` 5, `fallbackNumSteps` 3, temperature 0.3, seed 1, `silenceBetweenPiecesMs` 0, `decoderFramesPerCallback` 2, `longSentenceMaxChars` 400, `crossfadeMs` 10, `startAfterMs` 200, `maxDurationRatio` 2. Tier names are local. A cached route is reused when the config version matches. A tier the host marks as below the floor goes to the server without a benchmark. Anything else speaks `task_finished` once and stays on device only if TTFA is at most 500 ms and RTF is at least 1.5. `./fluency.sh` speaks a fixed 3-turn conversation and prints the acceptance metrics.

## JVM

```bash
cd voice
./jvm-check.sh
./jvm-check.sh --speak
```

`--speak` needs the model from `spikes/ember-voice/mac/setup.sh`. It is not committed.

Android uses the same Kotlin sherpa API once `voice/libs/sherpa-onnx.aar` is present (not committed) and the build is `-Pgamero.mobileTargets=true`.

## iOS

`iosMain` calls `voice/ios/ember_voice_bridge.h`. Kokoro is `KokoroIosPieceEngine`: the same model files, voice blends and lexicons as the Mac, with the lexicons' words kept as hashes (`LexiconWords`) to spare the phone's memory. The app compiles the bridge through `iosApp/iosApp/EmberVoiceBridge.mm`; without the sherpa headers on its search path every engine is NULL. The Swift and Objective-C++ next to that header include the sherpa-onnx 2.0.0 xcframework, so the Kotlin side does not guess sherpa's struct layout. `voice/ios-bench/embed-sherpa.sh` copies that framework into the app bundle. The Xcode target turns user-script sandboxing off so the script can read `SHERPA_ONNX_FRAMEWORKS`. `backend = "fluid-audio"` selects FluidAudio. One `PocketTtsManager` and one cloned voice last for the conversation. Later pieces only open a session on that clone. It does not speak a bundled named voice. Without that package linked, the bridge returns NULL and the engine says so. A second speak on the C bridge returns 0 instead of running two generations on one engine.

One command, on the Mac that has the phones plugged in:

```bash
export SHERPA_ONNX_XCFRAMEWORK=/path/to/sherpa-onnx.xcframework
cd voice/ios-bench
./run.sh
```

It writes `bench-config.json`, then runs the corpus on every connected iPhone 14 Pro Max and iPad Pro. An iPad Pro that can install an iOS 18 build is an M-series iPad. Simulators are skipped. The JSON is in the test log and attached to the xcresult under `voice/ios-bench/results/`.

`./run.sh --self-check` only checks the device-name matching, including the `xcodebuild -showdestinations` line shape. It does not need Xcode, and this VM does not produce iOS timings. Running `./run.sh` without that flag stops if `xcodebuild` is missing.

The piece list is `voice/ios-bench/EmberVoiceBench/pieces.json`, generated by `./jvm-check.sh --write-pieces` from the shared splitter. Regenerate it when the splitter or the frame curve changes.

`./run.sh --voice /path/to/clip.wav` selects the reference clip. The default, when you omit the flag, stays the CC0 `voice-donations/0a67_enhanced` clip. The runner resamples the file to 24 kHz mono and copies it into the test bundle before the phones are built.

## A voice of your own

Record a few seconds of clean speech in a quiet room. Export a WAV. The shared contract clip is 32 kHz, 16-bit mono PCM, 10 seconds. The module mixes a clip to mono and resamples it to 24 kHz, which is the synthesis rate, and rejects a clip shorter than 1 second or longer than 10 seconds. Version checks treat a pre-release as lower than the release (`2.0.0-rc1` is lower than `2.0.0`).

Written consent is required from the person on the recording.

The default stays the CC0 donation `kyutai/tts-voices` `voice-donations/0a67_enhanced`. Do not use Expresso. Do not use the clips under `test_wavs`, including `bria.wav`. Those are not licensed for this.

```bash
cd spikes/ember-voice/bench
./run.sh --voice /path/to/my-clip.wav
./run.sh --compare-voices ../mac/models/voices/0a67_enhanced-24k.wav /path/to/my-clip.wav
```

The comparison runs the same corpus on both clips and writes `results/voice-compare/summary.json` (TTFA, RTF, median WER on each line) plus a WAV set under `results/voice-compare/<clip>/wav/` so you can listen.

```bash
cd voice/ios-bench
./run.sh --voice /path/to/my-clip.wav
```
