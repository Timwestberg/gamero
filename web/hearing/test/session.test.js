import assert from "node:assert/strict";
import test from "node:test";
import { createSession } from "../src/session.js";

const policy = {
  silenceMs: 1000,
  minSpeechMs: 240,
  guardMs: 300,
  bargeSustainMs: 250,
  bargeMinProbability: 0.5,
  bargeGapResetMs: 240,
  echoOverlapPercent: 60,
  shortWordLimit: 2,
};

function harness(extra = {}) {
  const calls = [];
  let clock = 0;
  const sink = {
    arm() { calls.push("arm"); },
    stop() { calls.push("stop"); clock += 4; },
    commit(text) { calls.push(["commit", text]); return extra.commitPhase || "thinking"; },
  };
  const session = createSession({
    policy,
    sink,
    now: () => clock,
    aecAvailable: extra.aecAvailable !== false,
    selfCheck: extra.selfCheck || "pass",
    hostPresent: extra.hostPresent !== false,
    duplexRequest: extra.duplexRequest,
  });
  return { session, calls, setClock(ms) { clock = ms; } };
}

function speech(session, text, frames, prob = 0.9) {
  for (let i = 0; i < frames; i += 1) session.feed({ frameMs: 100, vadProb: prob, rms: 0.3, text });
}

test("click starts, one second of quiet sends, and a later click ends", () => {
  const { session, calls } = harness();
  assert.equal(session.click(), "start");
  assert.equal(session.phase, "listening");
  session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.3, text: "turn" });
  session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.3, text: "turn please" });
  session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.3, text: "turn please now" });
  let sent = null;
  let quiet = 0;
  for (let i = 0; i < 12; i += 1) {
    sent = session.feed({ frameMs: 100, vadProb: 0.05, rms: 0.001, text: "turn please now" });
    quiet += 100;
    if (sent && sent.decision === "send") break;
  }
  assert.equal(sent.decision, "send");
  assert.ok(quiet >= 1000, `sent after ${quiet}ms of quiet`);
  assert.deepEqual(calls.at(-1), ["commit", "turn please now"]);
  assert.equal(session.phase, "thinking");
  session.playbackEnded();
  assert.equal(session.phase, "listening");
  assert.equal(session.click(), "end");
  assert.equal(session.phase, "off");
  assert.equal(session.escape(), false);
});

test("a short burst and a one-word echo are not sent", () => {
  const { session, calls } = harness({ commitPhase: "speaking" });
  session.click();
  session.markSpeaking("hello there friend");
  speech(session, "I", 3, 0.2);
  let dropped = null;
  for (let i = 0; i < 12; i += 1) {
    dropped = session.feed({ frameMs: 100, vadProb: 0.1, rms: 0.001, text: "I" });
    if (dropped && dropped.decision === "drop") break;
  }
  assert.equal(dropped.discard, "too_short");
  assert.equal(calls.some((call) => Array.isArray(call)), false);
});

test("voice at probability 0.5 for 250 ms barges in while she speaks", () => {
  const { session, calls } = harness({ commitPhase: "speaking" });
  session.click();
  session.markSpeaking("the weather is fine today");
  assert.equal(session.phase, "speaking");
  session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.4, text: "stop" });
  session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.4, text: "stop" });
  assert.equal(session.phase, "speaking");
  session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.4, text: "stop that now" });
  assert.equal(session.phase, "listening");
  assert.ok(session.logs.some((line) => line.startsWith("barge-in vad flush_ms=")));
  assert.ok(calls.includes("stop"));
});

test("click and Escape barge in during a reply and end the session only while listening", () => {
  const { session } = harness({ commitPhase: "speaking" });
  session.click();
  session.markSpeaking("hello");
  assert.equal(session.click(), "barge");
  assert.equal(session.phase, "listening");
  session.markSpeaking("hello again");
  assert.equal(session.escape(), true);
  assert.equal(session.phase, "listening");
  assert.equal(session.escape(), true);
  assert.equal(session.phase, "off");
});

