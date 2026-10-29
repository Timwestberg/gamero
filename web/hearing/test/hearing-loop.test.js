import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";
import * as ort from "onnxruntime-web";
import { HearingEngine, PreRoll, chunkRms } from "../src/hearing-engine.js";
import { createListener } from "../src/listener.js";
import { HEARING_WEB } from "../src/models.js";
import { createSession } from "../src/session.js";
import { SileroVad } from "../src/silero.js";
import { decodeWav16 } from "../src/wav.js";
import { StreamingZipformer } from "../src/zipformer.js";

ort.env.wasm.numThreads = 1;
const assets = new URL("../../voice-assets/", import.meta.url);
const haveModels = Object.values(HEARING_WEB.files).every((file) => existsSync(new URL(file.name, assets)));
const oracle = JSON.parse(readFileSync(new URL("../fixtures/hearing-oracle.json", import.meta.url), "utf8"));
const POLICY = { silenceMs: 1000, minSpeechMs: 240, guardMs: 300, bargeSustainMs: 250, bargeMinProbability: 0.5, bargeGapResetMs: 240, echoOverlapPercent: 60, shortWordLimit: 2 };

/** onnxruntime-shaped fake: records each run's input and threads a counter through the state. */
function recordingSilero(probs = []) {
  const runs = [];
  const session = {
    async run(feeds) {
      runs.push({ input: Array.from(feeds.input.data), state: feeds.state.data[0] });
      const next = new Float32Array(256).fill(feeds.state.data[0] + 1);
      return { output: { data: [probs[runs.length - 1] ?? 0] }, stateN: new ort.Tensor("float32", next, [2, 1, 128]) };
    },
  };
  return { runs, session };
}

const window = (value) => new Float32Array(512).fill(value);

test("Silero runs the way desktop drives sherpa: compute(512), then acceptWaveform's 576-sample runs, on one state", async () => {
  const { runs, session } = recordingSilero([0.9, 0, 0.2, 0, 0.7]);
  const vad = new SileroVad(ort, session);
  assert.equal(await vad.compute(window(1)), 0.9);
  await vad.acceptWaveform(window(1));
  assert.equal(await vad.compute(window(2)), 0);
  await vad.acceptWaveform(window(2));
  await vad.compute(window(3));
  await vad.acceptWaveform(window(3));
  assert.deepEqual(runs.map((run) => run.input.length), [512, 512, 576, 512, 576]);
  assert.deepEqual(runs.map((run) => run.state), [0, 1, 2, 3, 4], "every run advances the same state");
  assert.deepEqual(runs[2].input.slice(510, 514), [1, 1, 2, 2], "the overlap run is the previous window plus 64 samples of this one");
  assert.deepEqual(runs[4].input.slice(0, 1).concat(runs[4].input.slice(-1)), [2, 3]);
  vad.reset();
  await vad.acceptWaveform(window(4));
  assert.equal(runs.length, 5, "after a reset the first window only buffers");
});

test("the engine reports voiced milliseconds per chunk at 0.6, the last window's probability, and desktop's speech length", async () => {
  const probs = [0.65, 0.59, 0.95, 0.1];
  let at = 0;
  const vad = { reset() {}, async compute() { return probs[at++] ?? 0; }, async acceptWaveform() {} };
  const asr = { createStream: () => ({ text: "" }), async acceptWaveform() {}, text: (stream) => stream.text, async finish() { return " HELLO THERE " } };
  const engine = new HearingEngine({ asr, vad });
  await engine.accept(new Float32Array(1600));
  assert.equal(engine.voicedMs, 64, "1600 samples hold three full windows; 0.65 and 0.95 clear 0.6, 0.59 does not");
  assert.equal(engine.lastProb, 0.95);
  await engine.accept(new Float32Array(1600));
  assert.equal(engine.voicedMs, 0, "the fourth window completes here, at 0.1");
  const result = await engine.finish();
  assert.equal(result.text, "HELLO THERE");
  assert.equal(result.speechMs, 200, "voiced, so the speech length is everything heard");
});

