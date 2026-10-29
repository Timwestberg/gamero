package org.formulagen.wrapper.voice

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The web port reads this fixture. Every row is produced by the Kotlin policy,
 * so a hand-edited web copy fails here and the browser test fails on the same file.
 */
class HearingTransitionFixtureTest {
    private val json = Json { prettyPrint = true }

    @Test fun theWebFixtureMatchesTheSharedPolicy() {
        val built = json.encodeToString(JsonObject.serializer(), fixture())
        val path = fixturePath()
        if (!Files.exists(path) || System.getProperty("hearing.fixture.write") == "true") {
            Files.createDirectories(path.parent)
            Files.writeString(path, built)
        }
        val parsed = json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals(json.parseToJsonElement(built).jsonObject, parsed)
    }

    private fun fixture(): JsonObject = buildJsonObject {
        put("schema", "ember-hearing-transitions.v1")
        put("policy", buildJsonObject {
            put("silenceMs", HEARING_ENDPOINT_SILENCE_MS)
            put("minSpeechMs", HEARING_MIN_SPEECH_MS)
            put("guardMs", HEARING_RESUME_GUARD_MS)
            put("bargeSustainMs", BargeInPolicy().sustainMs)
            put("bargeMinProbability", BargeInPolicy().minProbability)
            put("bargeGapResetMs", HEARING_BARGE_TALK_GAP_MS)
            put("echoOverlapPercent", BargeInPolicy().echoOverlapPercent)
            put("shortWordLimit", BargeInPolicy().shortWordLimit)
            put("sentenceGapMs", VoiceConversation.SENTENCE_GAP_MS)
            put("fillersDefault", VoiceConversation.FILLERS_DEFAULT)
            put("sampleRate", HEARING_SAMPLE_RATE)
            put("vadWindow", 512)
        })
        put("defaultVoiceId", VoiceConversation.defaultVoiceId())
        put("voices", buildJsonArray {
            VoiceConversation.voices().forEach { voice ->
                add(buildJsonObject {
                    put("id", voice.id)
                    put("name", voice.name)
                    put("speed", voice.speed)
                })
            }
        })
        val sums = VoiceConversation.checksums()
        put("checksums", buildJsonObject {
            put("modelOnnxSha256", sums.modelOnnxSha256)
            put("tokensSha256", sums.tokensSha256)
            put("lexiconSha256", sums.lexiconSha256)
            put("upstreamVoicesSha256", sums.upstreamVoicesSha256)
            put("derivedVoicesSha256", sums.derivedVoicesSha256)
            put("sileroVadSha256", sums.sileroVadSha256)
            put("zipformerArchiveSha256", sums.zipformerArchiveSha256)
            put("zipformerEncoderSha256", sums.zipformerEncoderSha256)
            put("zipformerDecoderSha256", sums.zipformerDecoderSha256)
            put("zipformerJoinerSha256", sums.zipformerJoinerSha256)
            put("zipformerTokensSha256", sums.zipformerTokensSha256)
        })
        put("permissionCopy", VoiceConversation.PERMISSION_COPY)
        put("noHostNotice", VoiceConversation.NO_HOST_NOTICE)
        put("reduceHearing", reduceRows())
        put("catalogHearing", catalogRows())
        put("screenHeard", screenRows())
    }

    private fun reduceRows(): JsonArray = buildJsonArray {
        for (state in HearingStates.ALL) {
            for (action in HearingActions.ALL) {
                for (host in listOf(false, true)) {
                    for (permission in listOf(false, true)) {
                        val step = reduceHearing(state, action, host, permission)
                        add(buildJsonObject {
                            put("state", state)
                            put("action", action)
                            put("hostPresent", host)
                            put("permissionGranted", permission)
                            put("next", step.state)
                            put("requestMicrophone", step.requestMicrophone)
                        })
                    }
                }
            }
        }
    }

    private fun catalogRows(): JsonArray = buildJsonArray {
        val phases = listOf("off", "listening", "thinking", "speaking", "other")
        val partials = listOf("", "hello")
        for (host in listOf(false, true)) {
            for (armed in listOf(false, true)) {
                for (muted in listOf(false, true)) {
                    for (unavailable in listOf(false, true)) {
                        for (phase in phases) {
                            for (partial in partials) {
                                add(buildJsonObject {
                                    put("hostPresent", host)
                                    put("armed", armed)
                                    put("muted", muted)
                                    put("unavailable", unavailable)
                                    put("phase", phase)
                                    put("partial", partial)
                                    put("state", catalogHearingState(host, armed, muted, phase, partial, unavailable))
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun screenRows(): JsonArray = buildJsonArray {
        val texts = listOf("", "I", "hi", "hello there friend", "turn please now")
        val vad = listOf(0, 100, 239, 240, 400, 1000)
        val probs = listOf(0.2f, 0.49f, 0.5f, 0.9f)
        val emberTexts = listOf("", "hello there friend")
        for (text in texts) {
            for (ms in vad) {
                for (prob in probs) {
                    for (hot in listOf(false, true)) {
                        for (ember in emberTexts) {
                            val discard = screenHeardTurn(text, ms, prob, hot, ember)
                            add(buildJsonObject {
                                put("text", text)
                                put("vadSpeechMs", ms)
                                put("maxProb", prob)
                                put("emberHot", hot)
                                put("emberText", ember)
                                put("discard", discard?.let { JsonPrimitive(it) } ?: JsonNull)
                            })
                        }
                    }
                }
            }
        }
    }

    private fun fixturePath(): Path {
        val name = Path.of("web/hearing/fixtures/hearing-transitions.json")
        var dir = Path.of("").toAbsolutePath()
        repeat(4) {
            val candidate = dir.resolve(name)
            if (Files.exists(candidate) || Files.isDirectory(dir.resolve("web"))) return candidate
            dir = dir.parent ?: return Path.of("").toAbsolutePath().resolve(name)
        }
        return Path.of("").toAbsolutePath().resolve(name)
    }
}
