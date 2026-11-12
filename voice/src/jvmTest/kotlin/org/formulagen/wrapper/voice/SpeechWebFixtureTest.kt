package org.formulagen.wrapper.voice

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The browser speaks with ports of SpeechText.kt, KokoroLexicon.kt, and the
 * sentence shaping in Speech.kt. This test writes what the Kotlin code does
 * to web/hearing/fixtures/speech-text.json, and the web tests compare against
 * it, so a change on either side fails one of them.
 *
 * The file is written when it is missing or SPEECH_FIXTURE_WRITE=1. When the
 * upstream lexicon-us-en.txt is on disk, the hash of the generated
 * lexicon-chat.txt goes to kokoro-lexicon-chat.json as well.
 */
class SpeechWebFixtureTest {
    @Test
    fun theWebSpeechFixtureMatchesKotlin() {
        val root = repoRoot()
        check(File(root, "web/hearing/fixtures/speech-text.json"), speechFixture())
        val lexicon = findLexicon(root)
        if (lexicon == null) {
            println("upstream lexicon-us-en.txt is not on disk; kokoro-lexicon-chat.json was not checked")
            return
        }
        check(File(root, "web/hearing/fixtures/kokoro-lexicon-chat.json"), lexiconChatFixture(lexicon))
    }

    private fun check(file: File, built: String) {
        if (!file.isFile || System.getenv("SPEECH_FIXTURE_WRITE") == "1") {
            file.parentFile.mkdirs()
            file.writeText(built)
        }
        assertEquals(built, file.readText(), "${file.name} is stale; rerun with SPEECH_FIXTURE_WRITE=1")
    }

    private fun speechFixture(): String {
        val out = Json()
        out.obj {
            field("schema") { string("ember-speech-text.v1") }
            field("sentenceSafetyChars") { number(SENTENCE_SAFETY_CHARS.toString()) }
            field("openingMinChars") { number(OPENING_MIN_CHARS.toString()) }
            field("openingMaxChars") { number(OPENING_MAX_CHARS.toString()) }
            field("normalize") {
                array(NORMALIZE_CASES) { input ->
                    obj {
                        field("input") { string(input) }
                        field("normalized") { string(normalizeForSpeech(input)) }
                        field("pieces") { strings(speechPieces(input)) }
                    }
                }
            }
            field("longSentence") {
                array(LONG_CASES) { (input, max) ->
                    obj {
                        field("input") { string(input) }
                        field("max") { number(max.toString()) }
                        field("pieces") { strings(splitLongSentence(input, max)) }
                    }
                }
            }
            field("opening") {
                array(OPENING_CASES) { (input, min, max) ->
                    obj {
                        field("input") { string(input) }
                        field("min") { number(min.toString()) }
                        field("max") { number(max.toString()) }
                        // Empty when there is no cut.
                        field("cut") { strings(openingClause(input, min, max)?.toList() ?: emptyList()) }
                    }
                }
            }
            val base = parseKokoroLexicon(LEXICON_FIXTURE)
            val chat = kokoroChatLexicon(base)
            field("lexicon") {
                obj {
                    field("text") { string(LEXICON_FIXTURE) }
                    field("parsed") { pairs(base.entries.map { it.key to it.value }) }
                    field("additions") { pairs(chat.additions.entries.map { it.key to it.value }) }
                    field("cover") {
                        array(COVER_CASES) { text ->
                            obj {
                                field("text") { string(text) }
                                field("out") { string(coverUnknownWords(text, chat.known)) }
                            }
                        }
                    }
                }
            }
            field("shape") {
                array(SHAPE_CASES) { case ->
                    obj {
                        field("input") { floats(case.samples) }
                        field("threshold") { number(case.threshold.toString()) }
                        field("fade") { number(case.fade.toString()) }
                        field("fadeHead") { bool(case.fadeHead) }
                        field("fadeTail") { bool(case.fadeTail) }
                        field("out") { floats(shapeSentence(case.samples, case.threshold, case.fade, case.fadeHead, case.fadeTail)) }
                    }
                }
            }
        }
        return out.text() + "\n"
    }

