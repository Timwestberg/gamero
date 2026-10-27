package ember.voice.bench

class JsonException(message: String) : RuntimeException(message)

sealed class Json {
    data class Obj(val fields: Map<String, Json>) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Str(val value: String) : Json()
    data class Num(val value: Double) : Json()
    data class Bool(val value: Boolean) : Json()
    data object Null : Json()

    fun obj(): Map<String, Json> = (this as? Obj)?.fields ?: throw JsonException("expected object")
    fun arr(): List<Json> = (this as? Arr)?.items ?: throw JsonException("expected array")
    fun str(): String = (this as? Str)?.value ?: throw JsonException("expected string")
    fun num(): Double = when (this) {
        is Num -> value
        is Str -> value.toDouble()
        else -> throw JsonException("expected number")
    }
    fun bool(): Boolean = (this as? Bool)?.value ?: throw JsonException("expected bool")
    fun field(name: String): Json = obj()[name] ?: throw JsonException("missing $name")
    fun optional(name: String): Json? = obj()[name]
}

fun parseJson(text: String): Json {
    val parser = JsonParser(text)
    val value = parser.parseValue()
    parser.skip()
    if (parser.i != text.length) throw JsonException("trailing data at ${parser.i}")
    return value
}

private class JsonParser(val text: String) {
    var i: Int = 0

    fun parseValue(): Json {
        skip()
        if (i >= text.length) throw JsonException("unexpected end")
        return when (text[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> Json.Str(parseString())
            't' -> parseLiteral("true", Json.Bool(true))
            'f' -> parseLiteral("false", Json.Bool(false))
            'n' -> parseLiteral("null", Json.Null)
            else -> Json.Num(parseNumber())
        }
    }

    fun skip() {
        while (i < text.length && text[i].isWhitespace()) i += 1
    }

    private fun parseObject(): Json.Obj {
        expect('{')
        val fields = LinkedHashMap<String, Json>()
        skip()
        if (peek('}')) {
            i += 1
            return Json.Obj(fields)
        }
        while (true) {
            skip()
            val key = parseString()
            skip()
            expect(':')
            fields[key] = parseValue()
            skip()
            if (peek('}')) {
                i += 1
                return Json.Obj(fields)
            }
            expect(',')
        }
    }

    private fun parseArray(): Json.Arr {
        expect('[')
        val items = ArrayList<Json>()
        skip()
        if (peek(']')) {
            i += 1
            return Json.Arr(items)
        }
        while (true) {
            items.add(parseValue())
            skip()
            if (peek(']')) {
                i += 1
                return Json.Arr(items)
            }
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (i < text.length) {
            val ch = text[i]
            i += 1
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= text.length) throw JsonException("bad escape")
                    val esc = text[i]
                    i += 1
                    when (esc) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > text.length) throw JsonException("bad unicode escape")
                            val hex = text.substring(i, i + 4)
                            out.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw JsonException("bad escape \\$esc")
                    }
                }
                else -> out.append(ch)
            }
        }
        throw JsonException("unterminated string")
    }

    private fun parseNumber(): Double {
        val start = i
        if (peek('-')) i += 1
        if (i >= text.length || !text[i].isDigit()) throw JsonException("expected number at $start")
        while (i < text.length && text[i].isDigit()) i += 1
        if (peek('.')) {
            i += 1
            while (i < text.length && text[i].isDigit()) i += 1
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i += 1
            if (peek('+') || peek('-')) i += 1
            while (i < text.length && text[i].isDigit()) i += 1
        }
        return text.substring(start, i).toDouble()
    }

    private fun parseLiteral(word: String, value: Json): Json {
        if (!text.startsWith(word, i)) throw JsonException("expected $word")
        i += word.length
        return value
    }

    private fun expect(ch: Char) {
        skip()
        if (i >= text.length || text[i] != ch) throw JsonException("expected $ch at $i")
        i += 1
    }

    private fun peek(ch: Char): Boolean = i < text.length && text[i] == ch
}

fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> {
                if (ch.code < 0x20) {
                    out.append("\\u")
                    out.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
    }
    out.append('"')
    return out.toString()
}
