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

`./run.sh` writes `results/ember-voice-bench.json` (schema in `SCHEMA.md`) and one WAV per line under `results/wav/`. `results/baseline-linux-vm.json` is the committed Linux VM timing baseline. `results/baseline-wer-linux-vm.json` is the same spike defaults scored with median WER. `results/loose-cap-linux-vm.json` is the uncapped intelligibility check. `results/quick-wins-linux-vm.json` is the tuned confirmation. Other result files are gitignored.

`--matrix` takes one or more `key=a,b` axes and runs the cartesian product in one process. Keys: `chunk_size`, `max_char_in_sentence`, `num_threads`, `num_steps`, `temperature`, `provider`, `model_dir`, `seed`, `chunker`, `warmup`, `max_frames`, `first_piece_target_chars`, `max_frames_per_character`, `frame_intercept`, `frame_floor`, `short_piece_chars`, `short_piece_min_frames`, `silence_ms`. `--set` applies to every cell. The summary is `results/matrix-summary.json`.

`chunker=ours` is the bench splitter: the first sentence stays whole when it is at most `first_piece_target_chars` (so "Done!" is not glued on), otherwise the first piece is cut in the 30-character to target window on a clause boundary. Numbers, times, and file names are spoken as words (`503` becomes "five hundred three", `PetBridge.kt` becomes "Pet Bridge dot kt") and a cut will not land inside that phrase. Later pieces pack to `later_piece_max_chars`. The frame ceiling is `ceil(max(frame_floor, frame_intercept + max_frames_per_character * chars))` (`4 + 0.8 * 28` ceils to 27). A piece of `short_piece_chars` or fewer gets at least `short_piece_min_frames`. All of those at 0 leaves sherpa-onnx's own cap.

`--set key=value` overrides one knob for a single run. `--no-wer` skips ASR. `--only LINE_ID` times one corpus line.

`./compare.sh` exits 1 when any shared line's warm TTFA median is more than 10% slower and a one-sided Mann–Whitney test puts that slowdown under p = 0.05. A noisy 10% move does not fail. A real slowdown does.

## How a run is timed

- Fixed `seed` and `temperature` from `config/bench.conf`. PocketTTS reads the seed from `GenerationConfig.extra`. With `num_threads` above 1, ONNX can still change a run by a few tens of milliseconds, so the compare tool uses the samples instead of assuming bitwise repeats.
- One cold synthesis of the first line, then one discarded warmup per line, then `warm_runs` measured runs (10).
- TTFA is the synthesis call to the first audio callback. When the first piece is regenerated, that audio stays buffered until the retry returns, and TTFA includes both takes. Total time is until the call returns. RTF is audio seconds / synthesis seconds.
- Expected audio is `max(0.8 s, characters / 15)`, where characters are the normalized piece sent to the model. A line's expectation is the sum of its pieces. Measured audio includes the 40 ms of silence between pieces. Sherpa's own splitter skips that packing, the silence, and the frame ceiling, and uses the raw line length. A run is a runaway when the audio is longer than twice the expectation. The count is in the JSON. When a piece has a frame ceiling and still runs long, it is generated once more and the shorter take is kept. A callback loop past 8192 chunks is stopped so a missed frame cap cannot grow the audio without a bound.
- The voice path is refused when it contains `expresso` or `test_wavs`. Those clips are CC-BY-NC. Warm runs keep only the last waveform; earlier takes are dropped so they do not sit in the result list and raise peak RSS.
- RSS is the process high-water mark (`VmHWM` on Linux, `ps` samples on macOS) plus `/usr/bin/time` when that binary exists.
- CPU percent is process CPU time over wall time for that call (`getProcessCpuTime` on HotSpot).
- WER transcribes every warm run with Whisper tiny.en int8 and reports the median against the corpus text after lowercasing and stripping punctuation. One short draw does not set the line.

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

`build_passed` still misses 500 ms, and one of the ten runs was 3.83 s of audio (TTFA 1391 ms). The WER column above is one draw from that file. `summary` at 1.06 was a Whisper loop (`T-T-T-...`) after a correct opening. The median over ten draws, in `results/baseline-wer-linux-vm.json`, is the before column used below. Every line's RTF is above 1.5x. Regenerate the timing file with:

```bash
./run.sh --output results/baseline-linux-vm.json
```

Do that only when the baseline itself should move. Later changes compare against the committed file. The bench branch still has the spike defaults in that file. This branch's `config/bench.conf` is the tuned default below, so `./run.sh` no longer reproduces the baseline unless those knobs are set back.

## Median WER at the spike defaults

`results/baseline-wer-linux-vm.json` reruns the spike defaults (sherpa splitter, 5 steps, temperature 0.7, no frame cap, seed 1, 2 threads) and scores every warm run. `wer` is the median. Git commit `9d68564`. This is the before column. The single-draw WER in `baseline-linux-vm.json` is not.

| line | TTFA med | RTF med | WER med | WER max | median hypothesis |
| --- | ---: | ---: | ---: | ---: | --- |
| build_passed | 536 | 2.42 | 0.50 | 12.75 | your build past. |
| task_finished | 897 | 3.05 | 0.00 | 0.67 | I finished the task. Three files changed and all tests are green. |
| sign_in | 798 | 3.65 | 0.19 | 0.19 | It's a codex need you to sign in before I can keep going on this one. |
| deploy_blocked | 933 | 3.59 | 0.25 | 0.29 | The deploy is blocked. The API returned error 503 at 245 pms. |
| summary | 683 | 3.25 | 0.34 | 0.97 | Here's the summary. I refactor Pet Session. Take T... |
| say_limit | 1634 | 3.52 | 0.24 | 0.27 | ...just under the 240 character cap... |
| numbers_code | 1235 | 3.44 | 0.50 | 0.67 | Petridge, Kate returned air 53 at 2.45pm after 30 seconds. |
| question | 753 | 3.19 | 0.00 | 0.08 | Should I rerun the suite or is this ready for you to merge? |
| say_cap | 1572 | 3.45 | 0.26 | 0.28 | ...the chunker has to find its own close... |

