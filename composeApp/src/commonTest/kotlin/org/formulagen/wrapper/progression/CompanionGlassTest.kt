package org.formulagen.wrapper.progression

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import org.formulagen.wrapper.ui.fgBlue300
import org.formulagen.wrapper.ui.fgGreen400

class CompanionGlassTest {
    /** Accents come from game data; a missing or malformed one falls back instead of failing the screen. */
    @Test fun gameColorsParseWithAFallback() {
        assertEquals(fgGreen400, glassColor("1ff8b0"))
        assertEquals(fgGreen400, glassColor("#1FF8B0"))
        for (bad in listOf(null, "", "1ff8b", "1ff8b0ff", "green!")) assertEquals(fgBlue300, glassColor(bad), bad)
        assertEquals(Color.White, glassColor(null, Color.White))
    }
}
