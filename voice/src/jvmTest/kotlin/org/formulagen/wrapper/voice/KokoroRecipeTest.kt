package org.formulagen.wrapper.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KokoroRecipeTest {
    @Test
    fun embeddedRecipeMatchesThePackerFileAndTheFetchPins() {
        val root = repoRoot()
        val recipe = File(root, "voice/kokoro/blends.json")
        assertEquals(normalize(KOKORO_BLENDS_JSON), normalize(recipe.readText()))
        val script = File(root, "scripts/fetch-kokoro.sh").readText()
        val catalog = kokoroCatalog()
        assertTrue(script.contains(catalog.modelSha256), "model hash")
        assertTrue(script.contains(catalog.tokensSha256), "tokens hash")
        assertTrue(script.contains(catalog.lexiconSha256), "lexicon hash")
        assertTrue(script.contains(catalog.upstreamVoicesSha256), "upstream voices hash")
        assertTrue(script.contains("60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4"), "archive hash")
        assertTrue(File(root, "voice/kokoro/pack-blends.py").readText().contains("derivedVoicesSha256"))
    }

    private fun normalize(text: String): String = text.trim().replace("\r\n", "\n")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val current = dir ?: return@repeat
            if (File(current, "settings.gradle.kts").isFile && File(current, "voice/kokoro/blends.json").isFile) {
                return current
            }
            dir = current.parentFile
        }
        error("repo root not found from ${File("").absolutePath}")
    }
}
