package org.formulagen.wrapper.util

/**
 * At most [limit] UTF-16 units of this text, the unit contract limits count, never ending inside a surrogate pair, so a
 * cut never leaves half an emoji.
 */
internal fun String.takeWhole(limit: Int): String {
    if (length <= limit) return this
    val end = if (limit > 0 && this[limit - 1].isHighSurrogate()) limit - 1 else limit
    return substring(0, end.coerceAtLeast(0))
}

/** At most [limit] characters, an ellipsis marking a cut, never splitting a surrogate pair. */
internal fun String.shortened(limit: Int): String = if (length <= limit) this else takeWhole(limit - 1).trimEnd() + "…"
