package org.formulagen.wrapper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosWebUrlTest {
    @Test fun prefersTheLaunchEnvironmentOverInfoPlist() {
        assertNull(iosWebUrl(null, null))
        assertNull(iosWebUrl("", " "))
        assertEquals("https://example.com/hovel.html", iosWebUrl(null, "https://example.com/hovel.html"))
        assertEquals("http://127.0.0.1:8080/hovel.html", iosWebUrl("http://127.0.0.1:8080/hovel.html", "https://example.com/hovel.html"))
        assertEquals("http://localhost:5173/hovel.html", iosWebUrl("", "http://localhost:5173/hovel.html"))
    }
    @Test fun rejectsUnsafeOrAmbiguousUrls() {
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "http://example.com", "https://user:password@example.com", "https://example.com/#secret", "not a url")) {
            assertNull(iosWebUrl(url, null), url)
        }
    }
    @Test fun anInvalidEnvironmentDoesNotFallBackToInfoPlist() {
        assertNull(iosWebUrl("http://example.com", "https://example.com/hovel.html"))
    }
    @Test fun reducesUrlsToTheirOrigin() {
        assertEquals("http://127.0.0.1:8080", originOf("http://127.0.0.1:8080/hovel.html?x=1"))
        assertEquals("https://example.com", originOf("https://example.com/hovel.html"))
        assertEquals("http://[::1]:8080", originOf("http://[::1]:8080/hovel.html"))
    }
}
