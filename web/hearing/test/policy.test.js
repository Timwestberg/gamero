import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  catalogHearingState,
  reduceHearing,
  screenHeardTurn,
} from "../src/policy.js";
import { MODEL_PINS } from "../src/models.js";
import { FILLERS_DEFAULT, KOKORO_VOICES, SENTENCE_GAP_MS } from "../src/engines.js";

const fixture = JSON.parse(readFileSync(new URL("../fixtures/hearing-transitions.json", import.meta.url), "utf8"));

test("the fixture matches the shared recipe", () => {
  assert.equal(fixture.policy.sentenceGapMs, SENTENCE_GAP_MS);
  assert.equal(fixture.policy.fillersDefault, FILLERS_DEFAULT);
  assert.equal(fixture.policy.silenceMs, 1000);
  assert.equal(fixture.policy.bargeSustainMs, 250);
  assert.equal(fixture.policy.bargeMinProbability, 0.5);
  assert.deepEqual(
    KOKORO_VOICES.map((voice) => [voice.id, voice.speed.toFixed(2)]),
    fixture.voices.map((voice) => [voice.id, voice.speed]),
  );
  assert.equal(fixture.defaultVoiceId, "adam70-michael30");
  for (const [key, value] of Object.entries(fixture.checksums)) {
    assert.equal(MODEL_PINS[key], value, key);
  }
});

test("every hearing transition matches the Kotlin fixture", () => {
  for (const row of fixture.reduceHearing) {
    const step = reduceHearing(row.state, row.action, row.hostPresent, row.permissionGranted);
    assert.equal(step.state, row.next, JSON.stringify(row));
    assert.equal(step.requestMicrophone, row.requestMicrophone, JSON.stringify(row));
  }
});

test("catalog hearing states match the fixture", () => {
  for (const row of fixture.catalogHearing) {
    const state = catalogHearingState(row.hostPresent, row.armed, row.muted, row.phase, row.partial, row.unavailable);
    assert.equal(state, row.state, JSON.stringify(row));
  }
});

test("endpoint discard reasons match the fixture", () => {
  const barge = {
    sustainMs: fixture.policy.bargeSustainMs,
    minProbability: fixture.policy.bargeMinProbability,
    echoOverlapPercent: fixture.policy.echoOverlapPercent,
    shortWordLimit: fixture.policy.shortWordLimit,
    minSpeechMs: fixture.policy.minSpeechMs,
  };
  for (const row of fixture.screenHeard) {
    const discard = screenHeardTurn(row.text, row.vadSpeechMs, row.maxProb, row.emberHot, row.emberText, barge);
    assert.equal(discard, row.discard, JSON.stringify(row));
  }
});
