import assert from "node:assert/strict";
import test from "node:test";
import { createSpeaker } from "../src/speaker.js";

const RATE = 24000;
const GAP = 3840;

function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function fakeEngine() {
  const requests = [];
  return {
    requests,
    cancels: 0,
    loads: 0,
    async load() {
      this.loads += 1;
    },
    synthesize(piece, voiceId) {
      const job = deferred();
      requests.push({ piece, voiceId, job });
      return job.promise;
    },
    cancel() {
      this.cancels += 1;
      for (const request of requests) request.job.resolve(null);
    },
    /** Finishes request `index` with `length` samples of a steady tone. */
    finish(index, length = 2400) {
      requests[index].job.resolve({ samples: new Float32Array(length).fill(0.5), sampleRate: RATE, synthMs: 100 });
    },
  };
}

function fakePlayer() {
  const marks = new Map();
  const drains = [];
  return {
    queued: [],
    flushes: 0,
    async start() {},
    enqueue(samples, { mark }) {
      this.queued.push({ length: samples.length, head: samples[0], mark });
    },
    reached(mark) {
      const waiter = deferred();
      marks.set(mark, waiter);
      return waiter.promise;
    },
    drained() {
      const waiter = deferred();
      drains.push(waiter);
      return waiter.promise;
    },
    flush() {
      this.flushes += 1;
      for (const waiter of marks.values()) waiter.resolve(false);
      for (const waiter of drains) waiter.resolve(false);
    },
    play(mark) {
      marks.get(mark).resolve(true);
    },
    finish() {
      for (const waiter of drains) waiter.resolve(true);
    },
  };
}

const tick = () => new Promise((resolve) => setImmediate(resolve));

test("piece N+1 is synthesized while piece N plays, with 160 ms of silence before it", async () => {
  const engine = fakeEngine();
  const player = fakePlayer();
  let starts = 0;
  const speaker = createSpeaker({ engine, player, voiceId: "adam60-george40" });
  const done = speaker.speak("First one. Second one! Third one?", { onStart: () => { starts += 1; } });
  await tick();
  assert.deepEqual(engine.requests.map((r) => [r.piece, r.voiceId]), [["First one.", "adam60-george40"]]);

  engine.finish(0);
  await tick();
  assert.deepEqual(player.queued.map((q) => q.length), [2400]);
  assert.equal(engine.requests.length, 1, "nothing ahead until piece 0 is heard");
  assert.equal(starts, 0);

  const mark0 = player.queued[0].mark;
  player.play(mark0);
  await tick();
  assert.equal(starts, 1);
  assert.equal(engine.requests.length, 2, "piece 1 starts when piece 0 starts playing");

  engine.finish(1);
  await tick();
  assert.equal(player.queued[1].length, GAP + 2400);
  assert.equal(player.queued[1].head, 0, "the gap is silence");
  assert.equal(engine.requests.length, 2);
  player.play(player.queued[1].mark);
  await tick();
  assert.equal(engine.requests.length, 3);
  engine.finish(2);
  await tick();
  player.play(player.queued[2].mark);
  await tick();
  player.finish();
  assert.equal(await done, "ended");
  assert.equal(starts, 1);
  assert.equal(speaker.lastReport.pieces.length, 3);
  assert.equal(speaker.lastReport.rtf, 1, "100 ms of synthesis per 100 ms of audio");
  assert.ok(speaker.lastReport.ttfaMs >= 0);
});

test("stop() flushes playback, cancels synthesis, and the reply resolves cancelled", async () => {
  const engine = fakeEngine();
  const player = fakePlayer();
  let starts = 0;
  const speaker = createSpeaker({ engine, player });
  const done = speaker.speak("One. Two.", { onStart: () => { starts += 1; } });
  await tick();
  engine.finish(0);
  await tick();
  speaker.stop();
  assert.equal(await done, "cancelled");
  assert.equal(player.flushes, 1);
  assert.equal(engine.cancels, 1);
  assert.equal(starts, 0);
  assert.equal(engine.requests.length, 1, "the next piece is never asked for");
});

test("stop() while a piece is still being synthesized drops it", async () => {
  const engine = fakeEngine();
  const player = fakePlayer();
  const speaker = createSpeaker({ engine, player });
  const done = speaker.speak("Only one.");
  await tick();
  speaker.stop();
  assert.equal(await done, "cancelled");
  assert.deepEqual(player.queued, []);
});

test("a new reply replaces the one still speaking", async () => {
  const engine = fakeEngine();
  const player = fakePlayer();
  const speaker = createSpeaker({ engine, player });
  const first = speaker.speak("Old reply.");
  await tick();
  const second = speaker.speak("New reply.");
  assert.equal(await first, "cancelled");
  assert.equal(player.flushes, 1);
  await tick();
  assert.equal(engine.requests.at(-1).piece, "New reply.");
  engine.finish(engine.requests.length - 1);
  await tick();
  player.play(player.queued[0].mark);
  await tick();
  player.finish();
  assert.equal(await second, "ended");
});

test("a reply with nothing to say ends without loading the voice", async () => {
  const engine = fakeEngine();
  const speaker = createSpeaker({ engine, player: fakePlayer() });
  assert.equal(await speaker.speak("   "), "ended");
  assert.equal(engine.loads, 0);
});

test("a piece that trims to nothing is skipped and adds no gap", async () => {
  const engine = fakeEngine();
  const player = fakePlayer();
  const speaker = createSpeaker({ engine, player });
  const done = speaker.speak("Quiet. Loud.");
  await tick();
  engine.requests[0].job.resolve({ samples: new Float32Array(0), sampleRate: RATE, synthMs: 5 });
  await tick();
  assert.equal(engine.requests.length, 2);
  engine.finish(1);
  await tick();
  assert.deepEqual(player.queued.map((q) => q.length), [2400]);
  player.play(player.queued[0].mark);
  await tick();
  player.finish();
  assert.equal(await done, "ended");
});
