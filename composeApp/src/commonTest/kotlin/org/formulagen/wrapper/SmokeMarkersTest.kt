package org.formulagen.wrapper

import org.formulagen.wrapper.bridge.HostPlatform
import org.formulagen.wrapper.hovel.autoEnterRequested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmokeMarkersTest {
    @Test
    fun pageLoadedLogsTheOriginAndAOneLineTitle() {
        assertEquals(
            "GAMERO_PAGE_LOADED origin=https://localhost:8123 title=Hovel  ready",
            SmokeMarkers.pageLoaded("https://localhost:8123/hovel.html?token=secret#x", "Hovel\r\n ready"),
        )
        assertEquals("GAMERO_PAGE_LOADED origin=https://hovel.example title=null", SmokeMarkers.pageLoaded("https://hovel.example/", null))
        assertEquals("GAMERO_PAGE_LOADED origin=invalid title=", SmokeMarkers.pageLoaded("file:///etc/passwd", ""))
    }

    @Test
    fun bridgeAnsweredLogsOnlyAValidMethodName() {
        assertEquals(
            "GAMERO_BRIDGE_ANSWERED platform=ios product=Hovel method=watchContext outcome=ok",
            SmokeMarkers.bridgeAnswered(HostPlatform.IOS, "Hovel", "watchContext", "ok"),
        )
        assertEquals(
            "GAMERO_BRIDGE_ANSWERED platform=android product=Hovel method=companion.capabilities outcome=unsupported",
            SmokeMarkers.bridgeAnswered(HostPlatform.ANDROID, "Hovel", "companion.capabilities", "unsupported"),
        )
        assertEquals(
            "GAMERO_BRIDGE_ANSWERED platform=desktop product=Hovel method=invalid outcome=invalid",
            SmokeMarkers.bridgeAnswered(HostPlatform.DESKTOP, "Hovel", "x\nGAMERO_BRIDGE_ANSWERED outcome=ok", "invalid"),
        )
        assertEquals(
            "GAMERO_BRIDGE_ANSWERED platform=desktop product=Hovel method=invalid outcome=invalid",
            SmokeMarkers.bridgeAnswered(HostPlatform.DESKTOP, "Hovel", null, "invalid"),
        )
    }

    @Test
    fun autoEnterTakesOneOrTrueOnly() {
        for (value in listOf("1", "true", " TRUE ")) assertTrue(autoEnterRequested(value), value)
        for (value in listOf(null, "", "0", "false", "yes")) assertFalse(autoEnterRequested(value), value.toString())
    }
}
