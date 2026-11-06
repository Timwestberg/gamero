package org.formulagen.wrapper.bridge

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.cef.browser.CefFrame
import org.cef.callback.CefMediaAccessCallback
import org.cef.callback.CefMediaAccessCallback.MediaPermissionFlags.DESKTOP_AUDIO_CAPTURE
import org.cef.callback.CefMediaAccessCallback.MediaPermissionFlags.DESKTOP_VIDEO_CAPTURE
import org.cef.callback.CefMediaAccessCallback.MediaPermissionFlags.DEVICE_AUDIO_CAPTURE
import org.cef.callback.CefMediaAccessCallback.MediaPermissionFlags.DEVICE_VIDEO_CAPTURE
import org.formulagen.wrapper.bridge.MediaGrant.Decision
import org.formulagen.wrapper.bridge.MediaGrant.Reason

class JcefMediaGrantTest {
    private val trusted = TrustedOrigin("http://127.0.0.1:8133/hovel")
    // CEF reports the requesting origin with a trailing slash (check 3 probe on JBR 21.0.9).
    private val page = "http://127.0.0.1:8133/"
    private val both = DEVICE_AUDIO_CAPTURE or DEVICE_VIDEO_CAPTURE

    /** Records the one answer CEF gets: the continued bits, or "cancel". */
    private class FakeCallback : CefMediaAccessCallback {
        var answer: Any? = null
        override fun Continue(allowedPermissions: Int) { check(answer == null); answer = allowedPermissions }
        override fun Cancel() { check(answer == null); answer = "cancel" }
    }

    private fun frame(main: Boolean) = Proxy.newProxyInstance(CefFrame::class.java.classLoader, arrayOf(CefFrame::class.java)) { _, method, _ ->
        when (method.name) {
            "isMain" -> main
            "isValid" -> true
            else -> null
        }
    } as CefFrame

    private fun ask(grant: JcefMediaGrant, frame: CefFrame?, requestingOrigin: String? = page, permissions: Int = both) =
        FakeCallback().also { assertTrue(grant.onRequestMediaAccessPermission(null, frame, requestingOrigin, permissions, it)) }.answer

    @Test
    fun deviceBitsMapToKinds() {
        assertEquals(setOf(MediaKind.Microphone), mediaKindsOf(DEVICE_AUDIO_CAPTURE))
        assertEquals(setOf(MediaKind.Camera), mediaKindsOf(DEVICE_VIDEO_CAPTURE))
        assertEquals(setOf(MediaKind.Microphone, MediaKind.Camera), mediaKindsOf(both))
        assertEquals(emptySet(), mediaKindsOf(0))
    }

    @Test
    fun theTrustedMainFrameIsGrantedEachDeviceKind() {
        for (bits in listOf(DEVICE_AUDIO_CAPTURE, DEVICE_VIDEO_CAPTURE, both)) {
            assertEquals(Decision.Grant(mediaKindsOf(bits)), jcefMediaDecision(trusted, page, mainFrame = true, bits), "bits $bits")
        }
    }

    @Test
    fun screenCaptureDeniesTheWholeRequest() {
        for (bits in listOf(DESKTOP_AUDIO_CAPTURE, DESKTOP_VIDEO_CAPTURE, both or DESKTOP_VIDEO_CAPTURE, 16)) {
            assertEquals(Decision.Deny(Reason.OtherResource), jcefMediaDecision(trusted, page, mainFrame = true, bits), "bits $bits")
        }
        assertEquals(Decision.Deny(Reason.NoKinds), jcefMediaDecision(trusted, page, mainFrame = true, 0))
    }

    @Test
    fun otherOriginsChildFramesAndOtherProductsAreDenied() {
        assertEquals(Decision.Deny(Reason.OtherOrigin), jcefMediaDecision(trusted, "http://localhost:8133/", mainFrame = true, both))
        assertEquals(Decision.Deny(Reason.ChildFrame), jcefMediaDecision(trusted, page, mainFrame = false, both))
        assertEquals(Decision.Deny(Reason.OtherOrigin), jcefMediaDecision(null, page, mainFrame = true, both))
    }

    @Test
    fun theHandlerContinuesWithTheRequestedBitsOrCancels() {
        val reports = mutableListOf<String>()
        val grant = JcefMediaGrant(trusted) { reports += it }
        assertEquals(both, ask(grant, frame(main = true)))
        assertEquals(DEVICE_AUDIO_CAPTURE, ask(grant, frame(main = true), permissions = DEVICE_AUDIO_CAPTURE))
        assertEquals("cancel", ask(grant, frame(main = false)))
        assertEquals("cancel", ask(grant, null))
        assertEquals("cancel", ask(grant, frame(main = true), requestingOrigin = null))
        assertEquals("cancel", ask(grant, frame(main = true), permissions = both or DESKTOP_AUDIO_CAPTURE))
        assertEquals(4, reports.size)
    }

    @Test
    fun aProductThatIsNotHovelCancelsEveryRequest() {
        assertEquals("cancel", ask(JcefMediaGrant(null) {}, frame(main = true)))
    }
}
