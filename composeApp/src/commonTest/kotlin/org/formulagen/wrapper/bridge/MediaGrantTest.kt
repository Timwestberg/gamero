package org.formulagen.wrapper.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import org.formulagen.wrapper.bridge.MediaGrant.Decision
import org.formulagen.wrapper.bridge.MediaGrant.Reason

class MediaGrantTest {
    private val origin = TrustedOrigin("https://hovel.example.test/hovel")
    private val both = setOf(MediaKind.Microphone, MediaKind.Camera)

    private fun decide(
        requestOrigin: String = "https://hovel.example.test",
        mainFrame: Boolean = true,
        kinds: Set<MediaKind> = both,
        otherResources: Boolean = false,
    ) = MediaGrant.decide(origin, requestOrigin, mainFrame, kinds, otherResources)

    @Test
    fun theTrustedMainFrameGetsBothKinds() {
        assertEquals(Decision.Grant(both), decide())
        assertEquals(Decision.Grant(both), decide(requestOrigin = "https://hovel.example.test:443/meet/abcdefghij"))
    }

    @Test
    fun eachKindAloneIsGrantedAlone() {
        for (kind in MediaKind.entries) {
            assertEquals(Decision.Grant(setOf(kind)), decide(kinds = setOf(kind)), kind.name)
        }
    }

    @Test
    fun anotherOriginIsDenied() {
        assertEquals(Decision.Deny(Reason.OtherOrigin), decide(requestOrigin = "https://attacker.test"))
    }

    @Test
    fun lookAlikeOriginsAreDenied() {
        for (lookAlike in listOf(
            "https://hovel.example.test:8443", "http://hovel.example.test", "https://games.hovel.example.test",
            "https://hovel.example.test.attacker", "https://user@hovel.example.test", "null", "", "data:text/html,x",
        )) {
            assertEquals(Decision.Deny(Reason.OtherOrigin), decide(requestOrigin = lookAlike), lookAlike)
        }
    }

    @Test
    fun aChildFrameOfTheTrustedOriginIsDenied() {
        assertEquals(Decision.Deny(Reason.ChildFrame), decide(mainFrame = false))
    }

    @Test
    fun anEmptyRequestIsDenied() {
        assertEquals(Decision.Deny(Reason.NoKinds), decide(kinds = emptySet()))
    }

    @Test
    fun anotherResourceDeniesTheWholeRequest() {
        assertEquals(Decision.Deny(Reason.OtherResource), decide(otherResources = true))
        assertEquals(Decision.Deny(Reason.OtherResource), decide(kinds = emptySet(), otherResources = true))
    }

    @Test
    fun theOriginIsCheckedFirst() {
        assertEquals(Decision.Deny(Reason.OtherOrigin), decide(requestOrigin = "https://attacker.test", mainFrame = false, otherResources = true))
    }
}
