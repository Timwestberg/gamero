import assert from "node:assert/strict";
import test from "node:test";
import { concatSamples, msToSamples, scaleSilence } from "../src/kokoro-audio.js";

const RATE = 24000;
const PAUSE = Math.trunc(RATE * 0.2);

function tone(length, value = 0.5) {
  return new Float32Array(length).fill(value);
}

test("sherpa ScaleSilence keeps 20% of a pause longer than 0.2 s", () => {
  const audio = concatSamples([tone(100), new Float32Array(10_000), tone(100)]);
  const out = scaleSilence(audio, RATE);
  assert.equal(out.length, 100 + Math.trunc(Math.fround(10_000 * Math.fround(0.2))) + 100);
  assert.equal(out[0], 0.5);
  assert.equal(out[out.length - 1], 0.5);
});

test("a short pause, a quiet tail under 0.2 s, and scale 1 are left alone", () => {
  const short = concatSamples([tone(100), new Float32Array(PAUSE - 1), tone(100)]);
  assert.equal(scaleSilence(short, RATE), short);
  const tail = concatSamples([tone(100), new Float32Array(PAUSE)]);
  assert.equal(scaleSilence(tail, RATE).length, tail.length, "a tail of exactly 0.2 s is not a pause");
  const longTail = concatSamples([tone(100), new Float32Array(PAUSE + 1000)]);
  assert.equal(scaleSilence(longTail, RATE).length, 100 + Math.trunc(Math.fround((PAUSE + 1000) * Math.fround(0.2))));
  assert.equal(scaleSilence(longTail, RATE, 1), longTail);
});

test("|x| <= 0.01 counts as silence and the range check skips bad scales", () => {
  const audio = concatSamples([tone(10), new Float32Array(6000).fill(0.01), tone(10, -0.5)]);
  assert.equal(scaleSilence(audio, RATE).length, 10 + 1200 + 10);
  assert.equal(scaleSilence(audio, RATE, Number.NaN), audio);
  assert.equal(scaleSilence(audio, RATE, 0.001), audio);
});

test("a scale above 1 pads the pause with zeros instead of reading into speech", () => {
  const audio = concatSamples([tone(10), new Float32Array(5000), tone(10)]);
  const out = scaleSilence(audio, RATE, 2);
  assert.equal(out.length, 10 + 10_000 + 10);
  assert.equal(out[10 + 9999], 0);
  assert.equal(out[out.length - 1], 0.5);
});

test("desktop rounds sample counts down", () => {
  assert.equal(msToSamples(24000, 160), 3840);
  assert.equal(msToSamples(24000, 10), 240);
  assert.equal(msToSamples(22050, 160), 3528);
});
