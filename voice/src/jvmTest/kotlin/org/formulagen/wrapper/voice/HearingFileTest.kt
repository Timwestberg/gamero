package org.formulagen.wrapper.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class HearingFileTest {
    @Test fun decodesThePinnedClipWhenTheModelsAreInstalled() {
        val dir = listOf(
            File("spikes/ember-voice/mac/models/hearing"),
            File("../spikes/ember-voice/mac/models/hearing"),
        ).firstOrNull { File(it, "sample.wav").isFile } ?: return
        val result = transcribeHearingFile(dir.path, File(dir, "sample.wav").path) ?: return
        assertTrue(result.text.isNotBlank(), "pinned clip produced no text")
        assertTrue(
            result.text.contains("yellow lamps", ignoreCase = true),
            "pinned clip transcript was \"${result.text}\"",
        )
        println("hearing sample decodeMs=${result.decodeMs.toInt()} speechMs=${result.speechMs} text=${result.text}")
    }
}
