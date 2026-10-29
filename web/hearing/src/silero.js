/**
 * Silero VAD v5 the way desktop drives it through sherpa-onnx 2.0.0.
 *
 * Desktop calls two things per 512-sample window, and both run the model on
 * one shared state:
 *   Vad.compute(window)        -> SileroVadModel::Run(window, 512). Its output is the probability desktop uses.
 *   Vad.acceptWaveform(window) -> VoiceActivityDetector keeps a buffer and, once it holds 576 samples
 *                                 (window + 64 of overlap), runs IsSpeech on the first 576 and drops 512.
 * So from the second window on, each window advances the state twice: once
 * on itself, then on the previous window plus the first 64 samples of this
 * one. silero.test.js pins that order with a recording model.
 */

export const SILERO_WINDOW = 512;
export const SILERO_OVERLAP = 64;
const STATE_SIZE = 2 * 1 * 128;

export class SileroVad {
  static async create(ort, bytes, options = {}) {
    const session = await ort.InferenceSession.create(bytes, { executionProviders: [options.provider ?? "wasm"], graphOptimizationLevel: "all" });
    return new SileroVad(ort, session);
  }

  /** session: { run(feeds) } returning { output, stateN }, as onnxruntime does. */
  constructor(ort, session) {
    this.ort = ort;
    this.session = session;
    this.reset();
  }

  reset() {
    this.state = new this.ort.Tensor("float32", new Float32Array(STATE_SIZE), [2, 1, 128]);
    this.last = new Float32Array(0);
  }

  async run(samples) {
    const out = await this.session.run({
      input: new this.ort.Tensor("float32", Float32Array.from(samples), [1, samples.length]),
      state: this.state,
      sr: new this.ort.Tensor("int64", BigInt64Array.from([16000n]), [1]),
    });
    this.state = out.stateN;
    return out.output.data[0];
  }

  /** Vad.compute: the probability for one 512-sample window. */
  compute(window) {
    return this.run(window);
  }

  /** Vad.acceptWaveform: the segmenter's runs on 576-sample windows that overlap by 64. */
  async acceptWaveform(samples) {
    const merged = new Float32Array(this.last.length + samples.length);
    merged.set(this.last);
    merged.set(samples, this.last.length);
    const size = SILERO_WINDOW + SILERO_OVERLAP;
    let at = 0;
    while (merged.length - at >= size) {
      await this.run(merged.subarray(at, at + size));
      at += SILERO_WINDOW;
    }
    this.last = merged.slice(at);
  }
}
