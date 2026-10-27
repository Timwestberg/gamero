package org.formulagen.wrapper.progression

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class CompanionGlassTest {
    /** Accents come from game data; a missing or malformed one falls back instead of failing the screen. */
    @Test fun gameColorsParseWithAFallback() {
        assertEquals(CompanionGlass.plum, glassColor("b9a0ff"))
        assertEquals(CompanionGlass.plum, glassColor("#B9A0FF"))
        for (bad in listOf(null, "", "b9a0f", "b9a0ffff", "plum!!")) assertEquals(CompanionGlass.amber, glassColor(bad), bad)
        assertEquals(Color.White, glassColor(null, Color.White))
    }
}
