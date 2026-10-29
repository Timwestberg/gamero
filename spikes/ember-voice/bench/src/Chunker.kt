package ember.voice.bench

/**
 * Pieces are sentences. A sentence longer than [longSentenceMaxChars] is split
 * at a word boundary. Keep this in step with voice SpeechText.kt.
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

fun normalizeForSpeech(text: String): String {
    val out = StringBuilder()
    var index = 0
    for (match in TOKEN.findAll(text)) {
        out.append(text, index, match.range.first)
        val file = match.groups[1]?.value
        val time = match.groups[2]?.value
        val number = match.groups[3]?.value
        when {
            file != null -> out.append(normalizeFile(file))
            time != null -> out.append(normalizeTime(time))
            number != null -> out.append(numberWords(number.toInt()))
        }
        index = match.range.last + 1
    }
    out.append(text, index, text.length)
    return out.toString()
}

private val TOKEN = Regex(
    """([A-Za-z][A-Za-z0-9]*\.(?:kt|kts|md|json|txt|wav))|(?<!\d)(\d{1,2}:\d{2}\s*[AaPp][Mm])|(?<!\d)(\d{1,4})(?!\d)""",
    RegexOption.IGNORE_CASE,
)

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
    if (value < 10000) {
        val thousands = value / 1000
        val rest = value % 1000
        val head = "${numberWords(thousands)} thousand"
        return if (rest == 0) head else "$head ${numberWords(rest)}"
    }
    return value.toString()
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
