package org.formulagen.wrapper.voice

/**
 * Splits a line into the pieces we send to PocketTTS.
 * Pieces are sentences after the speech normalizer. Numbers, versions,
 * units, URLs, and filenames are spoken as words before any split.
 * A sentence longer than [longSentenceMaxChars] is split at a word boundary.
 */
fun speechPieces(
    text: String,
    longSentenceMaxChars: Int = SENTENCE_SAFETY_CHARS,
): List<String> {
    val spoken = normalizeForSpeech(text)
    val pieces = ArrayList<String>()
    for (sentence in splitSentences(spoken)) {
        pieces.addAll(splitLongSentence(sentence, longSentenceMaxChars))
    }
    // A piece with no word in it, such as the ":" an emoticon leaves, would be a clip of nothing.
    return pieces.filter { piece -> piece.any { it.isLetterOrDigit() } }
}

/** A sentence this long is split on the last space at or before the limit. */
const val SENTENCE_SAFETY_CHARS = 400

internal fun splitLongSentence(sentence: String, maxChars: Int): List<String> {
    if (maxChars < 1 || sentence.length <= maxChars) return listOf(sentence)
    val pieces = ArrayList<String>()
    var rest = sentence
    while (rest.length > maxChars) {
        val cut = wordBoundary(rest, maxChars)
        if (cut <= 0 || cut >= rest.length) {
            pieces.add(rest)
            return pieces
        }
        pieces.add(rest.substring(0, cut).trim())
        rest = rest.substring(cut).trim()
        if (rest.isEmpty()) return pieces
    }
    pieces.add(rest)
    return pieces
}

