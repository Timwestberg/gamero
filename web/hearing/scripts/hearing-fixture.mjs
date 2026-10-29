#!/usr/bin/env node
/**
 * Writes the hearing test clips and what sherpa-onnx 2.0.0 hears in them.
 *
 *   fixtures/hearing/*.wav          16 kHz 16-bit mono speech, synthesized with
 *                                   Kokoro v1.0 (Apache-2.0), so no recorded
 *                                   voices are involved
 *   fixtures/hearing-oracle.json    per 100 ms chunk, the streaming Zipformer's
 *                                   partial text; then the text after finish
 *
 * The recognizer runs the way desktop's SherpaHearingEngine runs it: greedy
 * search, 1600-sample accepts, decode while ready, then inputFinished and
 * decode on finish. It uses the pinned web files in web/voice-assets/hearing.
 *
 * Needs web/voice-assets (fetch-web-voice.mjs) including .upstream/model.onnx.
 * usage: node web/hearing/scripts/hearing-fixture.mjs
 */

import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { decodeWav16, encodeWav16 } from "../src/wav.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const hearing = path.resolve(here, "..");
const assets = path.resolve(hearing, "../voice-assets");
const require = createRequire(path.join(hearing, "package.json"));

/** Phrase, upstream Kokoro speaker id (11 am_adam, 3 af_heart, 26 bm_george), speed. */
export const CLIPS = [
  { name: "hello-there", text: "Hello there, how are you today?", sid: 11, speed: 1 },
  { name: "open-catalog", text: "Please open the catalog and show me the weather.", sid: 3, speed: 1 },
  { name: "stop-that", text: "Stop. That's enough for now.", sid: 26, speed: 1.05 },
];
const LEAD_S = 0.5;
const TRAIL_S = 2.2;
const CHUNK = 1600;

/** Windowed-sinc resampler for the 24 kHz to 16 kHz test clips (3:2). */
export function resample(input, from, to) {
  const ratio = to / from;
  const out = new Float32Array(Math.floor(input.length * ratio));
  const cutoff = Math.min(1, ratio) * 0.95;
  const half = 32;
  for (let i = 0; i < out.length; i += 1) {
    const center = i / ratio;
    let acc = 0;
    let norm = 0;
    for (let k = Math.ceil(center - half); k <= Math.floor(center + half); k += 1) {
      if (k < 0 || k >= input.length) continue;
      const x = center - k;
      const sinc = x === 0 ? 1 : Math.sin(Math.PI * cutoff * x) / (Math.PI * cutoff * x);
      const window = 0.5 + 0.5 * Math.cos((Math.PI * x) / (half + 1));
      const weight = sinc * window;
      acc += input[k] * weight;
      norm += weight;
    }
    out[i] = norm ? acc / norm : 0;
  }
  return out;
}

function main() {
  const { createOfflineTts } = require("sherpa-onnx/sherpa-onnx-tts.js");
  const { createOnlineRecognizer } = require("sherpa-onnx/sherpa-onnx-asr.js");
  const Module = { print: () => {}, printErr: () => {} };
  require("sherpa-onnx/sherpa-onnx-wasm-nodejs.js")(Module);

  const tts = createOfflineTts(Module, {
    offlineTtsModelConfig: {
      offlineTtsKokoroModelConfig: {
        model: path.join(assets, ".upstream/model.onnx"),
        voices: path.join(assets, ".upstream/voices.bin"),
        tokens: path.join(assets, "tokens.txt"),
        lexicon: path.join(assets, "lexicon-us-en.txt"),
        dataDir: "",
        lengthScale: 1.0,
      },
      numThreads: 1,
      debug: 0,
      provider: "cpu",
    },
    ruleFsts: "",
    ruleFars: "",
    maxNumSentences: 1,
  });

  const dir = path.join(assets, "hearing");
  const recognizer = createOnlineRecognizer(Module, {
    featConfig: { sampleRate: 16000, featureDim: 80 },
    modelConfig: {
      transducer: {
        encoder: path.join(dir, "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
        decoder: path.join(dir, "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"),
        joiner: path.join(dir, "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
      },
      tokens: path.join(dir, "tokens.txt"),
      numThreads: 1,
      provider: "cpu",
      debug: 0,
      modelType: "",
    },
    decodingMethod: "greedy_search",
    enableEndpoint: 0,
  });

  const outDir = path.join(hearing, "fixtures/hearing");
  mkdirSync(outDir, { recursive: true });
  const clips = [];
  for (const clip of CLIPS) {
    const audio = tts.generate({ text: clip.text, sid: clip.sid, speed: clip.speed });
    const speech = resample(audio.samples, audio.sampleRate, 16000);
    const samples = new Float32Array(Math.round((LEAD_S + TRAIL_S) * 16000) + speech.length);
    samples.set(speech, Math.round(LEAD_S * 16000));
    const wav = encodeWav16(samples, 16000);
    writeFileSync(path.join(outDir, `${clip.name}.wav`), wav);
    const pcm = decodeWav16(wav).samples;

    const stream = recognizer.createStream();
    const partials = [];
    for (let offset = 0; offset < pcm.length; offset += CHUNK) {
      stream.acceptWaveform(16000, pcm.subarray(offset, Math.min(pcm.length, offset + CHUNK)));
      while (recognizer.isReady(stream)) recognizer.decode(stream);
      partials.push(recognizer.getResult(stream).text);
    }
    stream.inputFinished();
    while (recognizer.isReady(stream)) recognizer.decode(stream);
    const final = recognizer.getResult(stream).text;
    stream.free?.();
    clips.push({ name: clip.name, text: clip.text, sha256: createHash("sha256").update(wav).digest("hex"), samples: pcm.length, partials, final });
    console.log(`${clip.name}: "${final.trim()}"`);
  }
  const files = ["encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", "decoder-epoch-99-avg-1-chunk-16-left-128.onnx", "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", "tokens.txt"];
  const fixture = {
    schema: "ember-hearing-oracle.v1",
    sherpaOnnx: "2.0.0",
    chunkSamples: CHUNK,
    models: Object.fromEntries(files.map((file) => [file, createHash("sha256").update(readFileSync(path.join(dir, file))).digest("hex")])),
    clips,
  };
  writeFileSync(path.join(hearing, "fixtures/hearing-oracle.json"), `${JSON.stringify(fixture, null, 1)}\n`);
}

main();
