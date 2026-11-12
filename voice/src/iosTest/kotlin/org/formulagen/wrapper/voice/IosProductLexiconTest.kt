package org.formulagen.wrapper.voice

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosProductLexiconTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun hovelsOwnWordsAreWrittenWhereTheEngineCanReadThem() {
        val path = assertNotNull(productLexicon(), "the caches directory takes the file")
        val written = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        assertEquals(lexiconText(kokoroProductWords()), written)
        // The engine counts a word as known from the file's bytes, so a name in it is said and not spelled.
        val known = LexiconWords(listOf(lexiconText(kokoroProductWords()).encodeToByteArray()))
        assertTrue("quinn" in known && "iphones" in known)
        assertEquals("Avery, quinns iPhone", coverUnknownWords("Avery, Quinn's iPhone", known))
    }
}
