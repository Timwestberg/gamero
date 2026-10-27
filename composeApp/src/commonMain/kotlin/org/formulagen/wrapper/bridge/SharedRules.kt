package org.formulagen.wrapper.bridge

/** Rules more than one capability uses. Mirrors event-contracts `host-bridge/shared.ts`. */
object SharedRules {
    /** The profile of a person who is not signed in. */
    const val LOCAL_PROFILE = "local"
    const val COMPANION_NAME_LIMIT = 40

    /**
     * `local`, or a signed-in account's UUID in canonical lower case. Native code builds folder
     * paths from it, so an upper-case profile is invalid rather than folded into the same folder.
     */
    fun isProfileId(value: String): Boolean =
        value == LOCAL_PROFILE || (BridgeProtocol.UUID_PATTERN.matches(value) && value == value.lowercase())

    /**
     * A companion's display name: untrusted text that native code places in model instructions,
     * so it has no control characters and no whitespace at either end. A padded name is
     * rejected, never trimmed.
     */
    fun isCompanionName(value: String): Boolean = value.length in 1..COMPANION_NAME_LIMIT &&
        value.none { it.code < 0x20 || it.code == 0x7F } && value == value.trimEcma()
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
