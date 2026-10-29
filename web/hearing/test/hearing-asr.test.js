import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";
import * as ort from "onnxruntime-web";
import { FBANK, OnlineFbank, firstSampleOfFrame, melBanks, numFrames, poveyWindow } from "../src/fbank.js";
import { HEARING_WEB, MODEL_PINS } from "../src/models.js";
import { decodeWav16 } from "../src/wav.js";
import { StreamingZipformer, encoderStateShapes, parseAsrTokens, tokensToText } from "../src/zipformer.js";

ort.env.wasm.numThreads = 1;
const assets = new URL("../../voice-assets/", import.meta.url);
const haveModels = Object.values(HEARING_WEB.files).every((file) => existsSync(new URL(file.name, assets)));
const skip = haveModels ? false : "run web/hearing/scripts/fetch-web-voice.mjs";
const oracle = JSON.parse(readFileSync(new URL("../fixtures/hearing-oracle.json", import.meta.url), "utf8"));
const clip = (name) => decodeWav16(readFileSync(new URL(`../fixtures/hearing/${name}.wav`, import.meta.url))).samples;

test("kaldi framing: 25 ms windows every 10 ms, centred, with complete windows only until the input ends", () => {
  assert.equal(firstSampleOfFrame(0), -120);
  assert.equal(firstSampleOfFrame(3), 360);
  assert.equal(numFrames(1600, true), 10);
  assert.equal(numFrames(1600, false), 9, "frame 9 would end at 1720");
  assert.equal(numFrames(279, false), 0);
  assert.equal(numFrames(280, false), 1);
  assert.equal(numFrames(0, true), 0);
  const window = poveyWindow();
  assert.equal(window.length, 400);
  assert.equal(window[0], 0);
  assert.ok(Math.abs(window[200] - 1) < 1e-3);
  const banks = melBanks();
  assert.equal(banks.length, FBANK.bins);
  assert.ok(banks.every((bank) => bank.offset >= 1 && bank.offset + bank.weights.length <= 256));
});

test("frames do not depend on how the audio is chunked, and finishing adds the reflected edge frames", () => {
  const audio = clip("hello-there").subarray(8000, 24000);
  const whole = new OnlineFbank();
  whole.acceptWaveform(audio);
  const pieces = new OnlineFbank();
  for (let at = 0; at < audio.length; at += 173) pieces.acceptWaveform(audio.subarray(at, at + 173));
  assert.equal(whole.numFramesReady, pieces.numFramesReady);
  assert.deepEqual(whole.getFrames(0, whole.numFramesReady), pieces.getFrames(0, pieces.numFramesReady));
  const before = whole.numFramesReady;
  whole.inputFinished();
  assert.equal(whole.numFramesReady, numFrames(audio.length, true));
  assert.ok(whole.numFramesReady > before);
  whole.discardBefore(10);
  assert.deepEqual(whole.getFrames(10, 1), pieces.getFrames(10, 1));
  assert.throws(() => whole.acceptWaveform(audio), /after inputFinished/);
});

test("tokens: the first id wins, <unk> is skipped, and a leading ▁ is a space", () => {
  const table = parseAsrTokens("<blk> 0\n<sos/eos> 1\n<unk> 2\n▁HELLO 3\nS 4\n▁ 5\n");
  assert.equal(table.unk, 2);
  assert.equal(tokensToText([3, 4, 2, 5, 3], table), " HELLOS  HELLO");
});

test("the hard-coded encoder layout is the model's: 98 cached states with these shapes", { skip }, async () => {
  const encoder = await ort.InferenceSession.create(readFileSync(new URL(HEARING_WEB.files.encoder.name, assets)));
  const shapes = encoderStateShapes();
  assert.equal(encoder.inputNames.length, shapes.length + 1);
  encoder.inputMetadata.slice(1).forEach((input, i) => {
    assert.deepEqual(input.shape.map((dim) => (dim === "N" ? 1 : dim)), shapes[i][0], input.name);
    assert.equal(input.type, shapes[i][1], input.name);
  });
  assert.deepEqual(encoder.inputMetadata[0].shape, ["N", 45, 80]);
  await encoder.release();
});

test("the streaming Zipformer hears what sherpa-onnx 2.0.0 hears, chunk by chunk", { skip, timeout: 300_000 }, async () => {
  const read = (key) => readFileSync(new URL(HEARING_WEB.files[key].name, assets));
  assert.equal(HEARING_WEB.files.tokens.sha256, MODEL_PINS.zipformerTokensSha256);
  for (const [file, sha] of Object.entries(oracle.models)) {
    const pinned = Object.values(HEARING_WEB.files).find((entry) => entry.name.endsWith(file));
    assert.equal(pinned.sha256, sha, `the oracle used the pinned ${file}`);
  }
  const asr = await StreamingZipformer.create(ort, { encoder: read("encoder"), decoder: read("decoder"), joiner: read("joiner") }, read("tokens").toString("utf8"));
  for (const expected of oracle.clips) {
    const audio = clip(expected.name);
    const stream = asr.createStream();
    const partials = [];
    for (let at = 0; at < audio.length; at += oracle.chunkSamples) {
      await asr.acceptWaveform(stream, audio.subarray(at, at + oracle.chunkSamples));
      partials.push(asr.text(stream));
    }
    // The npm build reports text through JSON, which drops the leading space desktop's JNI result keeps.
    assert.deepEqual(partials.map((text) => text.trimStart()), expected.partials, expected.name);
    assert.equal((await asr.finish(stream)).trimStart(), expected.final, expected.name);
  }
});
