import assert from "node:assert/strict";
import test from "node:test";
import { createComposer, LOCAL_KOKORO_LINE, PERMISSION_COPY } from "../src/composer.js";

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

test("a finished turn speaks the Kokoro line", () => {
  const speaker = stubSpeaker();
  const composer = createComposer({ speaker, aecAvailable: true, selfCheck: "pass" });
  assert.equal(composer.permission, PERMISSION_COPY);
  composer.session.click();
  assert.equal(speaker.prepared, 1, "the talk click opens audio output and starts the voice download");
  assert.equal(composer.heard, null);
  assert.ok(speakTurn(composer));
  assert.equal(composer.heard, "hello there");
  assert.deepEqual(speaker.spoken, [LOCAL_KOKORO_LINE]);
  assert.equal(composer.session.phase, "thinking");
  speaker.starts[0]();
  assert.equal(composer.session.phase, "speaking");
  composer.session.escape();
  assert.equal(speaker.stops, 1);
  assert.equal(composer.session.phase, "listening");
});

test("a reply from the page is spoken through the slot", () => {
  const speaker = stubSpeaker();
  const composer = createComposer({ speaker, aecAvailable: true, selfCheck: "pass" });
  composer.reply("The answer is four.");
  assert.deepEqual(speaker.spoken, ["The answer is four."]);
});
