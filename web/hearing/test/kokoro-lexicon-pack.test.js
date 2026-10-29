import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";
import { loadLexicons, parseTokens, textToTokenIds } from "../src/kokoro-frontend.js";
import { coverUnknownWords, chatLexiconText, kokoroChatLexicon, parseKokoroLexicon } from "../src/kokoro-lexicon.js";
import { PACK_VERSION, loadPreparedLexicon, packKey, packLexicon, unpackLexicon } from "../src/kokoro-lexicon-pack.js";
import { KOKORO_WEB } from "../src/models.js";

const assets = new URL("../../voice-assets/", import.meta.url);
const haveAssets = existsSync(new URL("lexicon-us-en.txt", assets)) && existsSync(new URL("tokens.txt", assets));
const TOKENS = parseTokens("$ 0\n; 1\n: 2\n, 3\n. 4\n 16\na 43\nb 44\nk 53\nˈ 156\nɛ 86\nɪ 102\nz 68\n");
const SMALL = loadLexicons(["bee b ˈ i\nzed z ˈ ɛ d\ncafé k a\nkiz k ˈ ɪ z\n", "bees b ˈ i z\n"]);

function memoryCaches() {
  const entries = new Map();
  const store = {
    entries,
    async match(key) {
      const body = entries.get(key);
      return body ? new Response(body) : undefined;
    },
    async put(key, response) {
      entries.set(key, new Uint8Array(await response.arrayBuffer()));
    },
    async delete(key) {
      return entries.delete(key);
    },
  };
  return { store, open: async () => store };
}

test("a pack round-trips keys, token ids, phrase length, and the addition count", () => {
  const pack = packLexicon(SMALL, TOKENS, { chatAdditions: 1 });
  const { lexicon, maxPhraseLen, chatAdditions } = unpackLexicon(pack, TOKENS);
  assert.equal(lexicon.size, 5);
  assert.equal(maxPhraseLen, SMALL.maxPhraseLen);
  assert.equal(chatAdditions, 1);
  assert.deepEqual(lexicon.ids("bees"), [44, 156, 68], "i has no token id here, so it is dropped the way sherpa drops it");
  assert.deepEqual(lexicon.get("kiz"), ["k", "ˈ", "ɪ", "z"]);
  assert.deepEqual(lexicon.ids("café"), [53, 43]);
  assert.equal(lexicon.has("bee"), true);
  assert.equal(lexicon.has("be"), false);
  assert.equal(lexicon.get("beez"), undefined);
  for (const key of SMALL.lexicon.keys()) assert.equal(lexicon.has(key), true, key);
});

test("a stale or damaged pack is refused", () => {
  const pack = packLexicon(SMALL, TOKENS);
  const wrongVersion = Uint8Array.from(pack);
  new DataView(wrongVersion.buffer).setUint32(4, PACK_VERSION + 1, true);
  assert.throws(() => unpackLexicon(wrongVersion, TOKENS), /not a current/);
  assert.throws(() => unpackLexicon(pack.subarray(0, pack.length - 1), TOKENS), /expected/);
  assert.throws(() => packLexicon({ lexicon: new Map([["x", ["big"]]]), maxPhraseLen: 1 }, new Map([["big", 300]])), /does not fit/);
});

test("the pack key names the lexicon, the tokens, and the pack version", () => {
  const key = packKey("https://example.test/voice/", { lexiconSha256: "aa", tokensSha256: "bb" });
  assert.equal(key, `https://example.test/voice/kokoro-lexicon.pack?lexicon=aa&tokens=bb&v=${PACK_VERSION}`);
});

test("the first load builds and caches the pack; the next load reads it without the lexicon", async () => {
  const caches = memoryCaches();
  const files = { lexicon: { sha256: "lex" }, tokens: { sha256: "tok" } };
  const lexiconBytes = new TextEncoder().encode("bee b ˈ i\nkiss k ˈ ɪ s\n");
  const first = await loadPreparedLexicon({ baseUrl: "https://example.test/", files, tokens: TOKENS, lexiconBytes, caches });
  assert.equal(first.source, "built");
  assert.equal(first.lexicon.has("kisses"), true, "the desktop inflections are in the pack");
  assert.equal(caches.store.entries.size, 1);
  const second = await loadPreparedLexicon({
    baseUrl: "https://example.test/",
    files,
    tokens: TOKENS,
    lexiconBytes: () => {
      throw new Error("a cached pack needs no lexicon");
    },
    caches,
  });
  assert.equal(second.source, "cache");
  assert.deepEqual(second.lexicon.ids("kisses"), first.lexicon.ids("kisses"));
  const [key] = caches.store.entries.keys();
  caches.store.entries.set(key, new Uint8Array(8));
  const rebuilt = await loadPreparedLexicon({ baseUrl: "https://example.test/", files, tokens: TOKENS, lexiconBytes, caches });
  assert.equal(rebuilt.source, "built", "a damaged pack is dropped and rebuilt");
  const uncached = await loadPreparedLexicon({ baseUrl: "https://example.test/", files, tokens: TOKENS, lexiconBytes, caches: null });
  assert.equal(uncached.source, "built");
});

test("the packed real lexicon gives every word the ids sherpa's map gives, and knows the words desktop knows", {
  skip: haveAssets ? false : "run web/hearing/scripts/fetch-web-voice.mjs",
}, () => {
  const text = readFileSync(new URL("lexicon-us-en.txt", assets), "utf8");
  const tokens = parseTokens(readFileSync(new URL("tokens.txt", assets), "utf8"));
  const chat = kokoroChatLexicon(parseKokoroLexicon(text));
  const built = loadLexicons([text, chatLexiconText(chat.additions)]);
  const { lexicon, maxPhraseLen } = unpackLexicon(packLexicon(built, tokens, { chatAdditions: chat.additions.size }), tokens);
  assert.equal(lexicon.size, built.lexicon.size);
  assert.equal(maxPhraseLen, built.maxPhraseLen);
  for (const [key, phones] of built.lexicon) {
    const expected = phones.map((phone) => tokens.get(phone)).filter((id) => id !== undefined);
    assert.deepEqual(lexicon.ids(key), expected, key);
  }
  // coverUnknownWords only asks about [a-z]+('[a-z]+)? words; for those the pack is desktop's known set.
  const word = /^[a-z]+(?:'[a-z]+)?$/;
  for (const key of chat.known) if (word.test(key)) assert.equal(lexicon.has(key), true, key);
  for (const key of built.lexicon.keys()) if (word.test(key)) assert.equal(chat.known.has(key), true, key);

  const fixture = JSON.parse(readFileSync(new URL("../fixtures/kokoro-tokens.json", import.meta.url), "utf8"));
  const frontend = { tokens, lexicon, maxPhraseLen };
  for (const reply of fixture.cases) {
    for (const piece of reply.pieces) {
      assert.equal(coverUnknownWords(piece.piece, lexicon), piece.spoken);
      assert.deepEqual(textToTokenIds(piece.spoken, frontend).map((ids) => ids.slice(1, ids.indexOf(0, 1))), piece.sentences, piece.spoken);
    }
  }
  assert.equal(KOKORO_WEB.files.lexicon.sha256, fixture.lexiconSha256);
});
