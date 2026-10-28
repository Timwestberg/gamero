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
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.ChatMessage
import org.formulagen.wrapper.bridge.ChatRole
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.providers.codexProfileHome

/**
 * One companion's conversation for one profile, shared by every runtime on its team (version 2).
 * Native ids and handover text stay here; the page sees only the entries.
 */
@Serializable
internal data class ChatRecord(
    val version: Int = 2,
    /** Each runtime's own conversation with the companion: Codex's thread id, Claude Code's session id. */
    val conversations: Map<RuntimeKind, NativeConversation> = emptyMap(),
    /** Oldest first, at most [ChatLimits.HISTORY]; the oldest drop first, handover entries included. */
    val entries: List<HistoryEntry> = emptyList(),
    /** Handover text owed to one runtime's next turn, independent of the visible history. */
    val pendingContext: PendingContext? = null,
)

@Serializable
internal data class PendingContext(val runtimeId: RuntimeKind, val text: String)

/** One conversation entry, by `role`. The shape mirrors the chat contract's `chatEntry`, so the bridge type can replace it. */
@Serializable
internal sealed interface HistoryEntry {
    val id: String

    @Serializable @SerialName("user")
    data class User(override val id: String, val text: String) : HistoryEntry

    @Serializable @SerialName("assistant")
    data class Assistant(override val id: String, val author: MemberRef, val text: String) : HistoryEntry

    /** A switch between members; [at] is an ISO-8601 instant and [from] is null for the first member of a conversation. */
    @Serializable @SerialName("handover")
    data class Handover(
        override val id: String,
        val at: String,
        val from: MemberRef?,
        val to: MemberRef,
        val method: HandoverMethod,
        val reason: HandoverReason,
        val summary: String? = null,
    ) : HistoryEntry
}

@Serializable
internal enum class HandoverMethod {
    @SerialName("same-conversation") SAME_CONVERSATION,
    @SerialName("summary") SUMMARY,
    @SerialName("recent-messages") RECENT_MESSAGES,
    @SerialName("fresh") FRESH,
}

@Serializable
internal enum class HandoverReason { @SerialName("manual") MANUAL }

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

/** A companion's history for [profile]: `<providers root>/<profile>/companion-chats/<companionId>.json`, migrating from Codex's folder. */
internal fun companionChatHistory(providerRoot: Path, profile: String, companionId: String): ChatHistory {
    require(SharedRules.isProfileId(profile)) { "Invalid profile" }
    val name = "${UUID.fromString(companionId)}.json"
    return ChatHistory(providerRoot.resolve(profile).resolve("companion-chats").resolve(name),
        legacy = codexProfileHome(providerRoot, profile).resolve("companion-chats").resolve(name))
}

private const val MAX_RECORD_BYTES = 3_000_000
private val PERSONA_HASH = Regex("^([0-9a-f]{64})?$")
/** Writes `version` explicitly; the polymorphic entries are told apart by `role`, as on the wire. */
private val historyJson = Json { classDiscriminator = "role"; encodeDefaults = true }

private fun ChatRecord.validated(): ChatRecord = also { record ->
    require(record.version == 2 && record.entries.size <= ChatLimits.HISTORY)
    record.conversations.values.forEach { require(UUID.fromString(it.id).toString() == it.id && PERSONA_HASH.matches(it.personaHash)) }
    record.entries.forEach { entry ->
        UUID.fromString(entry.id)
        when (entry) {
            is HistoryEntry.User -> require(entry.text.length <= ChatLimits.REPLY)
            is HistoryEntry.Assistant -> require(entry.text.length <= ChatLimits.REPLY && entry.author.valid())
            is HistoryEntry.Handover -> {
                Instant.parse(entry.at)
                require(entry.from?.valid() != false && entry.to.valid() && (entry.summary?.length ?: 0) <= SUMMARY_LIMIT)
            }
        }
    }
    record.pendingContext?.let { require(it.text.length <= PENDING_CONTEXT_LIMIT) }
}

private fun MemberRef.valid() = MODEL_ID.matches(modelId)

/** The version 1 record: one Codex thread and its messages. Its writer left out default values. */
@Serializable
private data class LegacyRecord(val version: Int = 1, val threadId: String? = null, val messages: List<ChatMessage> = emptyList())

/** Version 1 → 2: the thread becomes Codex's conversation, and Codex at its default model wrote every reply. */
private fun migrate(file: Path): ChatRecord {
    require(Files.size(file) <= MAX_RECORD_BYTES)
    val old = Json.decodeFromString<LegacyRecord>(Files.readString(file))
    require(old.version == 1 && old.messages.size <= ChatLimits.HISTORY)
    val codex = MemberRef(RuntimeKind.CODEX, RUNTIME_DEFAULT_MODEL)
    return ChatRecord(
        conversations = old.threadId?.let { mapOf(RuntimeKind.CODEX to NativeConversation(it, personaHash = "")) }.orEmpty(),
        entries = old.messages.map {
            when (it.role) {
                ChatRole.USER -> HistoryEntry.User(it.id, it.text)
                ChatRole.ASSISTANT -> HistoryEntry.Assistant(it.id, codex, it.text)
            }
        },
    ).validated()
}
