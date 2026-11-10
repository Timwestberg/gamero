package org.formulagen.wrapper.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KokoroLexiconWordsTest {
    private val base = "kokoro k ˈ O k ə ɹ O\nmisaki m i s ˈ ɑ k i\nNASA n ˈ æ s ə\n"
    private val chat = "kokoros k ˈ O k ə ɹ O z\nwalked w ˈ ɔ k t"

    private fun words(vararg files: String) = LexiconWords(files.map { it.encodeToByteArray() })

    @Test
    fun aWordOfAnyFileIsKnownWhateverItsCase() {
        val known = words(base, chat)
        for (word in listOf("kokoro", "misaki", "nasa", "NASA", "Kokoro", "kokoros", "walked")) {
            assertTrue(word in known, "$word is in a lexicon")
        }
        assertEquals(5, known.size)
    }

    @Test
    fun aWordNoFileListsIsUnknown() {
        val known = words(base, chat)
        for (word in listOf("kokor", "kokoroo", "k", "walk", "", "ˈ")) {
            assertFalse(word in known, "\"$word\" is in no lexicon")
        }
    }

    @Test
    fun aLastLineWithNoLineEndStillCounts() {
        assertTrue("walked" in words(chat))
    }

    @Test
    fun aLineWithNoPronunciationAddsNothing() {
        val known = words("orphan\n\nkokoro k ˈ O\n")
        assertFalse("orphan" in known)
        assertTrue("kokoro" in known)
        assertEquals(1, known.size)
    }

    @Test
    fun itAgreesWithTheParsedLexicon() {
        val parsed = parseKokoroLexicon(base + chat)
        val known = words(base, chat)
        for (word in parsed.keys) assertTrue(word in known, "$word was parsed, so it is known")
    }

    @Test
    fun unknownWordsAreSpelledAndKnownOnesLeftAsWritten() {
        val known = words(base, "a ˈ A\nb b ˈ i\nc s ˈ i\n")
        assertEquals("Kokoro a b c", coverUnknownWords("Kokoro abc", known))
    }

    @Test
    fun onlyMembershipCanBeAsked() {
        assertFailsWith<UnsupportedOperationException> { words(base).iterator() }
    }

    @Test
    fun noFilesKnowNoWords() {
        val known = LexiconWords(emptyList())
        assertFalse("kokoro" in known)
        assertEquals(0, known.size)
    }
}
