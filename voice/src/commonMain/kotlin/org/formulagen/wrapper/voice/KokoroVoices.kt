package org.formulagen.wrapper.voice

/**
 * The Kokoro voices a person can pick. [KOKORO_BLENDS_JSON] is the same recipe
 * as voice/kokoro/blends.json. The packer reads the file. The app reads this
 * copy, so a build does not need the repo checkout. Adding a blend is a new
 * object in that recipe; the engine only asks this catalog for a sid and a speed.
 *
 * Slots 50 through 53 overwrite zm_yunxi, zm_yunxia, zm_yunyang, and em_santa.
 * Kokoro v1.0 has no spare speaker id without an ONNX metadata rebuild.
 */
internal const val KOKORO_BLENDS_JSON = """
{
  "defaultId": "adam70-michael30",
  "modelOnnxSha256": "b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11",
  "tokensSha256": "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425",
  "lexiconUsEnSha256": "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14",
  "upstreamVoicesSha256": "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29",
  "derivedVoicesSha256": "35cce6c473585fd74244f31c2623d6f2f835283918dad6fa2e2a79d60639b26f",
  "replacedSlots": "50 zm_yunxi, 51 zm_yunxia, 52 zm_yunyang, 53 em_santa",
  "layout": {
    "speakers": 54,
    "frames": 510,
    "dim": 256
  },
  "speakers": {
    "af_bella": 2,
    "af_heart": 3,
    "am_adam": 11,
    "am_michael": 16,
    "bm_george": 26
  },
  "voices": [
    {
      "id": "adam70-michael30",
      "name": "Sam",
      "sid": 50,
      "speed": "1.00",
      "weights": [
        {"speaker": "am_adam", "weight": "0.70"},
        {"speaker": "am_michael", "weight": "0.30"}
      ]
    },
    {
      "id": "adam60-george40",
      "name": "Jamie",
      "sid": 51,
      "speed": "0.95",
      "weights": [
        {"speaker": "am_adam", "weight": "0.60"},
        {"speaker": "bm_george", "weight": "0.40"}
      ]
    },
    {
      "id": "adam55-bella45",
      "name": "Riley",
      "sid": 52,
      "speed": "0.95",
      "weights": [
        {"speaker": "am_adam", "weight": "0.55"},
        {"speaker": "af_bella", "weight": "0.45"}
      ]
    },
    {
      "id": "heart60-adam40",
      "name": "Harper",
      "sid": 53,
      "speed": "0.97",
      "weights": [
        {"speaker": "af_heart", "weight": "0.60"},
        {"speaker": "am_adam", "weight": "0.40"}
      ]
    }
  ]
}
"""

data class KokoroWeight(
    val speaker: String,
    val sourceSid: Int,
    val weight: Float,
)

data class KokoroVoice(
    val id: String,
    val name: String,
    val sid: Int,
    val speedText: String,
    val speed: Float,
    val weights: List<KokoroWeight>,
)

data class KokoroCatalog(
    val defaultId: String,
    val modelSha256: String,
    val tokensSha256: String,
    val lexiconSha256: String,
    val upstreamVoicesSha256: String,
    val derivedVoicesSha256: String,
    val speakerCount: Int,
    val frames: Int,
    val dim: Int,
    val speakers: Map<String, Int>,
    val voices: List<KokoroVoice>,
)

/** What ember-voice.v2 still needs when the engine is Kokoro. Pocket fields do not apply. */
internal data class KokoroVoiceContract(
    val engine: String,
    val voiceId: String,
    val speed: String,
    val lexiconSha256: String,
)

internal object KokoroBlends {
    val catalog: KokoroCatalog by lazy { parseKokoroBlends(KOKORO_BLENDS_JSON) }
}

fun kokoroCatalog(): KokoroCatalog = KokoroBlends.catalog

fun kokoroVoices(): List<KokoroVoice> = kokoroCatalog().voices

fun kokoroVoice(id: String): KokoroVoice? = kokoroVoices().firstOrNull { it.id == id }

internal fun kokoroVoiceContract(id: String = kokoroCatalog().defaultId): KokoroVoiceContract {
    val voice = kokoroVoice(id) ?: throw IllegalArgumentException("Unknown Kokoro voice '$id'.")
    return KokoroVoiceContract(
        engine = VoiceConfig.KOKORO,
        voiceId = voice.id,
        speed = voice.speedText,
        lexiconSha256 = kokoroCatalog().lexiconSha256,
    )
}

