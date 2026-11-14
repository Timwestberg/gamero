package org.formulagen.wrapper.links

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IncomingLinkTest {
    private val web = "https://hovel.example/hovel"

    @Test
    fun aCodesLinkOpensOnTheWebAppsOwnOrigin() {
        assertEquals("https://hovel.example/link/in/AbC-12_x", incomingLinkTarget("https://hovel.example/link/in/AbC-12_x", web))
        assertEquals("https://hovel.example/link/add/AbC-12_x", incomingLinkTarget("https://hovel.example/link/add/AbC-12_x", web))
        assertEquals("https://hovel.example/link/in/abc", incomingLinkTarget("HTTPS://HOVEL.EXAMPLE/link/in/abc", web))
        assertEquals("https://hovel.example/link/in/abc", incomingLinkTarget("https://hovel.example:443/link/in/abc", web))
    }

    @Test
    fun theQueryAndTheFragmentAreDropped() {
        assertEquals("https://hovel.example/link/in/abc", incomingLinkTarget("https://hovel.example/link/in/abc?next=https://evil.example", web))
        assertEquals("https://hovel.example/link/in/abc", incomingLinkTarget("https://hovel.example/link/in/abc#/hovel/settings", web))
    }

    @Test
    fun aLocalAddressKeepsItsPort() {
        val local = "https://192.168.1.20:8127/hovel"
        assertEquals("https://192.168.1.20:8127/link/add/abc", incomingLinkTarget("https://192.168.1.20:8127/link/add/abc", local))
        assertNull(incomingLinkTarget("https://192.168.1.20:8128/link/add/abc", local))
        assertNull(incomingLinkTarget("https://192.168.1.20/link/add/abc", local))
    }

    @Test
    fun anotherAddressIsRefused() {
        assertNull(incomingLinkTarget("https://evil.example/link/in/abc", web))
        assertNull(incomingLinkTarget("https://hovel.example.evil.example/link/in/abc", web))
        assertNull(incomingLinkTarget("https://hovel.example@evil.example/link/in/abc", web))
        assertNull(incomingLinkTarget("https://evil.example/link/in/abc?https://hovel.example/link/in/abc", web))
        assertNull(incomingLinkTarget("http://hovel.example/link/in/abc", web))
        assertNull(incomingLinkTarget("hovel://hovel.example/link/in/abc", web))
        assertNull(incomingLinkTarget("javascript:alert(1)", web))
        assertNull(incomingLinkTarget("", web))
    }

    @Test
    fun anotherPathIsRefused() {
        for (path in listOf("", "/", "/hovel", "/hovel/settings", "/link", "/link/", "/linked/in/abc", "/link//abc", "/link/in/abc/", "/link/in/../../hovel", "/link/in/a%2Fb", "/link/in/a b", "/api/identity/v1/auth/links")) {
            assertNull(incomingLinkTarget("https://hovel.example$path", web), path)
        }
        assertNull(incomingLinkTarget("https://hovel.example/link/in/" + "a".repeat(600), web))
    }

    @Test
    fun anHttpWebAddressTakesNoLinks() {
        assertNull(incomingLinkTarget("https://127.0.0.1:8080/link/in/abc", "http://127.0.0.1:8080/hovel"))
        assertNull(incomingLinkTarget("http://127.0.0.1:8080/link/in/abc", "http://127.0.0.1:8080/hovel"))
        assertNull(incomingLinkTarget("https://hovel.example/link/in/abc", "not a url"))
    }
}
