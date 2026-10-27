# Ember voice: PocketTTS timing spike

Step 1 of the Ember Voice plan. This measures whether Kyutai PocketTTS, through sherpa-onnx, is fast enough for the desktop pet to speak in real time. It does not touch the app. The desktop companion is JVM code, so the harness is Kotlin on sherpa-onnx's Java binding (`OfflineTts.generateWithConfigAndCallback`), the same call the companion would make.

The model is the int8 build `sherpa-onnx-pocket-tts-int8-2026-01-26` (sherpa-onnx 2.0.0). Weights stay in a gitignored folder.

## Prerequisites

- Java 17 or newer (`java -version`). Verified with OpenJDK 21.
- `curl` and `tar` (both already on macOS).
- `unzip` (already on macOS). `setup.sh` falls back to `jar` or `python3` if `unzip` is missing.
- Homebrew only if Java is not installed yet:

```bash
brew install openjdk@21
export PATH="$(brew --prefix openjdk@21)/bin:$PATH"
java -version
```

No Python package is required. PocketTTS's own Python package is not used: later ports are iOS, Android, and web, and sherpa-onnx is the engine those can share.

## Commands

From Terminal.app, in this directory:

```bash
cd spikes/ember-voice/mac
./setup.sh
./run.sh
```

`./setup.sh` downloads the int8 model (~94 MB), the default CC0 voice, the sherpa-onnx JVM jar, the native library for this Mac, and a Kotlin compiler. Re-running it skips anything already downloaded and hash-checked. For the default voice it also converts the 32 kHz donation to 24 kHz once, so later synthesis calls do not resample it.

`./run.sh` prints a table, writes `results/ember-voice-results.json` and `results/ember-voice-results.csv`, and saves one WAV per line under `results/wav/`.

Play the WAVs (macOS uses `afplay`):

```bash
./run.sh --play
```

Run without `--play` first if you want the peak-RSS number from `/usr/bin/time` to cover synthesis only. Playback happens after the table is printed, inside the same process, so a `--play` run includes it in the `time` figure. The in-process peak is taken before playback.

Other flags:

```bash
./run.sh --only build_passed
./run.sh --warm-runs 2
./run.sh --sweep
./run.sh --self-check
./run.sh --help
```

`--sweep` keeps the model loaded and times `sweep_chunk_sizes` from the config (2, 4, 6, 10, and 15). For each value it prints warm TTFA median and p90, plus RTF, for every line. It writes `results/ember-voice-sweep.json` and `results/ember-voice-sweep.csv`. It does not write WAVs.

`--chunk-size N` and `--max-char N` override those two config values for one process. `--self-check` confirms the six fixed lines, the sentence splitter, and the median / percentile math. It does not load the model.

## Change the voice

PocketTTS in this sherpa-onnx build is a reference-audio cloner. There is no speaker id. The harness passes the wav to `GenerationConfig.setReferenceAudio` and `setReferenceSampleRate`. The clip must be mono 16-bit PCM, which is the only format sherpa-onnx's `WaveReader` accepts.

The default is a CC0 donation:

`kyutai/tts-voices` `voice-donations/0a67_enhanced.wav` (16-bit mono, 32 kHz, about 10 seconds).

`./setup.sh` keeps that file as `models/voices/0a67_enhanced.source.wav` and writes `models/voices/0a67_enhanced-24k.wav`, which is the `voice` path in the config. PocketTTS speaks at 24 kHz. A 24 kHz mono 16-bit prompt skips the per-call resample (sherpa-onnx otherwise logs `Creating a resampler` on every call). Conversion uses `ffmpeg` if it is installed, then `sox`, then a small Kotlin resampler in the harness. The Kotlin path is linear interpolation, so prefer ffmpeg or sox when you have them. The stamp file next to the 24 kHz wav records the source hash. Delete the 24 kHz wav to convert again.

Edit both of these in `config/harness.conf`, then rerun `./setup.sh` and `./run.sh`:

```
voice=models/voices/some-other.wav
voice_url=https://huggingface.co/kyutai/tts-voices/resolve/main/voice-donations/some-other.wav
```

`setup.sh` downloads `voice_url` only when `voice` is missing. To force a new download, delete the old wav or use a new `voice` path. A file you already have on disk works too: set `voice` to that path and leave the file in place.