test("half duplex drops the mic while Ember speaks and for the guard after", () => {
  const { session, calls } = harness({ selfCheck: "fail", commitPhase: "speaking" });
  assert.equal(session.duplex.mode, "half");
  assert.equal(session.duplex.reason, "self-check-failed");
  session.click();
  session.markSpeaking("hello there");
  const during = session.feed({ frameMs: 100, vadProb: 0.99, rms: 0.4, text: "stop that now" });
  assert.equal(during, null);
  assert.equal(session.phase, "speaking");
  session.playbackEnded();
  const guarded = session.feed({ frameMs: 100, vadProb: 0.99, rms: 0.4, text: "stop that now" });
  assert.equal(guarded, null);
  assert.equal(calls.some((call) => Array.isArray(call)), false);
});

test("a missing echo canceller stays half duplex", () => {
  const { session } = harness({ aecAvailable: false });
  assert.equal(session.duplex.reason, "aec-unavailable");
});

function speechHarness() {
  const speech = {
    calls: [],
    replies: [],
    speak(text, { onStart } = {}) {
      let resolve;
      const promise = new Promise((done) => {
        resolve = done;
      });
      this.calls.push(["speak", text]);
      this.replies.push({ onStart, resolve });
      return promise;
    },
    stop() {
      this.calls.push(["stop"]);
    },
  };
  const changes = [];
  const sink = { arm() {}, stop() {}, commit() { return "thinking"; } };
  const session = createSession({ policy, sink, speech, aecAvailable: true, selfCheck: "pass", onChange: () => changes.push(session.phase) });
  return { session, speech, changes };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test("say() prepares, speaks from the first sample, and listens again when the reply ends", async () => {
  const { session, speech, changes } = speechHarness();
  session.click();
  const done = session.say("hello there");
  assert.equal(session.phase, "thinking");
  assert.deepEqual(speech.calls, [["speak", "hello there"]]);
  speech.replies[0].onStart();
  assert.equal(session.phase, "speaking");
  speech.replies[0].resolve("ended");
  assert.equal(await done, "ended");
  assert.equal(session.phase, "listening");
  assert.deepEqual(changes, ["speaking", "listening"]);
});

test("a click while Ember prepares barges in once, and the stopped reply cannot move the state", async () => {
  const { session, speech } = speechHarness();
  session.click();
  session.say("a long answer");
  assert.equal(session.click(), "barge");
  assert.deepEqual(speech.calls.filter((call) => call[0] === "stop"), [["stop"]]);
  assert.equal(session.phase, "listening");
  speech.replies[0].onStart();
  speech.replies[0].resolve("ended");
  await settle();
  assert.equal(session.phase, "listening");
  session.click();
  assert.equal(session.phase, "off");
});

test("voice for 250 ms while Ember speaks stops the speech slot", () => {
  const { session, speech } = speechHarness();
  session.click();
  session.say("the weather is fine today");
  speech.replies[0].onStart();
  for (let i = 0; i < 3; i += 1) session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.4, text: "stop that now" });
  assert.equal(session.phase, "listening");
  assert.equal(speech.calls.filter((call) => call[0] === "stop").length, 1);
  assert.ok(session.logs.some((line) => line.startsWith("barge-in vad")));
});

test("a sink that speaks on commit keeps the turn in thinking until audio starts", () => {
  const speech = { speak: () => new Promise(() => {}), stop() {} };
  let session;
  const sink = { arm() {}, stop() {}, commit() { session.say("Hi, I'm Sam."); return "thinking"; } };
  session = createSession({ policy, sink, speech, aecAvailable: true, selfCheck: "pass" });
  session.click();
  for (let i = 0; i < 4; i += 1) session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.3, text: "hello there" });
  let sent = null;
  for (let i = 0; i < 12 && !sent; i += 1) {
    const event = session.feed({ frameMs: 100, vadProb: 0, rms: 0.001, text: "hello there" });
    if (event?.decision === "send") sent = event;
  }
  assert.ok(sent);
  assert.equal(session.phase, "thinking");
  assert.equal(session.assistantText, "Hi, I'm Sam.");
});

test("a speech error is a notice, and listening resumes", async () => {
  const speech = { speak: () => Promise.reject(new Error("no WebGPU and no WASM")), stop() {} };
  const session = createSession({ policy, sink: { arm() {}, stop() {}, commit() { return "thinking"; } }, speech, aecAvailable: true, selfCheck: "pass" });
  session.click();
  assert.equal(await session.say("hi"), "cancelled");
  assert.equal(session.phase, "listening");
  assert.match(session.notice, /could not speak: no WebGPU/);
});
