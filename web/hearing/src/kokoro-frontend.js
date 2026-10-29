/**
 * Kokoro text to token ids, ported from sherpa-onnx v2.0.0
 * (csrc/offline-tts-kokoro-impl.h lexicon path and csrc/text-utils.cc).
 * Desktop runs that C++ through the 2.0.0 jar, so the browser has to make the
 * same ids from the same text. fixtures/kokoro-tokens.json holds ids that
 * sherpa-onnx 2.0.0 logged for a corpus, and kokoro-frontend.test.js compares.
 *
 * Quirks kept on purpose, because desktop has them too:
 * - ParseLexiconFile overwrites the max phrase length per file, so the last
 *   lexicon (lexicon-chat.txt on desktop) decides it.
 * - A later lexicon entry replaces an earlier one with the same key.
 * - Short sentences are merged with no space ("Hi," + "I'm Sam." -> "Hi,I'm Sam.").
 * - The closing punctuation id is appended after the closing PAD.
 */

export const PAD_ID = 0;
export const MIN_WORDS_IN_SENTENCE = 5;
export const MAX_WORDS_IN_SENTENCE = 20;

/** csrc/symbol-table.cc ReadTokens. A line that is only an id is the space token. The first id wins. */
export function parseTokens(text) {
  const tokens = new Map();
  for (const raw of text.split("\n")) {
    const line = cTrim(raw);
    const fields = line.split(/[ \t\n\v\f\r]+/).filter((field) => field !== "");
    let symbol;
    let id;
    if (fields.length <= 1) {
      symbol = " ";
      id = Number.parseInt(fields[0] ?? "", 10) || 0;
    } else {
      symbol = fields[0];
      id = Number.parseInt(fields[1], 10);
    }
    if (!tokens.has(symbol)) tokens.set(symbol, id);
  }
  return tokens;
}

/** csrc/text-utils.cc ParseLexiconFile. */
export function parseLexiconFile(text) {
  const entries = [];
  let maxPhraseLen = 0;
  for (const line of text.split("\n")) {
    if (line === "" || line[0] === "#") continue;
    let key;
    let phonemes;
    const bars = line.indexOf("||");
    if (bars >= 0) {
      key = normalizeLexiconKey(cTrim(line.substring(0, bars)));
      phonemes = cFields(cTrim(line.substring(bars + 2)));
    } else {
      const fields = cFields(line);
      if (fields.length === 0) continue;
      key = normalizeLexiconKey(fields[0]);
      phonemes = fields.slice(1);
    }
    if (key === "" || phonemes.length === 0) continue;
    maxPhraseLen = Math.max(maxPhraseLen, countLexiconPhraseWords(key));
    const split = [];
    for (const phone of phonemes) {
      const points = [...phone];
      let keep = points.length <= 1;
      if (!keep) keep = /[0-9a-zA-Z]/.test(points[points.length - 1]);
      if (keep) split.push(phone);
      else split.push(...points);
    }
    entries.push({ key, phonemes: split });
  }
  return { entries, maxPhraseLen };
}

/** OfflineTtsKokoroImpl::LoadLexicon over each file in order. */
export function loadLexicons(texts) {
  const lexicon = new Map();
  let maxPhraseLen = 1;
  for (const text of texts) {
    const parsed = parseLexiconFile(text);
    for (const entry of parsed.entries) lexicon.set(entry.key, entry.phonemes);
    maxPhraseLen = parsed.maxPhraseLen;
  }
  return { lexicon, maxPhraseLen };
}

/**
 * One sherpa Generate() call, up to the model: the token ids of each sentence
 * the model runs, PAD-wrapped as sherpa passes them.
 */
export function textToTokenIds(text, frontend, options = {}) {
  const minWords = options.minWords ?? MIN_WORDS_IN_SENTENCE;
  const maxWords = options.maxWords ?? MAX_WORDS_IN_SENTENCE;
  const { tokens } = frontend;
  const normalized = replacePunctuations(text).normalize("NFC");
  let sentences = splitByAllPunctuation(normalized);
  if (sentences.length === 0) return [];
  sentences = mergeShortSentencesByWords(sentences, minWords);
  const chunks = [];
  for (const sentence of sentences) chunks.push(...splitLongSentenceByWords(sentence, maxWords));
  const merged = [];
  for (const chunk of chunks) {
    const points = [...cTrim(chunk)];
    const allPunct = points.length > 0 && points.every((ch) => ",.!?;: ".includes(ch));
    if (allPunct && merged.length > 0) merged[merged.length - 1] += cTrim(chunk);
    else merged.push(chunk);
  }
  const out = [];
  for (const sentence of merged) {
    const ids = tokenizeSentence(sentence, frontend);
    const trimmed = cTrim(sentence);
    if (trimmed !== "" && ids.length > 0) {
      const last = trimmed[trimmed.length - 1];
      if (isPunctuation(last)) {
        const punct = tokens.get(last);
        const sentenceIds = ids[ids.length - 1];
        if (punct !== undefined && sentenceIds[sentenceIds.length - 1] !== punct) sentenceIds.push(punct);
      }
    }
    out.push(...ids);
  }
  return out;
}

