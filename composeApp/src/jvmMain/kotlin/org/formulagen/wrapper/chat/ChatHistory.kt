package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.BridgeUpdateRequiredException
import org.formulagen.wrapper.bridge.ChatEntry
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.Activity
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.providers.legacyCodexProfileHome

/**
 * One companion's conversation for one profile, shared by every runtime on its team (version 2; version 3 after Grok).
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
    /**
     * Each runtime's working conversation (Claude Code's working session, Codex's working thread),
     * apart from its talk-only one: the two run with different personas and abilities.
     */
    val workConversations: Map<RuntimeId, NativeConversation> = emptyMap(),
)

/** Longest handover text a runtime is owed: recent messages (8,000 characters) or a summary, with their framing. */
internal const val PENDING_CONTEXT_LIMIT = 12_000

/**
 * One atomic native record per companion at [file]. A corrupt record is never silently
 * overwritten. A version 1 record at [legacy] (Codex's own folder, from before teams) moves here on
 * first read; an unreadable one is left untouched and the conversation starts fresh beside it.
 */
internal class ChatHistory(private val file: Path, private val legacy: Path? = null) {
    private var writtenVersion = 2
    private var incompatible = false
    fun read(): ChatRecord {
        if (Files.exists(file)) {
            require(Files.size(file) <= MAX_RECORD_BYTES)
            val text = Files.readString(file)
            val version = historyJson.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.intOrNull
            if (version != null && version > 3) { incompatible = true; throw BridgeUpdateRequiredException() }
            return historyJson.decodeFromString<ChatRecord>(text).validated().also { writtenVersion = it.version }
        }
        val old = legacy?.takeIf { Files.exists(it) } ?: return ChatRecord()
        val migrated = runCatching { migrate(old) }.getOrNull() ?: return ChatRecord()
        write(migrated)
        Files.deleteIfExists(old)
        return migrated
    }

    /** Replace only after the complete new record has been written privately, always small enough to [read] back. */
    fun write(record: ChatRecord) {
        if (incompatible) throw BridgeUpdateRequiredException()
        require(record.version in 2..3)
        val version = if (record.version >= 3 || RuntimeId.GROK_BUILD in record.runtimeIds() || writtenVersion >= 3) 3 else 2
        val text = fitted(record.copy(version = version).validated())
        Files.createDirectories(file.parent)
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("rwx------"))
        val temp = Files.createTempFile(file.parent, ".chat-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(temp, text)
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
            writtenVersion = version
        } finally { Files.deleteIfExists(temp) }
    }
}

/**
 * [record] as written, within [MAX_RECORD_BYTES] so it can always be read back: working replies'
 * step details go first, oldest reply first, then their steps (the reply keeps an empty, truncated
 * activity, so it still reads as work). Fails only when the messages alone do not fit.
 */
internal fun fitted(record: ChatRecord, limit: Int = MAX_RECORD_BYTES): String {
    var current = record
    fun encoded() = historyJson.encodeToString(current).takeIf { it.toByteArray().size <= limit }
    encoded()?.let { return it }
    val worked = record.entries.withIndex().filter { (it.value as? ChatEntry.Assistant)?.activity != null }.map { it.index }
    fun trim(index: Int, trim: (Activity) -> Activity) {
        val reply = current.entries[index] as ChatEntry.Assistant
        current = current.copy(entries = current.entries.toMutableList().also { it[index] = reply.copy(activity = trim(checkNotNull(reply.activity))) })
    }
    for (index in worked) { trim(index) { it.copy(steps = it.steps.map { step -> step.copy(detail = null) }) }; encoded()?.let { return it } }
    for (index in worked) { trim(index) { Activity(emptyList(), truncated = true) }; encoded()?.let { return it } }
    error("Chat record too large")
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
 * build does not know (a later build's) is skipped, so compatible additive fields do not break reading. Newer versions are rejected before decoding.
 */
private val historyJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private fun ChatRecord.validated(): ChatRecord = also { record ->
    require(record.version in 2..3 && record.entries.size <= ChatLimits.HISTORY)
    require(record.version >= 3 || RuntimeId.GROK_BUILD !in record.runtimeIds())
    (record.conversations.values + record.workConversations.values).forEach { require(UUID.fromString(it.id).toString() == it.id && PERSONA_HASH.matches(it.personaHash)) }
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

/** All runtime identities retained in a record, including context that is not displayed. */
internal fun ChatRecord.runtimeIds(): Set<RuntimeId> = buildSet {
    addAll(conversations.keys); addAll(workConversations.keys); addAll(pendingContext.keys)
    entries.forEach { entry -> when (entry) {
        is ChatEntry.Assistant -> add(entry.author.runtimeId)
        is ChatEntry.Handover -> { entry.from?.let { add(it.runtimeId) }; add(entry.to.runtimeId) }
        is ChatEntry.User -> Unit
    } }
}
