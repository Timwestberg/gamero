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

`./setup.sh` downloads the int8 model (~94 MB), the default CC0 voice, the sherpa-onnx JVM jar, the native library for this Mac, and a Kotlin compiler. Re-running it skips anything already downloaded and hash-checked.

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
./run.sh --self-check
./run.sh --help
```

`--self-check` confirms the six fixed lines and the median / percentile math. It does not load the model.

## Change the voice

PocketTTS in this sherpa-onnx build is a reference-audio cloner. There is no speaker id. The harness passes the wav to `GenerationConfig.setReferenceAudio` and `setReferenceSampleRate`. The clip must be mono 16-bit PCM, which is the only format sherpa-onnx's `WaveReader` accepts.

The default is a CC0 donation:

`kyutai/tts-voices` `voice-donations/0a67_enhanced.wav` (16-bit mono, 32 kHz, about 10 seconds).

Edit both of these in `config/harness.conf`, then rerun `./setup.sh` and `./run.sh`:

```
voice=models/voices/some-other.wav
voice_url=https://huggingface.co/kyutai/tts-voices/resolve/main/voice-donations/some-other.wav
```

`setup.sh` downloads `voice_url` only when `voice` is missing. To force a new download, delete the old wav or use a new `voice` path. A file you already have on disk works too: set `voice` to that path and leave the file in place.

Stay inside `voice-donations/` (or another CC0 set such as `voice-zero/`). Do not use Expresso. The clips under `models/.../test_wavs/` (including `bria.wav`) and anything under `expresso/` are CC-BY-NC.

`num_steps`, `num_threads`, `temperature`, and `chunk_size` are in the same config file. The defaults match sherpa-onnx's PocketTTS example: 5 flow steps, 2 threads, temperature 0.7, chunk size 15. Fewer `num_steps` is faster and lower quality. `chunk_size` is forwarded to PocketTTS as the streaming chunk size. On the Linux run below, 15 produced a first callback of 28800 samples at 24 kHz, which is 1.20 seconds of audio (80 ms per frame). TTFA cannot be shorter than the time to synthesize that first frame group. Lower `chunk_size` if you are chasing the 500 ms bar.

The default clip is 32 kHz and the model speaks at 24 kHz, so sherpa-onnx resamples the reference on every call and logs `Creating a resampler`. The voice embedding itself is cached after the first call. A mono 16-bit 24 kHz wav skips that resample. stderr will be noisy with the 32 kHz default; the table is printed after the runs.

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

## Linux VM check

These numbers are from the cloud Linux VM that built this spike. They are not Mac numbers. Tim's Apple Silicon run is the one that answers the pet question.

Host: Linux 6.12.94+ x86_64, Intel Xeon, 4 CPUs, OpenJDK 21.0.12.1. sherpa-onnx 2.0.0 JVM, int8 PocketTTS, `chunk_size` 15, 5 steps, 2 threads, temperature 0.7, CC0 voice `voice-donations/0a67_enhanced.wav`. Wall clock for `./run.sh` was 2 minutes 46 seconds.

| | |
| --- | --- |
| Native lib load | 143 ms (not part of cold start) |
| Model load | 592 ms |
| Cold TTFA (`build_passed`) | 747 ms, first callback |
| Cold synthesis | 822 ms, 1.79 s of audio, RTF 2.18x |
| Cold start (load + first line) | 1414 ms |
| RSS after model load | 374 MiB |
| Peak RSS | 738 MiB in-process (`VmHWM`), 741 MiB from `/usr/bin/time -v` |

Streaming did work. All 61 timed runs (1 cold + 60 warm) got more than one callback, and the first arrived before synthesis finished. The first callback was always 1.20 s of audio. Median callback count was 7.

| line | chars | TTFA med | TTFA p90 | synth med | audio s | RTF med | RTF p10 | TTFA | RTF |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| build_passed | 24 | 681 | 3948 | 779 | 1.90 | 2.45x | 1.75x | FAIL | PASS |
| task_finished | 65 | 1107 | 1415 | 1322 | 4.24 | 3.09x | 2.66x | FAIL | PASS |
| sign_in | 73 | 779 | 888 | 903 | 3.29 | 3.66x | 3.17x | FAIL | PASS |
| deploy_blocked | 114 | 1196 | 1454 | 2198 | 7.91 | 3.49x | 3.23x | FAIL | PASS |
| summary | 217 | 801 | 1081 | 3708 | 12.04 | 3.17x | 2.52x | FAIL | PASS |
| say_limit | 236 | 3542 | 3705 | 4886 | 14.00 | 3.04x | 2.84x | FAIL | PASS |

Overall on this VM: FAIL. Every line cleared the 1.5x RTF bar. None cleared the 500 ms warm-TTFA bar. `say_limit` was the outlier on TTFA (median 3542 ms) even though its RTF matched the other long lines. One `build_passed` run emitted 19.4 s of audio for that short sentence (TTFA 8885 ms); temperature 0.7 can occasionally run long, and p90 shows it. The median does not.

## Layout

| Path | What it is |
| --- | --- |
| `config/harness.conf` | Voice, engine knobs, and the six test lines |
| `setup.sh` / `run.sh` | Download, then measure |
| `src/EmberVoiceSpike.kt` | The JVM harness |
| `models/` | Gitignored model and voice |
| `results/` | Gitignored JSON, CSV, and WAVs |