private fun wordBoundary(text: String, maxChars: Int): Int {
    val limit = minOf(maxChars, text.length)
    val space = text.lastIndexOf(' ', limit)
    return if (space >= 1) space else limit
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
fun normalizeForSpeech(text: String): String = speakTokens(foldMarks(text))

private fun speakTokens(text: String): String {
    val out = SpokenText()
    var index = 0
    while (index < text.length) {
        val url = matchUrl(text, index)
        if (url != null) {
            out.word(speakUrl(url.first))
            index += url.second
            continue
        }
        val time = matchHere(TIME, text, index)
        if (time != null) {
            out.word(normalizeTime(time))
            index += time.length
            continue
        }
        val clock = matchClock(text, index)
        if (clock != null) {
            out.word(normalizeTime(clock))
            index += clock.length
            continue
        }
        val file = matchHere(FILE, text, index)
        if (file != null) {
            out.word(normalizeFile(file))
            index += file.length
            continue
        }
        val unit = matchUnit(text, index)
        if (unit != null) {
            out.word(unit.first)
            index += unit.second
            continue
        }
        val version = matchHere(VERSION, text, index)
        if (version != null) {
            out.word(speakVersion(version))
            index += version.length
            continue
        }
        val coded = matchCoded(text, index)
        if (coded != null) {
            out.word(spellCoded(coded))
            index += coded.length
            continue
        }
        val integer = matchInteger(text, index)
        if (integer != null) {
            out.word(integer.first)
            index += integer.second
            continue
        }
        val arrow = matchArrow(text, index)
        if (arrow > 0) {
            out.word("to")
            index += arrow
            continue
        }
        val symbol = SYMBOLS[text[index]]
        if (symbol != null) {
            out.word(symbol)
            index += 1
            continue
        }
        val ch = text[index]
        if (ch.isDigit()) {
            out.word(numberWords(ch.digitToInt()))
            index += 1
            continue
        }
        val marks = silentMark(text, index, out)
        if (marks > 0) {
            index += marks
            continue
        }
        out.keep(ch)
        index += 1
    }
    return out.finish()
}

/**
 * The spoken text as it is written. A pause is a comma that is owed until a
 * word follows it, so a mark never leaves one at the start, at the end,
 * doubled, or beside other punctuation. Whitespace is owed the same way: a
 * dropped mark leaves no double space, and no space before a comma or period.
 */
private class SpokenText {
    private val out = StringBuilder()
    private var pause = false
    private var space: Char? = null

    /** The last thing written was a word this pass made, so a letter after it starts a new word: "a/b" is "a slash b". */
    private var madeWord = false

    /** Words this pass made, such as "slash" or "five hundred three". */
    fun word(word: String) {
        if (word.isEmpty()) return
        settle(apart = true)
        out.append(word)
        madeWord = true
    }

    /** A character of the text, kept as written. A run of whitespace is kept as one character. */
    fun keep(ch: Char) {
        when {
            ch.isWhitespace() -> if (space == null || space == ' ') space = ch
            ch in CLOSERS -> {
                // It closes the word before it, so an owed pause or space is not written.
                clearOwed()
                // With no word before it there is nothing to close: "?!" is "?", and the ":" of "Done. :)" goes.
                if (out.isEmpty() || out.last() in SENTENCE_ENDS) return
                out.append(ch)
                madeWord = false
            }
            else -> {
                settle(apart = madeWord && ch.isLetterOrDigit())
                out.append(ch)
                madeWord = false
            }
        }
    }

    /** A dash, a bracket, a bullet or an ellipsis between words. */
    fun pause() {
        pause = true
    }

    /** A dropped mark still keeps the words on its two sides apart. */
    fun gap() {
        if (space == null) space = ' '
    }

    /** What is still owed is dropped: the text may go on, and its next word decides what the pause was. */
    fun finish(): String = out.toString()

    private fun settle(apart: Boolean) {
        if (out.isNotEmpty()) {
            if (pause && out.last() !in CLOSERS) out.append(',')
            val between = space ?: if (pause || apart) ' ' else null
            if (between != null) out.append(between)
        }
        clearOwed()
    }

    private fun clearOwed() {
        pause = false
        space = null
    }
}

/**
 * Typographic marks become the ASCII character the rest of this pass already
 * knows, before any token is matched: a curly apostrophe inside a word, a
 * non-breaking hyphen, and the many kinds of space. A mark that takes no room
 * goes, so it cannot split a word.
 */
private fun foldMarks(text: String): String {
    val out = StringBuilder(text.length)
    for ((index, ch) in text.withIndex()) {
        when {
            ch.code < 128 -> out.append(ch)
            takesNoRoom(ch) -> Unit
            ch.isWhitespace() -> out.append(' ')
            ch == '\u2010' || ch == '\u2011' -> out.append('-')
            // "don’t" has to reach the lexicon as "don't". A curly quote around a word is not between two letters.
            ch in APOSTROPHES && betweenLetters(text, index) -> out.append('\'')
            else -> out.append(ch)
        }
    }
    return out.toString()
}

private fun betweenLetters(text: String, index: Int): Boolean =
    index > 0 && index + 1 < text.length && text[index - 1].isLetter() && text[index + 1].isLetter()

/** Soft hyphen, combining accents, zero-width spaces and joiners, direction marks, keycap and variation selectors. */
private fun takesNoRoom(ch: Char): Boolean = when (ch.code) {
    0x00AD, in 0x0300..0x036F, in 0x200B..0x200F, in 0x202A..0x202E, in 0x2060..0x2064,
    in 0x20D0..0x20FF, in 0xFE00..0xFE0F, 0xFEFF -> true
    else -> false
}

/**
 * A mark at [index] that has no spoken name: what it leaves in [out], and how
 * many characters it took. Zero when the character there is not such a mark.
 */
private fun silentMark(text: String, index: Int, out: SpokenText): Int {
    val ch = text[index]
    when {
        ch == '.' || ch == ELLIPSIS -> {
            var end = index
            while (end < text.length && (text[end] == '.' || text[end] == ELLIPSIS)) end += 1
            // One dot or two end a sentence. Three are an ellipsis, a pause even at the end of the text.
            if (end - index < 3 && ELLIPSIS !in text.substring(index, end)) return 0
            out.pause()
            return end - index
        }
        ch == '-' || ch == EN_DASH -> {
            if (isRange(text, index)) {
                out.word("to")
                return 1
            }
            var end = index + 1
            while (ch == '-' && end < text.length && text[end] == '-') end += 1
            // A hyphen inside a word stays. " - " and "--" are a dash typed on a keyboard.
            if (ch == '-' && end - index < 2 && !spaced(text, index)) return 0
            out.pause()
            return end - index
        }
        ch in DASHES || ch in BRACKETS -> out.pause()
        ch in BULLETS -> if (!startsLine(text, index)) out.pause()
        // sherpa looks a word up with the quote marks that touch it, and skips it when that is not in the lexicon.
        ch == '"' || ch == '`' || (ch == '\'' && !betweenLetters(text, index)) -> out.gap()
        // U+02BC is a letter to Unicode, so the last test would keep it.
        ch == MODIFIER_APOSTROPHE -> out.gap()
        // Emoji are two surrogates, and neither is a letter, so both halves go here.
        ch.code > 127 && !ch.isLetterOrDigit() -> out.gap()
        else -> return 0
    }
    return 1
}

/** The length of "->" or "=>" at [start], or of a longer one such as "-->". Zero when there is no arrow. */
private fun matchArrow(text: String, start: Int): Int {
    val shaft = text[start]
    if (shaft != '-' && shaft != '=') return 0
    var end = start
    while (end < text.length && text[end] == shaft) end += 1
    return if (end < text.length && text[end] == '>') end + 1 - start else 0
}

/** A space is on both sides. The end of the text counts as one, so "works -" does not change when " then" follows. */
private fun spaced(text: String, index: Int): Boolean =
    index > 0 && text[index - 1] == ' ' && (index + 1 == text.length || text[index + 1] == ' ')

private fun startsLine(text: String, index: Int): Boolean {
    var before = index - 1
    while (before >= 0 && (text[before] == ' ' || text[before] == '\t')) before -= 1
    return before < 0 || text[before] == '\n' || text[before] == '\r'
}

/**
 * A hyphen or en dash between two numbers is a range: "3-5" is "three to
 * five". A date or a phone number has a second dash, and is not one.
 */
private fun isRange(text: String, index: Int): Boolean {
    if (!joinsNumbers(text, index)) return false
    var left = index - 1
    while (left >= 0 && text[left].isDigit()) left -= 1
    var right = index + 1
    while (right < text.length && text[right].isDigit()) right += 1
    return !joinsNumbers(text, left) && !joinsNumbers(text, right)
}

private fun joinsNumbers(text: String, index: Int): Boolean =
    index >= 1 && index + 1 < text.length && (text[index] == '-' || text[index] == EN_DASH) &&
        text[index - 1].isDigit() && text[index + 1].isDigit()

private const val ELLIPSIS = '\u2026'
private const val EN_DASH = '\u2013'
private const val MODIFIER_APOSTROPHE = '\u02BC'

/** Figure dash, em dash and horizontal bar. They break the sentence wherever they stand. */
private const val DASHES = "\u2012\u2014\u2015"
private const val BRACKETS = "()[]{}"

/** Bullet, triangular bullet, white bullet, small black square, middle dot. */
private const val BULLETS = "\u2022\u2023\u25E6\u25AA\u00B7"

/** Right and left single quote, modifier apostrophe, prime. */
private const val APOSTROPHES = "\u2019\u2018\u02BC\u2032"
private const val CLOSERS = ",.;:!?"
private const val SENTENCE_ENDS = ".!?"

private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val TIME = Regex("""\d{1,2}:\d{2}\s*[AaPp][Mm]""")
private val CLOCK = Regex("""\d{1,2}:\d{2}(?![\d:])""")
private val FILE = Regex("""[A-Za-z][A-Za-z0-9]*\.(?:kt|kts|md|json|txt|wav)""", RegexOption.IGNORE_CASE)
private val UNIT = Regex(
    """(\d+(?:\.\d+)?)\s*(kHz|MHz|GHz|Hz|ms|msec|KB|MB|GB|TB|px|%)""",
    RegexOption.IGNORE_CASE,
)
private val GLUED_SECONDS = Regex("""(\d+(?:\.\d+)?)s(?![A-Za-z])""")
private val VERSION = Regex("""[vV]?\d+(?:\.\d+)+(?:-(?:rc|alpha|beta|preview)\d*)?""")

private val SYMBOLS = mapOf(
    '@' to "at",
    '#' to "hash",
    '$' to "dollar",
    '%' to "percent",
    '&' to "and",
    '*' to "star",
    '+' to "plus",
    '/' to "slash",
    '\\' to "backslash",
    '=' to "equals",
    '_' to "underscore",
    '^' to "caret",
    '~' to "tilde",
    '|' to "bar",
    '<' to "less than",
    '>' to "greater than",
    // Marks outside ASCII that have a spoken name. Any other one is dropped by silentMark.
    '°' to "degrees",
    '×' to "times",
    '÷' to "divided by",
    '±' to "plus or minus",
    '\u2212' to "minus",
    '€' to "euros",
    '£' to "pounds",
    '¥' to "yen",
    '¢' to "cents",
    '½' to "one half",
    '¼' to "one quarter",
    '¾' to "three quarters",
    '²' to "squared",
    '³' to "cubed",
    '≈' to "about",
    '≠' to "not equal to",
    '≤' to "at most",
    '≥' to "at least",
    '∞' to "infinity",
    '§' to "section",
    '→' to "to",
    '⇒' to "to",
    '➜' to "to",
)

private fun matchHere(regex: Regex, text: String, start: Int): String? {
    val found = regex.find(text, start) ?: return null
    if (found.range.first != start) return null
    return found.value
}

/** Punctuation after a URL is the sentence's, not the URL's: ASCII, then the closing curly quotes, guillemets and ellipsis. */
private const val URL_TRAILERS = ".,;:!?)]\"'\u201D\u2019\u00BB\u203A\u2026"

private fun matchUrl(text: String, start: Int): Pair<String, Int>? {
    val raw = matchHere(URL, text, start) ?: return null
    var end = raw.length
    while (end > 0 && raw[end - 1] in URL_TRAILERS) end -= 1
    if (end < "http://a".length) return null
    return raw.substring(0, end) to end
}

/** A time with no AM or PM, "12:30". The last two fields of "1:12:30" are not one. */
private fun matchClock(text: String, start: Int): String? {
    if (start > 0 && text[start - 1] == ':') return null
    return matchHere(CLOCK, text, start)
}

private fun matchUnit(text: String, start: Int): Pair<String, Int>? {
    val seconds = matchHere(GLUED_SECONDS, text, start)
    if (seconds != null) {
        val number = seconds.dropLast(1)
        return speakUnitNumber(number) + " seconds" to seconds.length
    }
    val found = UNIT.find(text, start) ?: return null
    if (found.range.first != start) return null
    val number = found.groupValues[1]
    val name = unitName(found.groupValues[2])
    return speakUnitNumber(number) + " " + name to found.value.length
}

private fun speakUnitNumber(raw: String): String {
    return if ('.' in raw) speakDecimal(raw) else speakInteger(raw)
}

private fun unitName(raw: String): String = when (raw.lowercase()) {
    "ms", "msec" -> "milliseconds"
    "hz" -> "hertz"
    "khz" -> "kilohertz"
    "mhz" -> "megahertz"
    "ghz" -> "gigahertz"
    "kb" -> "kilobytes"
    "mb" -> "megabytes"
    "gb" -> "gigabytes"
    "tb" -> "terabytes"
    "px" -> "pixels"
    "%" -> "percent"
    else -> spellCoded(raw)
}

private fun matchCoded(text: String, start: Int): String? {
    if (start >= text.length) return null
    val first = text[start]
    if (!first.isLetter() && !first.isDigit()) return null
    var cursor = start
    var letters = 0
    var digits = 0
    while (cursor < text.length) {
        val ch = text[cursor]
        when {
            ch.isLetter() -> letters += 1
            ch.isDigit() -> digits += 1
            (ch == '-' || ch == '_') && cursor + 1 < text.length && text[cursor + 1].isLetterOrDigit() -> Unit
            else -> break
        }
        cursor += 1
    }
    if (letters == 0 || digits == 0 || cursor == start) return null
    return text.substring(start, cursor)
}

private fun matchInteger(text: String, start: Int): Pair<String, Int>? {
    if (start >= text.length || !text[start].isDigit()) return null
    var cursor = start
    while (cursor < text.length && text[cursor].isDigit()) cursor += 1
    val raw = text.substring(start, cursor)
    return speakInteger(raw) to raw.length
}

private fun speakInteger(raw: String): String {
    if (raw.length > 1 && raw.startsWith('0')) return spellDigits(raw)
    if (raw.length > 9) return spellDigits(raw)
    val value = raw.toInt()
    return if (value > 999_999) spellDigits(raw) else numberWords(value)
}

private fun speakDecimal(raw: String): String =
    raw.split('.').joinToString(" point ") { speakInteger(it) }

private fun speakVersion(token: String): String {
    val match = Regex("""([vV])?(\d+(?:\.\d+)+)(?:-(rc|alpha|beta|preview)(\d*))?""").find(token)
        ?: return spellCoded(token)
    val head = if (match.groupValues[1].isNotEmpty()) "vee " else ""
    val numbers = speakDecimal(match.groupValues[2])
    val tag = match.groupValues[3]
    val tagNumber = match.groupValues[4]
    val suffix = when (tag) {
        "" -> ""
        "rc" -> " release candidate" + spokenTagNumber(tagNumber)
        else -> " $tag" + spokenTagNumber(tagNumber)
    }
    return head + numbers + suffix
}

private fun spokenTagNumber(raw: String): String = if (raw.isEmpty()) "" else " " + speakInteger(raw)

private fun speakUrl(url: String): String {
    val rest = url.replace(Regex("""(?i)^https?://"""), "").removePrefix("www.")
    val out = StringBuilder()
    val token = StringBuilder()
    fun flush() {
        if (token.isEmpty()) return
        appendWord(out, speakUrlToken(token.toString()))
        token.clear()
    }
    for (ch in rest) {
        when (ch) {
            '.' -> { flush(); appendWord(out, "dot") }
            '/' -> { flush(); appendWord(out, "slash") }
            '?' -> { flush(); appendWord(out, "question") }
            '&' -> { flush(); appendWord(out, "and") }
            '=' -> { flush(); appendWord(out, "equals") }
            '#' -> { flush(); appendWord(out, "hash") }
            ':' -> { flush(); appendWord(out, "colon") }
            '-' -> { flush(); appendWord(out, "dash") }
            '_' -> { flush(); appendWord(out, "underscore") }
            else -> token.append(ch)
        }
    }
    flush()
    return out.toString()
}

private fun speakUrlToken(token: String): String {
    val version = matchHere(VERSION, token, 0)
    if (version != null && version.length == token.length) return speakVersion(token)
    if (token.all { it.isDigit() }) return speakInteger(token)
    if (matchCoded(token, 0) == token) return spellCoded(token)
    return token
}

private fun spellCoded(token: String): String {
    val parts = ArrayList<String>()
    val digits = StringBuilder()
    fun flushDigits() {
        if (digits.isEmpty()) return
        parts.add(speakInteger(digits.toString()))
        digits.clear()
    }
    for (ch in token) {
        when {
            ch.isDigit() -> digits.append(ch)
            ch.isLetter() -> {
                flushDigits()
                parts.add(letterName(ch))
            }
            ch == '-' -> {
                flushDigits()
                parts.add("dash")
            }
            ch == '_' -> {
                flushDigits()
                parts.add("underscore")
            }
            else -> {
                flushDigits()
                SYMBOLS[ch]?.let { parts.add(it) }
            }
        }
    }
    flushDigits()
    return parts.joinToString(" ")
}

private fun spellDigits(raw: String): String = raw.map { numberWords(it.digitToInt()) }.joinToString(" ")

private fun letterName(ch: Char): String = when (ch.lowercaseChar()) {
    'a' -> "ay"
    'b' -> "bee"
    'c' -> "see"
    'd' -> "dee"
    'e' -> "ee"
    'f' -> "ef"
    'g' -> "gee"
    'h' -> "aitch"
    'i' -> "eye"
    'j' -> "jay"
    'k' -> "kay"
    'l' -> "el"
    'm' -> "em"
    'n' -> "en"
    'o' -> "oh"
    'p' -> "pee"
    'q' -> "cue"
    'r' -> "ar"
    's' -> "ess"
    't' -> "tee"
    'u' -> "you"
    'v' -> "vee"
    'w' -> "double you"
    'x' -> "ex"
    'y' -> "why"
    'z' -> "zee"
    // A letter outside a to z has no name here. It is handed on as written, like any other word.
    else -> ch.toString()
}

private fun appendWord(out: StringBuilder, word: String) {
    if (word.isEmpty()) return
    if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
    out.append(word)
}

private val ONES = listOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
    "seventeen", "eighteen", "nineteen",
)