    private fun lexiconChatFixture(lexicon: File): String {
        val text = lexicon.readText()
        val chat = kokoroChatLexicon(parseKokoroLexicon(text))
        val supplement = StringBuilder()
        for ((word, phones) in chat.additions) supplement.append(word).append(' ').append(phones).append('\n')
        val out = Json()
        out.obj {
            field("schema") { string("ember-kokoro-lexicon-chat.v1") }
            field("lexiconSha256") { string(sha256(text.toByteArray(Charsets.UTF_8))) }
            field("additions") { number(chat.additions.size.toString()) }
            field("known") { number(chat.known.size.toString()) }
            field("lexiconChatSha256") { string(sha256(supplement.toString().toByteArray(Charsets.UTF_8))) }
            field("oov") { string(kokoroOovReport(OOV_PROBE, parseKokoroLexicon(text)).toString()) }
        }
        return out.text() + "\n"
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val current = dir ?: return@repeat
            if (File(current, "settings.gradle.kts").isFile && File(current, "web/hearing").isDirectory) return current
            dir = current.parentFile
        }
        error("repo root with web/hearing not found from ${File("").absolutePath}")
    }

    private fun findLexicon(root: File): File? = listOf(
        "web/voice-assets/lexicon-us-en.txt",
        "spikes/ember-voice/mac/models/kokoro-multi-lang-v1_0/lexicon-us-en.txt",
    ).map { File(root, it) }.firstOrNull { it.isFile }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class ShapeCase(
        val samples: FloatArray,
        val threshold: Float,
        val fade: Int,
        val fadeHead: Boolean,
        val fadeTail: Boolean,
    )

    /** Minimal pretty JSON writer; the module has no serialization dependency. */
    private class Json {
        private val out = StringBuilder()
        private var depth = 0
        private val first = ArrayDeque<Boolean>()

        fun text(): String = out.toString()

        fun obj(body: Json.() -> Unit) {
            out.append('{')
            open()
            body()
            close('}')
        }

        fun field(name: String, value: Json.() -> Unit) {
            item()
            string(name)
            out.append(": ")
            value()
        }

        fun <T> array(items: List<T>, each: Json.(T) -> Unit) {
            out.append('[')
            open()
            for (item in items) {
                item()
                each(item)
            }
            close(']')
        }

        fun strings(items: List<String>) = array(items) { string(it) }

        fun pairs(items: List<Pair<String, String>>) = array(items) { (a, b) -> tuple { string(a); out.append(", "); string(b) } }

        fun floats(items: FloatArray) {
            out.append('[')
            out.append(items.joinToString(", ") { it.toString() })
            out.append(']')
        }

        fun number(raw: String) {
            out.append(raw)
        }

        fun bool(value: Boolean) {
            out.append(value.toString())
        }

        fun string(value: String) {
            out.append('"')
            for (ch in value) {
                when {
                    ch == '"' -> out.append("\\\"")
                    ch == '\\' -> out.append("\\\\")
                    ch == '\n' -> out.append("\\n")
                    ch == '\r' -> out.append("\\r")
                    ch == '\t' -> out.append("\\t")
                    ch.code < 0x20 || ch.code in 0x7f..0x9f || ch.isSurrogate() || ch.code > 0x7e ->
                        out.append("\\u").append("%04x".format(ch.code))
                    else -> out.append(ch)
                }
            }
            out.append('"')
        }

        private fun tuple(body: Json.() -> Unit) {
            out.append('[')
            body()
            out.append(']')
        }

        private fun open() {
            depth += 1
            first.addLast(true)
        }

        private fun item() {
            if (!first.removeLast()) out.append(',')
            first.addLast(false)
            out.append('\n').append("  ".repeat(depth))
        }

        private fun close(ch: Char) {
            val empty = first.removeLast()
            depth -= 1
            if (!empty) out.append('\n').append("  ".repeat(depth))
            out.append(ch)
        }
    }

    private companion object {
        val NORMALIZE_CASES = listOf(
            "Hi, I'm Sam.",
            "Hi, I'm Ember. Sonnet 5.5 answered in 160 ms. What should we build next?",
            "Ember answered in 160 ms, about 1.5s faster.",
            "Open https://github.com/Timwestberg/gamero/pull/63 to see it.",
            "See http://www.example.com/a-b_c?x=1&y=v2.0#top, then https://x.io.",
            "Meet me at 3:05 PM or 10:30am, not 12:30.",
            "Edit DesktopSpeech.kt and voices-blends.json, then run v2.0.1-rc2.",
            "The GPU runs at 1.2 GHz with 16 GB, 48 kHz audio and 1920px; that's 100% better.",
            "Use gpt-4o or mp3 files like track_01 on x86_64 with C3PO and R2-D2.",
            "It costs \$5 & ships in 2026!",
            "Version 1.13.8-beta? Or 007? Or 3.14159?",
            "Phone 5551234567 has 10 digits and 1000000 is big.",
            "Emoji 🎉 and “quotes” — dashes…",
            "Ünïcödé café naïve İstanbul ٣ apples.",
            "Tabs\tand\nnew lines   stay.",
            "a/b\\c = d_e ^ f ~ g | h < i > j @ k # l * m + n",
            "Wait... what?! Really?",
            "ID-42-b and 0.5% and 5msec.",
            "Questions about the boxes, cities, and plays.",
            "",
            "   ",
            "No punctuation at the end",
            "Long " + "sentence ".repeat(60) + "ends here.",
            // Typographic marks. The invisible ones are written as escapes.
            "I don’t think it’s Tim’s — it’s ‘ours’, the dogs’ bones.",
            "She said “wait… what?” and left (quietly).",
            "The cache (the fast one) is ready [see notes] {draft}.",
            "Pages 3–5, 10-20 ms, 1.5-2.5 GB, 2026-11-09 and 555-867-5309.",
            "Ready - set -- go — now ― then ‒ done, well-known e-mail.",
            "Options:\n• first\n  ◦ second\n▪ third · fourth ‣ fifth",
            "Settings → Voice ⇒ Kokoro ➜ done",
            "20° at 3 × 4 ÷ 2 ± 1, €10, £3, ¥500, 5¢, ½ ¼ ¾, ≈ 10, a ≠ b, x ≤ 5, y ≥ 2, § 4, −2, x², y³, ∞",
            "10\u00A0km, 160\u202Fms, zero\u200Bwidth\uFEFF, non\u2011breaking GPT\u20114o, soft\u00ADhyphen.",
            "Great job 🎉! Kokoro™ is ready ✅ \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 👍🏽 1\uFE0F\u20E3 ©2026 Acme® │ box ├─ drawing",
            "«Bonjour» is ‹hello›, „low‟ quotes; \"plain\" too, said“hi”there.",
            "Hmm... let me think….. Really...? No.. two dots.",
            "I was thinking…",
            "— Hello — (yes), [no]. (Maybe.) …and then",
            "caf\u00E92go, e\u0301, ʼtis and yʼall, 5′ 10″, rock ’n’ roll",
            "See “https://example.com/docs”, then (https://x.io)…",
            "great👍thanks",
            "Say 'now', run `build` first, the boxes' lids, 'cause it's O'Neil's.",
            "a -> b => c --> d, x=>y but a = b and 3->5",
            "What?! Really!! Yes.. ok. :) Done. :)",
            "Meet at 12:30, 9:05, 12:00 or 18:00, from 9:00–10:30 AM, not 1:12:30 or 16:9.",
            "It works -",
            "the cache (",
            "?!",
            ":)",
            "🎉",
            "…",
        )

        val OPENING_CASES = listOf(
            Triple("Sure, I can help with that.", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
            Triple("I refactored the session code, moved the socket into the bridge, and the build is green again.", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
            Triple("First we open the catalog; then we pick a companion and start a long conversation with it.", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
            Triple("The build finished without a single warning and the tests all passed on the first run today.", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
            Triple("We should keep the old endpoint alive for now because three clients still call it every day.", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
            Triple("The total for the whole order came to 1,000 dollars after tax which nobody had expected at all.", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
            Triple("Their account balance was exactly 1,000,000 dollars yesterday morning at nine sharp.", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
            Triple("The server answered with error five hundred and three but the retry worked fine after it.", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
            Triple("The server answered with error five hundred and three after thirty seconds of waiting.", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
            Triple("It took twenty-one or twenty-two tries and one hundred sixty milliseconds each, or so the log says.", 30, 60),
            Triple("Supercalifragilistic words keep going without any pause whatsoever until the very end here.", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
            Triple("  one two three, four five six seven ", 5, 20),
            Triple("one two three four five, six seven", 5, 30),
            Triple("one two three four, five six seven", 5, 30),
            Triple("", OPENING_MIN_CHARS, OPENING_MAX_CHARS),
        )

        val LONG_CASES = listOf(
            "one two three four five six seven eight nine ten eleven twelve" to 20,
            "nospaceatallinthistextsoitcannotbesplitonaword" to 10,
            "short" to 0,
            "a b c d e f g h i j k l m n o p" to 5,
        )

        const val LEXICON_FIXTURE = """# comment line
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
stop s t ˈ ɑ p
go ɡ ˈ O
church ʧ ˈ ɜ ɹ ʧ
wish w ˈ ɪ ʃ
buzz b ˈ ʌ z
tie t ˈ I
free f ɹ ˈ i
Dog d ˈ ɔ ɡ ɡ
don't d ˈ O n t
a ˈ A
x ˈ ɛ k s
the ð ə
about ə b ˈ W t
and ˈ æ n d

bare
"""

        val COVER_CASES = listOf(
            "Questions about the boxes",
            "The cities' dogs ran and stopped",
            "Xyzzy plays wishes",
            "don't stop hoping",
            "O'Neil tried, tying, tries",
            "hello-world ABC123 buzzes",
            "The dog's box, the city's church's wish",
            "Xyzzy's dog's A's don't",
        )

        val SHAPE_CASES = listOf(
            ShapeCase(floatArrayOf(0.001f, 0f, 0.5f, -0.3f, 0.2f, 0.01f, 0f), 0.02f, 2, false, true),
            ShapeCase(floatArrayOf(0.001f, 0f, 0.5f, -0.3f, 0.2f, 0.4f, 0.01f), 0.02f, 3, true, true),
            ShapeCase(floatArrayOf(0.001f, 0.002f, 0.003f), 0.02f, 2, true, true),
            ShapeCase(floatArrayOf(0.3f, 0.6f, -0.9f, 0.7f, -0.2f, 0.1f, 0.05f, 0.25f), 0.02f, 240, true, true),
            ShapeCase(floatArrayOf(0.019999f, 0.02f, 0.5f, 0.02f, 0.0199f), 0.02f, 1, true, false),
        )

        val OOV_PROBE = listOf(
            "questions", "boxes", "tried", "playing", "hoped", "Ember", "Sonnet", "Hovel", "catalog", "companions",
            "barged", "replies", "whispered", "debugging", "repositories", "webgpu",
        )
    }
}
