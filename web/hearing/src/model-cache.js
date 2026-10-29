/**
 * Pinned bytes with a Cache Storage copy. The first load is a GET, a sha256
 * check, then a put under a key that names the hash. Later loads read the
 * cache and only check the byte count, because nothing reaches the cache
 * without passing the hash. Pass verifyCached to hash cached bytes as well.
 * Works on the page and in a worker. Nothing is posted anywhere.
 */

import { assertNotHovel, refuseNonCommercial, sha256Hex } from "./models.js";

export const VOICE_CACHE_NAME = "ember-voice-v1";

/** The cache key: the file URL plus its hash, so a new pin never reads an old entry. */
export function cacheKey(url, sha256) {
  const key = new URL(url);
  key.searchParams.set("sha256", sha256);
  return key.href;
}

/**
 * Loads one pinned file. Returns { bytes, source: "cache" | "network", ms }.
 * onProgress(loadedBytes) is called while the network body streams in.
 */
export async function loadPinned(file, options) {
  const started = now();
  const url = new URL(file.name, options.baseUrl).href;
  refuseNonCommercial(url);
  assertNotHovel(url, "GET");
  const store = await openCache(options.caches, options.cacheName ?? VOICE_CACHE_NAME);
  const key = cacheKey(url, file.sha256);
  if (store) {
    const hit = await store.match(key);
    if (hit) {
      const bytes = new Uint8Array(await hit.arrayBuffer());
      const fresh = bytes.byteLength === file.bytes && (!options.verifyCached || (await sha256Hex(bytes)) === file.sha256);
      if (fresh) {
        options.onProgress?.(bytes.byteLength);
        return { bytes, source: "cache", ms: now() - started };
      }
      await store.delete(key);
    }
  }
  const fetchImpl = options.fetch ?? globalThis.fetch;
  const response = await fetchImpl(url, { method: "GET" });
  if (!response.ok) throw new Error(`voice file ${file.name} failed: ${response.status}`);
  const bytes = await readBody(response, file.bytes, options.onProgress);
  if (bytes.byteLength !== file.bytes) throw new Error(`voice file ${file.name} is ${bytes.byteLength} bytes, expected ${file.bytes}`);
  if ((await sha256Hex(bytes)) !== file.sha256) throw new Error(`hash mismatch for ${file.name}`);
  if (store) {
    await store.put(key, new Response(bytes, { headers: { "content-type": "application/octet-stream" } }));
  }
  return { bytes, source: "network", ms: now() - started };
}

/** True when every file is already cached under its pinned hash. Reads no bodies. */
export async function allCached(files, options) {
  const store = await openCache(options.caches, options.cacheName ?? VOICE_CACHE_NAME);
  if (!store) return false;
  for (const file of files) {
    const hit = await store.match(cacheKey(new URL(file.name, options.baseUrl).href, file.sha256));
    if (!hit) return false;
  }
  return true;
}

async function openCache(caches = globalThis.caches, name) {
  if (!caches?.open) return null;
  try {
    return await caches.open(name);
  } catch {
    return null;
  }
}

async function readBody(response, expected, onProgress) {
  if (!response.body?.getReader) {
    const bytes = new Uint8Array(await response.arrayBuffer());
    onProgress?.(bytes.byteLength);
    return bytes;
  }
  const reader = response.body.getReader();
  let out = new Uint8Array(expected);
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (loaded + value.byteLength > out.byteLength) {
      const grown = new Uint8Array(Math.max(out.byteLength * 2, loaded + value.byteLength));
      grown.set(out.subarray(0, loaded));
      out = grown;
    }
    out.set(value, loaded);
    loaded += value.byteLength;
    onProgress?.(loaded);
  }
  return loaded === out.byteLength ? out : out.slice(0, loaded);
}

function now() {
  return globalThis.performance?.now() ?? Date.now();
}
