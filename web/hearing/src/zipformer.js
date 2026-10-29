/**
 * Streaming Zipformer2 transducer (sherpa-onnx-streaming-zipformer-en-2023-06-26,
 * chunk 16, left context 128) on onnxruntime-web. A port of sherpa-onnx 2.0.0's
 * online recognizer as desktop runs it: OnlineZipformer2TransducerModel,
 * OnlineTransducerGreedySearchDecoder, and the token-to-text step, with
 * features from fbank.js. Desktop makes a new stream per utterance, so a
 * stream here never resets in place.
 *
 * The encoder layout below is the model's metadata. zipformer.test.js reads
 * it back out of the model's inputs so a new model pin cannot drift from it.
 */

import { OnlineFbank } from "./fbank.js";

export const ZIPFORMER_META = {
  T: 45,
  decodeChunkLen: 32,
  numEncoderLayers: [2, 2, 3, 4, 3, 2],
  encoderDims: [192, 256, 384, 512, 384, 256],
  cnnModuleKernels: [31, 31, 15, 15, 15, 31],
  leftContextLen: [128, 64, 32, 16, 32, 64],
  queryHeadDims: [32, 32, 32, 32, 32, 32],
  valueHeadDims: [12, 12, 12, 12, 12, 12],
  numHeads: [4, 4, 4, 8, 4, 4],
  contextSize: 2,
  featureDim: 80,
};

const BLANK = 0;

/** GetEncoderInitStates: [shape, dtype] per state input, in encoder input order after x. */
export function encoderStateShapes(meta = ZIPFORMER_META) {
  const shapes = [];
  meta.numEncoderLayers.forEach((layers, i) => {
    const keyDim = meta.queryHeadDims[i] * meta.numHeads[i];
    const valueDim = meta.valueHeadDims[i] * meta.numHeads[i];
    const nonlin = (3 * meta.encoderDims[i]) / 4;
    const kernel = Math.trunc(meta.cnnModuleKernels[i] / 2);
    for (let j = 0; j < layers; j += 1) {
      shapes.push([[meta.leftContextLen[i], 1, keyDim], "float32"]);
      shapes.push([[1, 1, meta.leftContextLen[i], nonlin], "float32"]);
      shapes.push([[meta.leftContextLen[i], 1, valueDim], "float32"]);
      shapes.push([[meta.leftContextLen[i], 1, valueDim], "float32"]);
      shapes.push([[1, meta.encoderDims[i], kernel], "float32"]);
      shapes.push([[1, meta.encoderDims[i], kernel], "float32"]);
    }
  });
  const embedDim = Math.trunc((Math.trunc((meta.featureDim - 1) / 2) - 1) / 2);
  shapes.push([[1, 128, 3, embedDim], "float32"]);
  shapes.push([[1], "int64"]);
  return shapes;
}

/** csrc/symbol-table.cc: "sym id" lines; the first id wins; a leading U+2581 becomes a space on lookup. */
export function parseAsrTokens(text) {
  const symbols = [];
  let unk = -1;
  for (const raw of text.split("\n")) {
    const fields = raw.trim().split(/\s+/).filter(Boolean);
    if (fields.length === 0) continue;
    const [symbol, id] = fields.length === 1 ? [" ", Number(fields[0])] : [fields[0], Number(fields[1])];
    if (symbols[id] === undefined) symbols[id] = symbol;
    if (symbol === "<unk>" && unk === -1) unk = id;
  }
  return { symbols, unk };
}

/** OnlineRecognizerTransducerImpl Convert: skip <unk>, join, leading ▁ as a space. */
export function tokensToText(tokens, { symbols }) {
  let text = "";
  for (const id of tokens) {
    let symbol = symbols[id];
    if (symbol === "<unk>") continue;
    if (symbol.length >= 1 && symbol[0] === "▁") symbol = ` ${symbol.slice(1)}`;
    text += symbol;
  }
  return text;
}