internal fun parseKokoroBlends(json: String): KokoroCatalog {
    val root = JsonParser(json.trim()).parseValue() as? Map<*, *> ?: error("Kokoro recipe must be an object")
    val layout = map(root, "layout")
    val speakerMap = map(root, "speakers")
    val speakers = LinkedHashMap<String, Int>()
    for ((key, value) in speakerMap) {
        speakers[key.toString()] = jsonInt(value)
    }
    val voices = ArrayList<KokoroVoice>()
    for (item in list(root, "voices")) {
        val voice = item as? Map<*, *> ?: error("Kokoro voice must be an object")
        val weights = ArrayList<KokoroWeight>()
        val rawWeights = voice["weights"] as? List<*> ?: error("Kokoro voice ${voice["id"]} needs weights")
        for (weight in rawWeights) {
            val entry = weight as? Map<*, *> ?: error("Kokoro weight must be an object")
            val speaker = text(entry, "speaker")
            val source = speakers[speaker] ?: error("Kokoro weight uses unknown speaker $speaker")
            weights.add(KokoroWeight(speaker, source, text(entry, "weight").toFloat()))
        }
        val speedText = text(voice, "speed")
        voices.add(
            KokoroVoice(
                id = text(voice, "id"),
                name = text(voice, "name"),
                sid = jsonInt(voice["sid"]),
                speedText = speedText,
                speed = speedText.toFloat(),
                weights = weights,
            ),
        )
    }
    return KokoroCatalog(
        defaultId = text(root, "defaultId"),
        modelSha256 = text(root, "modelOnnxSha256"),
        tokensSha256 = text(root, "tokensSha256"),
        lexiconSha256 = text(root, "lexiconUsEnSha256"),
        upstreamVoicesSha256 = text(root, "upstreamVoicesSha256"),
        derivedVoicesSha256 = text(root, "derivedVoicesSha256"),
        speakerCount = jsonInt(layout["speakers"]),
        frames = jsonInt(layout["frames"]),
        dim = jsonInt(layout["dim"]),
        speakers = speakers,
        voices = voices,
    )
}

private fun map(root: Map<*, *>, key: String): Map<*, *> =
    root[key] as? Map<*, *> ?: error("Kokoro recipe needs $key")

private fun list(root: Map<*, *>, key: String): List<*> =
    root[key] as? List<*> ?: error("Kokoro recipe needs $key")

private fun text(root: Map<*, *>, key: String): String =
    root[key] as? String ?: error("Kokoro recipe needs $key")

private fun jsonInt(value: Any?): Int = when (value) {
    is Long -> value.toInt()
    is Int -> value
    is Double -> value.toInt()
    else -> error("expected a number, got $value")
}

private class JsonParser(private val text: String) {
    private var index = 0

    fun parseValue(): Any? {
        skip()
        if (index >= text.length) fail("unexpected end")
        return when (val ch = text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (ch == '-' || ch.isDigit()) parseNumber() else fail("unexpected '$ch'")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val values = LinkedHashMap<String, Any?>()
        skip()
        if (peek('}')) {
            index += 1
            return values
        }
        while (true) {
            skip()
            val key = parseString()
            skip()
            expect(':')
            values[key] = parseValue()
            skip()
            when (text[index]) {
                ',' -> index += 1
                '}' -> {
                    index += 1
                    return values
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val values = ArrayList<Any?>()
        skip()
        if (peek(']')) {
            index += 1
            return values
        }
        while (true) {
            values.add(parseValue())
            skip()
            when (text[index]) {
                ',' -> index += 1
                ']' -> {
                    index += 1
                    return values
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < text.length) {
            val ch = text[index]
            index += 1
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= text.length) fail("bad escape")
                    val escaped = text[index]
                    index += 1
                    out.append(
                        when (escaped) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> fail("bad escape")
                        },
                    )
                }
                else -> out.append(ch)
            }
        }
        fail("unterminated string")
    }

    private fun parseNumber(): Long {
        val start = index
        if (text[index] == '-') index += 1
        while (index < text.length && text[index].isDigit()) index += 1
        if (index < text.length && text[index] == '.') fail("Kokoro recipe numbers are integers")
        return text.substring(start, index).toLong()
    }

    private fun literal(token: String, value: Any?): Any? {
        if (!text.startsWith(token, index)) fail("expected $token")
        index += token.length
        return value
    }

    private fun expect(ch: Char) {
        skip()
        if (index >= text.length || text[index] != ch) fail("expected '$ch'")
        index += 1
    }

    private fun peek(ch: Char): Boolean = index < text.length && text[index] == ch

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) index += 1
    }

    private fun fail(message: String): Nothing = error("$message at $index")
}
