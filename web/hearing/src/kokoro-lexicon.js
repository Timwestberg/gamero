/**
 * The browser port of gamero voice/commonMain KokoroLexicon.kt. The Kokoro
 * lexicon has no espeak fallback, so a missing word is silent. Regular
 * inflections of a known stem are added (desktop writes them to
 * lexicon-chat.txt), and anything still unknown is spelled with letters.
 */

import { isKtWhitespace, ktTrim } from "./speech-text.js";

/** First pronunciation wins, as on desktop. Blank and # lines are skipped. */
export function parseKokoroLexicon(text) {
  const words = new Map();
  for (const line of text.split(/\r\n|\n|\r/)) {
    const trimmed = ktTrim(line);
    if (trimmed === "" || trimmed.startsWith("#")) continue;
    const space = trimmed.indexOf(" ");
    if (space <= 0) continue;
    const word = trimmed.substring(0, space).toLowerCase();
    const phones = ktTrim(trimmed.substring(space + 1));
    if (word === "" || phones === "" || words.has(word)) continue;
    words.set(word, phones);
  }
  return words;
}

/** Inflections that are not already in base. The base pronunciation is kept. */
export function inflectKokoroLexicon(base) {
  const extra = new Map();
  const known = new Set(base.keys());
  for (const [word, phones] of base) {
    if (word.length < 2 || !/^[a-z]+$/.test(word)) continue;
    addInflection(extra, known, pluralOf(word, phones));
    addInflection(extra, known, pastOf(word, phones));
    addInflection(extra, known, gerundOf(word, phones));
  }
  return extra;
}

export function kokoroChatLexicon(base) {
  const additions = inflectKokoroLexicon(base);
  const known = new Set(base.keys());
  for (const word of additions.keys()) known.add(word);
  return { additions, known };
}

/** lexicon-chat.txt as desktop writes it: one "word phones" line per addition. */
export function chatLexiconText(additions) {
  let out = "";
  for (const [word, phones] of additions) out += `${word} ${phones}\n`;
  return out;
}

const SPOKEN_WORD = /[A-Za-z]+(?:'[A-Za-z]+)?/g;

/**
 * Replaces a word the lexicon cannot say with its letters. A known word,
 * including an added inflection, is left as written.
 */
export function coverUnknownWords(text, known) {
  return text.replace(SPOKEN_WORD, (token) => {
    if (known.has(token.toLowerCase())) return token;
    return [...token].filter((ch) => /[A-Za-z]/.test(ch)).map((ch) => ch.toLowerCase()).join(" ");
  });
}

function addInflection(extra, known, form) {
  const [word, phones] = form;
  if (known.has(word) || word === "" || ktTrim(phones) === "") return;
  extra.set(word, phones);
  known.add(word);
}

function pluralOf(word, phones) {
  let spelling;
  if (word.endsWith("s") || word.endsWith("x") || word.endsWith("z") || word.endsWith("ch") || word.endsWith("sh")) {
    spelling = `${word}es`;
  } else if (word.endsWith("y") && word.length >= 2 && !isVowel(word[word.length - 2])) {
    spelling = `${word.slice(0, -1)}ies`;
  } else {
    spelling = `${word}s`;
  }
  const last = lastPhone(phones);
  let suffix;
  if (SIBILANTS.has(last) || word.endsWith("ch") || word.endsWith("sh") || word.endsWith("x") || word.endsWith("s") || word.endsWith("z")) {
    suffix = "ᵻ z";
  } else if (VOICELESS.has(last)) {
    suffix = "s";
  } else {
    suffix = "z";
  }
  return [spelling, `${trimEnd(phones)} ${suffix}`];
}

function pastOf(word, phones) {
  let spelling;
  if (word.endsWith("e")) spelling = `${word}d`;
  else if (word.endsWith("y") && word.length >= 2 && !isVowel(word[word.length - 2])) spelling = `${word.slice(0, -1)}ied`;
  else spelling = `${word}ed`;
  const last = lastPhone(phones);
  let suffix;
  if (last === "t" || last === "d") suffix = "ᵻ d";
  else if (VOICELESS.has(last) || last === "s" || last === "ʃ" || last === "ʧ") suffix = "t";
  else suffix = "d";
  return [spelling, `${trimEnd(phones)} ${suffix}`];
}

function gerundOf(word, phones) {
  let spelling;
  if (word.endsWith("ie") && word.length > 2) spelling = `${word.slice(0, -2)}ying`;
  else if (word.endsWith("e") && !word.endsWith("ee") && !word.endsWith("ye") && !word.endsWith("oe")) spelling = `${word.slice(0, -1)}ing`;
  else if (shouldDouble(word)) spelling = `${word}${word[word.length - 1]}ing`;
  else spelling = `${word}ing`;
  return [spelling, `${trimEnd(phones)} ɪ ŋ`];
}

function shouldDouble(word) {
  if (word.length < 3 || word.endsWith("y") || word.endsWith("w") || word.endsWith("x")) return false;
  if (!isVowel(word[word.length - 2]) || isVowel(word[word.length - 1])) return false;
  if (isVowel(word[word.length - 3])) return false;
  return [...word].filter(isVowel).length === 1;
}

function isVowel(ch) {
  return ch === "a" || ch === "e" || ch === "i" || ch === "o" || ch === "u";
}

function lastPhone(phones) {
  const parts = ktTrim(phones).split(" ");
  for (let index = parts.length - 1; index >= 0; index -= 1) {
    const token = parts[index];
    if (token !== "" && !STRESS.has(token)) return token;
  }
  return "";
}

/** Kotlin trimEnd(), which trims Char.isWhitespace. */
function trimEnd(text) {
  let end = text.length;
  while (end > 0 && isKtWhitespace(text[end - 1])) end -= 1;
  return text.substring(0, end);
}

const SIBILANTS = new Set(["s", "z", "ʃ", "ʒ", "ʧ", "ʤ"]);
const VOICELESS = new Set(["p", "t", "k", "f", "θ"]);
const STRESS = new Set(["ˈ", "ˌ", "ː", "ʰ", "ʲ"]);
