package org.formulagen.wrapper.hovel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HovelAutoEnterTest {
    @Test
    fun aDebuggableBuildTakesTheExtra() {
        assertTrue(hovelAutoEnter(debuggable = true, extra = "1"))
        assertFalse(hovelAutoEnter(debuggable = true, extra = null))
    }

    @Test
    fun aReleaseBuildIgnoresTheExtra() {
        assertFalse(hovelAutoEnter(debuggable = false, extra = "1"))
        assertFalse(hovelAutoEnter(debuggable = false, extra = "true"))
    }
}
