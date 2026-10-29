import assert from "node:assert/strict";
import test from "node:test";
import { createComposer, LOCAL_KOKORO_LINE, NO_HOST_NOTICE, PERMISSION_COPY } from "../src/composer.js";

function stubSpeaker() {
  return {
    spoken: [],
    prepared: 0,
    stops: 0,
    starts: [],
    speak(text, { onStart } = {}) {
      this.spoken.push(text);
      this.starts.push(onStart);
      return new Promise(() => {});
    },
    stop() {
      this.stops += 1;
    },
    prepare() {
      this.prepared += 1;
    },
  };
}

function speakTurn(composer) {
  for (let i = 0; i < 4; i += 1) composer.session.feed({ frameMs: 100, vadProb: 0.9, rms: 0.3, text: "hello there" });
  for (let i = 0; i < 12; i += 1) {
    const event = composer.session.feed({ frameMs: 100, vadProb: 0, rms: 0.001, text: "hello there" });
    if (event && event.decision === "send") return event;
  }
  return null;
}

test("without a host the turn speaks the Kokoro line and chat stays unavailable", () => {
  const speaker = stubSpeaker();
  const composer = createComposer({ host: null, speaker, aecAvailable: true, selfCheck: "pass" });
  assert.equal(composer.chatNotice, NO_HOST_NOTICE);
  assert.equal(composer.permission, PERMISSION_COPY);
  composer.session.click();
  assert.equal(speaker.prepared, 1, "the talk click opens audio output and starts the voice download");
  assert.ok(speakTurn(composer));
  assert.deepEqual(speaker.spoken, [LOCAL_KOKORO_LINE]);
  assert.equal(composer.session.phase, "thinking");
  speaker.starts[0]();
  assert.equal(composer.session.phase, "speaking");
  composer.session.escape();
  assert.equal(speaker.stops, 1);
  assert.equal(composer.session.phase, "listening");
});

test("with a host the turn is chat.hearing then chat.send, and a host reply is spoken", () => {
  const speaker = stubSpeaker();
  const host = { chatState: "ready", calls: [], hearing(action) { this.calls.push(["hearing", action]); }, send(text) { this.calls.push(["send", text]); }, stop() { this.calls.push(["stop"]); } };
  const composer = createComposer({ host, speaker, aecAvailable: true, selfCheck: "pass" });
  assert.equal(composer.chatNotice, null);
  composer.session.click();
  assert.deepEqual(host.calls[0], ["hearing", "press"]);
  assert.ok(speakTurn(composer));
  assert.deepEqual(host.calls.at(-1), ["send", "hello there"]);
  assert.equal(composer.session.phase, "thinking");
  assert.deepEqual(speaker.spoken, []);
  composer.reply("The answer is four.");
  assert.deepEqual(speaker.spoken, ["The answer is four."]);
});
