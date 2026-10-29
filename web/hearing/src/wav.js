/** 16-bit PCM mono WAV, the format desktop's readPcm16Wav takes. */

export function encodeWav16(samples, sampleRate) {
  const out = new Uint8Array(44 + samples.length * 2);
  const view = new DataView(out.buffer);
  const text = (offset, value) => [...value].forEach((ch, index) => view.setUint8(offset + index, ch.charCodeAt(0)));
  text(0, "RIFF");
  view.setUint32(4, 36 + samples.length * 2, true);
  text(8, "WAVE");
  text(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  text(36, "data");
  view.setUint32(40, samples.length * 2, true);
  for (let index = 0; index < samples.length; index += 1) {
    const value = Math.max(-1, Math.min(1, samples[index]));
    view.setInt16(44 + index * 2, Math.round(value * 32767), true);
  }
  return out;
}

/** Mono or stereo 16-bit PCM to floats in [-1, 1) (sample / 32768), mixed to mono. */
export function decodeWav16(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const tag = (offset) => String.fromCharCode(...bytes.subarray(offset, offset + 4));
  if (tag(0) !== "RIFF" || tag(8) !== "WAVE") throw new Error("not a WAV");
  let channels = 1;
  let sampleRate = 16000;
  let bits = 16;
  let cursor = 12;
  while (cursor + 8 <= bytes.length) {
    const id = tag(cursor);
    const size = view.getUint32(cursor + 4, true);
    const body = cursor + 8;
    if (id === "fmt ") {
      channels = view.getUint16(body + 2, true);
      sampleRate = view.getUint32(body + 4, true);
      bits = view.getUint16(body + 14, true);
    } else if (id === "data") {
      if (bits !== 16) throw new Error("only 16-bit PCM");
      const frames = Math.floor(Math.min(size, bytes.length - body) / 2 / channels);
      const samples = new Float32Array(frames);
      for (let frame = 0; frame < frames; frame += 1) {
        let sum = 0;
        for (let channel = 0; channel < channels; channel += 1) sum += view.getInt16(body + (frame * channels + channel) * 2, true);
        samples[frame] = sum / channels / 32768;
      }
      return { samples, sampleRate };
    }
    cursor = body + size + (size & 1);
  }
  throw new Error("WAV has no data chunk");
}