private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

private val NUMBER_WORDS = (ONES + TENS.filter { it.isNotEmpty() } + listOf("hundred", "thousand", "oh")).toSet()

private fun numberWords(value: Int): String {
    if (value < 0) return value.toString()
    if (value < 20) return ONES[value]
    if (value < 100) {
        val tens = value / 10
        val rest = value % 10
        return if (rest == 0) TENS[tens] else "${TENS[tens]}-${ONES[rest]}"
    }
    if (value < 1000) {
        val hundreds = value / 100
        val rest = value % 100
        val head = "${ONES[hundreds]} hundred"
        return if (rest == 0) head else "$head ${numberWords(rest)}"
    }
    if (value < 1_000_000) {
        val thousands = value / 1000
        val rest = value % 1000
        val head = "${numberWords(thousands)} thousand"
        return if (rest == 0) head else "$head ${numberWords(rest)}"
    }
    return spellDigits(value.toString())
}

private fun normalizeFile(token: String): String {
    val dot = token.lastIndexOf('.')
    val stem = token.substring(0, dot)
    val ext = token.substring(dot + 1).lowercase()
    return splitCamel(stem) + " dot " + ext
}

private fun splitCamel(stem: String): String {
    val hump = Regex("([a-z0-9])([A-Z])")
    val acronym = Regex("([A-Z]+)([A-Z][a-z])")
    val spaced = hump.replace(stem) { it.groupValues[1] + " " + it.groupValues[2] }
    return acronym.replace(spaced) { it.groupValues[1] + " " + it.groupValues[2] }
}

