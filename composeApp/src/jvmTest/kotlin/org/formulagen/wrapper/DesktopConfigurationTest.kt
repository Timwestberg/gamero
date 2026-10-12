package org.formulagen.wrapper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopConfigurationTest {
    @Test fun requiresAnExplicitAppUrl() {
        assertNull(desktopWebUrl(emptyArray(), null))
        assertEquals("https://example.com/hovel.html", desktopWebUrl(emptyArray(), "https://example.com/hovel.html"))
        assertEquals("http://localhost:5173/hovel.html", desktopWebUrl(arrayOf("--url=http://localhost:5173/hovel.html"), null))
    }
    @Test fun rejectsUnsafeOrAmbiguousUrls() {
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "http://example.com", "https://user:password@example.com", "https://example.com/#secret", "not a url")) {
            assertNull(desktopWebUrl(arrayOf(url), null), url)
        }
    }
}
