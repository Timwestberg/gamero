package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An AI runtime the host runs on the person's own subscription. The names are Hovel Brain's `runtimeId`. */
@Serializable
enum class RuntimeId(val providerId: RuntimeProviderId, val wireName: String) {
    @SerialName("codex") CODEX(RuntimeProviderId.OPENAI, "codex"),
    @SerialName("claude-code") CLAUDE_CODE(RuntimeProviderId.ANTHROPIC, "claude-code"),
    @SerialName("grok-build") GROK_BUILD(RuntimeProviderId.XAI, "grok-build"),
}

/** Hovel Brain's provider: the company behind a runtime. [RuntimeId.providerId] is `RUNTIME_PROVIDERS`. */
@Serializable
enum class RuntimeProviderId {
    @SerialName("openai") OPENAI,
    @SerialName("anthropic") ANTHROPIC,
    @SerialName("xai") XAI,
}

/** Reasoning levels advertised by installed runtimes; omitted effort uses the runtime default. */
@Serializable
enum class ModelEffort(val value: String) {
    @SerialName("none") NONE("none"),
    @SerialName("minimal") MINIMAL("minimal"),
    @SerialName("low") LOW("low"),
    @SerialName("medium") MEDIUM("medium"),
    @SerialName("high") HIGH("high"),
    @SerialName("xhigh") XHIGH("xhigh"),
    @SerialName("max") MAX("max"),
    @SerialName("ultra") ULTRA("ultra"),
}

/** Rules more than one capability uses. Mirrors event-contracts `host-bridge/shared.ts`. */
object SharedRules {
    const val COMPANION_NAME_LIMIT = 40
    /** Hovel Brain's sentinel model: the runtime's own default, or for Claude Code the default model of the person's Claude login. */
    const val RUNTIME_DEFAULT_MODEL = "runtime-default"
    /**
     * A model as its runtime names it, or [RUNTIME_DEFAULT_MODEL]: one lower-case token of at most
     * 128 characters. Native code hands it to Claude Code as a command-line argument, so it starts
     * with a letter or digit and is never read as an option. Optional `[1m]` preserves Claude
     * context selection. Mirrors `MODEL_ID_PATTERN`.
     */
    const val MODEL_ID_PATTERN = "^(?=.{1,128}$)[a-z0-9][a-z0-9._:/-]*(?:\\[1m\\])?$"
    private val MODEL_ID = Regex(MODEL_ID_PATTERN)

    /** Whether [value] is a model name by [MODEL_ID_PATTERN]. */
    fun isModelId(value: String): Boolean = MODEL_ID.matches(value)

    /**
     * An account record's id, such as the account's own or a project's: a UUID in canonical lower case. Native code keys
     * local stores and folder paths by it, so an upper-case id is invalid rather than folded into the same folder.
     */
    fun isRecordId(value: String): Boolean = BridgeProtocol.UUID_PATTERN.matches(value) && value == value.lowercase()

    /**
     * A companion's display name: untrusted text that native code places in model instructions,
     * so it follows event-contracts `displayText` ([isDisplayText]). A padded name is rejected,
     * never trimmed.
     */
    fun isCompanionName(value: String): Boolean = value.length in 1..COMPANION_NAME_LIMIT && isDisplayText(value)

    /**
     * Event-contracts `displayText`, for names and titles a person types: one line with no C0
     * control or DEL, and no whitespace at either end. Emoji stay whole, joiners included.
     */
    fun isDisplayText(value: String): Boolean = value.none { it.code < 0x20 || it.code == 0x7F } && value == value.trimEcma()

    /**
     * Event-contracts `dialogName`, for a computer's or phone's name a native dialog shows: one line
     * with no control (Cc), format (Cf: bidirectional, zero-width and joiner characters, tags) or
     * line and paragraph separator (Zl, Zp) characters, and no whitespace at either end.
     */
    fun isDialogName(value: String): Boolean = value.unicodePoints().none(::isDialogRefused) && value == value.trimEcma()

    /**
     * [value] as a native dialog shows a name: what [isDialogName] refuses taken out, then trimmed;
     * [fallback] when nothing is left. Names are read as stored, so a dialog cleans each one.
     */
    fun dialogName(value: String, fallback: String): String {
        val kept = StringBuilder()
        var index = 0
        while (index < value.length) {
            val point = value.unicodePointAt(index)
            val width = if (point > 0xFFFF) 2 else 1
            if (!isDialogRefused(point)) kept.appendRange(value, index, index + width)
            index += width
        }
        return kept.toString().trimEcma().ifEmpty { fallback }
    }

    /** Cc, Cf, Zl and Zp, the categories `dialogName` refuses, for one code point. */
    private fun isDialogRefused(point: Int): Boolean = if (point <= 0xFFFF) {
        point.toChar().category in DIALOG_REFUSED_CATEGORIES
    } else {
        ASTRAL_FORMAT.any { point in it }
    }

    private val DIALOG_REFUSED_CATEGORIES = setOf(
        CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR,
    )

    /** The format (Cf) characters past the Basic Multilingual Plane, as Unicode 16 lists them. */
    private val ASTRAL_FORMAT = listOf(
        0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x1343F, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0001..0xE0001,
        0xE0020..0xE007F,
    )

    /** [this] string's code points; an unpaired surrogate is its own code point (Cs, which `dialogName` lets through). */
    private fun String.unicodePoints(): Sequence<Int> = sequence {
        var index = 0
        while (index < length) {
            val point = unicodePointAt(index)
            yield(point)
            index += if (point > 0xFFFF) 2 else 1
        }
    }

    /** The code point at [index], joining a surrogate pair. */
    private fun String.unicodePointAt(index: Int): Int {
        val high = this[index]
        if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
            return ((high.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00) + 0x10000
        }
        return high.code
    }
}

/**
 * ECMAScript `String.prototype.trim`, which zod's `.trim()` uses. Kotlin's `trim()` differs: it
 * also strips U+001C..U+001F and keeps U+FEFF, so a length check could disagree with the page.
 */
fun String.trimEcma(): String = trim { it.isEcmaWhitespace() }

/** ECMAScript WhiteSpace and LineTerminator: tab, VT, FF, space, NBSP, BOM, LF, CR, LS, PS and Unicode Zs. */
private fun Char.isEcmaWhitespace(): Boolean = when (this) {
    '\u0009', '\u000B', '\u000C', ' ', ' ', '﻿', '\u000A', '\u000D', ' ', ' ',
    ' ', in ' '..' ', ' ', ' ', '　' -> true
    else -> false
}
