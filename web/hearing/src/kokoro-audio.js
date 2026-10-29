/**
 * Sample math the browser shares with desktop.
 * - scaleSilence is sherpa-onnx v2.0.0 GeneratedAudio::ScaleSilence, which
 *   desktop's Kokoro gets at the default silence_scale of 0.2.
 * - trimEdgeSilence, fadeEdge, and shapeSentence are gamero Speech.kt.
 * Comparisons and products are rounded to float32 where the originals use float.
 */

export const SHERPA_SILENCE_SCALE = 0.2;
export const SILENCE_TRIM_THRESHOLD = 0.02;
export const CROSSFADE_MS = 10;

const f32 = Math.fround;

/** Pauses longer than 0.2 s (|x| <= 0.01) are cut to `scale` of their length. */
export function scaleSilence(samples, sampleRate, scale = SHERPA_SILENCE_SCALE) {
  if (scale === 1 || !(scale >= 0.01 && scale <= 10)) return samples;
  const threshold = Math.trunc(sampleRate * 0.2);
  const n = samples.length;
  const intervals = [];
  let last = -1;
  for (let i = 0; i < n; i += 1) {
    if (Math.abs(samples[i]) <= 0.01) {
      if (last === -1) last = i;
      continue;
    }
    if (last !== -1 && i - last < threshold) {
      last = -1;
      continue;
    }
    if (last !== -1) {
      intervals.push([last, i]);
      last = -1;
    }
  }
  if (last !== -1 && n - last > threshold) intervals.push([last, n]);
  if (intervals.length === 0) return samples;

  const parts = [];
  let cursor = 0;
  const scale32 = f32(scale);
  for (const [start, end] of intervals) {
    parts.push(samples.subarray(cursor, start));
    cursor = end;
    const len = end - start;
    const keep = Math.trunc(f32(len * scale32));
    if (keep <= len) parts.push(samples.subarray(start, start + keep));
    else {
      parts.push(samples.subarray(start, end));
      parts.push(new Float32Array(keep - len));
    }
  }
  if (cursor < n) parts.push(samples.subarray(cursor));
  return concatSamples(parts);
}

/** Drops samples quieter than threshold at both ends. A take quiet throughout is kept. */
export function trimEdgeSilence(samples, threshold = SILENCE_TRIM_THRESHOLD) {
  const limit = f32(threshold);
  if (samples.length === 0 || limit <= 0) return samples;
  let start = 0;
  while (start < samples.length && Math.abs(samples[start]) < limit) start += 1;
  let end = samples.length;
  while (end > start && Math.abs(samples[end - 1]) < limit) end -= 1;
  if (start === 0 && end === samples.length) return samples;
  if (end <= start) return samples;
  return samples.slice(start, end);
}

/** Fades an edge to silence over `fade` samples so a trimmed cut does not click. */
export function fadeEdge(samples, fade, fadeHead, fadeTail) {
  if (fade <= 0 || samples.length === 0 || (!fadeHead && !fadeTail)) return samples;
  const used = Math.min(fade, Math.floor(samples.length / 2));
  if (used <= 0) return samples;
  const out = Float32Array.from(samples);
  for (let index = 0; index < used; index += 1) {
    const gain = f32(index / used);
    if (fadeHead) out[index] = out[index] * gain;
    if (fadeTail) out[out.length - 1 - index] = out[out.length - 1 - index] * gain;
  }
  return out;
}

export function shapeSentence(samples, threshold, fadeSamples, fadeHead, fadeTail) {
  return fadeEdge(trimEdgeSilence(samples, threshold), fadeSamples, fadeHead, fadeTail);
}

/** Desktop rounds down: sampleRate * ms / 1000 in integers. */
export function msToSamples(sampleRate, ms) {
  return Math.floor((sampleRate * ms) / 1000);
}

export function concatSamples(parts) {
  let total = 0;
  for (const part of parts) total += part.length;
  const out = new Float32Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}
