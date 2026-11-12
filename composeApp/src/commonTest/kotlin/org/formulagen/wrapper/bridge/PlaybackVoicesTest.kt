package org.formulagen.wrapper.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackVoicesTest {
    private val sam = PlaybackVoice("adam70-michael30", "Sam")
    private val harper = PlaybackVoice("heart60-adam40", "Harper")

    @Test
    fun theVoiceInUseIsOneOfTheVoicesListed() {
        assertEquals(PlaybackVoices(harper.id, listOf(sam, harper)), playbackVoices(harper.id, listOf(sam, harper)))
        assertEquals(PlaybackVoices(null, listOf(sam)), playbackVoices(harper.id, listOf(sam)), "a voice not listed is not selected")
        assertEquals(PlaybackVoices(null, emptyList()), playbackVoices(null, emptyList()))
    }

    @Test
    fun theListStaysWithinTheContractsBounds() {
        val many = (1..VoicePlaybackLimits.VOICES + 4).map { PlaybackVoice("voice-$it", "Voice $it") }
        assertEquals(VoicePlaybackLimits.VOICES, playbackVoices(null, many).voices.size)
        val long = PlaybackVoice("long", "n".repeat(VoicePlaybackLimits.VOICE_NAME + 9))
        assertEquals(VoicePlaybackLimits.VOICE_NAME, playbackVoices(null, listOf(long)).voices.single().name.length)
        val unnamed = listOf(PlaybackVoice("", "No id"), PlaybackVoice("no-name", ""), PlaybackVoice("i".repeat(VoicePlaybackLimits.VOICE_ID + 1), "Long id"))
        assertEquals(emptyList(), playbackVoices(null, unnamed).voices)
    }

    @Test
    fun noVoiceInUseIsWrittenAsNull() {
        // The contract's `selected` is nullable, not optional: the key stays.
        assertEquals("""{"selected":null,"voices":[]}""", bridgeJson.encodeToString(PlaybackVoices.serializer(), PlaybackVoices(null, emptyList())))
        assertEquals(
            """{"selected":"heart60-adam40","voices":[{"id":"heart60-adam40","name":"Harper"}]}""",
            bridgeJson.encodeToString(PlaybackVoices.serializer(), PlaybackVoices(harper.id, listOf(harper))),
        )
        assertEquals(VoiceHello, voiceHello("Harper"))
    }

    private companion object {
        const val VoiceHello = "Hi, I'm Harper."
    }
}
