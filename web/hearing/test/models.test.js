import assert from "node:assert/strict";
import test from "node:test";
import { WEB_MIC_CONSTRAINTS, aecFromSettings, leaksPhrase, selfCheckFromLoopback } from "../src/media.js";
import { assertNotHovel, fetchChecked, refuseNonCommercial, sha256Hex } from "../src/models.js";
import { accessibleName } from "../src/control.js";

test("the microphone asks for browser echo cancellation and noise suppression", () => {
  assert.equal(WEB_MIC_CONSTRAINTS.audio.echoCancellation, true);
  assert.equal(WEB_MIC_CONSTRAINTS.audio.noiseSuppression, true);
  assert.equal(WEB_MIC_CONSTRAINTS.audio.channelCount, 1);
  assert.equal(WEB_MIC_CONSTRAINTS.audio.sampleRate, 16000);
  assert.equal(aecFromSettings({ echoCancellation: true }), true);
  assert.equal(aecFromSettings({ echoCancellation: false }), false);
});

test("the self-check fails when the phrase leaks or the VAD stays hot", () => {
  assert.equal(selfCheckFromLoopback({ vadHotMs: 0, transcript: "", phrase: "Ember checking the microphone.", sustainMs: 250 }), "pass");
  assert.equal(selfCheckFromLoopback({ vadHotMs: 250, transcript: "", phrase: "Ember checking the microphone.", sustainMs: 250 }), "fail");
  assert.equal(leaksPhrase("ember checking", "Ember checking the microphone."), true);
});

test("model bytes are hash-checked and are not posted to Hovel", async () => {
  const bytes = new TextEncoder().encode("kokoro");
  const sha = await sha256Hex(bytes);
  let posted = false;
  const body = await fetchChecked("https://example.test/silero.onnx", sha, async (url, init) => {
    assert.equal(init.method, "GET");
    posted = url.includes("hovel");
    return { ok: true, arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) };
  });
  assert.equal(posted, false);
  assert.equal(body.byteLength, bytes.byteLength);
  await assert.rejects(() => fetchChecked("https://example.test/silero.onnx", "00".repeat(32), async () => ({
    ok: true,
    arrayBuffer: async () => bytes.buffer,
  })));
  assert.throws(() => assertNotHovel("https://hovel-service.example/models/kokoro.onnx"));
  assert.throws(() => refuseNonCommercial("kroko-streaming.onnx"));
  assert.throws(() => refuseNonCommercial("voice CC-BY-NC"));
});

test("the control names the hearing states and does not open a microphone", () => {
  assert.equal(accessibleName("listening"), "Listening");
  assert.equal(accessibleName("speaking"), "Ember is speaking");
  assert.equal(accessibleName("nope"), "Voice input unavailable");
});