Stay inside `voice-donations/` (or another CC0 set such as `voice-zero/`). Do not use Expresso. The clips under `models/.../test_wavs/` (including `bria.wav`) and anything under `expresso/` are CC-BY-NC. `setup.sh` and `run.sh` refuse a voice path or URL that contains `expresso` or `test_wavs`.

`num_steps`, `num_threads`, `temperature`, `chunk_size`, and `max_char_in_sentence` are in the same config file. The engine defaults match sherpa-onnx's PocketTTS example: 5 flow steps, 2 threads, temperature 0.7. Fewer `num_steps` is faster and lower quality.

`chunk_size` is decoder frames per callback. One frame is 1920 samples, 80 ms at 24 kHz. It has to be at least 1 (0 divides by zero inside sherpa-onnx). The language model still generates every latent frame of the first text piece, up to end-of-speech, before the decoder runs. `chunk_size` only decides how those frames are sliced into callbacks. A smaller value can start playback a little sooner and makes the first chunk shorter. It does not start audio before that first piece is finished. `./run.sh --sweep` prints the latency versus RTF tradeoff.

`max_char_in_sentence` is sherpa-onnx's long-sentence cut. The splitter breaks on `.` `!` `?`, merges pieces shorter than 30 characters, then cuts anything longer than this at the previous space. TTFA waits for the first piece. `PetSession.kt` splits at the period, which is why `summary` starts on a 44-character piece. `say_limit` has no period, so the default of 200 leaves a 196-character first piece. This config uses 120, which cuts only that line (116 + 119) and leaves the other five lines on the same pieces as 200. 80 was faster on `say_limit` but also left `summary` with leftover pieces `tests.` and `before merge.`

stderr still prints sherpa-onnx's config dump on model load. The table comes after the runs.

## How to read the results

The table's warm columns are 10 measured runs per line (the count is `warm_runs` in the config), after one discarded warmup of that line. Median of an even count is the average of the two middle values. p90 and p10 use nearest rank: for 10 runs, p90 is the 9th sorted value and p10 is the 1st.

- **TTFA** is the time from the `generateWithConfigAndCallback` call to the first callback that contains audio samples. The streaming line under the table says whether that callback was a partial chunk or the whole utterance.
- **synth** is the time until that call returns.
- **RTF** is audio seconds divided by synthesis seconds. 1.5x means the line was synthesized in two thirds of its audio length. Higher is faster. sherpa-onnx's own examples print the inverse (synthesis / audio). This harness does not.
- **Cold start** is model-load time plus synthesis of the first line (`build_passed`). Native-library load and JVM startup are printed separately and are not inside cold start. The companion process would already be running.
- **Peak RSS** is the process high-water mark. On Linux that is `VmHWM`. On macOS the harness samples `ps -o rss` and `./run.sh` also records `/usr/bin/time -l` (bytes there, converted to KiB in the JSON). On Linux, `./run.sh` records `/usr/bin/time -v` when that binary exists. The JSON field `external_peak_rss_kb` is the `time` figure; `peak_rss_kb` is the in-process figure.

Pass bar, printed with every run: warm median TTFA at or under 500 ms, and warm median RTF at or above 1.5x, on every line. The process still exits 0 when the bar is missed. A failed synthesis exits non-zero. `overall_pass` in the JSON is the bar.

`say_limit` is 236 characters. The pet's say limit is 240.

Temperature is 0.7, so duration can move a little between runs. The table uses the median audio length and the median RTF, not the RTF of the medians.

## October 2026 tiny models

Kyutai's 20 October 2026 English models (`english_18m_2026-10`, about 18.2M parameters, and the smaller 7M / 2M variants) ship in pocket-tts v4. sherpa-onnx 2.0.0 does not publish them. The tts-models release still has the January 2026 PocketTTS archives only. A community ONNX export exists for onnxruntime-web, which this JNI binding cannot load. This spike times the int8 sherpa-onnx archive. If a later sherpa-onnx drop uses the same seven files (`lm_flow`, `lm_main`, `encoder`, `decoder`, `text_conditioner`, `vocab.json`, `token_scores.json`), point `model_dir` at that directory.

## What dominates TTFA

Sherpa-onnx splits the line before PocketTTS runs. The language model then generates every latent frame of the first piece, until end-of-speech, and only then does the decoder emit callbacks. `chunk_size` groups those decoder frames. One frame is 80 ms at 24 kHz, so the default of 2 makes the first callback 160 ms of audio. TTFA still includes the whole first piece.

