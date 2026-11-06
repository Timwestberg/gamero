package org.formulagen.wrapper.hovel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HovelWebUrlTest {
    @Test
    fun releaseTakesHttpsOnly() {
        assertEquals("https://hovel.example/hovel.html", hovelWebUrl("hovel", "https://hovel.example/hovel.html"))
        assertNull(hovelWebUrl("hovel", "http://127.0.0.1:8080/hovel.html"))
        assertNull(hovelWebUrl("hovel", "http://localhost:8080/hovel.html"))
    }

    @Test
    fun debugAlsoTakesHttpOnLoopback() {
        for (url in listOf("http://127.0.0.1:8080/hovel.html", "http://localhost:8080/", "http://[::1]:8080/")) {
            assertEquals(url, hovelWebUrl("hovel", url, debuggable = true), url)
        }
        assertEquals("https://hovel.example/", hovelWebUrl("hovel", "https://hovel.example/", debuggable = true))
    }

    @Test
    fun debugStillRefusesOtherHttpAndBadUrls() {
        for (url in listOf("http://10.0.2.2:8080/", "http://hovel.example/", "http://127.0.0.1.evil.example/", "https://u:p@hovel.example/", "https://hovel.example/#x", "file:///sdcard/x.html")) {
            assertNull(hovelWebUrl("hovel", url, debuggable = true), url)
        }
        assertNull(hovelWebUrl("gamero", "https://hovel.example/", debuggable = true))
    }
}
