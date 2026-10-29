/**
 * The browser port of gamero voice/commonMain SpeechText.kt. Splits a reply
 * into the pieces Kokoro reads. Numbers, versions, units, URLs, and filenames
 * become words before any split. fixtures/speech-text.json is written by the
 * Kotlin side, so a drift fails speech-text.test.js.
 *
 * Kotlin walks UTF-16 code units and uses JVM character classes. This file
 * does the same, so an emoji is two "symbol" words here as it is on the Mac.
 */

/** A sentence this long is split on the last space at or before the limit. */
export const SENTENCE_SAFETY_CHARS = 400;

export function speechPieces(text, longSentenceMaxChars = SENTENCE_SAFETY_CHARS) {
  const spoken = normalizeForSpeech(text);
  const pieces = [];
  for (const sentence of splitSentences(spoken)) {
    pieces.push(...splitLongSentence(sentence, longSentenceMaxChars));
  }
  return pieces.filter((piece) => piece.length > 0);
}

export function splitLongSentence(sentence, maxChars) {
  if (maxChars < 1 || sentence.length <= maxChars) return [sentence];
  const pieces = [];
  let rest = sentence;
  while (rest.length > maxChars) {
    const cut = wordBoundary(rest, maxChars);
    if (cut <= 0 || cut >= rest.length) {
      pieces.push(rest);
      return pieces;
    }
    pieces.push(ktTrim(rest.substring(0, cut)));
    rest = ktTrim(rest.substring(cut));
    if (rest.length === 0) return pieces;
  }
  pieces.push(rest);
  return pieces;
}

function wordBoundary(text, maxChars) {
  const limit = Math.min(maxChars, text.length);
  const space = text.lastIndexOf(" ", limit);
  return space >= 1 ? space : limit;
}

/**
 * Turns text into words Kokoro will keep. A token this pass does not know is
 * spelled letter by letter and digit by digit. Nothing is deleted.
 */
export function normalizeForSpeech(text) {
  let out = "";
  let index = 0;
  while (index < text.length) {
    const url = matchUrl(text, index);
    if (url) {
      out = appendWord(out, speakUrl(url.value));
      index += url.length;
      continue;
    }
    const time = matchHere(TIME, text, index);
    if (time != null) {
      out = appendWord(out, normalizeTime(time));
      index += time.length;
      continue;
    }
    const file = matchHere(FILE, text, index);
    if (file != null) {
      out = appendWord(out, normalizeFile(file));
      index += file.length;
      continue;
    }
    const unit = matchUnit(text, index);
    if (unit) {
      out = appendWord(out, unit.words);
      index += unit.length;
      continue;
    }
    const version = matchHere(VERSION, text, index);
    if (version != null) {
      out = appendWord(out, speakVersion(version));
      index += version.length;
      continue;
    }
    const coded = matchCoded(text, index);
    if (coded != null) {
      out = appendWord(out, spellCoded(coded));
      index += coded.length;
      continue;
    }
    const integer = matchInteger(text, index);
    if (integer) {
      out = appendWord(out, integer.words);
      index += integer.length;
      continue;
    }
    const ch = text[index];
    const symbol = SYMBOLS[ch];
    if (symbol !== undefined) {
      out = appendWord(out, symbol);
      index += 1;
      continue;
    }
    if (isDigit(ch)) {
      out = appendWord(out, numberWords(digitValue(ch)));
      index += 1;
      continue;
    }
    if (ch.charCodeAt(0) > 127 && !isLetter(ch)) {
      out = appendWord(out, "symbol");
      index += 1;
      continue;
    }
    out += ch;
    index += 1;
  }
  return out;
}