The splitter breaks on `.` `!` `?`, merges pieces under 30 characters, and cuts anything over `max_char_in_sentence` at the previous space. A piece that ends in punctuation is joined to the next piece without an added space, so `build_passed` is one 23-character piece: `Done!Your build passed.` `PetSession.kt` breaks at the period, which is why `summary` starts on `Here's the summary: I refactored PetSession.` (44 characters) and the next piece begins `kt, moved...`.

Two slow numbers from the first Linux run come from that pipeline:

- `build_passed` warm TTFA p90 was 3948 ms. Sorted warm runs included 3948 ms for 9.53 s of audio (15 callbacks) and 8885 ms for 19.43 s of audio (34 callbacks). The short runs were about 1.5–2.5 s of audio and 530–1023 ms TTFA. Callback count and audio length grow with the TTFA, which is a late end-of-speech at temperature 0.7. The harness prints a tail line when a line's longest warm audio is more than twice its median. `max_frames` stays at sherpa-onnx's default of 500 (about 40 s). A cap low enough to stop a 19 s runaway would also cut off a long first piece: at `max_char` 200 the first `say_limit` piece is 196 characters and the utterance ran about 12–16 s.
- `say_limit` warm TTFA median was 3542 ms with RTF still about 3x. The line has no `.` `!` or `?`, so a cap of 200 leaves 196 + 39 characters and the first audio waits on the 196-character piece. `summary` is a similar length and was near 800 ms only because its first piece is 44 characters. The config now uses 120, which changes only `say_limit` (116 + 119). A cap of 80 was faster on that line and also left `summary` with leftover pieces `tests.` and `before merge.`

Converting the voice to 24 kHz removed the per-call resample. It did not move TTFA. With `chunk_size` 15 and `max_char` 200, four warm `say_limit` runs at 24 kHz had median TTFA 3657 ms and RTF 3.01x, against 3542 ms and 3.04x on the 32 kHz clip. In the chunk sweep, `build_passed` at chunk 15 (same 23-character piece) was 616 ms median, against 681 ms before. The 24 kHz runs do not log `Creating a resampler`.

## Linux VM check

These numbers are from the cloud Linux VM that built this spike. They are not Mac numbers. Tim's Apple Silicon run is the one that answers the pet question.

Host: Linux 6.12.94+ x86_64, Intel Xeon, 4 CPUs, OpenJDK 21.0.12.1. sherpa-onnx 2.0.0 JVM, int8 PocketTTS, 5 steps, 2 threads, temperature 0.7, CC0 voice `voice-donations/0a67_enhanced.wav`.

### Before

