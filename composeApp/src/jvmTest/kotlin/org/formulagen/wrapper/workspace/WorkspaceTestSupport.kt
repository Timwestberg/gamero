package org.formulagen.wrapper.workspace

import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/** Room a test leaves after its folder for Unix socket paths: a subfolder and a socket's name. */
private const val SOCKET_NAME_ROOM = 32

/** A temporary folder short enough for Unix sockets inside it: the system's, else one under `/tmp`. */
internal fun shortTempFolder(prefix: String = "hv"): Path {
    val folder = createTempDirectory(prefix)
    if (folder.toString().toByteArray().size + SOCKET_NAME_ROOM <= EditorSockets.MAX_SOCKET_PATH_BYTES) return folder
    folder.toFile().delete()
    return createTempDirectory(Path.of("/tmp"), prefix)
}
