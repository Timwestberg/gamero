/**
 * Page side of hearing-worker.js.
 *   load()                    -> Promise<report>, idempotent
 *   chunk(samples, restart)   -> Promise<{ voicedMs, chunkMs, rms, prob, text }>
 *   finish()                  -> Promise<{ text, speechMs, decodeMs }>
 *   reset()                   -> forget the pre-roll and start a fresh utterance
 */

export function createHearingClient(options) {
  const workerUrl = options.workerUrl ?? new URL("./hearing-worker.js", import.meta.url);
  const ortModuleUrl = options.ortModuleUrl ?? new URL("../node_modules/onnxruntime-web/dist/ort.webgpu.min.mjs", import.meta.url).href;
  const baseUrl = new URL(options.baseUrl, globalThis.location?.href).href;
  let worker = null;
  let ready = null;
  let nextId = 1;
  const pending = new Map();

  const client = {
    status: "idle",
    report: null,
    progress: { loaded: 0, total: 0 },
    load() {
      if (ready) return ready;
      client.status = "loading";
      ready = new Promise((resolve, reject) => {
        worker = options.createWorker ? options.createWorker(workerUrl) : new Worker(workerUrl, { type: "module" });
        worker.onmessage = (event) => {
          const message = event.data;
          if (message.type === "progress") {
            client.progress = { loaded: message.loaded, total: message.total };
            options.onProgress?.(client.progress);
          } else if (message.type === "ready") {
            client.status = "ready";
            client.report = message.report;
            options.onReady?.(message.report);
            resolve(message.report);
          } else if (message.type === "frame" || message.type === "result") {
            const entry = pending.get(message.id);
            pending.delete(message.id);
            entry?.resolve(message);
          } else if (message.type === "error") {
            const entry = message.id != null ? pending.get(message.id) : null;
            if (entry) {
              pending.delete(message.id);
              entry.reject(new Error(message.message));
            } else {
              client.status = "error";
              ready = null;
              reject(new Error(message.message));
            }
          }
        };
        worker.onerror = (event) => {
          client.status = "error";
          ready = null;
          reject(new Error(event.message || "the hearing worker stopped"));
        };
        worker.postMessage({ type: "load", baseUrl, ortModuleUrl });
      });
      return ready;
    },
    chunk(samples, restart = null) {
      return send({ type: "chunk", samples, restart }, [samples.buffer]);
    },
    finish() {
      return send({ type: "finish" });
    },
    reset() {
      worker?.postMessage({ type: "reset" });
    },
    dispose() {
      worker?.terminate();
      worker = null;
      ready = null;
      client.status = "idle";
    },
  };

  async function send(message, transfer = []) {
    await client.load();
    const id = nextId;
    nextId += 1;
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      worker.postMessage({ ...message, id }, transfer);
    });
  }

  return client;
}
