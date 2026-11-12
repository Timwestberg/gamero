package org.formulagen.wrapper.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A model writes typographic marks, and Kokoro was handed each of them as the
 * word "symbol". The escapes below are the marks themselves: \u2019 is the
 * curly apostrophe, \u2014 the em dash, \u2026 the ellipsis.
 */
class SpeechPunctuationTest {
    private fun says(expected: String, text: String) = assertEquals(expected, normalizeForSpeech(text), text)

    @Test
    fun aCurlyApostropheInsideAWordIsTheAsciiOne() {
        says("I don't think it's Tim's.", "I don\u2019t think it\u2019s Tim\u2019s.")
        says("We'll see, y'all", "We\u2018ll see, y\u02BCall")
        says("o'clock", "o\u2032clock")
    }

    @Test
    fun aContractionReachesTheLexiconAsOneWord() {
        val known = setOf("i", "don't", "think", "it's", "tim's", "dog")
        val spoken = coverUnknownWords(normalizeForSpeech("I don\u2019t think it\u2019s Tim\u2019s dog."), known)
        assertEquals("I don't think it's Tim's dog.", spoken)
    }

    @Test
    fun aPossessiveTheLexiconDoesNotListIsSaidAsItsPlural() {
        val lexicon = parseKokoroLexicon("tim t ˈ ɪ m\nthat ð æ t\ncity s ˈ ɪ ɾ i\nboss b ˈ ɔ s\nis ˈ ɪ z\nit's ˈ ɪ t s\na ˈ A\nas ˈ æ z")
        val known = kokoroChatLexicon(lexicon).known
        assertEquals("tims city, thats it's", coverUnknownWords(normalizeForSpeech("Tim\u2019s city, that\u2019s it\u2019s"), known))
        assertEquals("cities bosses", coverUnknownWords("city's boss's", known))
        assertEquals("z e d s a s", coverUnknownWords("Zed's A's", known), "an unknown stem and a letter name are still spelled")
    }

    @Test
    fun quotationMarksAreSilentAndLeaveTheWordsApart() {
        says("She said hello and left.", "She said \u201Chello\u201D and left.")
        says("He said stop.", "He said \"stop\".")
        says("Bonjour is hello, low quotes.", "\u00ABBonjour\u00BB is \u2039hello\u203A, \u201Elow\u201F quotes.")
        says("said hi there", "said\u201Chi\u201Dthere")
        says("It's fine, the dogs bones.", "It\u2019s \u2018fine\u2019, the dogs\u2019 bones.")
    }

    @Test
    fun aDashIsAPause() {
        says("The build passed, finally, and shipped.", "The build passed \u2014 finally \u2014 and shipped.")
        says("The build passed, finally.", "The build passed\u2014finally.")
        says("It works, mostly.", "It works \u2013 mostly.")
        says("Ready, set, go", "Ready - set -- go")
        says("one, two, three", "one \u2015 two \u2012 three")
    }

    @Test
    fun aPauseIsNeverDoubledOrLeftAtAnEdge() {
        says("Yes, and no.", "Yes, \u2014 and no.")
        says("Done! Next.", "Done! \u2014 Next.")
        says("Hello", "\u2014 Hello \u2014")
        says("So: yes", "So: (yes)")
    }

    @Test
    fun aDashBetweenTwoNumbersIsARange() {
        says("pages three to five and ten to twenty", "pages 3\u20135 and 10-20")
        says("ten to twenty milliseconds", "10-20 ms")
        says("a well-known state-of-the-art e-mail", "a well-known state-of-the-art e-mail")
        assertFalse(" to " in normalizeForSpeech("Due 2026-11-09, call 555-867-5309."), "a date and a phone number are not ranges")
    }

    @Test
    fun anEllipsisIsAPauseAndNeverEndsASentence() {
        says("Wait, what?", "Wait\u2026 what?")
        says("Hmm, let me think.", "Hmm... let me think.")
        says("I was thinking", "I was thinking\u2026")
        says("And then", "And then.....")
        says("Wait?", "Wait...?")
        says("She said well", "She said \u201Cwell\u2026\u201D")
        assertEquals(listOf("Wait, what?", "Really"), speechPieces("Wait... what? Really..."))
    }

    @Test
    fun theWordsOfATextDoNotChangeWhenMoreTextFollows() {
        val growing = listOf(
            "Wait\u2026" to " what?",
            "Wait..." to " what?",
            "It works \u2014" to " mostly.",
            "It works\u2013" to "mostly.",
            "It works -" to " mostly.",
            "It works -" to "- mostly.",
            "the cache (" to "the fast one) is ready",
            "the cache [the fast one" to "] is ready",
            "the cache (the fast one)" to " is ready",
            "the cache (the fast one)" to ".",
            "He said \u201C" to "stop\u201D.",
            "Say 'now" to "' and `go`.",
            "Great \uD83C" to "\uDF89 job!",
            "Options:\n\u2022" to " first",
            "What?" to "! Really?",
        )
        for ((text, more) in growing) {
            val before = normalizeForSpeech(text)
            val after = normalizeForSpeech(text + more)
            assertTrue(after.startsWith(before), "\"$text\" is \"$before\", and with \"$more\" it is \"$after\"")
            assertTrue(after.length > before.length, "\"$more\" adds words to \"$before\"")
        }
    }

