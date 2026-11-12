package org.formulagen.wrapper.bridge

import org.formulagen.wrapper.bridge.LocalSpeechQueue.Admission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSpeechQueueTest {
    @Test
    fun aSessionIsSpeakingUntilEverythingQueuedHasBeenSaid() {
        val queue = LocalSpeechQueue()
        assertEquals(Admission.START, queue.admit("one", replace = true))
        // The engine has not said a word yet: the gap before the first sound is still `speaking`.
        assertEquals("speaking", queue.status("one"))
        assertEquals(Admission.QUEUE, queue.admit("one", replace = false))
        assertEquals(Admission.QUEUE, queue.admit("one", replace = false))
        val ticket = queue.ticket
        assertFalse(queue.ended(ticket))
        assertFalse(queue.ended(ticket))
        assertEquals("speaking", queue.status("one"))
        assertTrue(queue.ended(ticket), "the last utterance empties the queue")
        assertEquals("done", queue.status("one"))
        assertFalse(queue.ended(ticket), "a report with nothing outstanding changes nothing")
    }

    @Test
    fun appendStartsSpeakingWhenTheSessionIsIdleOrDone() {
        val queue = LocalSpeechQueue()
        assertEquals(Admission.START, queue.admit("one", replace = false))
        assertTrue(queue.ended(queue.ticket))
        assertEquals("done", queue.status("one"))
        // The next sentence of a streamed reply arrives after the first one finished.
        assertEquals(Admission.START, queue.admit("one", replace = false))
        assertEquals("speaking", queue.status("one"))
    }

    @Test
    fun speakReplacesWhatTheSessionWasSayingAndLateReportsOfTheOldQueueDoNotCount() {
        val queue = LocalSpeechQueue()
        queue.admit("one", replace = true)
        queue.admit("one", replace = false)
        val old = queue.ticket
        assertEquals(Admission.START, queue.admit("one", replace = true))
        assertTrue(queue.ticket != old)
        // The engine reports the two flushed utterances as cancelled after the new one was queued.
        assertFalse(queue.ended(old))
        assertFalse(queue.ended(old))
        assertEquals("speaking", queue.status("one"))
        assertTrue(queue.ended(queue.ticket))
        assertEquals("done", queue.status("one"))
    }

    @Test
    fun anotherSessionCannotInterruptTheSpeakerButTakesOverOnceItIsDone() {
        val queue = LocalSpeechQueue()
        queue.admit("one", replace = true)
        for (replace in listOf(true, false)) assertEquals(Admission.BUSY, queue.admit("two", replace))
        assertEquals("error", queue.status("two"))
        assertEquals("one", queue.speaker)
        assertEquals("speaking", queue.status("one"))
        assertTrue(queue.ended(queue.ticket))
        assertEquals(Admission.START, queue.admit("two", replace = false))
        assertEquals("speaking", queue.status("two"))
        assertEquals("error", queue.status("one"), "only the session that owns the speaker has a playback state")
    }

    @Test
    fun aStoppedSessionStaysCancelled() {
        val queue = LocalSpeechQueue()
        queue.admit("one", replace = true)
        queue.admit("one", replace = false)
        val ticket = queue.ticket
        assertTrue(queue.stop("one"), "the engine must be flushed")
        assertEquals("cancelled", queue.status("one"))
        assertFalse(queue.speaking)
        assertFalse(queue.ended(ticket), "the flushed utterances report late")
        for (replace in listOf(true, false)) assertEquals(Admission.CANCELLED, queue.admit("one", replace))
        assertFalse(queue.stop("one"), "nothing is left to flush")
        // The speaker is free at once.
        assertEquals(Admission.START, queue.admit("two", replace = true))
    }

    @Test
    fun stoppingASessionThatNeverSpokeLeavesTheSpeakerAlone() {
        val queue = LocalSpeechQueue()
        queue.admit("one", replace = true)
        assertFalse(queue.stop("two"))
        assertEquals("cancelled", queue.status("two"))
        assertEquals(Admission.CANCELLED, queue.admit("two", replace = true), "a stop that arrives before its speak wins")
        assertEquals("speaking", queue.status("one"))
    }

    @Test
    fun anInterruptionCancelsOnlyALiveSpeaker() {
        val queue = LocalSpeechQueue()
        assertFalse(queue.interrupt())
        assertNull(queue.speaker)
        queue.admit("one", replace = true)
        assertTrue(queue.ended(queue.ticket))
        assertFalse(queue.interrupt(), "a finished reply is not cancelled after the fact")
        assertEquals("done", queue.status("one"))
        queue.admit("one", replace = false)
        assertTrue(queue.interrupt())
        assertEquals("cancelled", queue.status("one"))
    }

    @Test
    fun anEngineThatStallsIsAnErrorNotAnEndlessSpeaking() {
        val queue = LocalSpeechQueue()
        queue.admit("one", replace = true)
        queue.admit("one", replace = false)
        val ticket = queue.ticket
        assertTrue(queue.fail(ticket), "the live queue must be dropped")
        assertEquals("error", queue.status("one"))
        assertFalse(queue.speaking)
        assertFalse(queue.fail(ticket), "a watchdog of a queue that is gone does nothing")
        assertFalse(queue.ended(ticket))
        // The session may try again, and another may take the speaker.
        assertEquals(Admission.START, queue.admit("one", replace = false))
        assertEquals("speaking", queue.status("one"))
    }

    @Test
    fun onlyTheLastThirtyTwoStoppedSessionsAreRemembered() {
        val queue = LocalSpeechQueue()
        for (index in 0..32) queue.stop("session-$index")
        assertEquals("error", queue.status("session-0"))
        assertEquals("cancelled", queue.status("session-1"))
        assertEquals("cancelled", queue.status("session-32"))
    }

    private val ava = InstalledVoice("com.apple.voice.premium.en-US.Ava", "en-US", 3)
    private val evan = InstalledVoice("com.apple.voice.enhanced.en-US.Evan", "en-US", 2)
    private val samantha = InstalledVoice("com.apple.voice.compact.en-US.Samantha", "en-US", 1)
    private val danielPremium = InstalledVoice("com.apple.voice.premium.en-GB.Daniel", "en-GB", 3)
    private val bells = InstalledVoice("com.apple.speech.synthesis.voice.Bells", "en-US", 3, novelty = true)

    @Test
    fun theBestVoiceIsPremiumThenEnhancedOfTheDeviceLocale() {
        assertEquals(ava.id, bestInstalledVoice(listOf(samantha, evan, ava, danielPremium), "en-US"))
        assertEquals(evan.id, bestInstalledVoice(listOf(samantha, evan, danielPremium), "en_US"), "the device's own accent before a better voice of another")
        assertEquals(danielPremium.id, bestInstalledVoice(listOf(samantha, danielPremium), "en-US"), "a downloaded voice of the language before a default one")
    }

    @Test
    fun withOnlyDefaultVoicesTheSystemChoosesAndANoveltyVoiceIsNeverPicked() {
        assertNull(bestInstalledVoice(listOf(samantha, bells), "en-US"))
        assertNull(bestInstalledVoice(listOf(ava, evan), "de-DE"))
        assertNull(bestInstalledVoice(emptyList(), "en-US"))
    }

    @Test
    fun aChineseVoiceOfAnotherRegionIsAnotherLanguage() {
        val cantonese = InstalledVoice("com.apple.voice.premium.zh-HK.Sinji", "zh-HK", 3)
        assertNull(bestInstalledVoice(listOf(cantonese), "zh-CN"))
        assertEquals(cantonese.id, bestInstalledVoice(listOf(cantonese), "zh-HK"))
    }

    private val recognizers = listOf("en-US", "en_GB", "en-AU", "de-DE", "pt-BR", "pt-PT", "zh-CN", "ja-JP")

    @Test
    fun theRecognizerFollowsThePageLanguage() {
        assertEquals("de-DE", bestSpeechLocale("de-DE", listOf("en-US"), recognizers))
        assertEquals("de-DE", bestSpeechLocale("de", listOf("en-US"), recognizers), "a bare language takes its home region")
        assertEquals("en_GB", bestSpeechLocale("en", listOf("en-GB", "en_GB"), recognizers), "the device's region of the page's language, spelled as iOS spells it")
        assertEquals("en-US", bestSpeechLocale("en-NZ", listOf("de-DE"), recognizers))
        assertEquals("ja-JP", bestSpeechLocale("ja", listOf("en-US"), recognizers))
        assertEquals("pt-PT", bestSpeechLocale("pt", listOf("en-US"), recognizers))
        assertEquals("pt-BR", bestSpeechLocale("pt", listOf("en-US", "pt-BR"), recognizers))
    }

    @Test
    fun theRecognizerFallsBackToTheDeviceLocale() {
        assertEquals("en-AU", bestSpeechLocale(null, listOf("en-AU", "en_AU"), recognizers))
        assertEquals("en-US", bestSpeechLocale("xx-YY", listOf("fr-FR", "en-DE"), recognizers), "the first device language that has a recognizer")
        assertNull(bestSpeechLocale("xx", listOf("fr-FR"), recognizers))
        assertNull(bestSpeechLocale(null, emptyList(), recognizers))
    }

    @Test
    fun aLongerUtteranceHasLongerToFinish() {
        assertEquals(SPEECH_START_BUDGET_MS, speechBudgetMs(0))
        assertEquals(SPEECH_START_BUDGET_MS + 30_000, speechBudgetMs(100))
    }
}
