package org.formulagen.wrapper.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import org.formulagen.wrapper.bridge.MediaGrant.Decision
import org.formulagen.wrapper.bridge.MediaGrant.Reason
import platform.WebKit.WKMediaCaptureType

class IosMediaGrantTest {
    private val trusted = TrustedOrigin("https://hovel.example.test/hovel")
    private val page = "https://hovel.example.test"

    @Test
    fun eachCaptureTypeMapsToItsKinds() {
        assertEquals(setOf(MediaKind.Camera), mediaKindsOf(WKMediaCaptureType.WKMediaCaptureTypeCamera))
        assertEquals(setOf(MediaKind.Microphone), mediaKindsOf(WKMediaCaptureType.WKMediaCaptureTypeMicrophone))
        assertEquals(setOf(MediaKind.Camera, MediaKind.Microphone), mediaKindsOf(WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone))
    }

    @Test
    fun theTrustedMainFrameIsGrantedEachType() {
        for (type in WKMediaCaptureType.entries) {
            assertEquals(Decision.Grant(mediaKindsOf(type)), mediaCaptureDecision(trusted, page, mainFrame = true, type), type.name)
        }
    }

    @Test
    fun aChildFrameAndAnotherOriginAreDenied() {
        val both = WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone
        assertEquals(Decision.Deny(Reason.ChildFrame), mediaCaptureDecision(trusted, page, mainFrame = false, both))
        assertEquals(Decision.Deny(Reason.OtherOrigin), mediaCaptureDecision(trusted, "https://hovel.example.test:8443", mainFrame = true, both))
    }

    @Test
    fun aPageThatIsNotHovelIsDenied() {
        assertEquals(Decision.Deny(Reason.OtherOrigin), mediaCaptureDecision(null, page, mainFrame = true, WKMediaCaptureType.WKMediaCaptureTypeMicrophone))
    }
}
