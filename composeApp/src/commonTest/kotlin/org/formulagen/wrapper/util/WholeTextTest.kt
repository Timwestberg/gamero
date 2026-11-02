package org.formulagen.wrapper.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cutting text to a limit never leaves half a character. */
class WholeTextTest {
    private val fire = "🔥"

    private fun whole(text: String) =
        text.indices.none { text[it].isHighSurrogate() && (it + 1 >= text.length || !text[it + 1].isLowSurrogate()) } &&
            text.indices.none { text[it].isLowSurrogate() && (it == 0 || !text[it - 1].isHighSurrogate()) }

    @Test fun takeWholeKeepsWithinTheLimitAndNeverSplitsAPair() {
        assertEquals("abc", "abc".takeWhole(5))
        assertEquals("ab", "abc".takeWhole(2))
        assertEquals("a", "a$fire".takeWhole(2))
        assertEquals("a$fire", "a$fire".takeWhole(3))
        assertEquals("", fire.takeWhole(1))
        for (limit in 0..12) assertTrue(whole("x$fire$fire$fire$fire".takeWhole(limit)), "$limit")
    }

    @Test fun shortenedMarksACutAndNeverSplitsAPair() {
        assertEquals("abc", "abc".shortened(3))
        assertEquals("ab…", "abcd".shortened(3))
        assertEquals("a…", "a$fire$fire".shortened(3))
        for (limit in 2..12) {
            val cut = "x$fire$fire$fire$fire$fire".shortened(limit)
            assertTrue(cut.length <= limit && whole(cut), "$limit: $cut")
        }
    }
}