    @Test
    fun aQuoteMarkBesideAWordIsSilentAndAnApostropheInsideOneStays() {
        says("for now", "for 'now'")
        says("run build first", "run `build` first")
        says("the boxes lids", "the boxes' lids")
        says("Hello, she said, cause I'm sure it's O'Neil's", "'Hello,' she said, 'cause I'm sure it's O'Neil's")
    }

    @Test
    fun anArrowTypedOnAKeyboardIsTo() {
        says("a to b to c to d", "a -> b => c --> d")
        says("x to y and three to five", "x=>y and 3->5")
        says("a equals b and c greater than d", "a = b and c > d")
    }

    @Test
    fun aRunOfSentenceEndsIsTheFirstOne() {
        says("what? Really! Yes. ok.", "what?! Really!! Yes.. ok.")
        says("Done. Next, e.g. this", "Done. :) Next, e.g., this")
        assertEquals(listOf("Wait, what?", "Really?"), speechPieces("Wait... what?! Really?"))
    }

    @Test
    fun noPieceIsBarePunctuation() {
        assertEquals(listOf("Done."), speechPieces("Done. :)"))
        assertEquals(listOf("NET is fine."), speechPieces(".NET is fine."))
        for (text in listOf("?!", "...", ":)", ". . .", "\u2014", "- .", "'\"'", "!")) {
            assertEquals(emptyList(), speechPieces(text), "\"$text\"")
        }
    }

    @Test
    fun aTimeWithNoAmOrPmIsReadAsATime() {
        says("Meet me at three oh five PM or ten thirty AM, not twelve thirty.", "Meet me at 3:05 PM or 10:30am, not 12:30.")
        says("at nine oh five, twelve o'clock or eighteen hundred", "at 9:05, 12:00 or 18:00")
        says("from nine o'clock to ten thirty AM", "from 9:00\u201310:30 AM")
        says("one: twelve: thirty and sixteen: nine", "1:12:30 and 16:9")
    }

    @Test
    fun bracketsArePausesAroundWhatTheyHold() {
        says("the cache, the fast one, is ready", "the cache (the fast one) is ready")
        says("Use the list, a, b, and the map, k.", "Use the list [a, b] and the map {k}.")
        says("Yes. Then go.", "(Yes.) Then go.")
        says("Sure, really.", "Sure (really).")
    }

    @Test
    fun aBulletIsSilentAtTheStartOfALineAndAPauseBetweenWords() {
        says("Options:\nfirst\nsecond", "Options:\n\u2022 first\n  \u25E6 second")
        says("item", "\u25AA item")
        says("red, green, blue, black", "red \u00B7 green \u2022 blue \u2023 black")
    }

    @Test
    fun arrowsAreTo() {
        says("Settings to Voice to Kokoro to done", "Settings \u2192 Voice \u21D2 Kokoro\u279Cdone")
    }

    @Test
    fun aMarkWithASpokenNameKeepsIt() {
        says("twenty degrees, three times four, eight divided by two, plus or minus five", "20\u00B0, 3 \u00D7 4, 8 \u00F7 2, \u00B15")
        says("euros ten, pounds three, yen five hundred, five cents", "\u20AC10, \u00A33, \u00A5500, 5\u00A2")
        says("one half cup, one quarter, three quarters", "\u00BD cup, \u00BC, \u00BE")
        says("about ten, a not equal to b, x at most five, y at least two", "\u2248 10, a \u2260 b, x \u2264 5, y \u2265 2")
        says("section four, minus two, x squared, infinity", "\u00A7 4, \u22122, x\u00B2, \u221E")
    }

    @Test
    fun everyKindOfSpaceIsOneSpaceAndAZeroWidthOneVanishes() {
        says("ten km and a b", "10\u00A0km and a\u2009b")
        says("one hundred sixty milliseconds", "160\u202Fms")
        says("zerowidth joined", "zero\u200Bwidth\uFEFF join\u200Ded")
        says("Tabs\tand\nnew lines stay.", "Tabs\tand\nnew lines   stay.")
        says("", "   ")
    }

    @Test
    fun aMarkWithNoSoundIsDropped() {
        says("Great job!", "Great job \uD83C\uDF89!")
        says("Kokoro is ready", "Kokoro\u2122 is ready \u2705")
        says("the family and thumbs up", "the \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 family and thumbs \uD83D\uDC4D\uD83C\uDFFD up")
        says("one first", "1\uFE0F\u20E3 first")
        says("box drawing", "\u2502 box \u251C\u2500 drawing")
        says("two thousand twenty-six Acme", "\u00A92026 Acme\u00AE")
        says("great thanks", "great\uD83D\uDC4Dthanks")
        says("", "\uD83C\uDF89\u2026")
    }