test("an unvoiced turn with text still counts as the minimum speech, and an empty one as none", async () => {
  const vad = { reset() {}, async compute() { return 0.1; }, async acceptWaveform() {} };
  let text = "OK";
  const asr = { createStream: () => ({}), async acceptWaveform() {}, text: () => text, async finish() { return text; } };
  const engine = new HearingEngine({ asr, vad, now: () => 0 });
  await engine.accept(new Float32Array(1600));
  assert.deepEqual(await engine.finish(), { text: "OK", speechMs: 240, decodeMs: 0 });
  text = "  ";
  assert.deepEqual(await engine.finish(), { text: "", speechMs: 0, decodeMs: 0 });
  const pre = new PreRoll(4);
  pre.add(Float32Array.of(1, 2, 3));
  pre.add(Float32Array.of(4, 5));
  assert.deepEqual([...pre.copy()], [2, 3, 4, 5]);
  assert.equal(chunkRms(Float32Array.of(3, 4)), Math.fround(Math.sqrt(12.5)));
});

/** In-process stand-in for hearing-worker.js around a fake engine that hears a script. */
function scriptedHearing(script) {
  const calls = [];
  let index = 0;
  return {
    calls,
    async chunk(samples, restart) {
      calls.push(restart ?? "-");
      const frame = script[Math.min(index, script.length - 1)];
      index += 1;
      return { chunkMs: 100, rms: 0.3, voicedMs: 0, prob: 0, text: "", ...frame };
    },
    async finish() {
      calls.push("finish");
      return { text: script.finalText ?? "", speechMs: 400, decodeMs: 1 };
    },
  };
}

function sessionFor({ sink, speech, duplexRequest } = {}) {
  let listener = null;
  const session = createSession({
    policy: POLICY,
    sink: sink ?? { arm() {}, stop() {}, commit() { return "thinking"; } },
    speech,
    aecAvailable: true,
    selfCheck: "pass",
    duplexRequest,
    onBargeIn: () => listener?.restartWithPreRoll(),
  });
  return { session, attach: (value) => { listener = value; } };
}

test("a turn restarts the utterance first, sends the finished text after a second of quiet, then restarts again", async () => {
  const sent = [];
  const { session, attach } = sessionFor({ sink: { arm() {}, stop() {}, commit(text) { sent.push(text); return "thinking"; } } });
  const speech = Array.from({ length: 4 }, () => ({ voicedMs: 96, prob: 0.9, text: "HELLO THER" }));
  const quiet = Array.from({ length: 12 }, () => ({ voicedMs: 0, prob: 0.01, rms: 0.001, text: "HELLO THER" }));
  const script = [...speech, ...quiet];
  script.finalText = "HELLO THERE";
  const hearing = scriptedHearing(script);
  const listener = createListener({ session, hearing });
  attach(listener);
  session.click();
  await listener.start();
  for (let i = 0; i < 16; i += 1) listener.push(new Float32Array(1600));
  await listener.idle();
  assert.deepEqual(sent, ["HELLO THERE"], "the finished text is sent, not the last partial");
  assert.equal(hearing.calls[0], "drop", "the first chunk starts an utterance");
  assert.ok(hearing.calls.includes("finish"));
  assert.equal(session.phase, "thinking");
  await listener.stop();
});

test("barge-in asks for the 300 ms pre-roll, and Ember going quiet drops it one chunk later, as on desktop", async () => {
  let starts = null;
  const speech = { speak: (text, { onStart }) => { starts = onStart; return new Promise(() => {}); }, stop() {} };
  const { session, attach } = sessionFor({ speech });
  const hearing = scriptedHearing([{ voicedMs: 96, prob: 0.95, text: "WAIT" }]);
  const listener = createListener({ session, hearing });
  attach(listener);
  session.click();
  await listener.start();
  session.say("Here is a long answer.");
  starts();
  for (let i = 0; i < 5; i += 1) listener.push(new Float32Array(1600));
  await listener.idle();
  assert.equal(session.phase, "listening");
  assert.ok(session.logs.some((line) => line.startsWith("barge-in vad")));
  // chunk 1: the session's first utterance; Ember started after the listener did, so the playback sync drops it
  // chunk 2: fresh utterance; chunk 3: 250 ms of voice barges in and asks for the pre-roll
  // chunk 4: pre-roll utterance; she is now quiet, so the sync drops it again (desktop's noteEmberSilent)
  // chunk 5: fresh utterance
  assert.deepEqual(hearing.calls.slice(0, 5), ["drop", "drop", "-", "preroll", "drop"]);
  await listener.stop();
});