private fun normalizeTime(token: String): String {
    val match = Regex("""(\d{1,2}):(\d{2})\s*([AaPp][Mm])?""").find(token)
        ?: return token
    val hours = match.groupValues[1].toInt()
    val hour = numberWords(hours)
    val minute = match.groupValues[2].toInt()
    val ampm = match.groupValues[3].uppercase()
    val time = when {
        minute >= 10 -> "$hour ${numberWords(minute)}"
        minute > 0 -> "$hour oh ${numberWords(minute)}"
        ampm.isNotEmpty() -> hour
        // With no AM or PM the hour alone would be a bare number: "12:00" and "18:00".
        hours in 1..12 -> "$hour o'clock"
        else -> "$hour hundred"
    }
    return if (ampm.isEmpty()) time else "$time $ampm"
}

private fun splitSentences(text: String): List<String> {
    val sentences = ArrayList<String>()
    val current = StringBuilder()
    for (ch in text) {
        current.append(ch)
        if (ch == '.' || ch == '!' || ch == '?') {
            val piece = current.toString().trim()
            if (piece.isNotEmpty()) sentences.add(piece)
            current.clear()
        }
    }
    val tail = current.toString().trim()
    if (tail.isNotEmpty()) sentences.add(tail)
    return sentences
}

/** The first clause is cut off a sentence longer than this. */
const val OPENING_MAX_CHARS = 70

