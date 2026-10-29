/**
 * Module worker that hears one microphone at a time: Silero VAD v5 and the
 * streaming Zipformer on onnxruntime-web's WASM backend (the int8 encoder
 * stays on the CPU). Messages run strictly in order, so a turn is processed
 * chunk by chunk like desktop's hearing thread.
 *
 * In:  { type: "load", baseUrl, ortModuleUrl }
 *      { type: "chunk", id, samples, restart: null | "drop" | "preroll" }
 *      { type: "finish", id }
 * Out: { type: "progress", loaded, total } | { type: "ready", report }
 *      { type: "frame", id, voicedMs, chunkMs, rms, prob, text }
 *      { type: "result", id, text, speechMs, decodeMs }
 *      { type: "error", id?, message }
 */

import { HEARING_SAMPLE_RATE, HearingEngine, PreRoll, chunkRms } from "./hearing-engine.js";
import { loadPinned } from "./model-cache.js";
import { HEARING_WEB, KOKORO_WEB } from "./models.js";
import { SileroVad } from "./silero.js";
import { StreamingZipformer } from "./zipformer.js";

let engine = null;
let preRoll = new PreRoll();
let chain = Promise.resolve();

self.onmessage = (event) => {
  const message = event.data;
  chain = chain.then(() => handle(message)).catch((error) => {
    self.postMessage({ type: "error", id: message.id, message: String(error?.message || error) });
  });
};

async function handle(message) {
  if (message.type === "load") {
    if (!engine) await load(message);
  } else if (message.type === "chunk") {
    if (!engine) throw new Error("Ember's hearing is not loaded");
    const { samples, restart } = message;
    preRoll.add(samples);
    if (restart) {
      engine.startUtterance();
      await engine.accept(restart === "preroll" ? preRoll.copy() : samples);
    } else {
      await engine.accept(samples);
    }
    self.postMessage({
      type: "frame",
      id: message.id,
      voicedMs: engine.voicedMs,
      chunkMs: Math.trunc((samples.length * 1000) / HEARING_SAMPLE_RATE),
      rms: chunkRms(samples),
      prob: engine.lastProb,
      text: engine.partial(),
    });
  } else if (message.type === "finish") {
    const result = await engine.finish();
    self.postMessage({ type: "result", id: message.id, ...result });
  } else if (message.type === "reset") {
    preRoll = new PreRoll();
    engine?.startUtterance();
  }
}

async function load({ baseUrl, ortModuleUrl }) {
  const started = performance.now();
  const files = { ...HEARING_WEB.files, ortWasm: KOKORO_WEB.files.ortWasm };
  const keys = Object.keys(files);
  const total = keys.reduce((sum, key) => sum + files[key].bytes, 0);
  const loaded = Object.fromEntries(keys.map((key) => [key, 0]));
  const report = { files: {}, total };
  let lastPost = 0;
  const bytes = Object.fromEntries(await Promise.all(keys.map(async (key) => {
    const result = await loadPinned(files[key], {
      baseUrl,
      onProgress: (count) => {
        loaded[key] = count;
        const at = performance.now();
        if (at - lastPost > 100) {
          lastPost = at;
          self.postMessage({ type: "progress", loaded: keys.reduce((sum, k) => sum + loaded[k], 0), total });
        }
      },
    });
    report.files[key] = { name: files[key].name, bytes: files[key].bytes, source: result.source, ms: Math.round(result.ms) };
    return [key, result.bytes];
  })));
  report.downloadedBytes = Object.values(report.files).filter((file) => file.source === "network").reduce((sum, file) => sum + file.bytes, 0);

  const ort = await import(ortModuleUrl);
  ort.env.wasm.wasmBinary = bytes.ortWasm;
  ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 1) : 1;
  const sessionStarted = performance.now();
  const [asr, vad] = await Promise.all([
    StreamingZipformer.create(ort, { encoder: bytes.encoder, decoder: bytes.decoder, joiner: bytes.joiner }, new TextDecoder().decode(bytes.tokens)),
    SileroVad.create(ort, bytes.vad),
  ]);
  report.sessionMs = Math.round(performance.now() - sessionStarted);
  engine = new HearingEngine({ asr, vad });
  const warmStarted = performance.now();
  await engine.accept(new Float32Array(16000));
  engine.startUtterance();
  report.warmupMs = Math.round(performance.now() - warmStarted);
  report.readyMs = Math.round(performance.now() - started);
  self.postMessage({ type: "ready", report });
}
