package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.util.UUID
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import kotlin.test.*
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL

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
        assertEquals(mapOf(RuntimeId.CODEX to NativeConversation(thread, "")), record.conversations)
        assertEquals(listOf(ChatEntry.User(user, "Hello"), ChatEntry.Assistant(reply, TeamMember(RuntimeId.CODEX, RUNTIME_DEFAULT_MODEL), "Hi there")), record.entries)
        assertTrue(record.pendingContext.isEmpty())
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
            history.write(ChatRecord(entries = listOf(ChatEntry.User(user, "Hello"))))
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
        val claude = TeamMember(RuntimeId.CLAUDE_CODE, "sonnet")
        val record = ChatRecord(
            conversations = mapOf(RuntimeId.CODEX to NativeConversation(thread, ""), RuntimeId.CLAUDE_CODE to NativeConversation(session, hash)),
            entries = listOf(
                ChatEntry.User(user, "Hello"),
                ChatEntry.Handover(handover, "2026-10-27T12:00:00Z", TeamMember(RuntimeId.CODEX, RUNTIME_DEFAULT_MODEL), claude,
                    HandoverMethod.SUMMARY, HandoverReason.MANUAL, summary = "They said hello."),
                ChatEntry.Assistant(reply, claude, "Hi there"),
            ),
            pendingContext = mapOf(RuntimeId.CLAUDE_CODE to "Earlier in this conversation:\n<<<\nHello\n>>>", RuntimeId.CODEX to "Earlier:\n<<<\nHi\n>>>"),
        )
        val history = ChatHistory(file)
        history.write(record)
        assertEquals(record, history.read())
        val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(JsonPrimitive(2), json["version"])
        assertEquals(setOf("codex", "claude-code"), json.getValue("conversations").jsonObject.keys)
        assertEquals(setOf("claude-code", "codex"), json.getValue("pendingContext").jsonObject.keys)
        // A field a later build adds is skipped, so its record still opens here.
        Files.writeString(file, Files.readString(file).replaceFirst("{", "{\"laterField\":{\"x\":1},"))
        assertEquals(record, history.read())
        val entries = json.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "handover", "assistant"), entries.map { it.getValue("role").jsonPrimitive.content })
        assertEquals(buildJsonObject { put("runtimeId", "claude-code"); put("modelId", "sonnet") }, entries[2]["author"])
        assertEquals(JsonPrimitive("summary"), entries[1]["method"]); assertEquals(JsonPrimitive("manual"), entries[1]["reason"])

    }

    @Test fun recordsOutsideTheLimitsAreRejected() {
        val root = Files.createTempDirectory("history-invalid")
        val (file, _) = paths(root)
        val history = ChatHistory(file)
        fun rejects(record: ChatRecord) { history.write(record); assertFails { history.read() } }
        rejects(ChatRecord(version = 3))
        rejects(ChatRecord(entries = List(41) { ChatEntry.User(java.util.UUID.randomUUID().toString(), "x") }))
        rejects(ChatRecord(entries = listOf(ChatEntry.Assistant(reply, TeamMember(RuntimeId.CODEX, "--model"), ""))))
        rejects(ChatRecord(entries = listOf(ChatEntry.User(user, "x".repeat(4_001)))))
        rejects(ChatRecord(conversations = mapOf(RuntimeId.CODEX to NativeConversation(thread, "short"))))
        rejects(ChatRecord(conversations = mapOf(RuntimeId.CLAUDE_CODE to NativeConversation("../session", hash))))
        rejects(ChatRecord(entries = listOf(ChatEntry.Handover(handover, "yesterday", null, TeamMember(RuntimeId.CODEX, "x"), HandoverMethod.FRESH, HandoverReason.MANUAL))))
        rejects(ChatRecord(pendingContext = mapOf(RuntimeId.CODEX to "x".repeat(PENDING_CONTEXT_LIMIT + 1))))
        Files.writeString(file, " ".repeat(3_000_001)); assertFails { history.read() }
        assertFails { companionChatHistory(root, "../../.codex", companion) }
    }

    @Test fun aRecordTooLargeToReadBackLosesItsOldestStepDetailsFirst() {
        val sonnet = TeamMember(RuntimeId.CLAUDE_CODE, "sonnet")
        fun worked(detail: String) = ChatEntry.Assistant(UUID.randomUUID().toString(), sonnet, "Done.",
            Activity((1..50).map { ActivityStep(ActivityKind.COMMAND, "step $it", ActivityStatus.DONE, detail) }, false))
        val record = ChatRecord(entries = List(4) { worked("d".repeat(400)) })
        val whole = fitted(record, limit = 1_000_000)
        assertEquals(record, Json { ignoreUnknownKeys = true }.decodeFromString<ChatRecord>(whole))
        // Under a tighter cap, the oldest reply's details go first; its steps stay.
        val trimmed = Json { ignoreUnknownKeys = true }.decodeFromString<ChatRecord>(fitted(record, limit = 70_000))
        val first = trimmed.entries.first() as ChatEntry.Assistant
        assertTrue(first.activity!!.steps.all { it.detail == null } && first.activity!!.steps.size == 50)
        assertEquals("d".repeat(400), (trimmed.entries.last() as ChatEntry.Assistant).activity!!.steps.first().detail)
        // Then steps go, and a reply keeps an empty, truncated activity, so it still reads as work.
        val bare = Json { ignoreUnknownKeys = true }.decodeFromString<ChatRecord>(fitted(record, limit = 6_000))
        assertTrue(bare.entries.all { (it as ChatEntry.Assistant).activity != null })
        assertTrue((bare.entries.first() as ChatEntry.Assistant).activity!!.truncated)
        assertFails { fitted(record, limit = 100) }
    }
}
