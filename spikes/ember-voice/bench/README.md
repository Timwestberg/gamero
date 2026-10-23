# Ember voice benchmark

Phase 0 of the PocketTTS optimization plan. This measures the engine the Mac spike already runs, and it is what later changes are compared against. It does not touch the app. The spike under `../mac/` stays the one-shot timing harness.

The corpus in `corpus/ember-voice-corpus.json` is a local copy. The shared voice config and this corpus will live in server-platform `event-contracts/`. Another agent owns that side. The file has a `version` and a `sync` note so the copies can be lined up later.

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

`results/baseline-linux-vm.json` is from the cloud Linux VM, not a Mac. It uses the spike defaults: 24 kHz CC0 voice, `chunk_size` 2, `max_char_in_sentence` 120, 5 flow steps, temperature 0.7, 2 threads, seed 1, sherpa-onnx's own splitter. Regenerate it with:

```bash
./run.sh --output results/baseline-linux-vm.json
```

Do that only when the baseline itself should move. Later changes compare against the committed file.
