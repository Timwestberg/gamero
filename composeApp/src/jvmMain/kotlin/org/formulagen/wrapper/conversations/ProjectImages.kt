package org.formulagen.wrapper.conversations

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import org.formulagen.wrapper.bridge.ProjectImageResult

private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
private const val MAX_IMAGE_PIXELS = 32_000_000L

/** Read only a bounded PNG/JPEG inside the selected working root; never write or publish image bytes. */
internal fun readProjectImage(root: Path, relative: String): ProjectImageResult {
    require(relative.isNotBlank() && '\u0000' !in relative && '\\' !in relative) { "Use a project-relative image path" }
    val requested = Path.of(relative)
    require(!requested.isAbsolute) { "Use a project-relative image path" }
    val realRoot = root.toRealPath()
    val file = realRoot.resolve(requested).toRealPath()
    require(file.startsWith(realRoot) && Files.isRegularFile(file)) { "Image must be inside the project's working folder" }
    val bytes = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
    require(bytes.size in 1..MAX_IMAGE_BYTES) { "Local previews support images up to 2 MiB" }
    val mediaType = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
        val readers = ImageIO.getImageReaders(stream)
        require(readers.hasNext()) { "Use a PNG or JPEG image" }
        val reader = readers.next()
        try {
            reader.input = stream
            val format = reader.formatName.lowercase()
            require(format == "png" || format == "jpeg" || format == "jpg") { "Use a PNG or JPEG image" }
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= MAX_IMAGE_PIXELS) { "Image dimensions exceed the preview limit" }
            if (format == "png") "image/png" else "image/jpeg"
        } finally { reader.dispose() }
    }
    return ProjectImageResult(mediaType, Base64.getEncoder().encodeToString(bytes))
}
