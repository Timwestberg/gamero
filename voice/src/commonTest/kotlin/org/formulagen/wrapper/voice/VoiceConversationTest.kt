package org.formulagen.wrapper.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceConversationTest {
    @Test fun theSharedRecipeIsKokoroWithTheFourBlendsAndFillersOff() {
        assertEquals(VoiceConfig.KOKORO, VoiceConversation.ENGINE)
        assertEquals(160, VoiceConversation.SENTENCE_GAP_MS)
        assertFalse(VoiceConversation.FILLERS_DEFAULT)
        assertEquals("adam70-michael30", VoiceConversation.defaultVoiceId())
        assertEquals(
            listOf(
                "adam70-michael30" to "1.00",
                "adam60-george40" to "0.95",
                "adam55-bella45" to "0.95",
                "heart60-adam40" to "0.97",
            ),
            VoiceConversation.voices().map { it.id to it.speed },
        )
        assertEquals(listOf("Sam", "Jamie", "Riley", "Harper"), VoiceConversation.voices().map { it.name })
    }

    @Test fun modelAndLexiconChecksumsMatchThePinnedRecipe() {
        val sums = VoiceConversation.checksums()
        assertEquals("b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11", sums.modelOnnxSha256)
        assertEquals("6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425", sums.tokensSha256)
        assertEquals("7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14", sums.lexiconSha256)
        assertEquals("1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29", sums.upstreamVoicesSha256)
        assertEquals("35cce6c473585fd74244f31c2623d6f2f835283918dad6fa2e2a79d60639b26f", sums.derivedVoicesSha256)
        assertEquals(HearingModelPins.SILERO_VAD_SHA256, sums.sileroVadSha256)
        assertEquals(HearingModelPins.ZIPFORMER_ARCHIVE_SHA256, sums.zipformerArchiveSha256)
        assertEquals(HearingModelPins.ENCODER_SHA256, sums.zipformerEncoderSha256)
        assertEquals(HearingModelPins.DECODER_SHA256, sums.zipformerDecoderSha256)
        assertEquals(HearingModelPins.JOINER_SHA256, sums.zipformerJoinerSha256)
        assertEquals(HearingModelPins.TOKENS_SHA256, sums.zipformerTokensSha256)
        assertTrue(sums.modelOnnxSha256.length == 64 && sums.sileroVadSha256.length == 64)
    }

    @Test fun endpointDiscardBargeInAndLatencyUseOneSetOfNames() {
        assertEquals(listOf("vad_silence", "text_stable", "cap"), EndpointReason.entries.map { it.wire })
        assertEquals(listOf("no_vad_speech", "self_echo", "too_short"), DiscardReason.entries.map { it.wire })
        val barge = BargeInPolicy()
        assertEquals(250, barge.sustainMs)
        assertEquals(0.5f, barge.minProbability)
        assertEquals(60, barge.echoOverlapPercent)
        assertEquals(50, barge.flushCapMs)
        assertEquals(80L, LatencyMetric.ENDPOINT_TO_SEND_CAP_MS)
        assertEquals(280L, LatencyMetric.ENQUEUE_TO_SPEAKER_CAP_MS)
        assertEquals(50L, LatencyMetric.FLUSH_CAP_MS)
        assertEquals(DiscardReason.TOO_SHORT.wire, screenHeardTurn("I", vadSpeechMs = 1000, maxProb = 0.2f, emberHot = true, emberText = "hello there"))
        assertEquals(DiscardReason.SELF_ECHO.wire, screenHeardTurn("hello there friend", vadSpeechMs = 400, maxProb = 0.9f, emberHot = true, emberText = "hello there friend"))
        assertNull(screenHeardTurn("turn please now", vadSpeechMs = 400, maxProb = 0.9f, emberHot = false, emberText = ""))
    }

    @Test fun aBrowserWithNoHostIsUnavailableAndDoesNotAskForAMicrophone() {
        val step = hearingWithoutHost()
        assertEquals(HearingStates.UNAVAILABLE, step.state)
        assertFalse(step.requestMicrophone)
        assertEquals("Open Hovel on your Mac to connect…", VoiceConversation.NO_HOST_NOTICE)
    }

    @Test fun theCatalogOwnsTheControlsAndTheLauncherDoesNot() {
        assertTrue(catalogOwnsConversationControls(VoiceConversation.CATALOG_PRODUCT))
        assertFalse(catalogOwnsConversationControls(VoiceConversation.LAUNCHER_PRODUCT))
        assertEquals("Half duplex · aec-unavailable", duplexStatusLabel(HearingDuplex.HALF, "aec-unavailable"))
        assertEquals("Full duplex", duplexStatusLabel(HearingDuplex.FULL, "self-check-passed"))
    }

    @Test fun hearingActionsMoveThroughTheSharedStates() {
        assertEquals(HearingStates.LISTENING, reduceHearing(HearingStates.OFF, HearingActions.PRESS, hostPresent = true, permissionGranted = true).state)
        val asking = reduceHearing(HearingStates.OFF, HearingActions.PRESS, hostPresent = true, permissionGranted = false)
        assertEquals(HearingStates.REQUESTING, asking.state)
        assertTrue(asking.requestMicrophone)
        assertEquals(HearingStates.MUTED, reduceHearing(HearingStates.LISTENING, HearingActions.MUTE, hostPresent = true, permissionGranted = true).state)
        assertEquals(HearingStates.LISTENING, reduceHearing(HearingStates.MUTED, HearingActions.UNMUTE, hostPresent = true, permissionGranted = true).state)
        assertEquals(HearingStates.OFF, reduceHearing(HearingStates.HEARING, HearingActions.RELEASE, hostPresent = true, permissionGranted = true).state)
        assertEquals(HearingStates.HEARING, catalogHearingState(hostPresent = true, armed = true, muted = false, phase = "listening", partial = "hello", unavailable = false))
        assertEquals(HearingStates.THINKING, catalogHearingState(hostPresent = true, armed = true, muted = false, phase = "thinking", partial = "", unavailable = false))
        assertEquals(HearingStates.UNAVAILABLE, catalogHearingState(hostPresent = false, armed = true, muted = false, phase = "listening", partial = "", unavailable = false))
    }
}
