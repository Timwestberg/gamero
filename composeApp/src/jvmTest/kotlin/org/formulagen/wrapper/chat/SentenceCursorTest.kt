package org.formulagen.wrapper.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SentenceCursorTest {
    private val cursor = SentenceCursor()

    @Test fun oddTokenSplitsEnqueueOnlyClosedSentences() {
        assertEquals(emptyList(), cursor.feed("Hel", finish = false).pieces)
        assertEquals(emptyList(), cursor.feed("Hello", finish = false).pieces)
        assertEquals(listOf("Hello."), cursor.feed("Hello. Wor", finish = false).pieces)
        assertEquals(emptyList(), cursor.feed("Hello. World", finish = false).pieces)
        assertEquals(listOf("World is here."), cursor.feed("Hello. World is here. And th", finish = false).pieces)
        assertEquals(listOf("And then some."), cursor.feed("Hello. World is here. And then some.", finish = true).pieces)
    }

    @Test fun anUnclosedFenceIsHeldAndAClosedOneIsSummarized() {
        assertEquals(emptyList(), cursor.feed("Look at ```\nfun main() {", finish = false).pieces)
        val spoken = cursor.feed("Look at ```\nfun main() {\n}\n``` Done.", finish = true).pieces.joinToString(" ")
        assertTrue(spoken.contains("Code is shown in the chat."), spoken)
        assertFalse(spoken.contains("fun main"), spoken)
    }

    @Test fun listsAndMarkdownAreSpokenAsWords() {
        val pieces = cursor.feed("**Hello** [docs](https://example.test)\n- apples\n- pears.", finish = true).pieces
        val spoken = pieces.joinToString(" ")
        assertTrue(spoken.contains("Hello"), spoken)
        assertTrue(spoken.contains("docs"), spoken)
        assertTrue(spoken.contains("apples"), spoken)
        assertTrue(spoken.contains("pears"), spoken)
        assertFalse(spoken.contains("http"), spoken)
        assertFalse(spoken.contains("*"), spoken)
        assertFalse(spoken.contains("example.test"), spoken)
    }

    @Test fun theFirstPieceCanCutAtAnEarlyComma() {
        val opening = "Well, this opening keeps going without a period for quite a while"
        val cut = cursor.feed(opening, finish = false)
        assertEquals(listOf("Well,"), cut.pieces)
        assertEquals(emptyList(), cursor.feed(opening + " still", finish = false).pieces)
        val done = cursor.feed("$opening still.", finish = true)
        assertTrue(done.pieces.joinToString(" ").contains("still."), done.pieces.toString())
        assertFalse(done.pieces.any { it == "Well," })
    }

    @Test fun aReplacedReplyFlushesTheOldPrefix() {
        assertEquals(listOf("Hello."), cursor.feed("Hello. There", finish = false).pieces)
        val next = cursor.feed("Nope.", finish = false)
        assertTrue(next.replaced)
        assertEquals(listOf("Nope."), next.pieces)
    }
}
