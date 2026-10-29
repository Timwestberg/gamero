import http from "node:http";
import path from "node:path";
import { readFile } from "node:fs/promises";
import test from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright-core";

const root = path.resolve(import.meta.dirname, "../..");

function contentType(file) {
  if (file.endsWith(".html")) return "text/html";
  if (file.endsWith(".js")) return "text/javascript";
  if (file.endsWith(".json")) return "application/json";
  return "text/plain";
}

async function serve() {
  const server = http.createServer(async (request, response) => {
    const url = new URL(request.url, "http://127.0.0.1");
    if (request.method !== "GET") {
      response.writeHead(405);
      response.end("model bytes are not posted");
      return;
    }
    const file = path.normalize(path.join(root, decodeURIComponent(url.pathname)));
    if (!file.startsWith(root)) {
      response.writeHead(403);
      response.end();
      return;
    }
    try {
      const body = await readFile(file);
      response.writeHead(200, { "content-type": contentType(file) });
      response.end(body);
    } catch {
      response.writeHead(404);
      response.end();
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  return { server, port: address.port };
}

async function openPage(port, query) {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
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
  await page.goto(`http://127.0.0.1:${port}/catalog/index.html?${query}`);
  await page.waitForFunction(() => window.__app);
  return { browser, page, posts };
}

test("a headless browser shows a partial, plays the local line, and can send through the host", async () => {
  const { server, port } = await serve();
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
    await new Promise((resolve) => server.close(resolve));
  }
});

test("getUserMedia is asked for echo cancellation", async () => {
  const { server, port } = await serve();
  try {
    const browser = await chromium.launch({ channel: "chrome", headless: true });
    const page = await browser.newPage();
    await page.addInitScript(() => {
      navigator.mediaDevices.getUserMedia = async (constraints) => {
        window.__constraints = constraints;
        const track = { getSettings: () => ({ echoCancellation: true, noiseSuppression: true }), stop() {} };
        return { getAudioTracks: () => [track], getTracks: () => [track] };
      };
    });
    await page.goto(`http://127.0.0.1:${port}/catalog/index.html`);
    await page.waitForFunction(() => window.__constraints);
    const constraints = await page.evaluate(() => window.__constraints);
    assert.equal(constraints.audio.echoCancellation, true);
    assert.equal(constraints.audio.noiseSuppression, true);
    await browser.close();
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
