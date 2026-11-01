package org.formulagen.wrapper.conversations

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.formulagen.wrapper.bridge.*

class ProjectImagesTest {
    @Test fun readsOnlyAnExistingBoundedImageInsideTheWorkingRootWithoutCopying() {
        val root = Files.createTempDirectory("hovel-image-test")
        val outside = Files.createTempFile("hovel-image-outside", ".png")
        try {
            val file = root.resolve("preview.png")
            ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", file.toFile())
            val original = Files.readAllBytes(file)
            val result = readProjectImage(root, "preview.png")
            assertEquals("image/png", result.mediaType)
            assertContentEquals(original, Base64.getDecoder().decode(result.base64))
            assertContentEquals(original, Files.readAllBytes(file))
            assertEquals(1, Files.list(root).use { it.count() }.toInt())
            Files.copy(file, outside, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertFails { readProjectImage(root, outside.toString()) }
            assertFails { readProjectImage(root, "../${outside.fileName}") }
            Files.createSymbolicLink(root.resolve("escape.png"), outside)
            assertFails { readProjectImage(root, "escape.png") }
            Files.writeString(root.resolve("fake.png"), "not an image")
            assertFails { readProjectImage(root, "fake.png") }
            Files.write(root.resolve("large.png"), ByteArray(2 * 1024 * 1024 + 1))
            assertFails { readProjectImage(root, "large.png") }
            assertFails { readProjectImage(root, "missing.png") }
        } finally { root.toFile().deleteRecursively(); Files.deleteIfExists(outside) }
    }

    @Test fun imageReadsRequireConfirmedOwnershipAndNeverFallbackToCachedOwnership() = runBlocking {
        val root = Files.createTempDirectory("hovel-image-owner")
        try {
            val account = UUID.randomUUID().toString()
            val project = UUID.randomUUID().toString()
            val store = ProjectFolderStore(root, FolderPicker { _, _ -> null })
            for (owns in listOf<Boolean?>(false, null)) {
                val capability = DesktopProjects(store, { account }, { emptyList() }, { _, _ -> }, { _, _ -> owns })
                assertFailsWith<IllegalStateException> { capability.image(ProjectImageArgs(project, "preview.png")) }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun syncedTextExplicitlyMarksATruncatedTail() {
        assertEquals("short", sharedArtifactText("short"))
        val clipped = sharedArtifactText("x".repeat(BrainLimits.OUTPUT + 100))
        assertEquals(BrainLimits.OUTPUT, clipped.length)
        assertTrue(clipped.endsWith("The complete output remains on the project Mac.]"))
    }
}
