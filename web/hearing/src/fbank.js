/**
 * 80-bin log-mel filterbank for the streaming Zipformer, ported from
 * kaldi-native-fbank v1.22.4 (the version sherpa-onnx 2.0.0 builds) with
 * sherpa's FeatureExtractorConfig defaults: 16 kHz, 25 ms frames every 10 ms,
 * snip_edges false, no dither, DC removal, pre-emphasis 0.97, povey window,
 * 512-point FFT, Kaldi mel banks from 20 Hz to Nyquist - 400 Hz, power
 * spectrum, log floored at float epsilon. Samples are floats in [-1, 1].
 *
 * OnlineFbank keeps kaldi's online framing: frames appear as soon as their
 * window is complete, and inputFinished() adds the edge frames, reflected.
 * Arithmetic is rounded to float32 where kaldi uses float; the FFT runs in
 * double, so values can differ from kiss_fft in the last bits.
 */

const f32 = Math.fround;

export const FBANK = {
  sampleRate: 16000,
  bins: 80,
  shift: 160,
  length: 400,
  padded: 512,
  preemph: f32(0.97),
  lowFreq: 20,
  highFreq: -400,
};

const EPS = f32(1.1920928955078125e-7);

/** Kaldi "povey": pow(0.5 - 0.5 cos(2 pi i / (N - 1)), 0.85), computed in double, stored as float. */
export function poveyWindow(length = FBANK.length) {
  const out = new Float32Array(length);
  const a = (2 * Math.PI) / (length - 1);
  for (let i = 0; i < length; i += 1) out[i] = Math.pow(0.5 - 0.5 * Math.cos(a * i), 0.85);
  return out;
}

function melScale(freq) {
  return f32(f32(1127) * f32(Math.log(f32(1 + f32(freq / 700)))));
}

/** Kaldi mel banks: one triangle per bin over the first padded/2 FFT bins. */
export function melBanks({ sampleRate, bins, padded, lowFreq, highFreq } = FBANK) {
  const fftBins = padded / 2;
  const nyquist = f32(0.5 * sampleRate);
  const high = highFreq > 0 ? highFreq : f32(nyquist + highFreq);
  const binWidth = f32(sampleRate / padded);
  const melLow = melScale(lowFreq);
  const melHigh = melScale(high);
  const delta = f32(f32(melHigh - melLow) / (bins + 1));
  const banks = [];
  for (let bin = 0; bin < bins; bin += 1) {
    const left = f32(melLow + f32(bin * delta));
    const center = f32(melLow + f32((bin + 1) * delta));
    const right = f32(melLow + f32((bin + 2) * delta));
    const weights = [];
    let first = -1;
    for (let i = 0; i < fftBins; i += 1) {
      const mel = melScale(f32(binWidth * i));
      if (mel > left && mel < right) {
        const weight = mel <= center ? f32(f32(mel - left) / f32(center - left)) : f32(f32(right - mel) / f32(right - center));
        if (first === -1) first = i;
        weights[i - first] = weight;
      }
    }
    if (first === -1) throw new Error("mel bin has no FFT bins");
    banks.push({ offset: first, weights: Float32Array.from(weights) });
  }
  return banks;
}

/** First sample of frame f with snip_edges false: centered on f * shift + shift / 2. */
export function firstSampleOfFrame(frame, { shift, length } = FBANK) {
  return shift * frame + Math.trunc(shift / 2) - Math.trunc(length / 2);
}

/** Frames available from numSamples. Without flush, only frames whose window is complete. */
export function numFrames(numSamples, flush, opts = FBANK) {
  let frames = Math.trunc((numSamples + Math.trunc(opts.shift / 2)) / opts.shift);
  if (flush) return frames;
  let end = firstSampleOfFrame(frames - 1, opts) + opts.length;
  while (frames > 0 && end > numSamples) {
    frames -= 1;
    end -= opts.shift;
  }
  return frames;
}

/** In-place radix-2 real FFT of `n` points; returns the power spectrum of bins 0..n/2 (float32). */
function powerSpectrum(frame, n, cosTable, sinTable, re, im) {
  for (let i = 0; i < n; i += 1) {
    re[i] = frame[i];
    im[i] = 0;
  }
  for (let i = 1, j = 0; i < n; i += 1) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      const t = re[i];
      re[i] = re[j];
      re[j] = t;
    }
  }
  for (let size = 2; size <= n; size <<= 1) {
    const step = n / size;
    const half = size >> 1;
    for (let start = 0; start < n; start += size) {
      for (let k = 0; k < half; k += 1) {
        const c = cosTable[k * step];
        const s = sinTable[k * step];
        const a = start + k;
        const b = a + half;
        const tr = re[b] * c + im[b] * s;
        const ti = im[b] * c - re[b] * s;
        re[b] = re[a] - tr;
        im[b] = im[a] - ti;
        re[a] += tr;
        im[a] += ti;
      }
    }
  }
  const out = new Float32Array(n / 2 + 1);
  for (let k = 0; k <= n / 2; k += 1) {
    const r = f32(re[k]);
    const i = f32(im[k]);
    out[k] = f32(f32(r * r) + f32(i * i));
  }
  return out;
}

