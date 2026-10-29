import assert from "node:assert/strict";
import test from "node:test";
import { allCached, cacheKey, loadPinned } from "../src/model-cache.js";
import { sha256Hex } from "../src/models.js";

function memoryCaches() {
  const stores = new Map();
  return {
    stores,
    async open(name) {
      if (!stores.has(name)) {
        const entries = new Map();
        stores.set(name, {
          entries,
          async match(key) {
            const body = entries.get(String(key));
            return body ? new Response(body) : undefined;
          },
          async put(key, response) {
            entries.set(String(key), new Uint8Array(await response.arrayBuffer()));
          },
          async delete(key) {
            return entries.delete(String(key));
          },
        });
      }
      return stores.get(name);
    },
  };
}

async function pinned(text, name = "model.onnx") {
  const bytes = new TextEncoder().encode(text);
  return { file: { name, bytes: bytes.byteLength, sha256: await sha256Hex(bytes) }, bytes };
}

function server(bytes) {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push([url, init.method]);
    return new Response(bytes);
  };
  return { calls, fetch };
}

test("the first load is a checked GET, and the second load is a cache hit with no request", async () => {
  const { file, bytes } = await pinned("kokoro weights");
  const caches = memoryCaches();
  const net = server(bytes);
  const progress = [];
  const first = await loadPinned(file, { baseUrl: "https://example.test/voice/", caches, fetch: net.fetch, onProgress: (n) => progress.push(n) });
  assert.equal(first.source, "network");
  assert.deepEqual(net.calls, [["https://example.test/voice/model.onnx", "GET"]]);
  assert.equal(progress.at(-1), file.bytes);
  const second = await loadPinned(file, { baseUrl: "https://example.test/voice/", caches, fetch: net.fetch });
  assert.equal(second.source, "cache");
  assert.equal(net.calls.length, 1);
  assert.deepEqual([...second.bytes], [...bytes]);
  assert.equal(await allCached([file], { baseUrl: "https://example.test/voice/", caches }), true);
});

test("bytes that fail the hash are never cached", async () => {
  const { file } = await pinned("the real model");
  const caches = memoryCaches();
  const net = server(new TextEncoder().encode("the fake model"));
  await assert.rejects(() => loadPinned(file, { baseUrl: "https://example.test/", caches, fetch: net.fetch }), /expected|hash mismatch/);
  assert.equal(caches.stores.get("ember-voice-v1").entries.size, 0);
  assert.equal(await allCached([file], { baseUrl: "https://example.test/", caches }), false);
});

test("a new pin reads a new key, and a damaged entry is dropped and fetched again", async () => {
  const { file, bytes } = await pinned("voices");
  assert.notEqual(cacheKey("https://example.test/a.bin", "aa"), cacheKey("https://example.test/a.bin", "bb"));
  const caches = memoryCaches();
  const store = await caches.open("ember-voice-v1");
  await store.put(cacheKey("https://example.test/model.onnx", file.sha256), new Response(new Uint8Array(2)));
  const net = server(bytes);
  const result = await loadPinned(file, { baseUrl: "https://example.test/", caches, fetch: net.fetch });
  assert.equal(result.source, "network");
  assert.equal(net.calls.length, 1);
  const tampered = await pinned("voicez");
  await store.put(cacheKey("https://example.test/model.onnx", file.sha256), new Response(tampered.bytes));
  const verified = await loadPinned(file, { baseUrl: "https://example.test/", caches, fetch: net.fetch, verifyCached: true });
  assert.equal(verified.source, "network", "verifyCached re-hashes and refuses same-size wrong bytes");
});

test("voice files are GET-only, refused for non-commercial names, and never go to Hovel", async () => {
  const { file, bytes } = await pinned("x", "expresso-voice.bin");
  await assert.rejects(() => loadPinned(file, { baseUrl: "https://example.test/", caches: memoryCaches(), fetch: server(bytes).fetch }), /non-commercial/);
  const ok = await pinned("x");
  await assert.rejects(() => loadPinned(ok.file, { baseUrl: "https://hovel-service.example/", caches: memoryCaches(), fetch: server(ok.bytes).fetch }), /Hovel/);
});

test("without Cache Storage the file still loads, every time from the network", async () => {
  const { file, bytes } = await pinned("no cache here");
  const net = server(bytes);
  const result = await loadPinned(file, { baseUrl: "https://example.test/", caches: null, fetch: net.fetch });
  assert.equal(result.source, "network");
  assert.equal(await allCached([file], { baseUrl: "https://example.test/", caches: null }), false);
});
