@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.ExperimentalUnsignedTypes::class)

package org.formulagen.wrapper.lists

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/** Native driver factory; account coordinator integration must precede exposing any page capability. */
internal fun openIosListsJournal(): ListsJournal = ListsJournal(
    NativeSqliteDriver(ListsJournalSchema, "hovel-lists-local-v1.db"),
    ::journalHash,
)

/** Fingerprint immutable intents without keeping their deleted payloads in receipts. */
private fun journalHash(bytes: ByteArray): String {
    val output = UByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input -> output.usePinned { digest ->
        CC_SHA256(input.addressOf(0), bytes.size.convert(), digest.addressOf(0))
    } }
    return output.joinToString("") { it.toString(16).padStart(2, '0') }
}
