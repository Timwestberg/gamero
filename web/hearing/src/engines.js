/**
 * Web engines. Silero v5 runs in onnxruntime-web (the same 512-sample window the
 * sherpa file expects). Streaming Zipformer runs through sherpa-onnx WASM, which
 * owns the Zipformer cache. This file does not fetch weights and does not talk
 * to Hovel.
 *
 * Silero inputs, checked against silero_vad_v5.onnx: input [1, 512], state [2, 1, 128], sr int64.
 */

import { KOKORO_VOICE_LIST } from "./kokoro-voices.js";

export const SILERO_WINDOW = 512;
export const HEARING_SAMPLE_RATE = 16000;

export function createSileroVad(session) {
  let state = new Float32Array(2 * 128);
  return {
    async probability(samples) {
      if (samples.length !== SILERO_WINDOW) throw new Error(`Silero v5 wants ${SILERO_WINDOW} samples`);
      const input = new Float32Array(samples);
      const sr = BigInt64Array ? new BigInt64Array([BigInt(HEARING_SAMPLE_RATE)]) : Int32Array.of(HEARING_SAMPLE_RATE);
      const feeds = {
        input: tensor(session, "float32", input, [1, SILERO_WINDOW]),
        state: tensor(session, "float32", state, [2, 1, 128]),
        sr: tensor(session, "int64", sr, []),
      };
      const out = await session.run(feeds);
      state = out.stateN.data;
      return out.output.data[0];
    },
  };
}

function tensor(session, type, data, dims) {
  if (typeof session.tensor === "function") return session.tensor(type, data, dims);
  const Tensor = session.Tensor;
  return new Tensor(type, data, dims);
}

/**
 * sherpa-onnx WASM module. `sherpa.createOnlineRecognizer` is the streaming Zipformer.
 * Decoding is greedy_search. The four pinned files are passed as paths or buffers
 * the module already verified.
 */
export function createZipformerAsr(sherpa, files) {
  const recognizer = sherpa.createOnlineRecognizer({
    tokens: files.tokens,
    encoder: files.encoder,
    decoder: files.decoder,
    joiner: files.joiner,
    sampleRate: HEARING_SAMPLE_RATE,
    decodingMethod: "greedy_search",
    enableEndpoint: false,
  });
  let stream = recognizer.createStream();
  return {
    accept(samples) {
      stream.acceptWaveform(HEARING_SAMPLE_RATE, samples);
      while (recognizer.isReady(stream)) recognizer.decode(stream);
    },
    partial() {
      return recognizer.getResult(stream).text || "";
    },
    reset() {
      recognizer.reset(stream);
      stream = recognizer.createStream();
    },
  };
}

/**
 * Kokoro speaks through kokoro-worker.js (onnxruntime-web on WebGPU, WASM
 * fallback) and speaker.js. These are the voices a person can pick, in
 * recipe order, with Sam first and the default.
 */
export const KOKORO_VOICES = KOKORO_VOICE_LIST.map(({ id, name, speed, sid }) => ({ id, name, speed, sid }));

export const SENTENCE_GAP_MS = 160;
export const FILLERS_DEFAULT = false;
