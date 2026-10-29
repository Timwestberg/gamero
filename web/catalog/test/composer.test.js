import assert from "node:assert/strict";
import test from "node:test";
import { createComposer, LOCAL_KOKORO_LINE, NO_HOST_NOTICE, PERMISSION_COPY } from "../src/composer.js";

test("without a host the turn plays a Kokoro line and chat stays unavailable", () => {
  const player = { last: "", stop() {}, speak(line) { this.last = line; } };
  const composer = createComposer({ host: null, player, aecAvailable: true, selfCheck: "pass" });
  assert.equal(composer.chatNotice, NO_HOST_NOTICE);
  assert.equal(composer.permission, PERMISSION_COPY);
  composer.session.click();
  for (let i = 0; i < 4; i += 1) composer.session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.3, text: "hello there" });
  for (let i = 0; i < 12; i += 1) {
    const event = composer.session.feed({ frameMs: 100, vadProb: 0, rms: 0.001, text: "hello there" });
    if (event && event.decision === "send") break;
  }
  assert.equal(player.last, LOCAL_KOKORO_LINE);
  assert.equal(composer.session.phase, "speaking");
});

test("with a host the turn is chat.hearing then chat.send", () => {
  const player = { stop() {}, speak() {} };
  const host = { chatState: "ready", calls: [], hearing(action) { this.calls.push(["hearing", action]); }, send(text) { this.calls.push(["send", text]); }, stop() { this.calls.push(["stop"]); } };
  const composer = createComposer({ host, player, aecAvailable: true, selfCheck: "pass" });
  assert.equal(composer.chatNotice, null);
  composer.session.click();
  assert.deepEqual(host.calls[0], ["hearing", "press"]);
  for (let i = 0; i < 4; i += 1) composer.session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.3, text: "hello there" });
  for (let i = 0; i < 12; i += 1) {
    const event = composer.session.feed({ frameMs: 100, vadProb: 0, rms: 0.001, text: "hello there" });
    if (event && event.decision === "send") break;
  }
  assert.deepEqual(host.calls.at(-1), ["send", "hello there"]);
  assert.equal(composer.session.phase, "thinking");
});
