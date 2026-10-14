package org.formulagen.wrapper

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUrlPolicyTest {
    @Test fun allowsHttpsAnywhereAndHttpOnLoopback() {
        assertTrue(isAllowedWebUrl("https", "example.com", hasUserInfo = false, hasFragment = false))
        assertTrue(isAllowedWebUrl("HTTPS", "example.com", hasUserInfo = false, hasFragment = false))
        for (host in listOf("localhost", "127.0.0.1", "::1", "[::1]", "LOCALHOST")) {
            assertTrue(isAllowedWebUrl("http", host, hasUserInfo = false, hasFragment = false), host)
        }
    }
    @Test fun rejectsEverythingElse() {
        assertFalse(isAllowedWebUrl("http", "example.com", hasUserInfo = false, hasFragment = false))
        assertFalse(isAllowedWebUrl("file", "localhost", hasUserInfo = false, hasFragment = false))
        assertFalse(isAllowedWebUrl(null, "example.com", hasUserInfo = false, hasFragment = false))
        assertFalse(isAllowedWebUrl("https", null, hasUserInfo = false, hasFragment = false))
        assertFalse(isAllowedWebUrl("https", "", hasUserInfo = false, hasFragment = false))
        assertFalse(isAllowedWebUrl("https", "example.com", hasUserInfo = true, hasFragment = false))
        assertFalse(isAllowedWebUrl("https", "example.com", hasUserInfo = false, hasFragment = true))
    }
}
