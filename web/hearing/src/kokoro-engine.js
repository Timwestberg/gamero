/**
 * Page side of the Kokoro worker. Implements the SpeechEngine the speaker
 * drives (see speaker.js):
 *   load()                      -> Promise<report>   idempotent
 *   synthesize(piece, voiceId)  -> Promise<{ samples, sampleRate, synthMs } | null>   null when cancelled
 *   cancel()                    -> drops queued pieces; a piece already running is discarded when done
 */

export function createKokoroEngine(options) {
  const workerUrl = options.workerUrl ?? new URL("./kokoro-worker.js", import.meta.url);
  const ortModuleUrl = options.ortModuleUrl ?? new URL("../node_modules/onnxruntime-web/dist/ort.webgpu.min.mjs", import.meta.url).href;
  const baseUrl = new URL(options.baseUrl, globalThis.location?.href).href;
  let worker = null;
  let ready = null;
  let nextId = 1;
  const pending = new Map();
  const engine = {
    status: "idle",
    report: null,
    progress: { loaded: 0, total: 0 },
    load() {
      if (ready) return ready;
      engine.status = "loading";
      ready = new Promise((resolve, reject) => {
        worker = options.createWorker ? options.createWorker(workerUrl) : new Worker(workerUrl, { type: "module" });
        worker.onmessage = (event) => {
          const message = event.data;
          if (message.type === "progress") {
            engine.progress = { loaded: message.loaded, total: message.total };
            options.onProgress?.(engine.progress);
          } else if (message.type === "ready") {
            engine.status = "ready";
            engine.report = message.report;
            options.onReady?.(message.report);
            resolve(message.report);
          } else if (message.type === "audio") {
            settle(message.id, { samples: message.samples, sampleRate: message.sampleRate, synthMs: message.synthMs, sentences: message.sentences });
          } else if (message.type === "cancelled") {
            settle(message.id, null);
          } else if (message.type === "error") {
            if (message.id == null) {
              engine.status = "error";
              ready = null;
              reject(new Error(message.message));
            } else {
              fail(message.id, new Error(message.message));
            }
          }
        };
        worker.onerror = (event) => {
          engine.status = "error";
          ready = null;
          reject(new Error(event.message || "the voice worker stopped"));
        };
        worker.postMessage({ type: "load", baseUrl, ortModuleUrl, backend: options.backend, verifyCached: options.verifyCached });
      });
      return ready;
    },
    async synthesize(text, voiceId) {
      await engine.load();
      const id = nextId;
      nextId += 1;
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        worker.postMessage({ type: "synth", id, text, voiceId });
      });
    },
    cancel() {
      const upTo = nextId - 1;
      if (worker && upTo > 0) worker.postMessage({ type: "cancel", upTo });
      for (const [id, entry] of pending) {
        pending.delete(id);
        entry.resolve(null);
      }
    },
    dispose() {
      engine.cancel();
      worker?.terminate();
      worker = null;
      ready = null;
      engine.status = "idle";
    },
  };

  function settle(id, value) {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    entry.resolve(value);
  }

  function fail(id, error) {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    entry.reject(error);
  }

  return engine;
}