    @Test
    fun noMarkIsEverCalledSymbolAndTheTextStaysTidy() {
        val marks = "\u2019\u2018\u02BC\u2032\u201C\u201D\u201E\u201F\u00AB\u00BB\u2039\u203A\"'`\u2014\u2013\u2015\u2012\u2026()[]{}" +
            "\u2022\u2023\u25E6\u25AA\u00B7\u2192\u21D2\u279C\u00A0\u2009\u200B\u200D\uFE0F\u2122\u00AE\u00A9\u2500\u2502" +
            "\uD83C\uDF89\uD83D\uDE00\uD83C\uDDFA\uD83C\uDDF8\uD83D\uDC4D\uD83C\uDFFD\u2764\u2B50\u00B6\u2020\u2605\uD83D"
        val texts = ArrayList<String>()
        for (mark in marks) {
            texts.add("Well ${mark} this is it.")
            texts.add("Well${mark}this is it.")
            texts.add("${mark}Well, this is it${mark}.")
            texts.add("Well, ${mark} this is it ${mark}")
            texts.add("Well. ${mark}${mark} This is it.")
        }
        texts.add("Emoji \uD83C\uDF89 and \u201Cquotes\u201D \u2014 dashes\u2026 and (parentheses) [brackets].")
        texts.add("caf\u00E92go and \u00E9 and e\u0301")
        for (text in texts) {
            val spoken = normalizeForSpeech(text)
            assertFalse("symbol" in spoken, "\"$text\" -> \"$spoken\"")
            for (untidy in listOf("  ", " ,", " .", ", ,", ",,", ",.")) {
                assertFalse(untidy in spoken, "\"$untidy\" in \"$spoken\" from \"$text\"")
            }
            assertEquals(spoken.trim(), spoken, "\"$text\"")
            assertEquals(spoken, normalizeForSpeech(spoken), "a second pass changes nothing: \"$text\"")
            for (piece in speechPieces(text)) assertTrue(piece.any { it.isLetterOrDigit() }, "\"$piece\" from \"$text\"")
        }
    }

    @Test
    fun asciiMarksWithANameStillSayIt() {
        says("five star three slash two", "5 * 3 / 2")
        says("a slash b and c underscore d", "a/b and c_d")
        says("See example dot com slash docs.", "See \u201Chttps://example.com/docs\u201D.")
        says("Install vee two.", "Install v2.")
    }

    @Test
    fun aShortSentenceHasNoOpeningClause() {
        assertNull(openingClause("Sure, I can help with that."))
        assertNull(openingClause("x".repeat(OPENING_MAX_CHARS - 12) + ", and more"))
        assertNull(openingClause(""))
    }

    @Test
    fun theOpeningClauseEndsAtTheLastCommaInTheWindow() {
        val cut = openingClause("I refactored the session code, moved the socket into the bridge, and the build is green again.")
        assertEquals("I refactored the session code, moved the socket into the bridge," to "and the build is green again.", cut)
        val early = openingClause("First we open the catalog; then we pick a companion and start a long conversation with it.")
        assertEquals("First we open the catalog;" to "then we pick a companion and start a long conversation with it.", early)
        assertEquals("one two three," to "four five six seven", openingClause("  one two three, four five six seven ", 5, 20))
    }

    @Test
    fun withNoCommaTheOpeningClauseEndsBeforeAConjunction() {
        val cut = openingClause("The build finished without a single warning and the tests all passed on the first run today.")
        assertEquals("The build finished without a single warning" to "and the tests all passed on the first run today.", cut)
        val because = openingClause("We should keep the old endpoint alive for now because three clients still call it every day.")
        assertEquals("We should keep the old endpoint alive for now" to "because three clients still call it every day.", because)
    }

    @Test
    fun theOpeningClauseIsNeverCutInsideANumber() {
        val digits = openingClause("The total for the whole order came to 1,000 dollars after tax which nobody had expected at all.")
        assertEquals("The total for the whole order came to 1,000 dollars after tax" to "which nobody had expected at all.", digits)
        assertNull(openingClause("Their account balance was exactly 1,000,000 dollars yesterday morning at nine sharp."))
        val words = openingClause("The server answered with error five hundred and three but the retry worked fine after it.")
        assertEquals("The server answered with error five hundred and three" to "but the retry worked fine after it.", words)
        assertNull(openingClause("The server answered with error five hundred and three after thirty seconds of waiting."))
    }

    @Test
    fun aSentenceWithNoClauseBoundaryIsLeftWhole() {
        assertNull(openingClause("Supercalifragilistic words keep going without any pause whatsoever until the very end here."))
        assertNull(openingClause("Well, supercalifragilistic words keep going without any pause whatsoever until the very end."), "the comma is before the window")
    }

    @Test
    fun theOpeningClauseLeavesARestWorthSpeaking() {
        assertNull(openingClause("one two three four five, six seven", 5, 30), "ten characters would be left")
        assertEquals("one two three four," to "five six seven", openingClause("one two three four, five six seven", 5, 30))
    }
}
