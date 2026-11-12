package org.formulagen.wrapper.voice

/**
 * sherpa-onnx 2.0.0 dropped espeak, so a word missing from lexicon-us-en.txt is
 * skipped and never spoken. Regular inflections of a known stem are added, and
 * anything still unknown is spelled with letters the lexicon already contains.
 */
internal data class KokoroOovReport(
    val total: Int,
    val baseOov: Int,
    val inflectedOov: Int,
    val coveredOov: Int,
) {
    fun percent(count: Int): Double = if (total == 0) 0.0 else count * 100.0 / total

    override fun toString(): String {
        // One decimal place with halves rounded up, as `String.format` does; only the JVM has that call.
        fun pct(count: Int): String {
            val tenths = kotlin.math.floor(percent(count) * 10 + 0.5).toLong()
            return "${tenths / 10}.${tenths % 10}%"
        }
        return "OOV base $baseOov/$total (${pct(baseOov)}), " +
            "after inflections $inflectedOov/$total (${pct(inflectedOov)}), " +
            "after spelling $coveredOov/$total (${pct(coveredOov)})"
    }
}

internal data class KokoroChatLexicon(
    val additions: Map<String, String>,
    val known: Set<String>,
)

/** First pronunciation wins, matching sherpa. A blank line is ignored. */
internal fun parseKokoroLexicon(text: String): Map<String, String> {
    val words = LinkedHashMap<String, String>()
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val space = trimmed.indexOf(' ')
        if (space <= 0) continue
        val word = trimmed.substring(0, space).lowercase()
        val phones = trimmed.substring(space + 1).trim()
        if (word.isEmpty() || phones.isEmpty() || word in words) continue
        words[word] = phones
    }
    return words
}

/** Inflections that are not already in [base]. The base pronunciation is kept. */
internal fun inflectKokoroLexicon(base: Map<String, String>): Map<String, String> {
    val extra = LinkedHashMap<String, String>()
    val known = HashSet<String>(base.keys)
    for ((word, phones) in base) {
        if (!word.all { it in 'a'..'z' } || word.length < 2) continue
        addInflection(extra, known, pluralOf(word, phones))
        addInflection(extra, known, pastOf(word, phones))
        addInflection(extra, known, gerundOf(word, phones))
    }
    return extra
}

internal fun kokoroChatLexicon(base: Map<String, String>): KokoroChatLexicon {
    val additions = inflectKokoroLexicon(base)
    return KokoroChatLexicon(additions, base.keys + additions.keys)
}

/**
 * Words Hovel says that the upstream lexicon lacks: its voices' names, and products that come up in a
 * conversation. Without them the voice spells "Quinn" and "iPhone" letter by letter. Each pronunciation is put
 * together from words the lexicon holds (quinn from quince, avery from bravery, iphone from i and phone), so
 * every phone is one the model knows.
 */
internal val KOKORO_PRODUCT_WORDS: Map<String, String> = linkedMapOf(
    "quinn" to "k w ˈ ɪ n",
    "avery" to "ˈ A v ə ɹ i",
    "jamie" to "ʤ ˈ A m i",
    "iphone" to "ˈ I f ˌ O n",
    "ipad" to "ˈ I p ˌ æ d",
    "github" to "ɡ ˈ ɪ t h ˌ ʌ b",
    "todo" to "t ə d ˈ u",
    "wifi" to "w ˈ I f ˌ I",
    "gamero" to "ɡ ə m ˈ ɛ ɹ O",
)

/** The engines write this file and hand it to sherpa after the other lexicons. */
internal const val KOKORO_PRODUCT_LEXICON = "lexicon-hovel.txt"

/**
 * [KOKORO_PRODUCT_WORDS] with the plural of each, which is also how its possessive is said ("Quinn's"). A word the
 * upstream lexicon already holds keeps upstream's pronunciation: sherpa ignores a later duplicate.
 */
internal fun kokoroProductWords(): Map<String, String> {
    val words = LinkedHashMap<String, String>()
    for ((word, phones) in KOKORO_PRODUCT_WORDS) {
        words[word] = phones
        val (plural, pluralPhones) = pluralOf(word, phones)
        if (plural !in words) words[plural] = pluralPhones
    }
    return words
}

/** A lexicon file's text: one "word phones" line for each of [words]. */
internal fun lexiconText(words: Map<String, String>): String = words.entries.joinToString("") { "${it.key} ${it.value}\n" }

/**
 * Replaces a word the lexicon cannot say with its letters. A known word,
 * including one added by [inflectKokoroLexicon], is left as written, and a
 * possessive is said as the plural it sounds like.
 */