/** A first clause shorter than this is over before the rest is ready, and the listener hears a gap. */
const val OPENING_MIN_CHARS = 20

/** A tail shorter than this is not worth a second call to the engine. */
private const val OPENING_MIN_REST = 12

/**
 * The first clause of a long sentence, and the rest of it. Kokoro takes about
 * half a sentence's length to generate it on a phone, so the first sound of a
 * reply waits on its first sentence. Speaking the opening clause first halves
 * that wait.
 *
 * Null when [sentence] is [maxChars] or shorter, or has no clause boundary
 * that leaves a first part of [minChars] to [maxChars]. The cut is after the
 * last comma, semicolon or colon in that window, or else before its last
 * conjunction, which then starts the rest. It is never inside a number.
 */
fun openingClause(
    sentence: String,
    minChars: Int = OPENING_MIN_CHARS,
    maxChars: Int = OPENING_MAX_CHARS,
): Pair<String, String>? {
    val text = sentence.trim()
    if (text.length <= maxChars) return null
    val cut = clauseCut(text, minChars, maxChars)
    if (cut < 0) return null
    val head = text.substring(0, cut).trim()
    val rest = text.substring(cut).trim()
    if (head.length < minChars || rest.length < OPENING_MIN_REST) return null
    return head to rest
}

