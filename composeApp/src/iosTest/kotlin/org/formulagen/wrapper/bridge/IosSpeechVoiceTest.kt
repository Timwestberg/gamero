@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.formulagen.wrapper.bridge

import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.Foundation.NSLocale
import platform.Foundation.localeIdentifier
import platform.Speech.SFSpeechRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Apple's own lists, as this runtime reports them, through the app's choosers. */
class IosSpeechVoiceTest {
    @Test
    fun everyInstalledVoiceRanksAndTheChoiceIsARealVoiceOfTheLanguage() {
        val voices = AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>()
        val ranked = voices.map(::installedVoice)
        // The simulator has Apple's novelty voices (Bells, Bad News ...), and none of them may ever be chosen.
        assertTrue(ranked.all { it.quality in 1..3 && it.id.isNotEmpty() && it.language.isNotEmpty() })
        for (language in listOf("en-US", "en-GB", "de-DE", "ja-JP")) {
            val best = bestInstalledVoice(ranked, language) ?: continue
            val voice = ranked.single { it.id == best }
            assertTrue(voice.quality >= 2 && !voice.novelty, best)
            assertEquals(language.substringBefore('-'), voice.language.substringBefore('-'))
        }
    }

    @Test
    fun theRecognizerChoiceIsOneOfApplesLocales() {
        val supported = SFSpeechRecognizer.supportedLocales().filterIsInstance<NSLocale>().map { it.localeIdentifier }
        if (supported.isEmpty()) return
        for (requested in listOf("en", "en-US", "en-GB", "de", "fr-FR")) {
            val chosen = bestSpeechLocale(requested, listOf("en-US"), supported) ?: continue
            assertTrue(chosen in supported, "$requested -> $chosen")
            assertEquals(requested.substringBefore('-'), chosen.replace('_', '-').substringBefore('-').lowercase())
        }
    }
}
