package org.formulagen.wrapper.conversations

/**
 * Text that may leave the Mac, as event-contracts `shareableText` checks it (`SHAREABLE_TEXT_REFUSED`): no C0 or C1
 * controls, no bidirectional controls, no line or paragraph separators (U+2028, U+2029), no invisible characters
 * (U+200B to U+200D, U+2060 to U+2064, U+FEFF), and no absolute path (`/` or `~` at the start or after whitespace, a
 * quote or `=`, `$HOME`, or a `file://` URL). Whitespace here is JavaScript's `\s` and more, so neither the Mac nor the
 * phone is laxer than the server. The phone checks every line it shows with it, too.
 */
internal object ShareableText {
    private val REFUSED = Regex(
        "[\\u0000-\\u001f\\u007f-\\u009f\\u061c\\u200b-\\u200f\\u2028\\u2029\\u202a-\\u202e\\u2060-\\u2064\\u2066-\\u2069\\ufeff]",
    )
    private val ABSOLUTE_PATH = Regex(
        "(?:^|[\\t\\n\\u000b\\f\\r \\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff\"'`=])[/~]|\\$\\{?HOME\\b|file://",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [text] may leave the Mac within [max] characters (and at least [min]). */
    fun isShareable(text: String, max: Int, min: Int = 1): Boolean =
        text.length in min..max && !REFUSED.containsMatchIn(text) && !ABSOLUTE_PATH.containsMatchIn(text)
}

/** Line breaks as a reader sees them: CR LF, LF, CR, NEL and the line and paragraph separators. */
private val LINE_BREAK = Regex("\\r\\n|[\\n\\r\\u0085\\u2028\\u2029]")

/** This text's lines, split wherever a reader would see a new line, so no separator hides inside a line. */
internal fun String.splitLines(): List<String> = split(LINE_BREAK)
