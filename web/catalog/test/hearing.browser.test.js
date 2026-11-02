import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { fakeMicrophoneScript, launch, serve, voiceAssets, webRoot } from "./support.js";

const ready = existsSync(path.join(voiceAssets, "hearing/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"));
const oracle = JSON.parse(readFileSync(path.join(webRoot, "hearing/fixtures/hearing-oracle.json"), "utf8"));

test("Chrome hears a spoken turn from the microphone and finishes it with the recognized text", { skip: ready ? false : "run node web/hearing/scripts/fetch-web-voice.mjs first", timeout: 300_000 }, async () => {
  const server = await serve();
  const browser = await launch();
  const page = await browser.newPage();
  const posts = [];
  page.on("request", (request) => {
    if (request.method() !== "GET") posts.push(request.url());
  });
  try {
    await page.addInitScript(fakeMicrophoneScript());
    await page.goto(`http://127.0.0.1:${server.port}/catalog/index.html?voice=stub`);
    await page.waitForFunction(() => window.__app);
    assert.match(await page.locator("#hearing-status").textContent(), /loads when you first talk/);
    assert.equal(await page.locator("#duplex").textContent(), "Full duplex");
    const clicked = Date.now();
    await page.locator("#talk").click();
    await page.waitForFunction(() => window.__app.state().hearingStatus === "Ember can hear you.", null, { timeout: 120_000 });
    const readyAt = Date.now();
    assert.equal(await page.locator("#talk").textContent(), "Listening");

    const clip = oracle.clips.find((entry) => entry.name === "hello-there");
    const speechEndMs = (clip.samples / 16000 - 2.2) * 1000;
    const playStart = Date.now();
    const played = page.evaluate(() => window.__fakeMic.play("/hearing/fixtures/hearing/hello-there.wav")).catch(() => {});
    await page.waitForFunction(() => document.querySelector("#partial").textContent.includes("THERE"), null, { timeout: 30_000, polling: 50 });
    await page.waitForFunction(() => window.__app.state().heard !== null, null, { timeout: 30_000, polling: 20 });
    const sentAt = Date.now();
    const state = await page.evaluate(() => window.__app.state());
    // Exact parity with sherpa is hearing-asr.test.js, on identical samples. Here the clip lands on
    // different 100 ms chunk boundaries each run (and may be resampled by the MediaStream path), so
    // the first vowel can go either way: HELLO or HULLO.
    assert.match(state.heard, /^H[EU]LLO THERE HOW ARE YOU TO DAY$/, `sherpa hears "${clip.final}"`);
    assert.equal(state.phase, "thinking");
    assert.deepEqual(posts, [], "nothing was posted");
    const report = await page.evaluate(() => window.__app.hearing.report());
    await played;

    await page.reload();
    await page.waitForFunction(() => window.__app);
    const reclicked = Date.now();
    await page.locator("#talk").click();
    await page.waitForFunction(() => window.__app.state().hearingStatus === "Ember can hear you.", null, { timeout: 60_000, polling: 20 });
    const cachedReadyMs = Date.now() - reclicked;
    const cached = await page.evaluate(() => window.__app.hearing.report());
    assert.ok(Object.values(cached.files).every((file) => file.source === "cache"), "a reload reads the hearing files from Cache Storage");
    assert.equal(cached.downloadedBytes, 0);
    console.log(`HEARING_METRICS ${JSON.stringify({ sent: state.heard, clickToReadyMs: readyAt - clicked, cachedClickToReadyMs: cachedReadyMs, speechEndToSendMs: Math.round(sentAt - playStart - speechEndMs), first: { downloadedBytes: report.downloadedBytes, sessionMs: report.sessionMs, warmupMs: report.warmupMs, readyMs: report.readyMs }, cached: { sessionMs: cached.sessionMs, warmupMs: cached.warmupMs, readyMs: cached.readyMs } })}`);
  } finally {
    await browser.close();
    await server.close();
  }
});

test("speaking over Ember's real voice barges in through the microphone and keeps listening", { skip: ready && existsSync(path.join(voiceAssets, "kokoro-v1_0-fp16w.onnx")) ? false : "run node web/hearing/scripts/fetch-web-voice.mjs first", timeout: 300_000 }, async () => {
  const server = await serve();
  const browser = await launch();
  const page = await browser.newPage();
  try {
    await page.addInitScript(fakeMicrophoneScript());
    await page.goto(`http://127.0.0.1:${server.port}/catalog/index.html`);
    await page.waitForFunction(() => window.__app);
    await page.locator("#talk").click();
    await page.waitForFunction(() => window.__app.state().hearingStatus === "Ember can hear you." && /ready/.test(window.__app.state().voiceStatus), null, { timeout: 180_000 });
    await page.evaluate(() => window.__app.say("Here is a longer answer, so there is time to interrupt me. I will keep talking about the catalog, the companions, and the weather for quite a while."));
    await page.waitForFunction(() => window.__app.state().phase === "speaking", null, { timeout: 60_000, polling: 50 });
    const flushesBefore = await page.evaluate(() => window.__app.voice.stats().flushes.length);
    const spoke = Date.now();
    page.evaluate(() => window.__fakeMic.play("/hearing/fixtures/hearing/stop-that.wav")).catch(() => {});
    await page.waitForFunction(() => window.__app.state().logs.some((line) => line.startsWith("barge-in vad")), null, { timeout: 20_000, polling: 20 });
    const bargedAfterMs = Date.now() - spoke;
    const state = await page.evaluate(() => window.__app.state());
    assert.equal(state.phase, "listening");
    await page.waitForFunction((count) => window.__app.voice.stats().flushes.length > count && window.__app.voice.stats().flushes.at(-1).flushedFrame != null, flushesBefore);
    const flush = await page.evaluate(() => window.__app.voice.stats().flushes.at(-1));
    assert.ok(flush.heardAfterStop <= 2400, `heard ${flush.heardAfterStop} frames after the barge-in`);
    assert.ok(flush.dropped > 0, "the rest of her reply was dropped");
    assert.equal(state.heard, null, "the barge-in itself is not a turn");
    console.log(`BARGE_METRICS ${JSON.stringify({ bargedAfterMs, heardAfterStop: flush.heardAfterStop })}`);
  } finally {
    await browser.close();
    await server.close();
  }
});
