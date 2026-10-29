import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";
import { KOKORO_VOICES } from "../src/engines.js";
import {
  DEFAULT_VOICE_ID,
  KOKORO_BLENDS,
  KOKORO_VOICE_LIST,
  applyBlends,
  blendSpeaker,
  bytesFromFloats,
  emberStyles,
  floatsFromBytes,
  kokoroVoice,
  kokoroVoiceStrict,
  styleRow,
} from "../src/kokoro-voices.js";
import { KOKORO_WEB, MODEL_PINS } from "../src/models.js";

const repo = new URL("../../../", import.meta.url);
const assets = new URL("../../voice-assets/", import.meta.url);

test("the web recipe is desktop's voice/kokoro/blends.json", () => {
  const desktop = JSON.parse(readFileSync(new URL("voice/kokoro/blends.json", repo), "utf8"));
  assert.deepEqual(KOKORO_BLENDS, desktop);
  assert.equal(KOKORO_BLENDS.modelOnnxSha256, MODEL_PINS.modelOnnxSha256);
  assert.equal(KOKORO_BLENDS.derivedVoicesSha256, MODEL_PINS.derivedVoicesSha256);
  assert.equal(KOKORO_WEB.files.tokens.sha256, MODEL_PINS.tokensSha256);
  assert.equal(KOKORO_WEB.files.lexicon.sha256, MODEL_PINS.lexiconSha256);
});

test("Sam is the default and each blend keeps its desktop sid and speed", () => {
  assert.equal(DEFAULT_VOICE_ID, "adam70-michael30");
  assert.deepEqual(
    KOKORO_VOICE_LIST.map(({ id, name, sid, slot, speed }) => [id, name, sid, slot, speed]),
    [
      ["adam70-michael30", "Sam", 50, 0, 1],
      ["adam60-george40", "Jamie", 51, 1, 0.95],
      ["adam55-bella45", "Riley", 52, 2, 0.95],
      ["heart60-adam40", "Harper", 53, 3, 0.97],
    ],
  );
  assert.equal(kokoroVoice("").name, "Sam");
  assert.equal(kokoroVoice("not-a-voice").name, "Sam");
  assert.equal(kokoroVoice("heart60-adam40").name, "Harper");
  assert.throws(() => kokoroVoiceStrict("af_bella"), /Unknown Kokoro voice/);
  assert.deepEqual(KOKORO_VOICES.map((voice) => voice.name), ["Sam", "Jamie", "Riley", "Harper"]);
});

test("blending follows pack-blends.py: weighted, normalized, float32, other slots untouched", () => {
  // pack-blends.py --self-check, in JavaScript.
  const layout = { speakers: 4, frames: 2, dim: 2 };
  const table = new Float32Array(16).map((_, index) => Math.floor(index / 4) * 10 + (index % 4));
  const recipe = {
    layout,
    speakers: { s0: 0, s1: 1 },
    voices: [{ id: "blend", sid: 3, weights: [{ speaker: "s0", weight: "0.70" }, { speaker: "s1", weight: "0.30" }] }],
  };
  const out = applyBlends(table, recipe);
  for (let index = 0; index < 4; index += 1) {
    assert.ok(Math.abs(out[12 + index] - (0.7 * index + 0.3 * (10 + index))) < 1e-5);
  }
  assert.deepEqual([...out.subarray(0, 12)], [...table.subarray(0, 12)]);
  assert.deepEqual(applyBlends(table, recipe), out);
  assert.throws(() => blendSpeaker(table, layout, [[0, 0]]), /more than zero/);
  assert.throws(() => applyBlends(new Float32Array(3), recipe), /expected 16/);
});

test("a sentence uses the style row at its token count, inside the 510-row table", () => {
  const styles = new Float32Array(2 * 510 * 256).map((_, index) => index);
  assert.equal(styleRow(styles, 1, 12)[0], 510 * 256 + 12 * 256);
  assert.equal(styleRow(styles, 0, 0).length, 256);
  assert.throws(() => styleRow(styles, 0, 510), /holds 509/);
  assert.deepEqual([...floatsFromBytes(bytesFromFloats(Float32Array.of(1.5, -2)))], [1.5, -2]);
});

test("voices-ember.bin is the four blended slots of desktop's derived table", {
  skip: existsSync(new URL(".upstream/voices.bin", assets)) ? false : "run web/hearing/scripts/fetch-web-voice.mjs",
}, () => {
  const upstream = floatsFromBytes(new Uint8Array(readFileSync(new URL(".upstream/voices.bin", assets))));
  const derived = applyBlends(upstream);
  assert.equal(createHash("sha256").update(bytesFromFloats(derived)).digest("hex"), KOKORO_BLENDS.derivedVoicesSha256);
  const ember = bytesFromFloats(emberStyles(derived));
  assert.equal(ember.byteLength, KOKORO_WEB.files.styles.bytes);
  assert.equal(createHash("sha256").update(ember).digest("hex"), KOKORO_WEB.files.styles.sha256);
});
