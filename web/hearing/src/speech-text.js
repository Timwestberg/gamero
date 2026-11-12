/**
 * The browser port of gamero voice/commonMain SpeechText.kt. Splits a reply
 * into the pieces Kokoro reads. Numbers, versions, units, URLs, and filenames
 * become words before any split. fixtures/speech-text.json is written by the
 * Kotlin side, so a drift fails speech-text.test.js.
 *
 * Kotlin walks UTF-16 code units and uses JVM character classes. This file
 * does the same, so an emoji is two surrogates here as it is on the Mac, and
 * both are dropped.
 */

/** A sentence this long is split on the last space at or before the limit. */
export const SENTENCE_SAFETY_CHARS = 400;

export function speechPieces(text, longSentenceMaxChars = SENTENCE_SAFETY_CHARS) {
  const spoken = normalizeForSpeech(text);
  const pieces = [];
  for (const sentence of splitSentences(spoken)) {
    pieces.push(...splitLongSentence(sentence, longSentenceMaxChars));
  }
  // A piece with no word in it, such as the ":" an emoticon leaves, would be a clip of nothing.
  return pieces.filter((piece) => [...splitUnits(piece)].some(isLetterOrDigit));
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
 * spelled letter by letter and digit by digit, so a token with words in it is
 * never deleted. A mark with no sound is: a quotation mark or an emoji is
 * dropped, and a dash, a bracket or an ellipsis becomes the pause it stands
 * for, written as a comma.
 *
 * A reply is spoken while it is still being written, so the words of a text
 * must not change when more text follows it. A pause is therefore written
 * only once a word follows it, and an ellipsis never ends a sentence.
 */
export function normalizeForSpeech(text) {
  return speakTokens(foldMarks(text));
}

function speakTokens(text) {
  const out = new SpokenText();
  let index = 0;
  while (index < text.length) {
    const url = matchUrl(text, index);
    if (url) {
      out.word(speakUrl(url.value));
      index += url.length;
      continue;
    }
    const time = matchHere(TIME, text, index);
    if (time != null) {
      out.word(normalizeTime(time));
      index += time.length;
      continue;
    }
    const clock = matchClock(text, index);
    if (clock != null) {
      out.word(normalizeTime(clock));
      index += clock.length;
      continue;
    }
    const file = matchHere(FILE, text, index);
    if (file != null) {
      out.word(normalizeFile(file));
      index += file.length;
      continue;
    }
    const unit = matchUnit(text, index);
    if (unit) {
      out.word(unit.words);
      index += unit.length;
      continue;
    }
    const version = matchHere(VERSION, text, index);
    if (version != null) {
      out.word(speakVersion(version));
      index += version.length;
      continue;
    }
    const coded = matchCoded(text, index);
    if (coded != null) {
      out.word(spellCoded(coded));
      index += coded.length;
      continue;
    }
    const integer = matchInteger(text, index);
    if (integer) {
      out.word(integer.words);
      index += integer.length;
      continue;
    }
    const arrow = matchArrow(text, index);
    if (arrow > 0) {
      out.word("to");
      index += arrow;
      continue;
    }
    const ch = text[index];
    const symbol = SYMBOLS[ch];
    if (symbol !== undefined) {
      out.word(symbol);
      index += 1;
      continue;
    }
    if (isDigit(ch)) {
      out.word(numberWords(digitValue(ch)));
      index += 1;
      continue;
    }
    const marks = silentMark(text, index, out);
    if (marks > 0) {
      index += marks;
      continue;
    }
    out.keep(ch);
    index += 1;
  }
  return out.finish();
}

/**
 * The spoken text as it is written. A pause is a comma that is owed until a
 * word follows it, so a mark never leaves one at the start, at the end,
 * doubled, or beside other punctuation. Whitespace is owed the same way: a
 * dropped mark leaves no double space, and no space before a comma or period.
 */
class SpokenText {
  constructor() {
    this.out = "";
    this.owedPause = false;
    this.space = null;
    // The last thing written was a word this pass made, so a letter after it starts a new word: "a/b" is "a slash b".
    this.madeWord = false;
  }

  /** Words this pass made, such as "slash" or "five hundred three". */
  word(word) {
    if (word.length === 0) return;
    this.settle(true);
    this.out += word;
    this.madeWord = true;
  }

  /** A character of the text, kept as written. A run of whitespace is kept as one character. */
  keep(ch) {
    if (isKtWhitespace(ch)) {
      if (this.space === null || this.space === " ") this.space = ch;
    } else if (CLOSERS.includes(ch)) {
      // It closes the word before it, so an owed pause or space is not written.
      this.clearOwed();
      // With no word before it there is nothing to close: "?!" is "?", and the ":" of "Done. :)" goes.
      if (this.out.length === 0 || SENTENCE_ENDS.includes(this.out[this.out.length - 1])) return;
      this.out += ch;
      this.madeWord = false;
    } else {
      this.settle(this.madeWord && isLetterOrDigit(ch));
      this.out += ch;
      this.madeWord = false;
    }
  }

  /** A dash, a bracket, a bullet or an ellipsis between words. */
  pause() {
    this.owedPause = true;
  }

  /** A dropped mark still keeps the words on its two sides apart. */
  gap() {
    if (this.space === null) this.space = " ";
  }

  /** What is still owed is dropped: the text may go on, and its next word decides what the pause was. */
  finish() {
    return this.out;
  }

  settle(apart) {
    if (this.out.length > 0) {
      if (this.owedPause && !CLOSERS.includes(this.out[this.out.length - 1])) this.out += ",";
      const between = this.space ?? (this.owedPause || apart ? " " : null);
      if (between !== null) this.out += between;
    }
    this.clearOwed();
  }

  clearOwed() {
    this.owedPause = false;
    this.space = null;
  }
}

/**
 * Typographic marks become the ASCII character the rest of this pass already
 * knows, before any token is matched: a curly apostrophe inside a word, a
 * non-breaking hyphen, and the many kinds of space. A mark that takes no room
 * goes, so it cannot split a word.
 */
function foldMarks(text) {
  let out = "";
  for (let index = 0; index < text.length; index += 1) {
    const ch = text[index];
    if (ch.charCodeAt(0) < 128) out += ch;
    else if (takesNoRoom(ch)) continue;
    else if (isKtWhitespace(ch)) out += " ";
    else if (ch === "\u2010" || ch === "\u2011") out += "-";
    // "don’t" has to reach the lexicon as "don't". A curly quote around a word is not between two letters.
    else if (APOSTROPHES.includes(ch) && betweenLetters(text, index)) out += "'";
    else out += ch;
  }
  return out;
}

function betweenLetters(text, index) {
  return index > 0 && index + 1 < text.length && isLetter(text[index - 1]) && isLetter(text[index + 1]);
}

/** Soft hyphen, combining accents, zero-width spaces and joiners, direction marks, keycap and variation selectors. */
function takesNoRoom(ch) {
  const code = ch.charCodeAt(0);
  return code === 0x00ad || (code >= 0x0300 && code <= 0x036f) || (code >= 0x200b && code <= 0x200f) ||
    (code >= 0x202a && code <= 0x202e) || (code >= 0x2060 && code <= 0x2064) ||
    (code >= 0x20d0 && code <= 0x20ff) || (code >= 0xfe00 && code <= 0xfe0f) || code === 0xfeff;
}

/**
 * A mark at index that has no spoken name: what it leaves in out, and how
 * many characters it took. Zero when the character there is not such a mark.
 */
function silentMark(text, index, out) {
  const ch = text[index];
  if (ch === "." || ch === ELLIPSIS) {
    let end = index;
    while (end < text.length && (text[end] === "." || text[end] === ELLIPSIS)) end += 1;
    // One dot or two end a sentence. Three are an ellipsis, a pause even at the end of the text.
    if (end - index < 3 && !text.substring(index, end).includes(ELLIPSIS)) return 0;
    out.pause();
    return end - index;
  }
  if (ch === "-" || ch === EN_DASH) {
    if (isRange(text, index)) {
      out.word("to");
      return 1;
    }
    let end = index + 1;
    while (ch === "-" && end < text.length && text[end] === "-") end += 1;
    // A hyphen inside a word stays. " - " and "--" are a dash typed on a keyboard.
    if (ch === "-" && end - index < 2 && !spaced(text, index)) return 0;
    out.pause();
    return end - index;
  }
  if (DASHES.includes(ch) || BRACKETS.includes(ch)) out.pause();
  else if (BULLETS.includes(ch)) {
    if (!startsLine(text, index)) out.pause();
  }
  // sherpa looks a word up with the quote marks that touch it, and skips it when that is not in the lexicon.
  else if (ch === '"' || ch === "`" || (ch === "'" && !betweenLetters(text, index))) out.gap();
  // U+02BC is a letter to Unicode, so the last test would keep it.
  else if (ch === MODIFIER_APOSTROPHE) out.gap();
  // Emoji are two surrogates, and neither is a letter, so both halves go here.
  else if (ch.charCodeAt(0) > 127 && !isLetterOrDigit(ch)) out.gap();
  else return 0;
  return 1;
}

/** The length of "->" or "=>" at start, or of a longer one such as "-->". Zero when there is no arrow. */
function matchArrow(text, start) {
  const shaft = text[start];
  if (shaft !== "-" && shaft !== "=") return 0;
  let end = start;
  while (end < text.length && text[end] === shaft) end += 1;
  return end < text.length && text[end] === ">" ? end + 1 - start : 0;
}

/** A space is on both sides. The end of the text counts as one, so "works -" does not change when " then" follows. */
function spaced(text, index) {
  return index > 0 && text[index - 1] === " " && (index + 1 === text.length || text[index + 1] === " ");
}

function startsLine(text, index) {
  let before = index - 1;
  while (before >= 0 && (text[before] === " " || text[before] === "\t")) before -= 1;
  return before < 0 || text[before] === "\n" || text[before] === "\r";
}

/**
 * A hyphen or en dash between two numbers is a range: "3-5" is "three to
 * five". A date or a phone number has a second dash, and is not one.
 */
function isRange(text, index) {
  if (!joinsNumbers(text, index)) return false;
  let left = index - 1;
  while (left >= 0 && isDigit(text[left])) left -= 1;
  let right = index + 1;
  while (right < text.length && isDigit(text[right])) right += 1;
  return !joinsNumbers(text, left) && !joinsNumbers(text, right);
}

function joinsNumbers(text, index) {
  return index >= 1 && index + 1 < text.length && (text[index] === "-" || text[index] === EN_DASH) &&
    isDigit(text[index - 1]) && isDigit(text[index + 1]);
}

const ELLIPSIS = "\u2026";
const EN_DASH = "\u2013";
const MODIFIER_APOSTROPHE = "\u02bc";

/** Figure dash, em dash and horizontal bar. They break the sentence wherever they stand. */
const DASHES = "\u2012\u2014\u2015";
const BRACKETS = "()[]{}";

/** Bullet, triangular bullet, white bullet, small black square, middle dot. */
const BULLETS = "\u2022\u2023\u25e6\u25aa\u00b7";

/** Right and left single quote, modifier apostrophe, prime. */
const APOSTROPHES = "\u2019\u2018\u02bc\u2032";
const CLOSERS = ",.;:!?";
const SENTENCE_ENDS = ".!?";

// java.util.regex without UNICODE_CHARACTER_CLASS: \d is [0-9], \s is these six.
const JS = "[ \\t\\n\\x0B\\f\\r]";
const URL_RE = new RegExp(`https?://[^ \\t\\n\\x0B\\f\\r]+`, "iy");
const TIME = new RegExp(`\\d{1,2}:\\d{2}${JS}*[AaPp][Mm]`, "y");
const CLOCK = /\d{1,2}:\d{2}(?![\d:])/y;
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
  // Marks outside ASCII that have a spoken name. Any other one is dropped by silentMark.
  "°": "degrees",
  "×": "times",
  "÷": "divided by",
  "±": "plus or minus",
  "\u2212": "minus",
  "€": "euros",
  "£": "pounds",
  "¥": "yen",
  "¢": "cents",
  "½": "one half",
  "¼": "one quarter",
  "¾": "three quarters",
  "²": "squared",
  "³": "cubed",
  "≈": "about",
  "≠": "not equal to",
  "≤": "at most",
  "≥": "at least",
  "∞": "infinity",
  "§": "section",
  "→": "to",
  "⇒": "to",
  "➜": "to",
};

