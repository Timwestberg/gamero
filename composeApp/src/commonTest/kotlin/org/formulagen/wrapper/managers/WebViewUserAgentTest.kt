package org.formulagen.wrapper.managers

import kotlin.test.Test
import kotlin.test.assertEquals

class WebViewUserAgentTest {
    @Test
    fun dropsAndroidWebViewLegacyVersionToken() {
        val webView = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/UE1A; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/127.0.6533.64 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/UE1A; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.6533.64 Mobile Safari/537.36",
            withoutLegacyVersionToken(webView),
        )
    }

    @Test
    fun keepsUserAgentsWithoutTheToken() {
        val chrome = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
        assertEquals(chrome, withoutLegacyVersionToken(chrome))
    }
}
