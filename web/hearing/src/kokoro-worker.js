/**
 * Module worker that turns one piece of Ember's reply into Kokoro audio.
 *
 * Desktop path, step for step: coverUnknownWords (KokoroLexicon.kt), then
 * sherpa-onnx 2.0.0's lexicon frontend (kokoro-frontend.js), one model run per
 * sherpa sentence with style row = token count, ScaleSilence(0.2) per run,
 * and the runs joined. The model runs in onnxruntime-web on WebGPU, or on
 * the WASM CPU backend when WebGPU is missing.
 *
 * Messages in:  { type: "load", baseUrl, ortModuleUrl, backend?, verifyCached? }
 *               { type: "synth", id, text, voiceId }
 *               { type: "cancel", upTo }
 * Messages out: { type: "progress", loaded, total }
 *               { type: "ready", report }
 *               { type: "audio", id, samples, sampleRate, synthMs, sentences }
 *               { type: "cancelled", id }
 *               { type: "error", id?, message }
 */

import { scaleSilence, concatSamples } from "./kokoro-audio.js";
import { loadLexicons, parseTokens, textToTokenIds } from "./kokoro-frontend.js";
import { chatLexiconText, coverUnknownWords, kokoroChatLexicon, parseKokoroLexicon } from "./kokoro-lexicon.js";
import { floatsFromBytes, kokoroVoice, styleRow } from "./kokoro-voices.js";
import { loadPinned } from "./model-cache.js";
import { KOKORO_WEB } from "./models.js";

const WARMUP_TEXT = "Hi.";

let engine = null;
let loading = null;
let cancelledUpTo = 0;
const queue = [];
let running = false;

self.onmessage = (event) => {
  const message = event.data;
  if (message.type === "load") {
    loading ??= load(message).catch((error) => {
      loading = null;
      self.postMessage({ type: "error", message: String(error?.message || error) });
    });
  } else if (message.type === "synth") {
    queue.push(message);
    drain();
  } else if (message.type === "cancel") {
    cancelledUpTo = Math.max(cancelledUpTo, message.upTo);
  }
};

async function load(options) {
  const started = performance.now();
  const files = KOKORO_WEB.files;
  const order = ["ortWasm", "model", "styles", "tokens", "lexicon"];
  const total = order.reduce((sum, key) => sum + files[key].bytes, 0);
  const loaded = Object.fromEntries(order.map((key) => [key, 0]));
  const report = { files: {}, total };
  let lastPost = 0;
  const progress = () => {
    const sum = order.reduce((acc, key) => acc + loaded[key], 0);
    const at = performance.now();
    if (at - lastPost > 100 || sum === total) {
      lastPost = at;
      self.postMessage({ type: "progress", loaded: sum, total });
    }
  };
  const results = await Promise.all(order.map(async (key) => {
    const result = await loadPinned(files[key], {
      baseUrl: options.baseUrl,
      verifyCached: options.verifyCached,
      onProgress: (bytes) => {
        loaded[key] = bytes;
        progress();
      },
    });
    report.files[key] = { name: files[key].name, bytes: files[key].bytes, source: result.source, ms: round(result.ms) };
    return [key, result.bytes];
  }));
  const bytes = Object.fromEntries(results);
  report.downloadMs = round(performance.now() - started);
  report.downloadedBytes = Object.values(report.files).filter((file) => file.source === "network").reduce((sum, file) => sum + file.bytes, 0);

  const prepStarted = performance.now();
  const decoder = new TextDecoder();
  const baseText = decoder.decode(bytes.lexicon);
  const chat = kokoroChatLexicon(parseKokoroLexicon(baseText));
  const lexiconTexts = chat.additions.size > 0 ? [baseText, chatLexiconText(chat.additions)] : [baseText];
  const known = chat.additions.size > 0 ? chat.known : new Set([...chat.known].filter((word) => !chat.additions.has(word)));
  const { lexicon, maxPhraseLen } = loadLexicons(lexiconTexts);
  const frontend = { tokens: parseTokens(decoder.decode(bytes.tokens)), lexicon, maxPhraseLen };
  const styles = floatsFromBytes(bytes.styles);
  report.lexiconMs = round(performance.now() - prepStarted);
  report.lexiconWords = lexicon.size;
  report.chatAdditions = chat.additions.size;

  const ort = await import(options.ortModuleUrl);
  ort.env.wasm.wasmBinary = bytes.ortWasm;
  ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 1) : 1;
  const sessionStarted = performance.now();
  const { session, backend } = await createSession(ort, bytes.model, options.backend ?? "auto");
  report.sessionMs = round(performance.now() - sessionStarted);
  report.backend = backend;

  engine = { ort, session, frontend, known, styles };
  const warmStarted = performance.now();
  await synthesize(WARMUP_TEXT, kokoroVoice("").id);
  report.warmupMs = round(performance.now() - warmStarted);
  report.readyMs = round(performance.now() - started);
  self.postMessage({ type: "ready", report });
}

