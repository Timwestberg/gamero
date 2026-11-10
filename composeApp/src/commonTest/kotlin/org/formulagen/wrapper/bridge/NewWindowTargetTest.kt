package org.formulagen.wrapper.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

class NewWindowTargetTest {
    private val hovel = TrustedOrigin("https://hovel.example.test/hovel.html")
    private val page = "https://hovel.example.test"

    @Test
    fun anotherSiteOpensOutsideTheApp() {
        assertEquals(NewWindowTarget.OUTSIDE, newWindowTarget(hovel, page, "https://chatgpt.com/"))
        assertEquals(NewWindowTarget.OUTSIDE, newWindowTarget(hovel, page, "https://docs.anthropic.com/en/docs/claude-code/setup#install"))
    }

    @Test
    fun hovelsOwnPageLoadsInTheWebViewWhereThePersonIsSignedIn() {
        assertEquals(NewWindowTarget.HERE, newWindowTarget(hovel, page, "https://hovel.example.test/hovel.html?list=1"))
        val local = TrustedOrigin("http://localhost:3000/hovel.html")
        assertEquals(NewWindowTarget.HERE, newWindowTarget(local, "http://localhost:3000", "http://localhost:3000/other"))
    }

    @Test
    fun whatLinkOpenWouldRefuseDoesNotOpen() {
        for (url in listOf(null, "", "about:blank", "javascript:alert(1)", "http://example.com/", "https://user:pass@example.com/", "tel:+15551234567",
            "itms-apps://apps.apple.com/app/id1", "https://localhost/", "https://127.0.0.1/", "file:///etc/hosts")) {
            assertEquals(NewWindowTarget.IGNORE, newWindowTarget(hovel, page, url), url.toString())
        }
    }

    @Test
    fun onlyTheTrustedPagesOwnFramesOpenAnything() {
        assertEquals(NewWindowTarget.IGNORE, newWindowTarget(hovel, "https://ads.example.net", "https://chatgpt.com/"))
        assertEquals(NewWindowTarget.IGNORE, newWindowTarget(hovel, "https://hovel.example.test:8443", "https://hovel.example.test/hovel.html"))
        assertEquals(NewWindowTarget.IGNORE, newWindowTarget(null, page, "https://chatgpt.com/"), "a page that is not Hovel")
    }
}
