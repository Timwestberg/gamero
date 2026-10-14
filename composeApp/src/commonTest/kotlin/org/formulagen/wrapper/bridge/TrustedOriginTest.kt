package org.formulagen.wrapper.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedOriginTest {
    private val origin = TrustedOrigin("https://example.test/hovel.html")

    @Test
    fun theSameSchemeHostAndPortMatch() {
        for (url in listOf("https://example.test:443/another-path", "HTTPS://Example.TEST", "https://example.test?x=1", "https://example.test#top")) {
            assertTrue(origin.matches(url), url)
        }
    }

    @Test
    fun childOpaqueAndLookalikeFramesDoNotMatch() {
        for (url in listOf(
            "https://example.test.attacker/", "http://example.test/", "https://example.test:444/", "data:text/html,test",
            "about:blank", "https://user@example.test/", "null", "", "https://", "https://example.test:99999/",
            "https://exa mple.test/", "javascript://example.test/",
        )) {
            assertFalse(origin.matches(url), url)
        }
    }

    @Test
    fun theRuleOmitsTheDefaultPort() {
        assertEquals("https://example.test", origin.rule)
        assertEquals("http://127.0.0.1:8080", TrustedOrigin("http://127.0.0.1:8080/hovel.html").rule)
        assertEquals("https://[::1]:8443", TrustedOrigin("https://[::1]:8443/").rule)
    }

    @Test
    fun aLaunchUrlWithoutAnHttpOriginIsRefused() {
        for (url in listOf("file:///hovel.html", "about:blank", "https://user@example.test/")) {
            assertFailsWith<IllegalArgumentException>(url) { TrustedOrigin(url) }
        }
    }
}