// java.util.regex without UNICODE_CHARACTER_CLASS: \d is [0-9], \s is these six.
const JS = "[ \\t\\n\\x0B\\f\\r]";
const URL_RE = new RegExp(`https?://[^ \\t\\n\\x0B\\f\\r]+`, "iy");
const TIME = new RegExp(`\\d{1,2}:\\d{2}${JS}*[AaPp][Mm]`, "y");
const FILE = /[A-Za-z][A-Za-z0-9]*\.(?:kt|kts|md|json|txt|wav)/iy;
const UNIT = new RegExp(`(\\d+(?:\\.\\d+)?)${JS}*(kHz|MHz|GHz|Hz|ms|msec|KB|MB|GB|TB|px|%)`, "iy");
const GLUED_SECONDS = /(\d+(?:\.\d+)?)s(?![A-Za-z])/y;
const VERSION = /[vV]?\d+(?:\.\d+)+(?:-(?:rc|alpha|beta|preview)\d*)?/y;
const VERSION_PARTS = /([vV])?(\d+(?:\.\d+)+)(?:-(rc|alpha|beta|preview)(\d*))?/;

const SYMBOLS = {
  "@": "at",
  "#": "hash",
  $: "dollar",
  "%": "percent",
  "&": "and",
  "*": "star",
  "+": "plus",
  "/": "slash",
  "\\": "backslash",
  "=": "equals",
  _: "underscore",
  "^": "caret",
  "~": "tilde",
  "|": "bar",
  "<": "less than",
  ">": "greater than",
};

/** Kotlin's Regex.find(text, start) that must land on start. A sticky regex is the same test. */
function matchHere(regex, text, start) {
  regex.lastIndex = start;
  const found = regex.exec(text);
  return found ? found[0] : null;
}

function matchUrl(text, start) {
  const raw = matchHere(URL_RE, text, start);
  if (raw == null) return null;
  let end = raw.length;
  while (end > 0 && ".,;:!?)]\"'".includes(raw[end - 1])) end -= 1;
  if (end < "http://a".length) return null;
  return { value: raw.substring(0, end), length: end };
}

function matchUnit(text, start) {
  const seconds = matchHere(GLUED_SECONDS, text, start);
  if (seconds != null) {
    const number = seconds.slice(0, -1);
    return { words: `${speakUnitNumber(number)} seconds`, length: seconds.length };
  }
  UNIT.lastIndex = start;
  const found = UNIT.exec(text);
  if (!found) return null;
  return { words: `${speakUnitNumber(found[1])} ${unitName(found[2])}`, length: found[0].length };
}

function speakUnitNumber(raw) {
  return raw.includes(".") ? speakDecimal(raw) : speakInteger(raw);
}

function unitName(raw) {
  switch (raw.toLowerCase()) {
    case "ms":
    case "msec":
      return "milliseconds";
    case "hz":
      return "hertz";
    case "khz":
      return "kilohertz";
    case "mhz":
      return "megahertz";
    case "ghz":
      return "gigahertz";
    case "kb":
      return "kilobytes";
    case "mb":
      return "megabytes";
    case "gb":
      return "gigabytes";
    case "tb":
      return "terabytes";
    case "px":
      return "pixels";
    case "%":
      return "percent";
    default:
      return spellCoded(raw);
  }
}

function matchCoded(text, start) {
  if (start >= text.length) return null;
  const first = text[start];
  if (!isLetter(first) && !isDigit(first)) return null;
  let cursor = start;
  let letters = 0;
  let digits = 0;
  while (cursor < text.length) {
    const ch = text[cursor];
    if (isLetter(ch)) letters += 1;
    else if (isDigit(ch)) digits += 1;
    else if ((ch === "-" || ch === "_") && cursor + 1 < text.length && isLetterOrDigit(text[cursor + 1])) {
      // a joiner inside the token
    } else break;
    cursor += 1;
  }
  if (letters === 0 || digits === 0 || cursor === start) return null;
  return text.substring(start, cursor);
}

function matchInteger(text, start) {
  if (start >= text.length || !isDigit(text[start])) return null;
  let cursor = start;
  while (cursor < text.length && isDigit(text[cursor])) cursor += 1;
  const raw = text.substring(start, cursor);
  return { words: speakInteger(raw), length: raw.length };
}

function speakInteger(raw) {
  if (raw.length > 1 && raw.startsWith("0")) return spellDigits(raw);
  if (raw.length > 9) return spellDigits(raw);
  const value = digitsValue(raw);
  return value > 999_999 ? spellDigits(raw) : numberWords(value);
}