/** TokenizeFromLexiconStr: zero or one PAD-wrapped id list. */
export function tokenizeSentence(text, frontend) {
  const { tokens, lexicon, maxPhraseLen } = frontend;
  const normalized = replacePunctuations(text).normalize("NFC");
  const words = [];
  const points = [...normalized];
  let start = 0;
  let inWord = false;
  const flush = (end) => {
    if (inWord) {
      words.push(points.slice(start, end).join(""));
      inWord = false;
    }
  };
  for (let index = 0; index < points.length; index += 1) {
    const ch = points[index];
    if (ch === " " || ch === "\t" || ch === "\n" || ch === "\r") {
      flush(index);
      words.push(" ");
    } else if (isPunctuation(ch)) {
      flush(index);
      words.push(ch);
    } else if (CJK_PUNCT[ch]) {
      flush(index);
      words.push(CJK_PUNCT[ch]);
    } else if (isCjk(ch.codePointAt(0))) {
      flush(index);
      words.push(ch);
    } else if (!inWord) {
      start = index;
      inWord = true;
    }
  }
  flush(points.length);
  if (words.length === 0) return [];

  const ids = [];
  const n = words.length;
  let i = 0;
  while (i < n) {
    if (words[i] === " ") {
      const space = tokens.get(" ");
      if (space !== undefined) ids.push(space);
      i += 1;
      continue;
    }
    if (isPunctuation(words[i])) {
      const punct = tokens.get(words[i]);
      if (punct !== undefined) ids.push(punct);
      i += 1;
      continue;
    }
    let found = false;
    const maxTry = Math.min(maxPhraseLen, n - i);
    for (let len = maxTry; len >= 1; len -= 1) {
      let phrase = "";
      for (let j = i; j < i + len; j += 1) {
        const prevCjk = phrase !== "" && isCjk([...phrase].pop().codePointAt(0));
        const currCjk = words[j] !== "" && isCjk(words[j].codePointAt(0));
        if (j > i && !(prevCjk && currCjk)) phrase += " ";
        phrase += normalizeLexiconKey(words[j]);
      }
      const phones = lexicon.get(phrase);
      if (phones) {
        ids.push(...phonesToIds(phones, tokens));
        i += len;
        found = true;
        break;
      }
    }
    if (found) continue;
    const word = words[i];
    if (containsCjk(word)) {
      for (const ch of word) {
        const phones = lexicon.get(normalizeLexiconKey(ch));
        if (phones) ids.push(...phonesToIds(phones, tokens));
      }
    } else if (word.includes("-")) {
      for (const part of splitAndTrim(word, "-")) {
        const phones = lexicon.get(normalizeLexiconKey(part));
        if (phones) ids.push(...phonesToIds(phones, tokens));
      }
    }
    i += 1;
  }
  if (ids.length === 0) return [];
  return [[PAD_ID, ...ids, PAD_ID]];
}

function phonesToIds(phones, tokens) {
  const ids = [];
  for (const phone of phones) {
    const id = tokens.get(phone);
    if (id !== undefined) ids.push(id);
  }
  return ids;
}

const REPLACEMENTS = [
  ["，", ","], ["、", ","], ["；", ";"], ["：", ","], [":", ","],
  ["。", "."], ["？", "?"], ["！", "!"], ["…", "..."],
];

export function replacePunctuations(text) {
  let result = text;
  for (const [from, to] of REPLACEMENTS) result = result.split(from).join(to);
  return result;
}

const CJK_PUNCT = { "，": ",", "、": ",", "。": ".", "！": "!", "？": "?", "；": ";", "：": ",", "…": "..." };

export function isPunctuation(text) {
  return text.length === 1 && ",.;:!?".includes(text);
}

/** NFC, then the simple one-to-one lowercase per code point, then NFC (utf8proc). */
export function normalizeLexiconKey(text) {
  let lower = "";
  for (const ch of text.normalize("NFC")) lower += simpleLower(ch);
  return lower.normalize("NFC");
}

function simpleLower(ch) {
  const lower = ch.toLowerCase();
  if (lower === ch) return ch;
  return [...lower][0];
}

const WORD_CHAR = /[\p{L}\p{M}\p{N}]/u;