async function createSession(ort, model, backend) {
  const tries = backend === "wasm" ? ["wasm"] : backend === "webgpu" ? ["webgpu"] : ["webgpu", "wasm"];
  let lastError = null;
  for (const provider of tries) {
    if (provider === "webgpu" && !(await hasWebGpu())) {
      lastError = new Error("WebGPU is not available");
      continue;
    }
    try {
      const session = await ort.InferenceSession.create(model, {
        executionProviders: [provider],
        graphOptimizationLevel: "all",
      });
      return { session, backend: provider };
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError ?? new Error("no ONNX backend");
}

async function hasWebGpu() {
  try {
    return Boolean(await navigator.gpu?.requestAdapter());
  } catch {
    return false;
  }
}

async function drain() {
  if (running) return;
  running = true;
  try {
    await loading;
    while (queue.length > 0) {
      const job = queue.shift();
      if (job.id <= cancelledUpTo) {
        self.postMessage({ type: "cancelled", id: job.id });
        continue;
      }
      try {
        if (!engine) throw new Error("Ember's voice is not loaded");
        const started = performance.now();
        const { samples, sentences } = await synthesize(job.text, job.voiceId);
        const synthMs = performance.now() - started;
        if (job.id <= cancelledUpTo) {
          self.postMessage({ type: "cancelled", id: job.id });
          continue;
        }
        self.postMessage(
          { type: "audio", id: job.id, samples, sampleRate: KOKORO_WEB.sampleRate, synthMs, sentences },
          [samples.buffer],
        );
      } catch (error) {
        self.postMessage({ type: "error", id: job.id, message: String(error?.message || error) });
      }
    }
  } finally {
    running = false;
  }
}

/** One desktop Kokoro synthesize() call: one piece in, its samples out. */
async function synthesize(text, voiceId) {
  const { ort, session, frontend, known, styles } = engine;
  const voice = kokoroVoice(voiceId);
  const spoken = coverUnknownWords(text, known);
  const sentences = textToTokenIds(spoken, frontend);
  const parts = [];
  for (const ids of sentences) {
    const style = styleRow(styles, voice.slot, ids.length - 2);
    const feeds = {
      tokens: new ort.Tensor("int64", BigInt64Array.from(ids, BigInt), [1, ids.length]),
      style: new ort.Tensor("float32", Float32Array.from(style), [1, style.length]),
      speed: new ort.Tensor("float32", new Float32Array([voice.speed]), [1]),
    };
    const out = await session.run(feeds);
    const audio = out.audio.data instanceof Float32Array ? out.audio.data : Float32Array.from(out.audio.data);
    parts.push(scaleSilence(audio, KOKORO_WEB.sampleRate));
  }
  return { samples: concatSamples(parts), sentences: sentences.length };
}

function round(ms) {
  return Math.round(ms * 10) / 10;
}