function speakDecimal(raw) {
  return raw.split(".").map((part) => speakInteger(part)).join(" point ");
}

function speakVersion(token) {
  const match = VERSION_PARTS.exec(token);
  if (!match) return spellCoded(token);
  const head = match[1] ? "vee " : "";
  const numbers = speakDecimal(match[2]);
  const tag = match[3] || "";
  const tagNumber = match[4] || "";
  let suffix = "";
  if (tag === "rc") suffix = ` release candidate${spokenTagNumber(tagNumber)}`;
  else if (tag !== "") suffix = ` ${tag}${spokenTagNumber(tagNumber)}`;
  return head + numbers + suffix;
}

function spokenTagNumber(raw) {
  return raw === "" ? "" : ` ${speakInteger(raw)}`;
}

const URL_SEPARATORS = {
  ".": "dot",
  "/": "slash",
  "?": "question",
  "&": "and",
  "=": "equals",
  "#": "hash",
  ":": "colon",
  "-": "dash",
  _: "underscore",
};

function speakUrl(url) {
  let rest = url.replace(/^https?:\/\//i, "");
  if (rest.startsWith("www.")) rest = rest.substring(4);
  let out = "";
  let token = "";
  const flush = () => {
    if (token === "") return;
    out = appendWord(out, speakUrlToken(token));
    token = "";
  };
  for (const ch of splitUnits(rest)) {
    const word = URL_SEPARATORS[ch];
    if (word) {
      flush();
      out = appendWord(out, word);
    } else {
      token += ch;
    }
  }
  flush();
  return out;
}

function speakUrlToken(token) {
  const version = matchHere(VERSION, token, 0);
  if (version != null && version.length === token.length) return speakVersion(token);
  if ([...splitUnits(token)].every(isDigit)) return speakInteger(token);
  if (matchCoded(token, 0) === token) return spellCoded(token);
  return token;
}

function spellCoded(token) {
  const parts = [];
  let digits = "";
  const flushDigits = () => {
    if (digits === "") return;
    parts.push(speakInteger(digits));
    digits = "";
  };
  for (const ch of splitUnits(token)) {
    if (isDigit(ch)) digits += ch;
    else if (isLetter(ch)) {
      flushDigits();
      parts.push(letterName(ch));
    } else if (ch === "-") {
      flushDigits();
      parts.push("dash");
    } else if (ch === "_") {
      flushDigits();
      parts.push("underscore");
    } else {
      flushDigits();
      parts.push(SYMBOLS[ch] ?? "symbol");
    }
  }
  flushDigits();
  return parts.join(" ");
}

function spellDigits(raw) {
  return [...splitUnits(raw)].map((ch) => numberWords(digitValue(ch))).join(" ");
}

const LETTER_NAMES = {
  a: "ay", b: "bee", c: "see", d: "dee", e: "ee", f: "ef", g: "gee", h: "aitch", i: "eye",
  j: "jay", k: "kay", l: "el", m: "em", n: "en", o: "oh", p: "pee", q: "cue", r: "ar",
  s: "ess", t: "tee", u: "you", v: "vee", w: "double you", x: "ex", y: "why", z: "zee",
};

function letterName(ch) {
  return LETTER_NAMES[lowercaseChar(ch)] ?? "symbol";
}

function appendWord(out, word) {
  if (word.length === 0) return out;
  if (out.length > 0 && !isKtWhitespace(out[out.length - 1])) out += " ";
  return out + word;
}

const ONES = [
  "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
  "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
  "seventeen", "eighteen", "nineteen",
];

const TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];

function numberWords(value) {
  if (value < 0) return String(value);
  if (value < 20) return ONES[value];
  if (value < 100) {
    const tens = Math.floor(value / 10);
    const rest = value % 10;
    return rest === 0 ? TENS[tens] : `${TENS[tens]}-${ONES[rest]}`;
  }
  if (value < 1000) {
    const head = `${ONES[Math.floor(value / 100)]} hundred`;
    const rest = value % 100;
    return rest === 0 ? head : `${head} ${numberWords(rest)}`;
  }
  if (value < 1_000_000) {
    const head = `${numberWords(Math.floor(value / 1000))} thousand`;
    const rest = value % 1000;
    return rest === 0 ? head : `${head} ${numberWords(rest)}`;
  }
  return spellDigits(String(value));
}