test("half duplex drops chunks while Ember speaks and through the guard, then restarts the utterance", async () => {
  let starts = null;
  let ended = null;
  const speech = { speak: (text, { onStart }) => { starts = onStart; return new Promise((resolve) => { ended = resolve; }); }, stop() {} };
  const { session } = sessionFor({ speech, duplexRequest: "half" });
  const hearing = scriptedHearing([{ prob: 0.01 }]);
  const listener = createListener({ session, hearing });
  session.click();
  await listener.start();
  session.say("Hello.");
  starts();
  for (let i = 0; i < 3; i += 1) listener.push(new Float32Array(1600));
  await listener.idle();
  assert.deepEqual(hearing.calls, [], "nothing reaches the recognizer while she speaks");
  ended("ended");
  await new Promise((resolve) => setImmediate(resolve));
  for (let i = 0; i < 5; i += 1) listener.push(new Float32Array(1600));
  await listener.idle();
  assert.deepEqual(hearing.calls, ["drop", "-"], "300 ms of guard, then a fresh utterance");
  await listener.stop();
});

test("a blank finished turn is dropped instead of sent", async () => {
  const sent = [];
  const { session } = sessionFor({ sink: { arm() {}, stop() {}, commit(text) { sent.push(text); return "thinking"; } } });
  const script = [
    ...Array.from({ length: 4 }, () => ({ voicedMs: 96, prob: 0.9, text: "UH" })),
    ...Array.from({ length: 12 }, () => ({ prob: 0.01, rms: 0.001, text: "UH" })),
  ];
  script.finalText = "";
  const listener = createListener({ session, hearing: scriptedHearing(script) });
  session.click();
  await listener.start();
  for (let i = 0; i < 16; i += 1) listener.push(new Float32Array(1600));
  await listener.idle();
  assert.deepEqual(sent, []);
  assert.ok(session.logs.includes("drop blank"));
  assert.equal(session.phase, "listening");
});

test("with the real models, a spoken clip becomes one turn with the text sherpa finishes with", { skip: haveModels ? false : "run web/hearing/scripts/fetch-web-voice.mjs", timeout: 300_000 }, async () => {
  const read = (key) => readFileSync(new URL(HEARING_WEB.files[key].name, assets));
  const asr = await StreamingZipformer.create(ort, { encoder: read("encoder"), decoder: read("decoder"), joiner: read("joiner") }, read("tokens").toString("utf8"));
  const vad = await SileroVad.create(ort, read("vad"));
  const engine = new HearingEngine({ asr, vad });
  const preRoll = new PreRoll();
  const hearing = {
    async chunk(samples, restart) {
      preRoll.add(samples);
      if (restart) {
        engine.startUtterance();
        await engine.accept(restart === "preroll" ? preRoll.copy() : samples);
      } else {
        await engine.accept(samples);
      }
      return { voicedMs: engine.voicedMs, chunkMs: 100, rms: chunkRms(samples), prob: engine.lastProb, text: engine.partial() };
    },
    finish: () => engine.finish(),
  };
  for (const expected of oracle.clips) {
    const sent = [];
    const { session } = sessionFor({ sink: { arm() {}, stop() {}, commit(text) { sent.push(text); return "thinking"; } } });
    const listener = createListener({ session, hearing });
    session.click();
    await listener.start();
    const audio = decodeWav16(readFileSync(new URL(`../fixtures/hearing/${expected.name}.wav`, import.meta.url))).samples;
    for (let at = 0; at + 1600 <= audio.length; at += 1600) listener.push(audio.slice(at, at + 1600));
    await listener.idle();
    assert.deepEqual(sent, [expected.final], expected.name);
    await listener.stop();
  }
});
