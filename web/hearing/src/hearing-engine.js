/**
 * The browser port of desktop's SherpaHearingEngine (SherpaHearing.kt): one
 * utterance of Silero VAD plus the streaming Zipformer. accept() feeds a mic
 * chunk to both; voicedMs and lastProb describe that chunk the way desktop
 * reports them; finish() decodes what is left and returns the trimmed text.
 */

import { SILERO_WINDOW } from "./silero.js";

/** Desktop: "Room noise often sits near 0.5. A short hangover then never lets a second of quiet accumulate." */
export const HEARING_VAD_THRESHOLD = 0.6;
export const HEARING_SAMPLE_RATE = 16000;
export const HEARING_MIN_SPEECH_MS = 240;
const WINDOW_MS = Math.trunc((SILERO_WINDOW * 1000) / HEARING_SAMPLE_RATE);

export class HearingEngine {
  /** asr: StreamingZipformer, vad: SileroVad or null (then every accept counts as voiced, as on desktop). */
  constructor({ asr, vad, threshold = HEARING_VAD_THRESHOLD, now = () => performance.now() }) {
    this.asr = asr;
    this.vad = vad;
    this.threshold = threshold;
    this.now = now;
    this.stream = null;
    this.window = new Float32Array(SILERO_WINDOW);
    this.lastProb = 0;
    this.voicedMs = 0;
    this.startUtterance();
  }

  startUtterance() {
    this.stream = this.asr.createStream();
    this.voiced = false;
    this.heardMs = 0;
    this.voicedMs = 0;
    this.fill = 0;
    this.vad?.reset();
  }

  async accept(samples) {
    if (samples.length === 0) return;
    this.voicedMs = 0;
    this.lastProb = 0;
    this.heardMs += Math.trunc((samples.length * 1000) / HEARING_SAMPLE_RATE);
    await this.asr.acceptWaveform(this.stream, samples);
    await this.noteSpeech(samples);
  }

  partial() {
    return this.asr.text(this.stream);
  }

  /** { text, speechMs, decodeMs }. The stream is done afterwards; call startUtterance() for the next turn. */
  async finish() {
    const started = this.now();
    const text = (await this.asr.finish(this.stream)).trim();
    const decodeMs = this.now() - started;
    let speechMs = 0;
    if (text !== "") speechMs = this.voiced ? this.heardMs : Math.max(this.heardMs, HEARING_MIN_SPEECH_MS);
    return { text, speechMs, decodeMs };
  }

  async noteSpeech(samples) {
    if (!this.vad) {
      this.voiced = true;
      this.voicedMs = Math.trunc((samples.length * 1000) / HEARING_SAMPLE_RATE);
      return;
    }
    let offset = 0;
    while (offset < samples.length) {
      const count = Math.min(SILERO_WINDOW - this.fill, samples.length - offset);
      this.window.set(samples.subarray(offset, offset + count), this.fill);
      this.fill += count;
      offset += count;
      if (this.fill === SILERO_WINDOW) {
        const prob = await this.vad.compute(this.window);
        this.lastProb = prob;
        await this.vad.acceptWaveform(this.window);
        if (prob >= this.threshold) {
          this.voiced = true;
          this.voicedMs += WINDOW_MS;
        }
        this.fill = 0;
      }
    }
  }
}

/** Root mean square of a chunk, as desktop's chunkRms. */
export function chunkRms(samples) {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (const sample of samples) sum += sample * sample;
  return Math.fround(Math.sqrt(sum / samples.length));
}

/** Desktop's PreRoll: the last `capacity` samples heard. */
export class PreRoll {
  constructor(capacity = Math.trunc((HEARING_SAMPLE_RATE * 300) / 1000)) {
    this.buf = new Float32Array(Math.max(1, capacity));
    this.count = 0;
    this.at = 0;
  }

  add(samples) {
    for (const sample of samples) {
      this.buf[this.at] = sample;
      this.at = (this.at + 1) % this.buf.length;
      if (this.count < this.buf.length) this.count += 1;
    }
  }

  copy() {
    const out = new Float32Array(this.count);
    const start = (this.at - this.count + this.buf.length) % this.buf.length;
    for (let i = 0; i < this.count; i += 1) out[i] = this.buf[(start + i) % this.buf.length];
    return out;
  }
}
