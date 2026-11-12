package org.formulagen.wrapper.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KokoroLexiconTest {
    @Test
    fun aBroadWordListIsNeverLeftUnspeakable() {
        val fixture = parseKokoroLexicon(FIXTURE + ('a'..'z').joinToString("\n") { "$it $it" })
        val fixtureReport = kokoroOovReport(probeWords(), fixture)
        println("fixture lexicon $fixtureReport")
        assertTrue(fixtureReport.baseOov > 0, fixtureReport.toString())
        assertEquals(0, fixtureReport.coveredOov, fixtureReport.toString())
        val real = findLexicon()
        if (real == null) {
            println("upstream lexicon-us-en.txt is not on disk; the fixture list was checked")
            return
        }
        val upstream = parseKokoroLexicon(real.readText())
        val report = kokoroOovReport(probeWords(), upstream)
        println("upstream lexicon ${real.path} $report")
        assertTrue(report.baseOov > 0, "expected ordinary inflections such as questions to be missing: $report")
        assertTrue("questions" !in upstream, "questions should be the OOV Tim heard")
        assertTrue("questions" in kokoroChatLexicon(upstream).known)
        assertEquals(0, report.coveredOov, report.toString())
    }

    @Test
    fun hovelsOwnWordsAreSaidAndNotSpelled() {
        val product = kokoroProductWords()
        // A plural is how a possessive is said, so "Quinn's" needs "quinns".
        assertEquals("k w ˈ ɪ n z", product["quinns"])
        assertEquals("ˈ I f ˌ O n z", product["iphones"])
        assertTrue(lexiconText(product).startsWith("quinn k w ˈ ɪ n\nquinns k w ˈ ɪ n z\n"))
        val real = findLexicon()
        if (real == null) {
            println("upstream lexicon-us-en.txt is not on disk; the product words were not checked against it")
            return
        }
        val upstream = parseKokoroLexicon(real.readText())
        val known = kokoroChatLexicon(upstream).known + product.keys
        // Every voice says "Hi, I'm <its name>." when a person picks it.
        for (voice in kokoroVoices()) {
            val hello = "Hi, I'm ${voice.name}."
            assertEquals(hello, coverUnknownWords(hello, known), "${voice.name} is spelled")
        }
        assertEquals("quinns iPhone and GitHub todos", coverUnknownWords("Quinn's iPhone and GitHub todos", known))
        // Each pronunciation uses only phones the model has a token for, and none replaces an upstream word.
        val tokens = File(real.parentFile, "tokens.txt").readLines().mapNotNull { it.substringBeforeLast(' ', "").ifEmpty { null } }.toSet()
        for ((word, phones) in product) {
            assertTrue(phones.split(' ').all { it in tokens }, "$word has a phone the model lacks: $phones")
        }
        assertEquals(emptySet(), KOKORO_PRODUCT_WORDS.keys.filter { it in upstream }.toSet(), "upstream already says these")
    }

    private fun findLexicon(): File? {
        val names = listOf(
            "spikes/ember-voice/mac/models/kokoro-multi-lang-v1_0/lexicon-us-en.txt",
            "voice/../spikes/ember-voice/mac/models/kokoro-multi-lang-v1_0/lexicon-us-en.txt",
            "/tmp/kokoro/kokoro-multi-lang-v1_0/lexicon-us-en.txt",
        )
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val current = dir ?: return@repeat
            for (name in names) {
                val file = File(current, name)
                if (file.isFile) return file
            }
            dir = current.parentFile
        }
        return names.firstNotNullOfOrNull { name -> File(name).takeIf { it.isFile } }
    }
}

private const val FIXTURE = """
question k w ˈ ɛ s ʧ ᵊ n
try t ɹ ˈ I
city s ˈ ɪ ɾ i
dog d ˈ ɔ ɡ
box b ˈ ɑ k s
want w ˈ ɑ n t
ask ˈ æ s k
play p l ˈ A
hope h ˈ O p
need n ˈ i d
run ɹ ˈ ʌ n
make m ˌ A k
church ʧ ˈ ɜ ɹ ʧ
bus b ˈ ʌ s
"""

/** Common words plus the inflections sherpa was skipping, and a few tokens no lexicon lists. */
private fun probeWords(): List<String> {
    val common = listOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "it", "for", "not", "on", "with", "he", "as", "you",
        "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she", "or", "an", "will", "my",
        "one", "all", "would", "there", "their", "what", "so", "up", "out", "if", "about", "who", "get", "which", "go",
        "me", "when", "make", "can", "like", "time", "no", "just", "him", "know", "take", "people", "into", "year",
        "your", "good", "some", "could", "them", "see", "other", "than", "then", "now", "look", "only", "come", "its",
        "over", "think", "also", "back", "after", "use", "two", "how", "our", "work", "first", "well", "way", "even",
        "new", "want", "because", "any", "these", "give", "day", "most", "us", "question", "ask", "play", "hope",
    )
    val stems = listOf(
        "question", "try", "city", "dog", "box", "want", "ask", "play", "hope", "need", "run", "make", "church", "bus",
        "book", "day", "baby", "watch", "pass", "miss", "push", "fix", "buzz", "walk", "call", "turn", "help", "show",
        "move", "live", "believe", "carry", "study", "open", "close", "start", "stop", "talk", "work", "seem",
    )
    val gaps = listOf(
        "questions", "tries", "cities", "dogs", "boxes", "wanted", "churches", "buses", "babies", "studies",
        "walked", "called", "helped", "showed", "moved", "lived", "opened", "closed", "started", "talking",
        "working", "seemed", "books", "days", "watches", "passes", "misses", "pushes", "fixes",
    )
    val unknown = listOf("zxqvik", "blorpt", "quizzaciously")
    return (common + stems + gaps + unknown).distinct()
}
