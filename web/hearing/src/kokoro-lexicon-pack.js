/**
 * The prepared Kokoro lexicon in one buffer, so a later page load skips the
 * 1.2 s of parsing and inflecting. It holds exactly what the worker builds
 * the slow way: sherpa's lexicon map (lexicon-us-en.txt plus the
 * lexicon-chat.txt inflections, later entries winning) with each entry's
 * phones already converted to token ids, and the max phrase length.
 *
 * Lookups are a binary search over keys sorted in UTF-16 order, so a load
 * builds no Map and no Set. get(key) returns the token symbols, which map
 * back to the same ids, so kokoro-frontend.js is unchanged.
 *
 * Layout (little-endian):
 *   0   u32 magic "EKLX"   4 u32 PACK_VERSION   8 u32 entries   12 u32 maxPhraseLen
 *   16  u32 chatAdditions  20 u32 idBytes       24 u32 keyBytes 28 u32 reserved
 *   32  u32[entries + 1] key offsets (UTF-16 units into the decoded keys)
 *   ..  u32[entries + 1] id offsets
 *   ..  u8[idBytes] token ids
 *   ..  u8[keyBytes] UTF-8 keys, each followed by "\n"
 *
 * The pack also stands in for desktop's known-word set (coverUnknownWords).
 * For the pinned lexicon, the words [a-z]+('[a-z]+)? that KokoroLexicon.kt
 * knows are exactly the pack's keys of that shape; kokoro-lexicon-pack.test.js
 * checks it, so a new lexicon pin has to pass that test too.
 */

import { loadLexicons } from "./kokoro-frontend.js";
import { chatLexiconText, kokoroChatLexicon, parseKokoroLexicon } from "./kokoro-lexicon.js";
import { VOICE_CACHE_NAME } from "./model-cache.js";

/** Bump when the layout or anything that feeds it (lexicon rules, frontend parse) changes. */
export const PACK_VERSION = 1;
const MAGIC = 0x584c4b45;
const HEADER = 32;

/** The cache key for a pack: it names every input, so a new pin or rule never reads an old pack. */
export function packKey(baseUrl, { lexiconSha256, tokensSha256 }) {
  const key = new URL("kokoro-lexicon.pack", baseUrl);
  key.searchParams.set("lexicon", lexiconSha256);
  key.searchParams.set("tokens", tokensSha256);
  key.searchParams.set("v", String(PACK_VERSION));
  return key.href;
}

/**
 * lexicon: Map key -> phone symbols (loadLexicons), tokens: Map symbol -> id (parseTokens).
 * Phones with no token id are dropped here, as sherpa drops them when it converts.
 */
export function packLexicon({ lexicon, maxPhraseLen }, tokens, { chatAdditions = 0 } = {}) {
  const keys = [...lexicon.keys()].sort();
  const keyOffsets = new Uint32Array(keys.length + 1);
  const idOffsets = new Uint32Array(keys.length + 1);
  const ids = [];
  const parts = [];
  let units = 0;
  for (let index = 0; index < keys.length; index += 1) {
    keyOffsets[index] = units;
    const key = keys[index];
    parts.push(key, "\n");
    units += key.length + 1;
    idOffsets[index] = ids.length;
    for (const phone of lexicon.get(key)) {
      const id = tokens.get(phone);
      if (id === undefined) continue;
      if (id < 0 || id > 255) throw new Error(`token id ${id} does not fit the pack`);
      ids.push(id);
    }
  }
  keyOffsets[keys.length] = units;
  idOffsets[keys.length] = ids.length;
  const keyBytes = new TextEncoder().encode(parts.join(""));
  const size = HEADER + keyOffsets.byteLength + idOffsets.byteLength + ids.length + keyBytes.byteLength;
  const out = new Uint8Array(size);
  const view = new DataView(out.buffer);
  [MAGIC, PACK_VERSION, keys.length, maxPhraseLen, chatAdditions, ids.length, keyBytes.byteLength, 0]
    .forEach((value, index) => view.setUint32(index * 4, value, true));
  let offset = HEADER;
  for (const value of keyOffsets) {
    view.setUint32(offset, value, true);
    offset += 4;
  }
  for (const value of idOffsets) {
    view.setUint32(offset, value, true);
    offset += 4;
  }
  out.set(ids, offset);
  offset += ids.length;
  out.set(keyBytes, offset);
  return out;
}

