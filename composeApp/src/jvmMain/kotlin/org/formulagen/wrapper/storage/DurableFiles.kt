package org.formulagen.wrapper.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/*
 * The local stores' file discipline, as `ExecutionJournal` keeps it: private folders
 * (0700), private files (0600), and a record replaced only after its complete new content is synced
 * to disk, then the folder synced, so a crash leaves the old record or the new one. Conversations and
 * project folders use these; the older stores keep their own copies until they move here.
 */

/** [path] as a private folder, created if needed. */
internal fun privateFolder(path: Path): Path {
    Files.createDirectories(path)
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    return path
}

/** Replace [file] with [bytes] durably and atomically. */
internal fun writeDurably(file: Path, bytes: ByteArray) {
    privateFolder(file.parent)
    val temp = Files.createTempFile(file.parent, ".record-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    try {
        FileChannel.open(temp, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        FileChannel.open(file.parent, READ).use { it.force(true) }
    } finally { Files.deleteIfExists(temp) }
}

/** [file]'s bytes, refusing one longer than [limit] before reading it. */
internal fun readBounded(file: Path, limit: Long): ByteArray {
    require(Files.size(file) <= limit) { "Record too large: $file" }
    return Files.readAllBytes(file)
}

/** SHA-256 of [bytes], lower-case hex: an artifact's digest. */
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Bounds and kinds of the immutable artifacts a run keeps: an input file or a specialist's output. */
internal object Artifacts {
    /** The largest input file or output an artifact holds. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** The media type of an artifact that is not UTF-8 text. */
    const val BINARY = "application/octet-stream"

    /** The media types whose artifact text syncs. */
    val TEXT = setOf("text/plain", "text/markdown")
}
