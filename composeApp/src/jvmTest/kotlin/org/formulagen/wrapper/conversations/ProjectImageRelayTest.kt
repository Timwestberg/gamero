package org.formulagen.wrapper.conversations

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.formulagen.wrapper.bridge.FolderRole
import org.formulagen.wrapper.hovel.NativeRoute

class ProjectImageRelayTest {
    @Test fun realProjectImageTravelsInBoundedChunksWithoutChangingFilesOrCheckpointing() {
        val disk = Files.createTempDirectory("image-relay")
        val config = Files.createTempDirectory("image-relay-config")
        val owner = id(); val project = id(); val host = id(); val request = id()
        val folders = ProjectFolderStore(config, FolderPicker { _, _ -> disk })
        folders.choose(owner, project, FolderRole.WORKING)
        val server = FakeServer(host)
        server.projects[project] = CompanionProject(false, id(), host, emptyList(), project, "Project", 1)
        val image = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
        val random = java.util.Random(42)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, random.nextInt())
        val file = disk.resolve("preview.png")
        ImageIO.write(image, "png", file.toFile())
        val original = Files.readAllBytes(file)
        val chunks = mutableListOf<JsonObject>()
        var work = ImageRelayWork(project, "preview.png", Instant.now().plusSeconds(12).toString())
        val api = object : ConversationApi by server {
            override fun imageWork(requestId: String): ImageRelayWork { assertEquals(request, requestId); return work }
            override fun imageChunk(requestId: String, chunk: JsonObject) { assertEquals(request, requestId); chunks += chunk }
        }
        val relay = ProjectImageRelay(api, folders, owner, host)
        try {
            relay.send(request)
            assertTrue(chunks.size > 1)
            var offset = 0
            val received = java.io.ByteArrayOutputStream()
            chunks.forEachIndexed { index, chunk ->
                assertEquals(offset, chunk.getValue("offset").jsonPrimitive.int)
                val encoded = chunk.getValue("base64").jsonPrimitive.content
                assertTrue(encoded.length <= ProjectImageRelay.CHUNK_CHARACTERS)
                val bytes = Base64.getDecoder().decode(encoded)
                received.write(bytes); offset += bytes.size
                assertEquals(index == chunks.lastIndex, chunk.getValue("done").jsonPrimitive.boolean)
            }
            assertContentEquals(original, received.toByteArray())
            assertContentEquals(original, Files.readAllBytes(file))
            assertEquals(1, Files.list(disk).use { it.count() }.toInt())
            assertTrue(server.checkpoints.isEmpty())
            chunks.clear()
            work = work.copy(expiresAt = Instant.now().minusSeconds(1).toString())
            assertFails { relay.send(request) }
            work = work.copy(expiresAt = Instant.now().plusSeconds(12).toString(), path = "../escape.png")
            assertFails { relay.send(request) }
            work = work.copy(path = "preview.png")
            server.projects[project] = server.projects.getValue(project).copy(computerId = id())
            assertFails { relay.send(request) }
            assertTrue(chunks.isEmpty())
        } finally { relay.close(); disk.toFile().deleteRecursively(); config.toFile().deleteRecursively() }
    }

    @Test fun relayEventAndNativeRoutesAreRecognized() {
        val request = id()
        assertEquals(HostStreamEvent.ImageRequested(request), HostEvents.decode("""{"type":"artifact.image.requested","requestId":"$request"}"""))
        assertTrue(NativeRoute.allows("GET", NativeRoute.IMAGE_RELAY_READ.path(request)))
        assertTrue(NativeRoute.allows("POST", NativeRoute.IMAGE_RELAY_CHUNK.path(request)))
        assertFalse(NativeRoute.allows("POST", "image-relays/../../secret/chunks"))
    }
}