32 kHz voice, resampled on every call. `chunk_size` 15. `max_char_in_sentence` 200 (sherpa-onnx's default). `./run.sh` wall clock 2 minutes 46 seconds.

| | |
| --- | --- |
| Native lib load | 143 ms (not part of cold start) |
| Model load | 592 ms |
| Cold TTFA (`build_passed`) | 747 ms, first callback |
| Cold synthesis | 822 ms, 1.79 s of audio, RTF 2.18x |
| Cold start (load + first line) | 1414 ms |
| RSS after model load | 374 MiB |
| Peak RSS | 738 MiB in-process (`VmHWM`), 741 MiB from `/usr/bin/time -v` |

First callback was 1.20 s of audio. Median callback count was 7. All 61 timed runs streamed.

| line | chars | TTFA med | TTFA p90 | synth med | audio s | RTF med | RTF p10 | TTFA | RTF |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| build_passed | 24 | 681 | 3948 | 779 | 1.90 | 2.45x | 1.75x | FAIL | PASS |
| task_finished | 65 | 1107 | 1415 | 1322 | 4.24 | 3.09x | 2.66x | FAIL | PASS |
| sign_in | 73 | 779 | 888 | 903 | 3.29 | 3.66x | 3.17x | FAIL | PASS |
| deploy_blocked | 114 | 1196 | 1454 | 2198 | 7.91 | 3.49x | 3.23x | FAIL | PASS |
| summary | 217 | 801 | 1081 | 3708 | 12.04 | 3.17x | 2.52x | FAIL | PASS |
| say_limit | 236 | 3542 | 3705 | 4886 | 14.00 | 3.04x | 2.84x | FAIL | PASS |

### Chunk-size sweep

Same VM, after the 24 kHz voice and `max_char_in_sentence` 120. One process, one discarded warmup and 10 measured runs per line per size. Every size kept every line's warm median RTF above 2.6x, so the smallest size is the default. Warm TTFA median (ms) and RTF:

| chunk_size | build_passed | task_finished | sign_in | deploy_blocked | summary | say_limit | min RTF |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2 | 560 / 2.63x | 1074 / 2.82x | 772 / 3.16x | 1055 / 3.37x | 641 / 3.22x | 1565 / 3.18x | 2.63x |
| 4 | 724 / 2.81x | 1130 / 3.03x | 755 / 3.41x | 1049 / 3.52x | 831 / 3.17x | 1617 / 3.31x | 2.81x |
| 6 | 662 / 2.85x | 1035 / 3.13x | 727 / 3.65x | 1060 / 3.67x | 695 / 3.35x | 1905 / 3.26x | 2.85x |
| 10 | 654 / 2.96x | 1192 / 3.06x | 801 / 3.66x | 1280 / 3.51x | 761 / 3.25x | 1664 / 3.34x | 2.96x |
| 15 | 616 / 2.70x | 1269 / 2.96x | 828 / 3.20x | 1381 / 3.07x | 785 / 3.23x | 1783 / 3.29x | 2.70x |

TTFA barely moves on the short lines. On `deploy_blocked` and `say_limit` chunk 2 was about 200–300 ms faster than chunk 15. The first callback at 2 is 160 ms of audio, and at 15 it is 1.20 s. RTF stayed above the 1.5x bar at every size, with the slowest line at chunk 2 still 2.63x. `config/harness.conf` therefore uses `chunk_size=2`.

### After

24 kHz voice, no per-call resample. `chunk_size` 2. `max_char_in_sentence` 120. `./run.sh` wall clock 2 minutes 45 seconds. This is the table `./run.sh` prints with the config Tim will run.

| | |
| --- | --- |
| Native lib load | 143 ms (not part of cold start) |
| Model load | 548 ms |
| Cold TTFA (`build_passed`) | 729 ms, first callback |
| Cold synthesis | 945 ms, 1.93 s of audio, RTF 2.04x |
| Cold start (load + first line) | 1493 ms |
| RSS after model load | 374 MiB |
| Peak RSS | 678 MiB in-process (`VmHWM`), 680 MiB from `/usr/bin/time -v` |

First callback was 160 ms of audio. Median callback count was 55. All 61 timed runs streamed.

| line | chars | TTFA med | TTFA p90 | synth med | audio s | RTF med | RTF p10 | TTFA | RTF |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| build_passed | 24 | 699 | 1511 | 934 | 2.28 | 2.33x | 1.86x | FAIL | PASS |
| task_finished | 65 | 1159 | 1965 | 1571 | 4.48 | 2.81x | 2.27x | FAIL | PASS |
| sign_in | 73 | 703 | 763 | 947 | 3.14 | 3.36x | 3.12x | FAIL | PASS |
| deploy_blocked | 114 | 1128 | 1326 | 2368 | 8.06 | 3.40x | 2.97x | FAIL | PASS |
| summary | 217 | 682 | 721 | 3828 | 12.08 | 3.19x | 2.81x | FAIL | PASS |
| say_limit | 236 | 1843 | 2311 | 4537 | 14.53 | 3.13x | 2.82x | FAIL | PASS |

Overall on this VM: FAIL. Every line still clears 1.5x RTF. None clears 500 ms warm TTFA. `say_limit` median TTFA went from 3542 ms to 1843 ms. Cold start and the short lines are in the same range as the first run. `build_passed` p90 was 1511 ms this time: the 9th sorted run was 3.81 s of audio, and the longest was 6.41 s of audio at 2303 ms TTFA (72 callbacks). Same late end-of-speech, a smaller draw than the 19.4 s run in the first table.

## Layout

| Path | What it is |
| --- | --- |
| `config/harness.conf` | Voice, engine knobs, and the six test lines |
| `setup.sh` / `run.sh` | Download, then measure |
| `src/EmberVoiceSpike.kt` | The JVM harness |
| `models/` | Gitignored model and voice |
| `results/` | Gitignored JSON, CSV, WAVs, and the `--sweep` table |
