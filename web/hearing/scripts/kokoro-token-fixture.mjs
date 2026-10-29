#!/usr/bin/env node
/**
 * Writes fixtures/kokoro-tokens.json: the Kokoro token ids sherpa-onnx 2.0.0
 * (the version desktop links) makes for a corpus of replies. It runs the
 * desktop steps (speechPieces, coverUnknownWords, then sherpa's Generate with
 * lexicon-us-en.txt,lexicon-chat.txt) through the npm sherpa-onnx 2.0.0 WASM
 * build with debug on, and reads the "Sentence N tokens: [...]" lines.
 *
 * The fixture carries the lexicon entries and known words the corpus touches,
 * so kokoro-frontend.test.js runs without the 6 MB lexicon.
 *
 * Needs web/voice-assets (fetch-web-voice.mjs) for model.onnx, tokens and lexicon.
 * usage: node web/hearing/scripts/kokoro-token-fixture.mjs
 */

import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadLexicons, normalizeLexiconKey, parseTokens } from "../src/kokoro-frontend.js";
import { chatLexiconText, coverUnknownWords, kokoroChatLexicon, parseKokoroLexicon } from "../src/kokoro-lexicon.js";
import { speechPieces } from "../src/speech-text.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const hearing = path.resolve(here, "..");
const assets = path.resolve(hearing, "../voice-assets");
const require = createRequire(path.join(hearing, "package.json"));

export const TOKEN_CORPUS = [
  "Hi, I'm Sam.",
  "Hi, I'm Ember. Sonnet 5.5 answered in 160 ms. What should we build next?",
  "The weather is fine today, and I think we should take the long way home.",
  "Sure! I can help with that: first, open the catalog; then pick a companion.",
  "Wait... what?! Really?",
  "That's twenty-one questions, and the boxes are well-known state-of-the-art.",
  "I'd say it's about 3:05 PM, so we've got time.",
  "Open https://github.com/Timwestberg/gamero/pull/63 to see the change.",
  "Edit DesktopSpeech.kt and voices-blends.json, then run v2.0.1-rc2.",
  "Questions about the boxes, cities, and plays were answered quickly.",
  "Xyzzy and Hovel and Gamero are names the lexicon may not know.",
  "Emoji \u{1F389} and “quotes” — dashes… and (parentheses) [brackets].",
  "Café naïve résumé co-operate e-mail U.S. A.I.",
  "One two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one twenty-two.",
  "Yes.",
  "OK, go.",
  "Hmm; let me think: maybe, perhaps, or not.",
  "I barged in, so Ember stopped speaking and listened again.",
];

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function idsFromLog(line) {
  const list = line.slice(line.indexOf("tokens: [") + "tokens: [".length);
  return [...list.matchAll(/\((\d+)\)(?=, |\]$)/g)].map((match) => Number(match[1]));
}

function main() {
  const lexiconText = readFileSync(path.join(assets, "lexicon-us-en.txt"), "utf8");
  const tokensText = readFileSync(path.join(assets, "tokens.txt"), "utf8");
  const chat = kokoroChatLexicon(parseKokoroLexicon(lexiconText));
  const chatText = chatLexiconText(chat.additions);
  const scratch = mkdtempSync(path.join(os.tmpdir(), "kokoro-tokens-"));
  const chatFile = path.join(scratch, "lexicon-chat.txt");
  writeFileSync(chatFile, chatText);

  // The NODERAWFS build can write its log straight to fd 1 and 2, so catch it there too.
  const lines = [];
  const fs = require("node:fs");
  const writeSync = fs.writeSync;
  fs.writeSync = (fd, buffer, offset, length, ...rest) => {
    if (fd !== 1 && fd !== 2) return writeSync(fd, buffer, offset, length, ...rest);
    const bytes = typeof buffer === "string" ? Buffer.from(buffer) : Buffer.from(buffer.buffer, buffer.byteOffset + (offset ?? 0), length ?? buffer.byteLength);
    lines.push(bytes.toString("utf8"));
    return bytes.byteLength;
  };
  const Module = { print: (text) => lines.push(String(text)), printErr: (text) => lines.push(String(text)) };
  require("sherpa-onnx/sherpa-onnx-wasm-nodejs.js")(Module);
  const { createOfflineTts } = require("sherpa-onnx/sherpa-onnx-tts.js");
  const model = path.join(assets, ".upstream/model.onnx");
  const tts = createOfflineTts(Module, {
    offlineTtsModelConfig: {
      offlineTtsKokoroModelConfig: {
        model,
        voices: path.join(assets, ".upstream/voices.bin"),
        tokens: path.join(assets, "tokens.txt"),
        lexicon: `${path.join(assets, "lexicon-us-en.txt")},${chatFile}`,
        dataDir: "",
        lengthScale: 1.0,
      },
      numThreads: 1,
      debug: 1,
      provider: "cpu",
    },
    ruleFsts: "",
    ruleFars: "",
    maxNumSentences: 1,
  });
  // One "Loaded lexicon" line per file; sherpa keeps the last file's value.
  const loads = [...lines.join("\n").matchAll(/max phrase len (\d+)/g)];
  if (loads.length === 0) throw new Error("sherpa did not log the lexicon load; is debug on?");
  const maxPhraseLen = Number(loads.at(-1)[1]);

  const { lexicon } = loadLexicons([lexiconText, chatText]);
  const subset = new Map();
  const knownSubset = new Set();
  const cases = [];
  for (const text of TOKEN_CORPUS) {
    const pieces = [];
    for (const piece of speechPieces(text)) {
      const spoken = coverUnknownWords(piece, chat.known);
      for (const match of piece.matchAll(/[A-Za-z]+(?:'[A-Za-z]+)?/g)) {
        const key = match[0].toLowerCase();
        if (chat.known.has(key)) knownSubset.add(key);
      }
      for (const word of spoken.split(/[ \t\n\r,.!?;:]+/)) {
        for (const part of [word, ...word.split("-")]) {
          const key = normalizeLexiconKey(part.trim());
          if (key !== "" && lexicon.has(key)) subset.set(key, lexicon.get(key));
        }
      }
      lines.length = 0;
      const audio = tts.generate({ text: spoken, sid: 50, speed: 1.0 });
      const sentences = lines.join("").split("\n").filter((line) => line.includes(" tokens: [")).map(idsFromLog);
      pieces.push({ piece, spoken, sentences, samples: audio.samples.length });
    }
    cases.push({ text, pieces });
    process.stdout.write(".");
  }
  tts.free?.();
  fs.writeSync = writeSync;
  const fixture = {
    schema: "ember-kokoro-tokens.v1",
    sherpaOnnx: "2.0.0",
    modelSha256: sha256(readFileSync(model)),
    lexiconSha256: sha256(lexiconText),
    lexiconChatSha256: sha256(chatText),
    tokensSha256: sha256(tokensText),
    maxPhraseLen,
    tokens: tokensText,
    lexicon: [...subset].map(([key, phones]) => [key, phones]),
    known: [...knownSubset].sort(),
    cases,
  };
  const out = path.join(hearing, "fixtures/kokoro-tokens.json");
  writeFileSync(out, `${JSON.stringify(fixture, null, 1)}\n`);
  console.log(`\nwrote ${path.relative(process.cwd(), out)}: ${cases.length} replies, max phrase len ${maxPhraseLen}`);
}

main();
