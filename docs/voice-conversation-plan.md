# Two-way voice conversation with Ember

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> This document is the plan. It does not contain feature code. Implement it on top of draft PR #56, not on today's `main` PocketTTS speaker.

**Goal:** Tim can talk to Ember in the catalog chat and hear her answer, with turn-taking that feels like a phone call, on device, Mac first.

**Architecture:** The Mac captures the microphone in the same audio graph that plays Kokoro, runs Silero VAD and a streaming Zipformer on the echo-cancelled 16 kHz stream, and on endpoint submits the transcript through the existing `chat.send` talk path. Reply partials are already the full text so far; newly finished sentences are enqueued onto the Kokoro player from PR #56, which synthesizes the next sentence while the current one plays and flushes the device on stop. Barge-in flushes that player and cancels the provider turn. Audio, models, and provider credentials stay in the gamero process.

**Tech Stack:** sherpa-onnx 2.0.0 JVM (already loaded for Kokoro 82M v1.0), Silero VAD v5, streaming Zipformer English int8, AVAudioEngine voice processing on Apple, the host-bridge `chat.*` contract, Kokoro blends Sam / Jamie / Riley / Harper.

## What the draft stack shipped

Phases 1–4 are on `main`. Milestone 5, the browser loop, is a draft in this repo under `web/` because tool-webkit and game-platform were not readable from the agent that wrote it. Milestones 6–7 are not started.

**Browser speech (milestone 5, stacked on #63).** Ember speaks in the browser with the same Kokoro v1.0 graph desktop loads, run by onnxruntime-web 1.30 on WebGPU (WASM CPU fallback) in a module worker. sherpa-onnx's official WASM build was measured first and ran at 1.8 to 2.6 times slower than real time on an M3 Max, so it cannot keep a sentence ahead of playback. The text path is a port, checked against desktop: `SpeechText.kt`, `KokoroLexicon.kt`, and the sentence shaping in `Speech.kt` through `web/hearing/fixtures/speech-text.json` (written by `SpeechWebFixtureTest`), and sherpa-onnx 2.0.0's Kokoro lexicon frontend and `ScaleSilence(0.2)` through `web/hearing/fixtures/kokoro-tokens.json` (token ids sherpa 2.0.0 logged). The download is the model with float16-stored weights (`voice/kokoro/fp16-weights.py`, float32 compute), the four blends cut from `voices-blends.bin`, the tokens and lexicon, and the runtime: 198,251,088 bytes, fetched once by `web/hearing/scripts/fetch-web-voice.mjs`, sha256-checked, and kept in Cache Storage.

**Browser hearing (milestone 5, stacked on #68).** The catalog page hears the microphone the way `DesktopHearing` does: 100 ms chunks at 16 kHz into a module worker that runs Silero VAD v5 and the streaming Zipformer on onnxruntime-web's WASM backend. The recognizer is a port of sherpa-onnx 2.0.0's online transducer (kaldi-native-fbank 1.22.4 features, Zipformer2 encoder states, greedy search), and `web/hearing/fixtures/hearing-oracle.json` holds what sherpa 2.0.0 hears in Kokoro-voiced clips, chunk by chunk. Silero keeps desktop's call order (`compute` on 512 samples, then `acceptWaveform`'s 576-sample overlap run, on one state), voiced is 0.6, barge-in spans follow `onHearingFrame`, and the sent text is the recognizer's finished text. The encoder, decoder, and joiner are fetched one by one from Hugging Face, not from the release archive, because the archive carries `test_wavs`.