export function countLexiconPhraseWords(key) {
  let count = 0;
  let inRun = false;
  for (const ch of key) {
    if (WORD_CHAR.test(ch) && !isCjk(ch.codePointAt(0))) {
      if (!inRun) {
        inRun = true;
        count += 1;
      }
    } else {
      inRun = false;
      count += 1;
    }
  }
  return count;
}

export function isCjk(cp) {
  return (cp >= 0x1100 && cp <= 0x11ff) || (cp >= 0x2e80 && cp <= 0xa4cf) ||
    (cp >= 0xa840 && cp <= 0xd7af) || (cp >= 0xf900 && cp <= 0xfaff) ||
    (cp >= 0xfe30 && cp <= 0xfe4f) || (cp >= 0xff65 && cp <= 0xffdc) ||
    (cp >= 0x20000 && cp <= 0x2ffff);
}

function containsCjk(text) {
  for (const ch of text) if (isCjk(ch.codePointAt(0))) return true;
  return false;
}

const SENTENCE_BOUNDARY = new Set([".", "!", "?", "。", "！", "？"]);
const CHUNK_BOUNDARY = new Set([...SENTENCE_BOUNDARY, ",", ";", ":", "，", "；", "："]);
const WORD_BREAK = new Set([",", ";", ":", "，", "；", "："]);

function isSpace(ch) {
  return ch === " " || ch === "\t" || ch === "\n" || ch === "\r" || ch === "\f" || ch === "\v";
}

export function splitByAllPunctuation(text) {
  const sentences = [];
  let current = "";
  for (const ch of text) {
    current += ch;
    if (CHUNK_BOUNDARY.has(ch)) {
      const piece = cTrim(current);
      if (piece !== "") sentences.push(piece);
      current = "";
    }
  }
  const tail = cTrim(current);
  if (tail !== "") sentences.push(tail);
  return sentences;
}

export function countWords(text) {
  let count = 0;
  let inWord = false;
  for (const ch of text) {
    if (isCjk(ch.codePointAt(0)) || SENTENCE_BOUNDARY.has(ch)) {
      count += 1;
      inWord = false;
    } else if (isSpace(ch) || WORD_BREAK.has(ch)) {
      inWord = false;
    } else if (!inWord) {
      count += 1;
      inWord = true;
    }
  }
  return count;
}

export function mergeShortSentencesByWords(sentences, minWords) {
  const merged = [];
  let buffer = "";
  for (const sentence of sentences) {
    const piece = cTrim(sentence);
    if (piece === "") continue;
    buffer += piece;
    if (countWords(buffer) >= minWords) {
      merged.push(cTrim(buffer));
      buffer = "";
    }
  }
  if (buffer !== "") merged.push(cTrim(buffer));
  return merged;
}

export function splitLongSentenceByWords(sentence, maxWords) {
  const chunks = [];
  if (maxWords === 0) return chunks;
  const trimmed = cTrim(sentence);
  if (trimmed === "") return chunks;
  const points = [...trimmed];
  const len = points.length;
  let start = 0;
  while (start < len) {
    let wordCount = 0;
    let inWord = false;
    let end = start;
    for (let i = start; i < len; i += 1) {
      const ch = points[i];
      if (isCjk(ch.codePointAt(0)) || SENTENCE_BOUNDARY.has(ch)) {
        wordCount += 1;
        inWord = false;
      } else if (isSpace(ch) || WORD_BREAK.has(ch)) {
        inWord = false;
      } else if (!inWord) {
        wordCount += 1;
        inWord = true;
      }
      if (wordCount > maxWords) break;
      end = i + 1;
    }
    if (end >= len) {
      const piece = cTrim(points.slice(start).join(""));
      if (piece !== "") chunks.push(piece);
      break;
    }
    let split = end;
    let found = false;
    for (let i = end; i > start; i -= 1) {
      const ch = points[i - 1];
      if (isSpace(ch)) {
        split = i - 1;
        found = true;
        break;
      }
      if (CHUNK_BOUNDARY.has(ch)) {
        split = i;
        found = true;
        break;
      }
    }
    if (!found || split <= start) split = end;
    const piece = cTrim(points.slice(start, split).join(""));
    if (piece !== "") chunks.push(piece);
    start = split;
    while (start < len && isSpace(points[start])) start += 1;
  }
  return chunks;
}

/** SplitStringAndTrim: split on one character, trim each part, drop empty parts. */
function splitAndTrim(text, delimiter) {
  return text.split(delimiter).map(cTrim).filter((part) => part !== "");
}

/** C isspace in the "C" locale: only these six ASCII bytes. */
function cTrim(text) {
  let start = 0;
  let end = text.length;
  while (start < end && isSpace(text[start])) start += 1;
  while (end > start && isSpace(text[end - 1])) end -= 1;
  return text.substring(start, end);
}

function cFields(text) {
  return text.split(/[ \t\n\v\f\r]+/).filter((field) => field !== "");
}