export class StreamingZipformer {
  /** ort: the onnxruntime-web module; models: { encoder, decoder, joiner } bytes; tokens: tokens.txt text. */
  static async create(ort, models, tokensText, options = {}) {
    const sessionOptions = { executionProviders: [options.provider ?? "wasm"], graphOptimizationLevel: "all" };
    const [encoder, decoder, joiner] = await Promise.all([
      ort.InferenceSession.create(models.encoder, sessionOptions),
      ort.InferenceSession.create(models.decoder, sessionOptions),
      ort.InferenceSession.create(models.joiner, sessionOptions),
    ]);
    return new StreamingZipformer(ort, { encoder, decoder, joiner }, parseAsrTokens(tokensText), options.meta ?? ZIPFORMER_META);
  }

  constructor(ort, sessions, table, meta) {
    this.ort = ort;
    this.sessions = sessions;
    this.table = table;
    this.meta = meta;
    this.stateShapes = encoderStateShapes(meta);
    if (sessions.encoder.inputNames.length !== this.stateShapes.length + 1) {
      throw new Error(`encoder has ${sessions.encoder.inputNames.length} inputs, expected ${this.stateShapes.length + 1}`);
    }
  }

  createStream() {
    const { ort } = this;
    const states = this.stateShapes.map(([dims, type]) => {
      const size = dims.reduce((a, b) => a * b, 1);
      return new ort.Tensor(type, type === "int64" ? new BigInt64Array(size) : new Float32Array(size), dims);
    });
    const tokens = new Array(this.meta.contextSize).fill(-1);
    tokens[tokens.length - 1] = BLANK;
    return { fbank: new OnlineFbank(), states, tokens, decoderOut: null, processed: 0 };
  }

  isReady(stream) {
    return stream.processed + this.meta.T < stream.fbank.numFramesReady;
  }

  /** Feed samples, then decode every chunk that is ready (desktop: accept, then while isReady decode). */
  async acceptWaveform(stream, samples) {
    stream.fbank.acceptWaveform(samples);
    while (this.isReady(stream)) await this.decodeChunk(stream);
  }

  /** Desktop finish(): inputFinished, then decode what is ready. A last partial chunk stays undecoded, as in sherpa. */
  async finish(stream) {
    stream.fbank.inputFinished();
    while (this.isReady(stream)) await this.decodeChunk(stream);
    return this.text(stream);
  }

  text(stream) {
    return tokensToText(stream.tokens.slice(this.meta.contextSize), this.table);
  }

  async decodeChunk(stream) {
    const { ort, sessions, meta } = this;
    const x = new ort.Tensor("float32", stream.fbank.getFrames(stream.processed, meta.T), [1, meta.T, meta.featureDim]);
    stream.processed += meta.decodeChunkLen;
    stream.fbank.discardBefore(stream.processed);
    const feeds = { [sessions.encoder.inputNames[0]]: x };
    sessions.encoder.inputNames.slice(1).forEach((name, i) => {
      feeds[name] = stream.states[i];
    });
    const out = await sessions.encoder.run(feeds);
    stream.states = sessions.encoder.outputNames.slice(1).map((name) => out[name]);
    await this.greedy(stream, out[sessions.encoder.outputNames[0]]);
  }

  async greedy(stream, encoderOut) {
    const [, frames, dim] = encoderOut.dims;
    let decoderOut = stream.decoderOut ?? (await this.runDecoder(stream.tokens));
    for (let t = 0; t < frames; t += 1) {
      const frame = new this.ort.Tensor("float32", encoderOut.data.slice(t * dim, (t + 1) * dim), [1, dim]);
      const logits = (await this.sessions.joiner.run({ encoder_out: frame, decoder_out: decoderOut })).logit.data;
      let y = 0;
      for (let k = 1; k < logits.length; k += 1) if (logits[k] > logits[y]) y = k;
      if (y !== BLANK && y !== this.table.unk) {
        stream.tokens.push(y);
        decoderOut = await this.runDecoder(stream.tokens);
      }
    }
    stream.decoderOut = decoderOut;
  }

  async runDecoder(tokens) {
    const context = tokens.slice(-this.meta.contextSize).map(BigInt);
    const y = new this.ort.Tensor("int64", BigInt64Array.from(context), [1, this.meta.contextSize]);
    return (await this.sessions.decoder.run({ y })).decoder_out;
  }
}
