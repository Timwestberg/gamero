import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { launch, serve, voiceAssets } from "./support.js";

const ready = existsSync(path.join(voiceAssets, "kokoro-v1_0-fp16w.onnx"));
const skip = ready ? false : "run node web/hearing/scripts/fetch-web-voice.mjs first";
const REPLY = "Hi, I'm Ember. Sonnet 5.5 answered in 160 ms. What should we build next?";
const LONG_REPLY = "Here is a longer answer so there is time to interrupt me. I will keep talking about the catalog, the companions, and the weather for a while. You can stop me at any point.";

async function waitFor(page, predicate, arg, timeout = 180_000) {
  await page.waitForFunction(predicate, arg, { timeout, polling: 50 });
}

async function sayAndFinish(page, text) {
  await page.evaluate((line) => window.__app.say(line), text);
  await waitFor(page, (line) => window.__app.voice.last()?.text === line && window.__app.voice.last()?.rtf != null, text);
  return page.evaluate(() => window.__app.voice.last());
}

async function bargeCheck(page, interrupt) {
  if ((await page.evaluate(() => window.__app.state().phase)) === "off") await page.locator("#talk").click();
  await page.evaluate((line) => window.__app.say(line), LONG_REPLY);
  await waitFor(page, () => window.__app.state().phase === "speaking");
  await page.waitForTimeout(700);
  const before = await page.evaluate(() => window.__app.voice.stats().flushes.length);
  await interrupt();
  await waitFor(page, (count) => {
    const flushes = window.__app.voice.stats().flushes;
    return flushes.length > count && flushes.at(-1).flushedFrame != null;
  }, before);
  const state = await page.evaluate(() => ({ phase: window.__app.state().phase, stats: window.__app.voice.stats(), last: window.__app.voice.last() }));
  const flush = state.stats.flushes.at(-1);
  assert.equal(state.phase, "listening");
  assert.ok(flush.heardAfterStop <= state.stats.chunkSamples, `heard ${flush.heardAfterStop} frames after stop; one chunk is ${state.stats.chunkSamples}`);
  assert.ok(flush.dropped > 0, "the rest of the reply was dropped");
  assert.equal(state.last.rtf, null, "a stopped reply never reports as finished");
  return flush;
}

test("Ember speaks in Chrome: chosen blend, cached reload, barge-in within one chunk", { skip, timeout: 600_000 }, async (t) => {
  const server = await serve();
  const browser = await launch();
  const context = await browser.newContext();
  const page = await context.newPage();
  const posts = [];
  page.on("request", (request) => {
    if (request.method() !== "GET") posts.push(request.url());
  });
  const metrics = {};
  try {
    await page.goto(`http://127.0.0.1:${server.port}/catalog/index.html?scripted=1`);
    await page.waitForFunction(() => window.__app);

    await t.test("the first reply downloads the voice, then speaks with the chosen blend", async () => {
      assert.match(await page.locator("#voice-status").textContent(), /loads when you first talk/);
      assert.deepEqual(await page.locator("#voice option").allTextContents(), ["Sam", "Jamie", "Riley", "Harper"]);
      assert.equal(await page.locator("#voice").inputValue(), "adam70-michael30");
      await page.locator("#voice").selectOption("adam60-george40");
      const started = Date.now();
      const statusSeen = page.waitForFunction(() => /Getting Ember's voice ready/.test(document.querySelector("#voice-status").textContent), null, { timeout: 60_000 });
      const last = await sayAndFinish(page, REPLY);
      await statusSeen;
      const report = await page.evaluate(() => window.__app.voice.report());
      assert.equal(last.voiceId, "adam60-george40");
      assert.deepEqual(last.pieces.map((piece) => piece.chars), [14, 66, 26]);
      assert.ok(last.pieces.every((piece) => piece.audioMs > 300));
      assert.ok(Object.values(report.files).every((file) => file.source === "network"));
      assert.equal(report.downloadedBytes, 198_251_088);
      if (report.backend === "webgpu") assert.ok(last.rtf < 1, `real-time factor ${last.rtf}`);
      assert.match(await page.locator("#voice-status").textContent(), /ready/);
      metrics.backend = report.backend;
      metrics.firstLoad = { bytes: report.downloadedBytes, readyMs: report.readyMs, downloadMs: report.downloadMs, lexiconMs: report.lexiconMs, sessionMs: report.sessionMs, warmupMs: report.warmupMs, ttfaColdMs: last.ttfaMs, wallMs: Date.now() - started };
    });

    await t.test("a warm reply starts fast and keeps up with playback", async () => {
      const last = await sayAndFinish(page, "Riley here. This one should start quickly, because the model is already warm.");
      metrics.warm = { ttfaMs: last.ttfaMs, rtf: last.rtf, pieces: last.pieces };
      if (metrics.backend === "webgpu") assert.ok(last.ttfaMs < 1500, `warm time to first audio ${last.ttfaMs} ms`);
    });

    await t.test("a reload reads every voice file from Cache Storage and keeps the blend", async () => {
      const before = server.requests.length;
      await page.reload();
      await page.waitForFunction(() => window.__app);
      assert.equal(await page.locator("#voice").inputValue(), "adam60-george40");
      await waitFor(page, () => window.__app.voice.report() != null);
      const report = await page.evaluate(() => window.__app.voice.report());
      assert.ok(Object.values(report.files).every((file) => file.source === "cache"), JSON.stringify(report.files));
      assert.equal(report.downloadedBytes, 0);
      const voiceGets = server.requests.slice(before).filter((request) => request.path.startsWith("/voice-assets/"));
      assert.deepEqual(voiceGets, [], "no voice file was requested again");
      const last = await sayAndFinish(page, "Back again, from the cache.");
      metrics.cached = { readyMs: report.readyMs, cacheReadMs: Math.max(...Object.values(report.files).map((file) => file.ms)), lexiconMs: report.lexiconMs, sessionMs: report.sessionMs, warmupMs: report.warmupMs, ttfaMs: last.ttfaMs };
    });

    await t.test("clicking the talk button while Ember speaks stops her within one chunk", async () => {
      const flush = await bargeCheck(page, () => page.locator("#talk").click());
      metrics.bargeClickFrames = flush.heardAfterStop;
      const stats = await page.evaluate(() => window.__app.voice.stats());
      metrics.deviceLatencyMs = Math.round(((stats.baseLatency ?? 0) + (stats.outputLatency ?? 0)) * 1000);
    });

    await t.test("Escape barges in the same way", async () => {
      const flush = await bargeCheck(page, () => page.keyboard.press("Escape"));
      metrics.bargeEscapeFrames = flush.heardAfterStop;
    });

    await t.test("250 ms of voice at probability 0.9 barges in through the session", async () => {
      const flush = await bargeCheck(page, () => page.evaluate(() => {
        for (let i = 0; i < 3; i += 1) window.__app.feed({ frameMs: 100, vadProb: 0.9, rms: 0.4, text: "stop" });
      }));
      metrics.bargeVadFrames = flush.heardAfterStop;
      assert.match((await page.evaluate(() => window.__app.voice.stats())).flushes.length.toString(), /\d+/);
    });

    assert.deepEqual(posts, [], "nothing was posted");
    console.log(`VOICE_METRICS ${JSON.stringify(metrics)}`);
  } finally {
    await browser.close();
    await server.close();
  }
});