/** Reads a pack. Throws on a wrong magic or version, so a caller can rebuild. */
export function unpackLexicon(bytes, tokens) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const header = Array.from({ length: 8 }, (_, index) => view.getUint32(index * 4, true));
  const [magic, version, entries, maxPhraseLen, chatAdditions, idBytes, keyBytes] = header;
  if (magic !== MAGIC || version !== PACK_VERSION) throw new Error("not a current Kokoro lexicon pack");
  const expected = HEADER + (entries + 1) * 8 + idBytes + keyBytes;
  if (bytes.byteLength !== expected) throw new Error(`lexicon pack is ${bytes.byteLength} bytes, expected ${expected}`);
  let offset = HEADER;
  const keyOffsets = readU32(view, offset, entries + 1);
  offset += (entries + 1) * 4;
  const idOffsets = readU32(view, offset, entries + 1);
  offset += (entries + 1) * 4;
  const ids = bytes.subarray(offset, offset + idBytes);
  offset += idBytes;
  const keyText = new TextDecoder().decode(bytes.subarray(offset, offset + keyBytes));
  if (keyText.length !== keyOffsets[entries]) throw new Error("lexicon pack keys do not match their offsets");

  const symbols = [];
  for (const [symbol, id] of tokens) if (symbols[id] === undefined) symbols[id] = symbol;
  const keyAt = (index) => keyText.substring(keyOffsets[index], keyOffsets[index + 1] - 1);
  const find = (key) => {
    let low = 0;
    let high = entries - 1;
    while (low <= high) {
      const middle = (low + high) >>> 1;
      const probe = keyAt(middle);
      if (probe === key) return middle;
      if (probe < key) low = middle + 1;
      else high = middle - 1;
    }
    return -1;
  };
  const lexicon = {
    size: entries,
    has: (key) => find(key) >= 0,
    get(key) {
      const index = find(key);
      if (index < 0) return undefined;
      const out = [];
      for (let at = idOffsets[index]; at < idOffsets[index + 1]; at += 1) out.push(symbols[ids[at]]);
      return out;
    },
    /** Token ids for a key, for tests and tools. */
    ids(key) {
      const index = find(key);
      return index < 0 ? undefined : Array.from(ids.subarray(idOffsets[index], idOffsets[index + 1]));
    },
  };
  return { lexicon, maxPhraseLen, chatAdditions };
}

function readU32(view, offset, count) {
  const out = new Uint32Array(count);
  for (let index = 0; index < count; index += 1) out[index] = view.getUint32(offset + index * 4, true);
  return out;
}

/**
 * The prepared lexicon for the pinned files: from Cache Storage when a pack
 * is there, otherwise built the desktop way, packed, and cached. Both paths
 * return the unpacked form, so the first load and later loads look words up
 * the same way. Returns { lexicon, maxPhraseLen, chatAdditions, source, ms }.
 * lexiconBytes is only read on a build, and may be a function that loads them.
 */
export async function loadPreparedLexicon({ baseUrl, files, tokens, lexiconBytes, caches = globalThis.caches }) {
  const started = now();
  const key = packKey(baseUrl, { lexiconSha256: files.lexicon.sha256, tokensSha256: files.tokens.sha256 });
  const store = await openStore(caches);
  const hit = await store?.match(key);
  if (hit) {
    try {
      return { ...unpackLexicon(new Uint8Array(await hit.arrayBuffer()), tokens), source: "cache", ms: now() - started };
    } catch {
      await store.delete(key);
    }
  }
  const bytes = typeof lexiconBytes === "function" ? await lexiconBytes() : lexiconBytes;
  const baseText = new TextDecoder().decode(bytes);
  const chat = kokoroChatLexicon(parseKokoroLexicon(baseText));
  const texts = chat.additions.size > 0 ? [baseText, chatLexiconText(chat.additions)] : [baseText];
  const pack = packLexicon(loadLexicons(texts), tokens, { chatAdditions: chat.additions.size });
  await store?.put(key, new Response(pack, { headers: { "content-type": "application/octet-stream" } }));
  return { ...unpackLexicon(pack, tokens), source: "built", ms: now() - started };
}

async function openStore(caches) {
  if (!caches?.open) return null;
  try {
    return await caches.open(VOICE_CACHE_NAME);
  } catch {
    return null;
  }
}

function now() {
  return globalThis.performance?.now() ?? Date.now();
}
