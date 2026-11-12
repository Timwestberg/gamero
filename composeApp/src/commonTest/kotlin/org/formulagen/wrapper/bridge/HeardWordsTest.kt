package org.formulagen.wrapper.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

class HeardWordsTest {
    @Test
    fun wordsGrowWithinOneStretch() {
        val heard = HeardWords()
        assertEquals("Tell me", heard.hear("Tell me", closedStretch = false))
        assertEquals("Tell me about tea", heard.hear("Tell me about tea", closedStretch = false))
        assertEquals("Tell me about tea", heard.all)
    }

    @Test
    fun aStretchClosedByAPauseIsKeptWhenTheNextStartsFromNothing() {
        val heard = HeardWords()
        heard.hear("Tell me about tea", closedStretch = true)
        assertEquals("Tell me about tea", heard.hear("", closedStretch = false))
        assertEquals("Tell me about tea and coffee", heard.hear("and coffee", closedStretch = false))
    }

    @Test
    fun anEmptyOrRepeatedLastReportAddsNothing() {
        val heard = HeardWords()
        heard.hear("Tell me about tea", closedStretch = true)
        // Asked to finish, the recognizer answers with nothing, or with the stretch it already closed.
        assertEquals("Tell me about tea", heard.hear("", closedStretch = true))
        assertEquals("Tell me about tea", heard.hear("Tell me about tea", closedStretch = true))
        assertEquals("Tell me about tea", heard.hear("about tea", closedStretch = true))
    }

    @Test
    fun aRecognizerThatGoesOnFromItsClosedStretchIsNotDoubled() {
        val heard = HeardWords()
        heard.hear("Tell me", closedStretch = true)
        assertEquals("Tell me about tea", heard.hear("Tell me about tea", closedStretch = false))
    }

    @Test
    fun anErrorAfterWordsKeepsTheWordsAndSilenceIsNotAFailure() {
        assertEquals(RecognitionEnd.HEARD, recognitionEnd("Tell me about tea", noSpeech = false))
        assertEquals(RecognitionEnd.HEARD, recognitionEnd("Tell me about tea", noSpeech = true))
        assertEquals(RecognitionEnd.SILENCE, recognitionEnd(" ", noSpeech = true))
        assertEquals(RecognitionEnd.FAILED, recognitionEnd("", noSpeech = false))
    }
}
