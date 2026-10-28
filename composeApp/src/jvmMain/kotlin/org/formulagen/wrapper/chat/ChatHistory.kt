package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.ChatEntry
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.providers.legacyCodexProfileHome

/**
 * One companion's conversation for one profile, shared by every runtime on its team (version 2).
 * Native ids and handover text stay here; the page sees only the entries, in their wire shape.
 */
@Serializable
internal data class ChatRecord(
    val version: Int = 2,
    /** Each runtime's own conversation with the companion: Codex's thread id, Claude Code's session id. */
    val conversations: Map<RuntimeId, NativeConversation> = emptyMap(),
    /** Oldest first, at most [ChatLimits.HISTORY]; the oldest drop first, handover entries included. */
    val entries: List<ChatEntry> = emptyList(),
    /** Handover text each runtime is owed for its next turn, independent of the visible history. */
    val pendingContext: Map<RuntimeId, String> = emptyMap(),
)

/** Longest handover text a runtime is owed: recent messages (8,000 characters) or a summary, with their framing. */
internal const val PENDING_CONTEXT_LIMIT = 12_000

/**
 * One atomic native record per companion at [file]. A corrupt record is never silently
 * overwritten. A version 1 record at [legacy] (Codex's own folder, from before teams) moves here on
 * first read; an unreadable one is left untouched and the conversation starts fresh beside it.
 */
internal class ChatHistory(private val file: Path, private val legacy: Path? = null) {
    fun read(): ChatRecord {
        if (Files.exists(file)) {
            require(Files.size(file) <= MAX_RECORD_BYTES)
            return historyJson.decodeFromString<ChatRecord>(Files.readString(file)).validated()
        }
        val old = legacy?.takeIf { Files.exists(it) } ?: return ChatRecord()
        val migrated = runCatching { migrate(old) }.getOrNull() ?: return ChatRecord()
        write(migrated)
        Files.deleteIfExists(old)
        return migrated
    }

    /** Replace only after the complete new record has been written privately. */
    fun write(record: ChatRecord) {
        Files.createDirectories(file.parent)
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("rwx------"))
        val temp = Files.createTempFile(file.parent, ".chat-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(temp, historyJson.encodeToString(record))
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
}

/**
 * A companion's history for [profile]: `<providers root>/<profile>/companion-chats/<companionId>.json`,
 * runtime-neutral, migrating a version 1 record from Hovel's old per-profile Codex home.
 */
internal fun companionChatHistory(providerRoot: Path, profile: String, companionId: String): ChatHistory {
    require(SharedRules.isProfileId(profile)) { "Invalid profile" }
    val name = "${UUID.fromString(companionId)}.json"
    return ChatHistory(providerRoot.resolve(profile).resolve("companion-chats").resolve(name),
        legacy = legacyCodexProfileHome(providerRoot, profile).resolve("companion-chats").resolve(name))
}

private const val MAX_RECORD_BYTES = 3_000_000
private val PERSONA_HASH = Regex("^([0-9a-f]{64})?$")
/**
 * Writes `version` explicitly. Entries keep their wire shape, told apart by `role`. A field this
 * build does not know (a later build's) is skipped, so a record a newer build wrote still opens.
 */
private val historyJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private fun ChatRecord.validated(): ChatRecord = also { record ->
    require(record.version == 2 && record.entries.size <= ChatLimits.HISTORY)
    record.conversations.values.forEach { require(UUID.fromString(it.id).toString() == it.id && PERSONA_HASH.matches(it.personaHash)) }
    record.entries.forEach { entry ->
        UUID.fromString(entry.id)
        when (entry) {
            is ChatEntry.User -> require(entry.text.length <= ChatLimits.MESSAGE)
            is ChatEntry.Assistant -> require(entry.text.length <= ChatLimits.REPLY && entry.author.valid())
            is ChatEntry.Handover -> {
                Instant.parse(entry.at)
                require(entry.from?.valid() != false && entry.to.valid() && (entry.summary?.length ?: 0) <= SUMMARY_LIMIT)
            }
        }
    }
    record.pendingContext.values.forEach { require(it.length <= PENDING_CONTEXT_LIMIT) }
}

private fun TeamMember.valid() = SharedRules.isModelId(modelId)

/** The version 1 record: one Codex thread and its messages. Its writer left out default values. */
@Serializable
private data class LegacyRecord(val version: Int = 1, val threadId: String? = null, val messages: List<LegacyMessage> = emptyList())

@Serializable
private data class LegacyMessage(val id: String, val role: LegacyRole, val text: String)

@Serializable
private enum class LegacyRole { @SerialName("user") USER, @SerialName("assistant") ASSISTANT }

/** Version 1 → 2: the thread becomes Codex's conversation, and Codex at its default model wrote every reply. */
private fun migrate(file: Path): ChatRecord {
    require(Files.size(file) <= MAX_RECORD_BYTES)
    val old = Json.decodeFromString<LegacyRecord>(Files.readString(file))
    require(old.version == 1 && old.messages.size <= ChatLimits.HISTORY)
    val codex = TeamMember(RuntimeId.CODEX, RUNTIME_DEFAULT_MODEL)
    return ChatRecord(
        conversations = old.threadId?.let { mapOf(RuntimeId.CODEX to NativeConversation(it, personaHash = "")) }.orEmpty(),
        entries = old.messages.map {
            when (it.role) {
                LegacyRole.USER -> ChatEntry.User(it.id, it.text)
                LegacyRole.ASSISTANT -> ChatEntry.Assistant(it.id, codex, it.text)
            }
        },
    ).validated()
}
