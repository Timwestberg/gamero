import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import test from "node:test";
import { fadeEdge, shapeSentence, trimEdgeSilence } from "../src/kokoro-audio.js";
import { loadLexicons, parseTokens, textToTokenIds } from "../src/kokoro-frontend.js";
import { chatLexiconText, coverUnknownWords, kokoroChatLexicon, parseKokoroLexicon } from "../src/kokoro-lexicon.js";
import { SENTENCE_SAFETY_CHARS, normalizeForSpeech, speechPieces, splitLongSentence } from "../src/speech-text.js";

const read = (name) => JSON.parse(readFileSync(new URL(`../fixtures/${name}`, import.meta.url), "utf8"));
const speech = read("speech-text.json");
const tokens = read("kokoro-tokens.json");
const assets = new URL("../../voice-assets/", import.meta.url);
const haveAssets = existsSync(new URL("lexicon-us-en.txt", assets)) && existsSync(new URL("tokens.txt", assets));

test("the normalizer says what desktop SpeechText.kt says", () => {
  assert.equal(SENTENCE_SAFETY_CHARS, speech.sentenceSafetyChars);
  assert.ok(speech.normalize.length >= 20);
  for (const row of speech.normalize) {
    assert.equal(normalizeForSpeech(row.input), row.normalized, JSON.stringify(row.input));
    assert.deepEqual(speechPieces(row.input), row.pieces, JSON.stringify(row.input));
  }
  for (const row of speech.longSentence) {
    assert.deepEqual(splitLongSentence(row.input, row.max), row.pieces, JSON.stringify(row));
  }
});

test("the chat lexicon adds the same inflections and covers the same words as KokoroLexicon.kt", () => {
  const base = parseKokoroLexicon(speech.lexicon.text);
  assert.deepEqual([...base], speech.lexicon.parsed);
  const chat = kokoroChatLexicon(base);
  assert.deepEqual([...chat.additions], speech.lexicon.additions);
  for (const row of speech.lexicon.cover) {
    assert.equal(coverUnknownWords(row.text, chat.known), row.out, row.text);
  }
});

test("sentence shaping matches Speech.kt trim and fade sample for sample", () => {
  for (const row of speech.shape) {
    const out = shapeSentence(Float32Array.from(row.input), row.threshold, row.fade, row.fadeHead, row.fadeTail);
    assert.deepEqual([...out], row.out.map(Math.fround), JSON.stringify(row.input));
  }
  assert.deepEqual([...trimEdgeSilence(Float32Array.of(0.001, 0.002))], [Math.fround(0.001), Math.fround(0.002)]);
  assert.equal(fadeEdge(Float32Array.of(1), 4, true, true).length, 1);
});

test("Kokoro token ids match the ids sherpa-onnx 2.0.0 logged for the same replies", () => {
  assert.equal(tokens.sherpaOnnx, "2.0.0");
  assert.equal(tokens.maxPhraseLen, 1, "desktop loads lexicon-chat.txt last, so sherpa's phrase length is 1");
  const frontend = {
    tokens: parseTokens(tokens.tokens),
    lexicon: new Map(tokens.lexicon),
    maxPhraseLen: tokens.maxPhraseLen,
  };
  const known = new Set(tokens.known);
  let sentences = 0;
  for (const reply of tokens.cases) {
    assert.deepEqual(speechPieces(reply.text), reply.pieces.map((piece) => piece.piece), reply.text);
    for (const piece of reply.pieces) {
      const spoken = coverUnknownWords(piece.piece, known);
      assert.equal(spoken, piece.spoken, piece.piece);
      const ids = textToTokenIds(spoken, frontend);
      assert.deepEqual(ids.map(inner), piece.sentences, piece.spoken);
      sentences += ids.length;
    }
  }
  assert.ok(sentences >= 25, `${sentences} sentences checked`);
});

test("the full lexicon files build the same frontend sherpa loaded", { skip: haveAssets ? false : "run web/hearing/scripts/fetch-web-voice.mjs" }, () => {
  const lexiconText = readFileSync(new URL("lexicon-us-en.txt", assets), "utf8");
  const chatFixture = read("kokoro-lexicon-chat.json");
  const chat = kokoroChatLexicon(parseKokoroLexicon(lexiconText));
  const chatText = chatLexiconText(chat.additions);
  assert.equal(sha256(lexiconText), chatFixture.lexiconSha256);
  assert.equal(chat.additions.size, chatFixture.additions);
  assert.equal(chat.known.size, chatFixture.known);
  assert.equal(sha256(chatText), chatFixture.lexiconChatSha256, "lexicon-chat.txt is byte-identical to desktop's");
  const { lexicon, maxPhraseLen } = loadLexicons([lexiconText, chatText]);
  assert.equal(maxPhraseLen, tokens.maxPhraseLen);
  assert.equal(lexicon.size, 661766, "sherpa logged 661766 entries");
  const frontend = { tokens: parseTokens(readFileSync(new URL("tokens.txt", assets), "utf8")), lexicon, maxPhraseLen };
  for (const reply of tokens.cases) {
    for (const piece of reply.pieces) {
      assert.equal(coverUnknownWords(piece.piece, chat.known), piece.spoken);
      assert.deepEqual(textToTokenIds(piece.spoken, frontend).map(inner), piece.sentences, piece.spoken);
    }
  }
});

/** sherpa logs the ids before it adds PAD at both ends and the closing punctuation after the last PAD. */
function inner(ids) {
  return ids.slice(1, ids.indexOf(0, 1));
}

function sha256(text) {
  return createHash("sha256").update(text).digest("hex");
}