private val CONJUNCTIONS = listOf(" and ", " but ", " or ", " so ", " because ", " which ", " that ")

/** Where the last clause boundary of [text] between [minChars] and [maxChars] is, or -1. A space is at the cut. */
private fun clauseCut(text: String, minChars: Int, maxChars: Int): Int {
    val floor = maxOf(minChars, 1)
    val limit = minOf(maxChars, text.length - OPENING_MIN_REST - 1)
    val spans = numberSpans(text)
    var index = limit
    while (index >= floor) {
        val previous = text[index - 1]
        // The comma in "1,000" has a digit after it. A clause's has a space.
        val closed = previous == ',' || previous == ';' || previous == ':'
        if (closed && text[index] == ' ' && cutAllowed(spans, index)) return index
        index -= 1
    }
    var conjunction = -1
    for (word in CONJUNCTIONS) {
        var at = text.lastIndexOf(word, limit)
        while (at >= floor && !cutAllowed(spans, at)) at = text.lastIndexOf(word, at - 1)
        if (at >= floor && at > conjunction) conjunction = at
    }
    return conjunction
}

private fun cutAllowed(spans: List<IntRange>, index: Int): Boolean {
    for (span in spans) {
        if (index > span.first && index < span.last) return false
    }
    return true
}

/** Ranges of multi-word numbers, so a cut cannot land inside "five hundred three" or "two hundred and six". */
private fun numberSpans(text: String): List<IntRange> {
    val words = WORD.findAll(text).toList()
    val spans = ArrayList<IntRange>()
    var index = 0
    while (index < words.size) {
        if (!isNumberWord(words[index].value)) {
            index += 1
            continue
        }
        var end = index
        while (true) {
            var next = end + 1
            // "and" is inside a number only when a number word follows it.
            if (next + 1 < words.size && words[next].value == "and" && follows(words, end)) next += 1
            if (next >= words.size || !follows(words, next - 1) || !isNumberWord(words[next].value)) break
            end = next
        }
        if (end > index) spans.add(words[index].range.first..words[end].range.last + 1)
        index = end + 1
    }
    return spans
}

/** One space, and nothing else, is between the word at [index] and the next one. */
private fun follows(words: List<MatchResult>, index: Int): Boolean =
    words[index + 1].range.first == words[index].range.last + 2

private val WORD = Regex("""[A-Za-z][A-Za-z'-]*""")

private fun isNumberWord(token: String): Boolean {
    val lower = token.lowercase()
    if (lower in NUMBER_WORDS) return true
    if (!lower.contains('-')) return false
    return lower.split('-').all { it in NUMBER_WORDS }
}