/** Kotlin's Regex.find(text, start) that must land on start. A sticky regex is the same test. */
function matchHere(regex, text, start) {
  regex.lastIndex = start;
  const found = regex.exec(text);
  return found ? found[0] : null;
}

/** Punctuation after a URL is the sentence's, not the URL's: ASCII, then the closing curly quotes, guillemets and ellipsis. */
const URL_TRAILERS = ".,;:!?)]\"'\u201d\u2019\u00bb\u203a\u2026";

function matchUrl(text, start) {
  const raw = matchHere(URL_RE, text, start);
  if (raw == null) return null;
  let end = raw.length;
  while (end > 0 && URL_TRAILERS.includes(raw[end - 1])) end -= 1;
  if (end < "http://a".length) return null;
  return { value: raw.substring(0, end), length: end };
}

/** A time with no AM or PM, "12:30". The last two fields of "1:12:30" are not one. */
function matchClock(text, start) {
  if (start > 0 && text[start - 1] === ":") return null;
  return matchHere(CLOCK, text, start);
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
      if (SYMBOLS[ch] !== undefined) parts.push(SYMBOLS[ch]);
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

/** A letter outside a to z has no name here. It is handed on as written, like any other word. */
function letterName(ch) {
  return LETTER_NAMES[lowercaseChar(ch)] ?? ch;
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
  const match = new RegExp(`(\\d{1,2}):(\\d{2})${JS}*([AaPp][Mm])?`).exec(token);
  if (!match) return token;
  const hours = Number(match[1]);
  const hour = numberWords(hours);
  const minute = Number(match[2]);
  const ampm = (match[3] ?? "").toUpperCase();
  let time;
  if (minute >= 10) time = `${hour} ${numberWords(minute)}`;
  else if (minute > 0) time = `${hour} oh ${numberWords(minute)}`;
  else if (ampm !== "") time = hour;
  // With no AM or PM the hour alone would be a bare number: "12:00" and "18:00".
  else if (hours >= 1 && hours <= 12) time = `${hour} o'clock`;
  else time = `${hour} hundred`;
  return ampm === "" ? time : `${time} ${ampm}`;
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

/** The first clause is cut off a sentence longer than this. */
export const OPENING_MAX_CHARS = 70;

/** A first clause shorter than this is over before the rest is ready, and the listener hears a gap. */
export const OPENING_MIN_CHARS = 20;

/** A tail shorter than this is not worth a second call to the engine. */
const OPENING_MIN_REST = 12;

/**
 * The first clause of a long sentence, and the rest of it, as [head, rest].
 * Kokoro takes about half a sentence's length to generate it on a phone, so
 * the first sound of a reply waits on its first sentence. Speaking the opening
 * clause first halves that wait.
 *
 * Null when the sentence is maxChars or shorter, or has no clause boundary
 * that leaves a first part of minChars to maxChars. The cut is after the last
 * comma, semicolon or colon in that window, or else before its last
 * conjunction, which then starts the rest. It is never inside a number.
 */
export function openingClause(sentence, minChars = OPENING_MIN_CHARS, maxChars = OPENING_MAX_CHARS) {
  const text = ktTrim(sentence);
  if (text.length <= maxChars) return null;
  const cut = clauseCut(text, minChars, maxChars);
  if (cut < 0) return null;
  const head = ktTrim(text.substring(0, cut));
  const rest = ktTrim(text.substring(cut));
  if (head.length < minChars || rest.length < OPENING_MIN_REST) return null;
  return [head, rest];
}

const CONJUNCTIONS = [" and ", " but ", " or ", " so ", " because ", " which ", " that "];

/** Where the last clause boundary of text between minChars and maxChars is, or -1. A space is at the cut. */
function clauseCut(text, minChars, maxChars) {
  const floor = Math.max(minChars, 1);
  const limit = Math.min(maxChars, text.length - OPENING_MIN_REST - 1);
  const spans = numberSpans(text);
  for (let index = limit; index >= floor; index -= 1) {
    const previous = text[index - 1];
    // The comma in "1,000" has a digit after it. A clause's has a space.
    const closed = previous === "," || previous === ";" || previous === ":";
    if (closed && text[index] === " " && cutAllowed(spans, index)) return index;
  }
  let conjunction = -1;
  // Kotlin's lastIndexOf finds nothing from a negative start. JavaScript's would look at index 0.
  if (limit < floor) return conjunction;
  for (const word of CONJUNCTIONS) {
    let at = text.lastIndexOf(word, limit);
    while (at >= floor && !cutAllowed(spans, at)) at = text.lastIndexOf(word, at - 1);
    if (at >= floor && at > conjunction) conjunction = at;
  }
  return conjunction;
}

function cutAllowed(spans, index) {
  for (const [first, last] of spans) {
    if (index > first && index < last) return false;
  }
  return true;
}

/** Ranges of multi-word numbers, so a cut cannot land inside "five hundred three" or "two hundred and six". */
function numberSpans(text) {
  const words = [...text.matchAll(WORD)].map((match) => ({ value: match[0], first: match.index, last: match.index + match[0].length - 1 }));
  const spans = [];
  let index = 0;
  while (index < words.length) {
    if (!isNumberWord(words[index].value)) {
      index += 1;
      continue;
    }
    let end = index;
    while (true) {
      let next = end + 1;
      // "and" is inside a number only when a number word follows it.
      if (next + 1 < words.length && words[next].value === "and" && follows(words, end)) next += 1;
      if (next >= words.length || !follows(words, next - 1) || !isNumberWord(words[next].value)) break;
      end = next;
    }
    if (end > index) spans.push([words[index].first, words[end].last + 1]);
    index = end + 1;
  }
  return spans;
}

/** One space, and nothing else, is between the word at index and the next one. */
function follows(words, index) {
  return words[index + 1].first === words[index].last + 2;
}

const WORD = /[A-Za-z][A-Za-z'-]*/g;

const NUMBER_WORDS = new Set([...ONES, ...TENS.filter((word) => word !== ""), "hundred", "thousand", "oh"]);

function isNumberWord(token) {
  const lower = token.toLowerCase();
  if (NUMBER_WORDS.has(lower)) return true;
  if (!lower.includes("-")) return false;
  return lower.split("-").every((part) => NUMBER_WORDS.has(part));
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
