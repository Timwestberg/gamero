package org.formulagen.wrapper.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.LoadedFont
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HovelSansTest {
    private class Face(val weight: FontWeight, val style: FontStyle, name: String) {
        val file = File("src/commonMain/composeResources/font/$name.ttf")
    }

    private val faces = listOf(
        Face(FontWeight.Normal, FontStyle.Normal, "hovel_sans_regular"),
        Face(FontWeight.Normal, FontStyle.Italic, "hovel_sans_italic"),
        Face(FontWeight.Medium, FontStyle.Normal, "hovel_sans_medium"),
        Face(FontWeight.SemiBold, FontStyle.Normal, "hovel_sans_semibold"),
        Face(FontWeight.Bold, FontStyle.Normal, "hovel_sans_bold"),
        Face(FontWeight.Bold, FontStyle.Italic, "hovel_sans_bold_italic"),
        Face(FontWeight.ExtraBold, FontStyle.Normal, "hovel_sans_heavy"),
        Face(FontWeight.Black, FontStyle.Normal, "hovel_sans_black"),
    )

    /** Each weight and style loads its own file (800 is Heavy, 900 is Black), and every Material style uses the family. */
    @Test fun everyFaceAndTypeStyleIsHovelSans() = runDesktopComposeUiTest {
        lateinit var family: FontFamily
        lateinit var typography: Typography
        setContent { family = hovelSans; typography = hovelTypography() }
        waitForIdle()
        val loaded = (family as FontListFontFamily).fonts.map { it as LoadedFont }
        assertEquals(faces.map { it.weight to it.style }, loaded.map { it.weight to it.style })
        faces.zip(loaded).forEach { (face, font) -> assertContentEquals(face.file.readBytes(), font.data, face.file.name) }
        with(typography) {
            listOf(displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall,
                titleLarge, titleMedium, titleSmall, bodyLarge, bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall)
        }.forEach { assertEquals(family, it.fontFamily) }
    }

    /** A rebuilt subset must come with its new checksums. */
    @Test fun provenanceRecordsEveryFace() {
        val provenance = File("src/commonMain/composeResources/files/hovel/PROVENANCE.md").readText()
        faces.forEach { face ->
            val sha = MessageDigest.getInstance("SHA-256").digest(face.file.readBytes()).joinToString("") { "%02x".format(it) }
            assertTrue(sha in provenance, "${face.file.name} SHA-256 $sha is missing from PROVENANCE.md")
        }
    }
}