function normalizeFile(token) {
  const dot = token.lastIndexOf(".");
  return `${splitCamel(token.substring(0, dot))} dot ${token.substring(dot + 1).toLowerCase()}`;
}

function splitCamel(stem) {
  const spaced = stem.replace(/([a-z0-9])([A-Z])/g, "$1 $2");
  return spaced.replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2");
}

function normalizeTime(token) {
  const match = new RegExp(`(\\d{1,2}):(\\d{2})${JS}*([AaPp][Mm])`).exec(token);
  if (!match) return token;
  const hour = numberWords(Number(match[1]));
  const minute = Number(match[2]);
  const ampm = match[3].toUpperCase();
  if (minute === 0) return `${hour} ${ampm}`;
  if (minute < 10) return `${hour} oh ${numberWords(minute)} ${ampm}`;
  return `${hour} ${numberWords(minute)} ${ampm}`;
}

function splitSentences(text) {
  const sentences = [];
  let current = "";
  for (const ch of splitUnits(text)) {
    current += ch;
    if (ch === "." || ch === "!" || ch === "?") {
      const piece = ktTrim(current);
      if (piece.length > 0) sentences.push(piece);
      current = "";
    }
  }
  const tail = ktTrim(current);
  if (tail.length > 0) sentences.push(tail);
  return sentences;
}

// JVM character classes, one UTF-16 code unit at a time.

const LETTER = /\p{L}/u;
const DECIMAL_DIGIT = /\p{Nd}/u;
const SPACE_SEPARATOR = /[\p{Zs}\u2028\u2029]/u;

/** Kotlin String iteration: UTF-16 code units, not code points. */
function* splitUnits(text) {
  for (let i = 0; i < text.length; i += 1) yield text[i];
}

/** Char.isLetter: a lone surrogate is not a letter. */
export function isLetter(ch) {
  return LETTER.test(ch);
}

/** Char.isDigit: any Unicode decimal digit. */
export function isDigit(ch) {
  return DECIMAL_DIGIT.test(ch);
}

function isLetterOrDigit(ch) {
  return isLetter(ch) || isDigit(ch);
}

/** Char.isWhitespace on the JVM: Character.isWhitespace or Character.isSpaceChar. */
export function isKtWhitespace(ch) {
  const code = ch.charCodeAt(0);
  if (code === 0x09 || code === 0x0a || code === 0x0b || code === 0x0c || code === 0x0d) return true;
  if (code >= 0x1c && code <= 0x1f) return true;
  return SPACE_SEPARATOR.test(ch);
}

/** String.trim() in Kotlin, which trims Char.isWhitespace. */
export function ktTrim(text) {
  let start = 0;
  let end = text.length;
  while (start < end && isKtWhitespace(text[start])) start += 1;
  while (end > start && isKtWhitespace(text[end - 1])) end -= 1;
  return text.substring(start, end);
}

/** Char.lowercaseChar: the simple mapping. Only U+0130 lowercases to two units, and its simple form is the first. */
function lowercaseChar(ch) {
  return ch.toLowerCase()[0];
}

/**
 * Char.digitToInt for any Nd character. Unicode lays decimal digits out in
 * runs of ten that start at zero, so the value is the offset in its run.
 */
function digitValue(ch) {
  const code = ch.charCodeAt(0);
  if (code >= 0x30 && code <= 0x39) return code - 0x30;
  let start = code;
  while (start > 0 && DECIMAL_DIGIT.test(String.fromCharCode(start - 1))) start -= 1;
  return (code - start) % 10;
}

function digitsValue(raw) {
  let value = 0;
  for (const ch of splitUnits(raw)) value = value * 10 + digitValue(ch);
  return value;
}
