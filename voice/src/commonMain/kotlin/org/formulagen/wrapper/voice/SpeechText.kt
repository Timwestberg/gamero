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
    return pieces.filter { it.isNotEmpty() }
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
 * spelled letter by letter and digit by digit. Nothing is deleted.
 */
fun normalizeForSpeech(text: String): String {
    val out = StringBuilder()
    var index = 0
    while (index < text.length) {
        val url = matchUrl(text, index)
        if (url != null) {
            appendWord(out, speakUrl(url.first))
            index += url.second
            continue
        }
        val time = matchHere(TIME, text, index)
        if (time != null) {
            appendWord(out, normalizeTime(time))
            index += time.length
            continue
        }
        val file = matchHere(FILE, text, index)
        if (file != null) {
            appendWord(out, normalizeFile(file))
            index += file.length
            continue
        }
        val unit = matchUnit(text, index)
        if (unit != null) {
            appendWord(out, unit.first)
            index += unit.second
            continue
        }
        val version = matchHere(VERSION, text, index)
        if (version != null) {
            appendWord(out, speakVersion(version))
            index += version.length
            continue
        }
        val coded = matchCoded(text, index)
        if (coded != null) {
            appendWord(out, spellCoded(coded))
            index += coded.length
            continue
        }
        val integer = matchInteger(text, index)
        if (integer != null) {
            appendWord(out, integer.first)
            index += integer.second
            continue
        }
        val symbol = SYMBOLS[text[index]]
        if (symbol != null) {
            appendWord(out, symbol)
            index += 1
            continue
        }
        val ch = text[index]
        if (ch.isDigit()) {
            appendWord(out, numberWords(ch.digitToInt()))
            index += 1
            continue
        }
        if (ch.code > 127 && !ch.isLetter()) {
            appendWord(out, "symbol")
            index += 1
            continue
        }
        out.append(ch)
        index += 1
    }
    return out.toString()
}

private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val TIME = Regex("""\d{1,2}:\d{2}\s*[AaPp][Mm]""")
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
)

private fun matchHere(regex: Regex, text: String, start: Int): String? {
    val found = regex.find(text, start) ?: return null
    if (found.range.first != start) return null
    return found.value
}

private fun matchUrl(text: String, start: Int): Pair<String, Int>? {
    val raw = matchHere(URL, text, start) ?: return null
    var end = raw.length
    while (end > 0 && raw[end - 1] in ".,;:!?)]\"'") end -= 1
    if (end < "http://a".length) return null
    return raw.substring(0, end) to end
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
                parts.add(SYMBOLS[ch] ?: "symbol")
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
    else -> "symbol"
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
    val match = Regex("""(\d{1,2}):(\d{2})\s*([AaPp][Mm])""").find(token)
        ?: return token
    val hour = numberWords(match.groupValues[1].toInt())
    val minute = match.groupValues[2].toInt()
    val ampm = match.groupValues[3].uppercase()
    return when {
        minute == 0 -> "$hour $ampm"
        minute < 10 -> "$hour oh ${numberWords(minute)} $ampm"
        else -> "$hour ${numberWords(minute)} $ampm"
    }
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

private fun firstWindowMin(target: Int): Int {
    if (target < 30) return target
    return 30
}

private fun packSentences(sentences: List<String>, maxChars: Int, out: MutableList<String>) {
    var current = ""
    fun flush() {
        if (current.isNotEmpty()) out.add(current)
        current = ""
    }
    for (sentence in sentences) {
        if (sentence.length > maxChars) {
            flush()
            var rest = sentence
            while (rest.length > maxChars) {
                val cut = cutAt(rest, laterWindowMin(maxChars), maxChars)
                if (cut <= 0 || cut >= rest.length) break
                out.add(rest.substring(0, cut).trim())
                rest = rest.substring(cut).trim()
            }
            current = rest
        } else if (current.isEmpty()) {
            current = sentence
        } else if (current.length + 1 + sentence.length <= maxChars) {
            current = "$current $sentence"
        } else {
            flush()
            current = sentence
        }
    }
    flush()
}

private fun laterWindowMin(maxChars: Int): Int {
    val half = maxChars / 2
    return if (half < 1) 1 else half
}

private fun cutAt(text: String, minChars: Int, maxChars: Int): Int {
    if (text.length <= maxChars) return text.length
    val limit = minOf(maxChars, text.length)
    val floor = minOf(minChars, limit)
    val spans = numberSpans(text)
    var clause = -1
    var conjunction = -1
    var space = -1
    var index = floor
    while (index <= limit) {
        if (cutAllowed(spans, index)) {
            val previous = text[index - 1]
            if (previous == ',' || previous == ';' || previous == ':') clause = index
            if (index < text.length && text[index] == ' ') space = index
        }
        index += 1
    }
    for (word in listOf(" and ", " or ", " but ")) {
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(word, from)
            if (at < 0) break
            if (at in floor..limit && cutAllowed(spans, at)) conjunction = at
            from = at + 1
        }
    }
    var cut = when {
        clause >= floor -> clause
        conjunction >= floor -> conjunction
        space >= floor -> space
        else -> limit
    }
    for (span in spans) {
        if (cut > span.first && cut < span.last) {
            cut = if (span.last <= maxChars) span.last else span.first
        }
    }
    if (cut <= 0) return limit
    if (cut < text.length && text[cut].isLetter() && text[cut - 1].isLetter()) {
        val space = text.lastIndexOf(' ', cut - 1)
        if (space >= 8) return space
    }
    return cut
}

private fun cutAllowed(spans: List<IntRange>, index: Int): Boolean {
    for (span in spans) {
        if (index > span.first && index < span.last) return false
    }
    return true
}

/** Ranges of multi-word numbers, so a cut cannot land inside "five hundred three". */
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
        while (end + 1 < words.size && words[end + 1].range.first == words[end].range.last + 2 && isNumberWord(words[end + 1].value)) {
            end += 1
        }
        if (end > index) spans.add(words[index].range.first..words[end].range.last + 1)
        index = end + 1
    }
    return spans
}

private val WORD = Regex("""[A-Za-z][A-Za-z'-]*""")

private fun isNumberWord(token: String): Boolean {
    val lower = token.lowercase()
    if (lower in NUMBER_WORDS) return true
    if (!lower.contains('-')) return false
    return lower.split('-').all { it in NUMBER_WORDS }
}