export class OnlineFbank {
  constructor(opts = FBANK) {
    this.opts = opts;
    this.window = poveyWindow(opts.length);
    this.banks = melBanks(opts);
    this.cos = new Float64Array(opts.padded / 2);
    this.sin = new Float64Array(opts.padded / 2);
    for (let k = 0; k < opts.padded / 2; k += 1) {
      this.cos[k] = Math.cos((2 * Math.PI * k) / opts.padded);
      this.sin[k] = Math.sin((2 * Math.PI * k) / opts.padded);
    }
    this.re = new Float64Array(opts.padded);
    this.im = new Float64Array(opts.padded);
    this.remainder = new Float32Array(0);
    this.offset = 0;
    this.finished = false;
    this.frames = [];
    this.dropped = 0;
  }

  get numFramesReady() {
    return this.dropped + this.frames.length;
  }

  acceptWaveform(samples) {
    if (samples.length === 0) return;
    if (this.finished) throw new Error("acceptWaveform after inputFinished");
    const merged = new Float32Array(this.remainder.length + samples.length);
    merged.set(this.remainder);
    merged.set(samples, this.remainder.length);
    this.remainder = merged;
    this.compute();
  }

  inputFinished() {
    this.finished = true;
    this.compute();
  }

  /** Frames [start, start + count) as one row-major Float32Array. */
  getFrames(start, count) {
    const out = new Float32Array(count * this.opts.bins);
    for (let i = 0; i < count; i += 1) out.set(this.frames[start + i - this.dropped], i * this.opts.bins);
    return out;
  }

  /** Forgets frames before `index`; a streaming decoder never reads them again. */
  discardBefore(index) {
    const count = Math.max(0, Math.min(index - this.dropped, this.frames.length));
    if (count === 0) return;
    this.frames.splice(0, count);
    this.dropped += count;
  }

  compute() {
    const { opts } = this;
    const total = this.offset + this.remainder.length;
    const old = this.numFramesReady;
    const next = numFrames(total, this.finished, opts);
    for (let frame = old; frame < next; frame += 1) this.frames.push(this.computeFrame(frame));
    const discard = firstSampleOfFrame(next, opts) - this.offset;
    if (discard > 0) {
      if (discard >= this.remainder.length) {
        this.offset += this.remainder.length;
        this.remainder = new Float32Array(0);
      } else {
        this.remainder = this.remainder.slice(discard);
        this.offset += discard;
      }
    }
  }

  computeFrame(frame) {
    const { opts, remainder } = this;
    const window = new Float32Array(opts.padded);
    const start = firstSampleOfFrame(frame, opts) - this.offset;
    const dim = remainder.length;
    for (let s = 0; s < opts.length; s += 1) {
      let at = s + start;
      while (at < 0 || at >= dim) at = at < 0 ? -at - 1 : 2 * dim - 1 - at;
      window[s] = remainder[at];
    }
    let sum = 0;
    for (let i = 0; i < opts.length; i += 1) sum = f32(sum + window[i]);
    const mean = f32(sum / opts.length);
    for (let i = 0; i < opts.length; i += 1) window[i] = window[i] - mean;
    for (let i = opts.length - 1; i > 0; i -= 1) window[i] = window[i] - f32(opts.preemph * window[i - 1]);
    window[0] = window[0] - f32(opts.preemph * window[0]);
    for (let i = 0; i < opts.length; i += 1) window[i] = window[i] * this.window[i];
    const power = powerSpectrum(window, opts.padded, this.cos, this.sin, this.re, this.im);
    const out = new Float32Array(opts.bins);
    for (let bin = 0; bin < this.banks.length; bin += 1) {
      const { offset, weights } = this.banks[bin];
      let energy = 0;
      for (let k = 0; k < weights.length; k += 1) energy = f32(energy + f32(weights[k] * power[k + offset]));
      out[bin] = Math.log(Math.max(energy, EPS));
    }
    return out;
  }
}
