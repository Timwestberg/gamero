import { existsSync, readdirSync } from "node:fs";
import { readFile } from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { chromium } from "playwright-core";

export const webRoot = path.resolve(import.meta.dirname, "../..");
export const voiceAssets = path.join(webRoot, "voice-assets");

const TYPES = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".mjs": "text/javascript",
  ".json": "application/json",
  ".wasm": "application/wasm",
};

/** Static server for web/. GET only, and it logs every path so a test can count downloads. */
export async function serve() {
  const requests = [];
  const server = http.createServer(async (request, response) => {
    const url = new URL(request.url, "http://127.0.0.1");
    requests.push({ method: request.method, path: url.pathname });
    if (request.method !== "GET") {
      response.writeHead(405);
      response.end("model bytes are not posted");
      return;
    }
    const file = path.normalize(path.join(webRoot, decodeURIComponent(url.pathname)));
    if (!file.startsWith(webRoot)) {
      response.writeHead(403);
      response.end();
      return;
    }
    try {
      const body = await readFile(file);
      response.writeHead(200, { "content-type": TYPES[path.extname(file)] || "application/octet-stream" });
      response.end(body);
    } catch {
      response.writeHead(404);
      response.end();
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return {
    port: server.address().port,
    requests,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

/**
 * CHROME_PATH, then Google Chrome, then the newest Chromium in Playwright's
 * cache (playwright-core 1.58 does not install one), then Playwright's default.
 */
export function chromeExecutable() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
  if (existsSync(chrome)) return chrome;
  const cache = process.platform === "darwin"
    ? path.join(os.homedir(), "Library/Caches/ms-playwright")
    : path.join(os.homedir(), ".cache/ms-playwright");
  const builds = existsSync(cache) ? readdirSync(cache).filter((name) => /^chromium-\d+$/.test(name)).sort().reverse() : [];
  for (const build of builds) {
    for (const candidate of [
      "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
      "chrome-mac/Chromium.app/Contents/MacOS/Chromium",
      "chrome-linux/chrome",
      "chrome-linux64/chrome",
    ]) {
      const file = path.join(cache, build, candidate);
      if (existsSync(file)) return file;
    }
  }
  if (existsSync("/opt/pw-browsers/chromium")) return "/opt/pw-browsers/chromium";
  return undefined;
}

/** Headless Chromium that may play audio without a click and exposes WebGPU. */
export function launch() {
  return chromium.launch({
    executablePath: chromeExecutable(),
    headless: true,
    args: ["--autoplay-policy=no-user-gesture-required", "--enable-unsafe-webgpu"],
  });
}
