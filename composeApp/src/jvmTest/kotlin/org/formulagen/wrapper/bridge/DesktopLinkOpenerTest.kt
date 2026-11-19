package org.formulagen.wrapper.bridge

import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DesktopLinkOpenerTest {
    @Test
    fun opensTheUrlOffTheCallingThread() = runBlocking {
        val browsed = mutableListOf<URI>()
        var browseThread: Thread? = null
        DesktopLinkOpener { uri -> browsed += uri; browseThread = Thread.currentThread() }.open("https://example.com/a?b=c")
        assertEquals(listOf(URI("https://example.com/a?b=c")), browsed)
        assertNotEquals(Thread.currentThread(), browseThread)
    }

    @Test
    fun aHostThatCannotBrowseFailsTheCall() {
        val opener = DesktopLinkOpener { throw IllegalStateException("java.awt.Desktop cannot browse on this host") }
        assertFailsWith<IllegalStateException> { runBlocking { opener.open("https://example.com/") } }
    }
}
