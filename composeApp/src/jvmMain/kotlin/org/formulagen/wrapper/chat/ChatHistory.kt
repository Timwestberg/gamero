package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.ChatMessage

@Serializable
internal data class ChatRecord(val version: Int = 1, val threadId: String? = null, val messages: List<ChatMessage> = emptyList())

/** One atomic native record per companion. A corrupt file is never silently overwritten. */
internal class ChatHistory(private val file: Path) {
    fun read(): ChatRecord {
        if (!Files.exists(file)) return ChatRecord()
        require(Files.size(file) <= 3_000_000)
        return Json.decodeFromString<ChatRecord>(Files.readString(file)).also { record ->
            require(record.version == 1 && record.messages.size <= ChatLimits.HISTORY)
            record.threadId?.let { require(UUID.fromString(it).toString() == it) }
            record.messages.forEach { require(it.text.length <= ChatLimits.REPLY); UUID.fromString(it.id) }
        }
    }

    /** Replace only after the complete new record has been written privately. */
    fun write(record: ChatRecord) {
        Files.createDirectories(file.parent)
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("rwx------"))
        val temp = Files.createTempFile(file.parent, ".chat-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(temp, Json.encodeToString(record))
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
}