`build_passed` never comes through as "Done!" even with no frame cap. Whisper hears "your build past." The max of 12.75 is one loop, which is why the median is the score. `numbers_code` is the recognizer mangling `PetBridge.kt` and `503`. `summary`'s median is 0.34; the old 1.06 was the loop draw.

## Loose cap (is it the clip, or the recognizer?)

`results/loose-cap-linux-vm.json` uses the tuned sampler and our splitter with the frame ceiling off (sherpa's default 500). Git commit `9d68564`. If WER stays high here, the ceiling is not what ate the words.

| line | TTFA med | WER med | what Whisper heard |
| --- | ---: | ---: | --- |
| build_passed | 312 | 0.50 | BUNT, your bill passed. |
| task_finished | 310 | 0.00 | exact, apart from punctuation |
| sign_in | 472 | 0.06 | Heads up, Codex needs you to sign in touch... |
| deploy_blocked | 2149 | 0.48 | The API returned, repeated |
| summary | 1654 | 0.46 | pet session.kt, with a "pet pet pet" loop |
| say_limit | 500 | 0.13 | the line, with a small stutter |
| numbers_code | 442 | 0.58 | petbridge.kate returned arid 53245pm after 30 seconds |
| question | 1935 | 1.38 | sweet, repeated |
| say_cap | 500 | 0.12 | the line, with "class" for "clause" |

`build_passed` at 0.50 matches the spike baseline, and the second piece is a full "your bill passed." "Bunt." by itself was a clipped draw, not the recognizer's floor. `numbers_code` is 0.58 with no ceiling, same as the tuned run, so that miss is Whisper plus the spoken numbers, not the cap. `deploy_blocked`, `summary`, and `question` get worse when the cap is off, because the model loops. The ceiling has to be high enough to finish the words and low enough to stop the loop.

## Chosen defaults (Linux VM)

Not a Mac. Same host as the baseline. Confirmation file `results/quick-wins-linux-vm.json`, git commit `6324050`. `num_steps` 1, temperature 0.3, `chunker=ours`, `first_piece_target_chars` 40, 2 threads, seed 1. The frame ceiling is `max(12, 4 + 0.8 * chars)`. Pieces of 24 characters or fewer get at least 36 frames (2.88 s). That is the measured point where "Your build passed." stays 1.34 s instead of being cut to "your buil". Native load 140 ms, model load 520 ms, cold `build_passed` TTFA 378 ms. No runaway in the 10 warm runs. `./compare.sh` against `baseline-linux-vm.json` reports no TTFA regression.

WER is the median of the 10 warm runs. The bar is about +0.05 versus the median baseline above. Where speed and that bar disagreed, the run keeps the wording.

| line | baseline TTFA med / p90 | tuned TTFA med / p90 | tuned RTF | baseline WER | tuned WER | delta |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| build_passed | 585 / 1054 | 319 / 470 | 1.98 | 0.50 | 0.50 | 0.00 |
| task_finished | 931 / 938 | 316 / 345 | 3.14 | 0.00 | 0.00 | 0.00 |
| sign_in | 781 / 800 | 488 / 490 | 3.11 | 0.19 | 0.06 | -0.13 |
| deploy_blocked | 974 / 1017 | 516 / 526 | 3.22 | 0.25 | 0.29 | +0.04 |
| summary | 688 / 701 | 468 / 471 | 3.23 | 0.34 | 0.31 | -0.03 |
| say_limit | 1631 / 1712 | 466 / 468 | 3.40 | 0.24 | 0.22 | -0.02 |
| numbers_code | 1379 / 1542 | 451 / 457 | 3.78 | 0.50 | 0.58 | +0.08 |
| question | 778 / 857 | 470 / 472 | 3.65 | 0.00 | 0.15 | +0.15 |
| say_cap | 1628 / 1673 | 461 / 517 | 3.93 | 0.26 | 0.11 | -0.15 |

`deploy_blocked` is the line over 500 ms: median 516 ms, 16 ms over, p90 526 ms. Raising its first-piece ceiling from 36 frames to 52 frames (the `air` sweep) brought WER to 0.23 and TTFA to 771 ms, and Whisper still heard "there are 503" rather than "the API returned". The 16 ms stays.

`numbers_code` is +0.08. The loose-cap run is 0.58 as well, and `wer_spoken` against "five hundred three" / "two forty-five" is 0.82, so the normalizer is not what Whisper rewards. The words are still spoken as words. `question` is +0.15 because Whisper hears "sweet" for "suite" on nine of the ten draws (one draw says "suite" once). The loose cap makes that a loop (WER 1.38). A four-run probe of this line only, same frame curve, did not clear it: temperature 0.5 median WER 0.23, and temperature 0.7 median WER 0.08 with "sweet" still in the median hypothesis. Neither replaces the default. The baseline's 5-step sampler says "suite". One thread was slower than two, and four threads was slower and less stable, so the default stays at 2.

Shared contract values to put in server-platform #180, if the placeholders differ: `numSteps` 1, `temperature` 0.3, `seed` 1, `firstPieceTargetChars` 40, `maxDurationRatio` 2. `maxFramesPerCharacter` is no longer a single rate. The ceiling is `max(12, 4 + 0.8 * chars)`, and a piece of 24 characters or fewer gets at least 36 frames. Leave `tiers` as they are. This VM never read that repo, so the harness JSON is still the schema in `SCHEMA.md`, not `ember-voice-benchmark.v1`.
