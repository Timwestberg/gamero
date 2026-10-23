# Ember voice benchmark JSON (schema 1)

`./run.sh` writes this document. `./compare.sh` reads two of them. Numbers from this VM are labeled `Linux VM` in `label`. A Mac run is labeled from the machine that produced the file.

The shared result schema is `ember-voice-benchmark.v1` in server-platform draft PR 180 (`benchmark-result.v1.ts`, example `fixtures/results/valid/example.json`). This harness document is not that schema yet. The private repo is not readable from the environment that produced the Linux baseline, so these field names were not copied from it.

```json
{
  "schema": 1,
  "kind": "ember-voice-bench",
  "label": "Linux VM",
  "measured_at": "ISO-8601",
  "git_commit": "commit of the code that ran, or unknown",
  "corpus": { "id": "ember-voice", "version": "1.0.0", "path": "corpus/ember-voice-corpus.json" },
  "device": {
    "os_name": "Linux",
    "os_version": "",
    "os_arch": "amd64",
    "chip": "model name from /proc/cpuinfo or sysctl",
    "logical_cpus": 4
  },
  "model": { "name": "", "dir": "", "provider": "cpu" },
  "voice": { "path": "", "license": "CC0", "source": "", "sample_rate": 24000 },
  "asr": { "name": "", "used": true },
  "config": {
    "num_threads": 2,
    "num_steps": 5,
    "temperature": "0.7",
    "chunk_size": "2",
    "max_char_in_sentence": 120,
    "seed": "1",
    "chunker": "sherpa",
    "max_frames": 0,
    "warmup": false,
    "warm_runs": 10,
    "chars_per_second": 15,
    "min_expected_audio_s": 0.8,
    "runaway_factor": 2
  },
  "native_lib_load_ms": 0,
  "model_load_ms": 0,
  "warmup_ms": 0,
  "peak_rss_kb": 0,
  "peak_rss_source": "max(VmHWM, samples)",
  "external_peak_rss_kb": null,
  "cold": {
    "line_id": "build_passed",
    "ttfa_ms": 0,
    "total_ms": 0,
    "audio_s": 0,
    "rtf": 0,
    "cpu_percent": 0,
    "runaway": false,
    "callback_count": 0
  },
  "lines": [
    {
      "id": "build_passed",
      "categories": ["very_short"],
      "chars": 24,
      "text": "",
      "expected_audio_s": 1.6,
      "wav": "results/wav/build_passed.wav",
      "ttfa_ms": { "median": 0, "p90": 0, "p99": 0, "max": 0, "samples": [] },
      "total_ms": { "median": 0, "p90": 0, "p99": 0, "max": 0, "samples": [] },
      "rtf": { "median": 0, "p90": 0, "p99": 0, "max": 0, "min": 0, "samples": [] },
      "audio_s": { "median": 0, "p90": 0, "p99": 0, "max": 0, "samples": [] },
      "cpu_percent": { "median": 0, "p90": 0, "p99": 0, "max": 0, "samples": [] },
      "runaway_count": 0,
      "rss_kb": 0,
      "wer": {
        "wer": 0.0,
        "reference_words": 0,
        "errors": 0,
        "hypothesis": ""
      }
    }
  ]
}
```

`ttfa_ms` is the time from the synthesis call to the first callback that contains samples. `total_ms` is the time until that call returns. `rtf` is audio seconds / synthesis seconds. Higher RTF is faster.

`expected_audio_s` is `max(min_expected_audio_s, chars / chars_per_second)`. A warm run counts as a runaway when its audio is longer than `runaway_factor` times that value.

Percentiles are nearest-rank. With 10 samples, p99 is the worst sample and p90 is the 9th. Median of an even count is the average of the two middle values.

`wer` is word error rate of the last warm WAV against the corpus text, after lowercasing and stripping punctuation. It is one draw, not a median. `null` when ASR was skipped (`--no-wer`).

`./compare.sh baseline.json candidate.json` compares warm `ttfa_ms.samples` per line with a one-sided Mann–Whitney test (candidate slower). It exits 1 when a line's median TTFA is more than 10% higher and the test's p-value is under 0.05. Exact p-values are used when both samples have at most 10 values and no ties. Otherwise it uses the normal approximation with a tie correction and a continuity correction.

A `--matrix` run writes the same document per cell under `results/matrix/` plus `results/matrix-summary.json`, whose `cells` list the config and each line's TTFA median, RTF median, runaway count, and WER.
