import test from "node:test";
import assert from "node:assert/strict";
import { launch, serve } from "./support.js";

// voice=stub keeps these on the recorder; voice.browser.test.js covers the real voice.
async function openPage(port, query) {
  const browser = await launch();
  const page = await browser.newPage();
  const posts = [];
  page.on("request", (request) => {
    if (request.method() !== "GET") posts.push(request.url());
  });
  await page.addInitScript(() => {
    navigator.mediaDevices.getUserMedia = async (constraints) => {
      window.__constraints = constraints;
      const track = {
        getSettings: () => ({ echoCancellation: true, noiseSuppression: true, channelCount: 1, sampleRate: 16000 }),
        stop() {},
      };
      return { getAudioTracks: () => [track], getTracks: () => [track] };
    };
  });
  await page.goto(`http://127.0.0.1:${port}/catalog/index.html?voice=stub&${query}`);
  await page.waitForFunction(() => window.__app);
  return { browser, page, posts };
}

test("a headless browser shows a partial, plays the local line, and can send through the host", async () => {
  const server = await serve();
  const { port } = server;
  try {
    const local = await openPage(port, "scripted=1");
    const permission = await local.page.locator("#notice").textContent();
    assert.match(permission, /recording is not saved/);
    assert.match(await local.page.locator("#chat-status").textContent(), /Open Hovel on your Mac/);
    await local.page.locator("#talk").click();
    assert.equal(await local.page.locator("#talk").textContent(), "Listening");
    for (let i = 0; i < 4; i += 1) {
      await local.page.evaluate(() => window.__app.feed({ frameMs: 100, vadProb: 0.92, rms: 0.3, text: "hello there" }));
    }
    assert.equal(await local.page.locator("#partial").textContent(), "hello there");
    for (let i = 0; i < 12; i += 1) {
      const state = await local.page.evaluate(() => window.__app.feed({ frameMs: 100, vadProb: 0, rms: 0.001, text: "hello there" }));
      if (state.phase === "speaking") break;
    }
    assert.equal(await local.page.evaluate(() => window.__app.state().line), "Hi, I'm Sam.");
    assert.equal(await local.page.locator("#talk").textContent(), "Ember is speaking");
    await local.page.keyboard.press("Escape");
    assert.equal(await local.page.locator("#talk").textContent(), "Listening");
    await local.page.locator("#talk").click();
    assert.equal(await local.page.locator("#talk").textContent(), "Mic off");
    assert.deepEqual(local.posts, []);
    await local.browser.close();

    const hosted = await openPage(port, "scripted=1&host=1");
    await hosted.page.locator("#talk").click();
    for (let i = 0; i < 4; i += 1) {
      await hosted.page.evaluate(() => window.__app.feed({ frameMs: 100, vadProb: 0.92, rms: 0.3, text: "hello there" }));
    }
    let calls = [];
    for (let i = 0; i < 12; i += 1) {
      calls = await hosted.page.evaluate(() => window.__app.feed({ frameMs: 100, vadProb: 0, rms: 0.001, text: "hello there" }).calls);
      if (calls.some((call) => call[0] === "send")) break;
    }
    assert.deepEqual(calls[0], ["hearing", "press"]);
    assert.deepEqual(calls.at(-1), ["send", "hello there"]);
    await hosted.browser.close();
  } finally {
    await server.close();
  }
});

test("getUserMedia is asked for echo cancellation", async () => {
  const server = await serve();
  const { port } = server;
  try {
    const browser = await launch();
    const page = await browser.newPage();
    await page.addInitScript(() => {
      navigator.mediaDevices.getUserMedia = async (constraints) => {
        window.__constraints = constraints;
        const track = { getSettings: () => ({ echoCancellation: true, noiseSuppression: true }), stop() {} };
        return { getAudioTracks: () => [track], getTracks: () => [track] };
      };
    });
    await page.goto(`http://127.0.0.1:${port}/catalog/index.html?voice=stub`);
    await page.waitForFunction(() => window.__constraints);
    const constraints = await page.evaluate(() => window.__constraints);
    assert.equal(constraints.audio.echoCancellation, true);
    assert.equal(constraints.audio.noiseSuppression, true);
    await browser.close();
  } finally {
    await server.close();
  }
});
