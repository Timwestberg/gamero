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
    chatState: extra.chatState,
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

test("an approval dialog does not arm the microphone", () => {
  const { session, calls } = harness({ chatState: "awaiting-you" });
  assert.equal(session.click(), "refused");
  assert.equal(session.notice, "Answer the approval first.");
  assert.deepEqual(calls, []);
});
