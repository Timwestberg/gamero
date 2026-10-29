/**
 * Web engines. Silero v5 runs in onnxruntime-web (the same 512-sample window the
 * sherpa file expects). Streaming Zipformer and Kokoro run through sherpa-onnx
 * WASM, which owns the Zipformer cache and the Kokoro graph. This file does not
 * fetch weights and does not talk to Hovel.
 *
 * Silero inputs, checked against silero_vad_v5.onnx: input [1, 512], state [2, 1, 128], sr int64.
 */

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
 * Kokoro through the same WASM build. Playback is PCM into an AudioContext so the
 * browser's echo canceller hears the far end. Fillers stay off.
 */
export function createKokoroSpeaker(sherpa, files, audio) {
  const tts = sherpa.createOfflineTts({
    model: files.model,
    voices: files.voices,
    tokens: files.tokens,
    lexicon: files.lexicon,
    lengthScale: files.lengthScale,
  });
  return {
    state: "idle",
    async speak(text) {
      this.state = "preparing";
      const audioData = tts.generate({ text, sid: files.sid });
      this.state = "speaking";
      await audio.play(audioData.samples, audioData.sampleRate);
      if (this.state === "speaking") this.state = "idle";
    },
    stop() {
      audio.stop();
      this.state = "idle";
    },
  };
}

export const KOKORO_VOICES = [
  { id: "adam70-michael30", name: "Sam", speed: 1, sid: 50 },
  { id: "adam60-george40", name: "Jamie", speed: 0.95, sid: 51 },
  { id: "adam55-bella45", name: "Riley", speed: 0.95, sid: 52 },
  { id: "heart60-adam40", name: "Harper", speed: 0.97, sid: 53 },
];

export const SENTENCE_GAP_MS = 160;
export const FILLERS_DEFAULT = false;
