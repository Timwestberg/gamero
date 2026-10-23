# Ember voice benchmark

Phase 0 of the PocketTTS optimization plan. This measures the engine the Mac spike already runs, and it is what later changes are compared against. It does not touch the app. The spike under `../mac/` stays the one-shot timing harness.

The corpus in `corpus/ember-voice-corpus.json` is a local copy. The shared files are draft [server-platform #180](https://github.com/Timwestberg/server-platform/pull/180) (`cursor/ember-voice-contract-c987`): config `event-contracts/src/voice/fixtures/config/ember-voice.v1.json` (`ember-voice.v1`), corpus `fixtures/corpus/ember-voice-corpus.v1.json` (`ember-voice-corpus.v1`), and benchmark results `ember-voice-benchmark.v1`. This environment cannot read that private repo, so the harness JSON still uses the field names in `SCHEMA.md`. Do not treat this copy as already synced.

## Prerequisites

Same as the Mac spike: Java 17 or newer, `curl`, and `tar`. `./setup.sh` runs `../mac/setup.sh` and then downloads sherpa-onnx's Whisper tiny.en model for the word-error-rate check. Weights stay gitignored.

## Commands

From Terminal.app, in this directory:

```bash
cd spikes/ember-voice/bench
./setup.sh
./run.sh --self-check
./run.sh
./run.sh --matrix num_steps=1,2 temperature=0.3,0.7 --only build_passed --warm-runs 4 --no-wer
./compare.sh results/baseline-linux-vm.json results/ember-voice-bench.json
```

`./run.sh` writes `results/ember-voice-bench.json` (schema in `SCHEMA.md`) and one WAV per line under `results/wav/`. `results/baseline-linux-vm.json` is the committed Linux VM baseline. Other result files are gitignored.

`--matrix` takes one or more `key=a,b` axes and runs the cartesian product in one process. Keys: `chunk_size`, `max_char_in_sentence`, `num_threads`, `num_steps`, `temperature`, `provider`, `model_dir`, `seed`, `chunker`, `warmup`, `max_frames`. The summary is `results/matrix-summary.json`.

`--set key=value` overrides one knob for a single run. `--no-wer` skips ASR. `--only LINE_ID` times one corpus line.

`./compare.sh` exits 1 when any shared line's warm TTFA median is more than 10% slower and a one-sided Mann–Whitney test puts that slowdown under p = 0.05. A noisy 10% move does not fail. A real slowdown does.

## How a run is timed

- Fixed `seed` and `temperature` from `config/bench.conf`. PocketTTS reads the seed from `GenerationConfig.extra`. With `num_threads` above 1, ONNX can still change a run by a few tens of milliseconds, so the compare tool uses the samples instead of assuming bitwise repeats.
- One cold synthesis of the first line, then one discarded warmup per line, then `warm_runs` measured runs (10).
- TTFA is the synthesis call to the first audio callback. Total time is until the call returns. RTF is audio seconds / synthesis seconds.
- Expected audio is `max(0.8 s, characters / 15)`. A run is a runaway when the audio is longer than twice that. The count is in the JSON. This harness records it. The runaway guard that caps frames is a later change.
- RSS is the process high-water mark (`VmHWM` on Linux, `ps` samples on macOS) plus `/usr/bin/time` when that binary exists.
- CPU percent is process CPU time over wall time for that call (`getProcessCpuTime` on HotSpot).
- WER transcribes the last warm WAV with Whisper tiny.en int8 and scores it against the corpus text after lowercasing and stripping punctuation. It is one draw per line.

Percentiles are nearest-rank. With 10 runs, p99 is the worst run and p90 is the 9th.

The pass bar printed with the table is warm median TTFA at or under 500 ms and RTF at or above 1.5x. The process still exits 0 when the bar is missed. A failed synthesis exits non-zero.

## Linux VM baseline

`results/baseline-linux-vm.json` is from this cloud Linux VM (Linux 6.12.94+, Intel Xeon, 4 CPUs, OpenJDK 21), not a Mac. Git commit `be9a33e`. Spike defaults: 24 kHz CC0 voice, `chunk_size` 2, `max_char_in_sentence` 120, 5 flow steps, temperature 0.7, 2 threads, seed 1, sherpa-onnx's own splitter, no warmup. Native library load 140 ms, model load 546 ms, cold `build_passed` TTFA 765 ms, cold start 1311 ms. Peak RSS 1020532 KiB from `/usr/bin/time` (997 MiB), which includes Whisper. CPU during synthesis was about 273% with 2 threads.

| line | chars | TTFA med | TTFA p90 | RTF med | audio med (s) | runaways | WER |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| build_passed | 24 | 585 | 1054 | 2.24 | 1.72 | 1 | 0.50 |
| task_finished | 65 | 931 | 938 | 2.99 | 3.73 | 0 | 0.00 |
| sign_in | 73 | 781 | 800 | 3.69 | 3.88 | 0 | 0.06 |
| deploy_blocked | 114 | 974 | 1017 | 3.50 | 7.74 | 0 | 0.29 |
| summary | 217 | 688 | 701 | 3.16 | 12.01 | 0 | 1.06 |
| say_limit | 236 | 1631 | 1712 | 3.39 | 13.23 | 0 | 0.27 |
| numbers_code | 60 | 1379 | 1542 | 3.11 | 5.72 | 0 | 0.50 |
| question | 60 | 778 | 857 | 3.22 | 3.25 | 0 | 0.00 |
| say_cap | 240 | 1628 | 1673 | 3.42 | 14.58 | 0 | 0.26 |

`build_passed` still misses 500 ms, and one of the ten runs was 3.83 s of audio (TTFA 1391 ms). `summary` WER is a Whisper loop (`T-T-T-...`) after a correct opening, not forty-five real word errors. `question` at 0.00 WER is the check that the recognizer is working. Every line's RTF is above 1.5x. Regenerate the file with:

```bash
./run.sh --output results/baseline-linux-vm.json
```

Do that only when the baseline itself should move. Later changes compare against the committed file.