internal fun coverUnknownWords(text: String, known: Set<String>): String {
    return SPOKEN_WORD.replace(text) { match ->
        val token = match.value
        val key = token.lowercase()
        if (key in known) {
            token
        } else {
            possessiveAsPlural(key, known) ?: token.filter { it.isLetter() }.map { it.lowercaseChar() }.joinToString(" ")
        }
    }
}

/**
 * The lexicon lists contractions such as "it's", but no possessive of a name
 * or noun: "Tim's" and "that's" are not in it. A possessive sounds the same as
 * the plural, which [inflectKokoroLexicon] adds, so "tims" is said instead.
 */
private fun possessiveAsPlural(key: String, known: Set<String>): String? {
    // A one-letter stem is a letter name: "A's" is not the word "as".
    if (!key.endsWith("'s") || key.length < 4) return null
    val plural = pluralSpelling(key.dropLast(2))
    return if (plural in known) plural else null
}

internal fun kokoroOovReport(words: List<String>, base: Map<String, String>): KokoroOovReport {
    val chat = kokoroChatLexicon(base)
    val baseMissing = words.count { it.lowercase() !in base }
    val inflectedMissing = words.count { it.lowercase() !in chat.known }
    val coveredMissing = words.count { word ->
        SPOKEN_WORD.findAll(coverUnknownWords(word, chat.known)).any { it.value.lowercase() !in chat.known }
    }
    return KokoroOovReport(words.size, baseMissing, inflectedMissing, coveredMissing)
}

private fun addInflection(extra: MutableMap<String, String>, known: MutableSet<String>, form: Pair<String, String>?) {
    if (form == null) return
    val (word, phones) = form
    if (word in known || word.isEmpty() || phones.isBlank()) return
    extra[word] = phones
    known.add(word)
}

private fun pluralSpelling(word: String): String = when {
    word.endsWith("s") || word.endsWith("x") || word.endsWith("z") || word.endsWith("ch") || word.endsWith("sh") -> word + "es"
    word.endsWith("y") && word.length >= 2 && !isVowel(word[word.length - 2]) -> word.dropLast(1) + "ies"
    else -> word + "s"
}

private fun pluralOf(word: String, phones: String): Pair<String, String> {
    val spelling = pluralSpelling(word)
    val last = lastPhone(phones)
    val suffix = when {
        last in SIBILANTS || word.endsWith("ch") || word.endsWith("sh") || word.endsWith("x") || word.endsWith("s") || word.endsWith("z") -> "ᵻ z"
        last in VOICELESS -> "s"
        else -> "z"
    }
    return spelling to phones.trimEnd() + " " + suffix
}

private fun pastOf(word: String, phones: String): Pair<String, String> {
    val spelling = when {
        word.endsWith("e") -> word + "d"
        word.endsWith("y") && word.length >= 2 && !isVowel(word[word.length - 2]) -> word.dropLast(1) + "ied"
        else -> word + "ed"
    }
    val last = lastPhone(phones)
    val suffix = when {
        last == "t" || last == "d" -> "ᵻ d"
        last in VOICELESS || last == "s" || last == "ʃ" || last == "ʧ" -> "t"
        else -> "d"
    }
    return spelling to phones.trimEnd() + " " + suffix
}

private fun gerundOf(word: String, phones: String): Pair<String, String> {
    val spelling = when {
        word.endsWith("ie") && word.length > 2 -> word.dropLast(2) + "ying"
        word.endsWith("e") && !word.endsWith("ee") && !word.endsWith("ye") && !word.endsWith("oe") -> word.dropLast(1) + "ing"
        shouldDouble(word) -> word + word.last() + "ing"
        else -> word + "ing"
    }
    return spelling to phones.trimEnd() + " ɪ ŋ"
}

private fun shouldDouble(word: String): Boolean {
    if (word.length < 3 || word.endsWith("y") || word.endsWith("w") || word.endsWith("x")) return false
    if (!isVowel(word[word.length - 2]) || isVowel(word.last())) return false
    if (isVowel(word[word.length - 3])) return false
    return word.count { isVowel(it) } == 1
}

private fun isVowel(ch: Char): Boolean = ch == 'a' || ch == 'e' || ch == 'i' || ch == 'o' || ch == 'u'

private fun lastPhone(phones: String): String {
    val parts = phones.trim().split(' ')
    for (index in parts.lastIndex downTo 0) {
        val token = parts[index]
        if (token.isNotEmpty() && token !in STRESS) return token
    }
    return ""
}

private val SPOKEN_WORD = Regex("""[A-Za-z]+(?:'[A-Za-z]+)?""")

private val SIBILANTS = setOf("s", "z", "ʃ", "ʒ", "ʧ", "ʤ")

private val VOICELESS = setOf("p", "t", "k", "f", "θ")

private val STRESS = setOf("ˈ", "ˌ", "ː", "ʰ", "ʲ")
