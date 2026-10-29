/**
 * Ember's four Kokoro voices. KOKORO_BLENDS is a copy of gamero
 * voice/kokoro/blends.json (kokoro-voices.test.js compares them), and the
 * blend math is voice/kokoro/pack-blends.py: a weighted sum accumulated in
 * float64, divided by the weight total, stored as float32.
 *
 * The browser does not need the 28 MB upstream voices.bin. It loads
 * voices-ember.bin, the four blended style tables in recipe order
 * (4 x 510 x 256 float32, 2,088,960 bytes), cut from the same derived table
 * desktop builds.
 */

export const KOKORO_BLENDS = {
  defaultId: "adam70-michael30",
  modelOnnxSha256: "b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11",
  tokensSha256: "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425",
  lexiconUsEnSha256: "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14",
  upstreamVoicesSha256: "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29",
  derivedVoicesSha256: "35cce6c473585fd74244f31c2623d6f2f835283918dad6fa2e2a79d60639b26f",
  replacedSlots: "50 zm_yunxi, 51 zm_yunxia, 52 zm_yunyang, 53 em_santa",
  layout: { speakers: 54, frames: 510, dim: 256 },
  speakers: { af_bella: 2, af_heart: 3, am_adam: 11, am_michael: 16, bm_george: 26 },
  voices: [
    {
      id: "adam70-michael30",
      name: "Sam",
      sid: 50,
      speed: "1.00",
      weights: [{ speaker: "am_adam", weight: "0.70" }, { speaker: "am_michael", weight: "0.30" }],
    },
    {
      id: "adam60-george40",
      name: "Jamie",
      sid: 51,
      speed: "0.95",
      weights: [{ speaker: "am_adam", weight: "0.60" }, { speaker: "bm_george", weight: "0.40" }],
    },
    {
      id: "adam55-bella45",
      name: "Riley",
      sid: 52,
      speed: "0.95",
      weights: [{ speaker: "am_adam", weight: "0.55" }, { speaker: "af_bella", weight: "0.45" }],
    },
    {
      id: "heart60-adam40",
      name: "Harper",
      sid: 53,
      speed: "0.97",
      weights: [{ speaker: "af_heart", weight: "0.60" }, { speaker: "am_adam", weight: "0.40" }],
    },
  ],
};

/** One voice, ready for synthesis. `slot` is its index in voices-ember.bin. */
export const KOKORO_VOICE_LIST = KOKORO_BLENDS.voices.map((voice, slot) => ({
  id: voice.id,
  name: voice.name,
  sid: voice.sid,
  slot,
  speed: Number.parseFloat(voice.speed),
  speedText: voice.speed,
}));

export const DEFAULT_VOICE_ID = KOKORO_BLENDS.defaultId;

/** The voice for an id. An unknown or empty id is Sam, the default. */
export function kokoroVoice(id) {
  return KOKORO_VOICE_LIST.find((voice) => voice.id === id) ?? kokoroVoiceStrict(DEFAULT_VOICE_ID);
}

export function kokoroVoiceStrict(id) {
  const voice = KOKORO_VOICE_LIST.find((entry) => entry.id === id);
  if (!voice) throw new Error(`Unknown Kokoro voice '${id}'.`);
  return voice;
}

/** Floats in one speaker's style table: frames x dim. */
export function styleSpan(layout = KOKORO_BLENDS.layout) {
  return layout.frames * layout.dim;
}

/** pack-blends.py blend_speaker: float64 accumulation, float32 result. */
export function blendSpeaker(table, layout, sources) {
  const total = sources.reduce((sum, [, weight]) => sum + weight, 0);
  if (!(total > 0)) throw new Error("blend weights must sum to more than zero");
  const span = styleSpan(layout);
  const acc = new Float64Array(span);
  for (const [sid, weight] of sources) {
    const scale = weight / total;
    const start = sid * span;
    for (let index = 0; index < span; index += 1) acc[index] += table[start + index] * scale;
  }
  return Float32Array.from(acc);
}

function sourcesOf(voice, recipe) {
  return voice.weights.map((item) => {
    const sid = recipe.speakers[item.speaker];
    if (sid === undefined) throw new Error(`unknown speaker ${item.speaker} in ${voice.id}`);
    return [sid, Number.parseFloat(item.weight)];
  });
}

function checkTable(table, layout) {
  const expected = layout.speakers * styleSpan(layout);
  if (table.length !== expected) {
    throw new Error(`voices.bin has ${table.length} floats, expected ${expected} for ${layout.speakers}x${layout.frames}x${layout.dim}`);
  }
}

/** pack-blends.py apply_blends: the upstream table with the blend slots overwritten. */
export function applyBlends(table, recipe = KOKORO_BLENDS) {
  const { layout } = recipe;
  checkTable(table, layout);
  const span = styleSpan(layout);
  const out = Float32Array.from(table);
  const used = new Set();
  for (const voice of recipe.voices) {
    if (used.has(voice.sid)) throw new Error(`duplicate blend sid ${voice.sid}`);
    if (voice.sid < 0 || voice.sid >= layout.speakers) throw new Error(`blend sid ${voice.sid} is outside 0..${layout.speakers - 1}`);
    used.add(voice.sid);
    out.set(blendSpeaker(table, layout, sourcesOf(voice, recipe)), voice.sid * span);
  }
  return out;
}

/** The blended slots of a derived table, in recipe order: voices-ember.bin. */
export function emberStyles(derived, recipe = KOKORO_BLENDS) {
  const { layout } = recipe;
  checkTable(derived, layout);
  const span = styleSpan(layout);
  const out = new Float32Array(recipe.voices.length * span);
  recipe.voices.forEach((voice, slot) => out.set(derived.subarray(voice.sid * span, (voice.sid + 1) * span), slot * span));
  return out;
}

/** The style vector sherpa picks for a sentence: row `tokenCount` (ids without the two PADs) of the voice's table. */
export function styleRow(styles, slot, tokenCount, layout = KOKORO_BLENDS.layout) {
  if (tokenCount < 0 || tokenCount >= layout.frames) {
    throw new Error(`a Kokoro sentence has ${tokenCount} tokens; the style table holds ${layout.frames - 1}`);
  }
  const start = slot * styleSpan(layout) + tokenCount * layout.dim;
  return styles.subarray(start, start + layout.dim);
}

/** float32 little-endian bytes to floats, without assuming the host is little-endian. */
export function floatsFromBytes(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const out = new Float32Array(bytes.byteLength / 4);
  for (let index = 0; index < out.length; index += 1) out[index] = view.getFloat32(index * 4, true);
  return out;
}

export function bytesFromFloats(floats) {
  const out = new Uint8Array(floats.length * 4);
  const view = new DataView(out.buffer);
  for (let index = 0; index < floats.length; index += 1) view.setFloat32(index * 4, floats[index], true);
  return out;
}
