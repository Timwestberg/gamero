package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.*
import kotlin.test.*

class ChatHistoryTest {
    private val companion = "20000000-0000-4000-8000-000000000002"
    private val thread = "10000000-0000-4000-8000-000000000001"
    private val session = "30000000-0000-4000-8000-000000000003"
    private val user = "40000000-0000-4000-8000-000000000004"
    private val reply = "50000000-0000-4000-8000-000000000005"
    private val handover = "60000000-0000-4000-8000-000000000006"
    private val hash = "a".repeat(64)

    private fun paths(root: Path) = root.resolve("local/companion-chats/$companion.json") to root.resolve("local/codex/companion-chats/$companion.json")
    private fun writeLegacy(file: Path, text: String) { Files.createDirectories(file.parent); Files.writeString(file, text) }
    private fun permissions(path: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    /** As the version 1 writer stored it: default values, including `version`, were left out. */
    private val legacyRecord = """{"threadId":"$thread","messages":[{"id":"$user","role":"user","text":"Hello"},{"id":"$reply","role":"assistant","text":"Hi there"}]}"""

    @Test fun aVersionOneRecordMovesOnFirstReadWithCodexAsTheAuthor() {
        val root = Files.createTempDirectory("history-migrate")
        val (file, legacy) = paths(root)
        writeLegacy(legacy, legacyRecord)
        val history = companionChatHistory(root, "local", companion)
        val record = history.read()
        assertEquals(2, record.version)
        assertEquals(mapOf(RuntimeKind.CODEX to NativeConversation(thread, "")), record.conversations)
        assertEquals(listOf(HistoryEntry.User(user, "Hello"), HistoryEntry.Assistant(reply, MemberRef(RuntimeKind.CODEX, RUNTIME_DEFAULT_MODEL), "Hi there")), record.entries)
        assertNull(record.pendingContext)
        assertFalse(Files.exists(legacy)); assertTrue(Files.exists(file))
        assertEquals("rw-------", permissions(file)); assertEquals("rwx------", permissions(file.parent))
        assertEquals(record, history.read())
        assertEquals(ChatRecord(), companionChatHistory(root, "local", "20000000-0000-4000-8000-000000000009").read())
    }

    @Test fun anUnreadableVersionOneRecordIsLeftUntouchedBesideAFreshConversation() {
        for (broken in listOf("broken", """{"threadId":"not-a-uuid"}""", """{"version":7,"messages":[]}""")) {
            val root = Files.createTempDirectory("history-legacy-broken")
            val (file, legacy) = paths(root)
            writeLegacy(legacy, broken)
            val history = companionChatHistory(root, "local", companion)
            assertEquals(ChatRecord(), history.read())
            assertFalse(Files.exists(file))
            history.write(ChatRecord(entries = listOf(HistoryEntry.User(user, "Hello"))))
            assertEquals(1, history.read().entries.size)
            assertEquals(broken, Files.readString(legacy))
        }
    }

    @Test fun aCorruptRecordIsNeverOverwrittenOrReplacedByTheOldOne() {
        val root = Files.createTempDirectory("history-corrupt")
        val (file, legacy) = paths(root)
        writeLegacy(legacy, legacyRecord)
        Files.createDirectories(file.parent); Files.writeString(file, "broken")
        assertFails { companionChatHistory(root, "local", companion).read() }
        assertEquals("broken", Files.readString(file)); assertEquals(legacyRecord, Files.readString(legacy))
    }

    @Test fun everyEntryKindRoundTripsInTheContractShape() {
        val root = Files.createTempDirectory("history-v2")
        val (file, _) = paths(root)
        val claude = MemberRef(RuntimeKind.CLAUDE_CODE, "sonnet")
        val record = ChatRecord(
            conversations = mapOf(RuntimeKind.CODEX to NativeConversation(thread, ""), RuntimeKind.CLAUDE_CODE to NativeConversation(session, hash)),
            entries = listOf(
                HistoryEntry.User(user, "Hello"),
                HistoryEntry.Handover(handover, "2026-10-27T12:00:00Z", MemberRef(RuntimeKind.CODEX, RUNTIME_DEFAULT_MODEL), claude,
                    HandoverMethod.SUMMARY, HandoverReason.MANUAL, summary = "They said hello."),
                HistoryEntry.Assistant(reply, claude, "Hi there"),
            ),
            pendingContext = PendingContext(RuntimeKind.CLAUDE_CODE, "Earlier in this conversation:\n<<<\nHello\n>>>"),
        )
        val history = ChatHistory(file)
        history.write(record)
        assertEquals(record, history.read())
        val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(JsonPrimitive(2), json["version"])
        assertEquals(setOf("codex", "claude-code"), json.getValue("conversations").jsonObject.keys)
        val entries = json.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "handover", "assistant"), entries.map { it.getValue("role").jsonPrimitive.content })
        assertEquals(buildJsonObject { put("runtimeId", "claude-code"); put("modelId", "sonnet") }, entries[2]["author"])
        assertEquals(JsonPrimitive("summary"), entries[1]["method"]); assertEquals(JsonPrimitive("manual"), entries[1]["reason"])
        assertEquals(JsonPrimitive("claude-code"), json.getValue("pendingContext").jsonObject["runtimeId"])
    }

    @Test fun recordsOutsideTheLimitsAreRejected() {
        val root = Files.createTempDirectory("history-invalid")
        val (file, _) = paths(root)
        val history = ChatHistory(file)
        fun rejects(record: ChatRecord) { history.write(record); assertFails { history.read() } }
        rejects(ChatRecord(version = 3))
        rejects(ChatRecord(entries = List(41) { HistoryEntry.User(java.util.UUID.randomUUID().toString(), "x") }))
        rejects(ChatRecord(entries = listOf(HistoryEntry.Assistant(reply, MemberRef(RuntimeKind.CODEX, "--model"), ""))))
        rejects(ChatRecord(entries = listOf(HistoryEntry.User(user, "x".repeat(16_001)))))
        rejects(ChatRecord(conversations = mapOf(RuntimeKind.CODEX to NativeConversation(thread, "short"))))
        rejects(ChatRecord(conversations = mapOf(RuntimeKind.CLAUDE_CODE to NativeConversation("../session", hash))))
        rejects(ChatRecord(entries = listOf(HistoryEntry.Handover(handover, "yesterday", null, MemberRef(RuntimeKind.CODEX, "x"), HandoverMethod.FRESH, HandoverReason.MANUAL))))
        rejects(ChatRecord(pendingContext = PendingContext(RuntimeKind.CODEX, "x".repeat(PENDING_CONTEXT_LIMIT + 1))))
        Files.writeString(file, " ".repeat(3_000_001)); assertFails { history.read() }
        assertFails { companionChatHistory(root, "../../.codex", companion) }
    }
}
