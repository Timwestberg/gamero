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
    @Test fun environmentOverridesTheBakedInSetting() {
        assertEquals("https://dev.example/hovel.html", desktopSetting("https://dev.example/hovel.html", "https://example.com/hovel.html"))
        assertEquals("https://example.com/hovel.html", desktopSetting(null, "https://example.com/hovel.html"))
        assertEquals("https://example.com/hovel.html", desktopSetting(" ", "https://example.com/hovel.html"))
        assertNull(desktopSetting(null, ""))
    }
    @Test fun rejectsUnsafeOrAmbiguousUrls() {
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "http://example.com", "https://user:password@example.com", "https://example.com/#secret", "not a url")) {
            assertNull(desktopWebUrl(arrayOf(url), null), url)
        }
    }
}
