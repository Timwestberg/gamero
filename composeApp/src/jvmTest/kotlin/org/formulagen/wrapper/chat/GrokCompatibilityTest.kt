package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

class GrokCompatibilityTest {
    private val grok = TeamMember(RuntimeId.GROK_BUILD, "runtime-default")
    private fun record() = ChatRecord(entries = listOf(ChatEntry.Assistant(UUID.randomUUID().toString(), grok, "Saved reply")))
    private class Speech : ChatSpeech {
        override fun status() = ChatVoice()
        override fun speak(text: String) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    @Test fun historiesUpgradeOnlyForGrokAndNeverDowngradeAfterRemoval() {
        val root = Files.createTempDirectory("grok-history")
        try {
            val file = root.resolve("chat.json")
            val history = ChatHistory(file)
            history.write(ChatRecord())
            assertEquals(2, history.read().version)
            history.write(record())
            assertEquals(3, history.read().version)
            history.write(ChatRecord())
            assertEquals(3, ChatHistory(file).read().version)
            val newer = """{"version":4,"conversations":{"future-runtime":{"id":"opaque"}},"entries":[]}"""
            Files.writeString(file, newer)
            assertFailsWith<BridgeUpdateRequiredException> { history.read() }
            assertFailsWith<BridgeUpdateRequiredException> { history.write(ChatRecord()) }
            assertEquals(newer, Files.readString(file))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun legacyLeaseCannotReadOrWriteGrokAndSupportedLeaseKeepsTheHistory() = runBlocking {
        val root = Files.createTempDirectory("grok-client")
        try {
            val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())
            val history = companionChatHistory(root, scope.profile, scope.companionId)
            history.write(record())
            val saved = history.read()
            DesktopChat(root, emptyList(), Speech(), offered = emptySet()).use { chat ->
                assertFailsWith<BridgeUpdateRequiredException> { chat.open(scope) }
                assertEquals(saved, history.read())
                val args = ChatOpenForClient(scope.profile, scope.companionId, scope.name, scope.sessionId, listOf("grok-build", "future-runtime"))
                assertEquals(saved.entries, chat.openForClient(args).messages)
                assertFailsWith<BridgeUpdateRequiredException> { chat.open(scope) }
                assertEquals(saved.entries, chat.snapshot(scope).messages)
            }
            val empty = scope.copy(companionId = UUID.randomUUID().toString(), sessionId = UUID.randomUUID().toString())
            DesktopChat(root, emptyList(), Speech()).use { chat ->
                chat.open(empty)
                assertFailsWith<BridgeUpdateRequiredException> {
                    chat.send(ChatSend(empty.profile, empty.companionId, empty.name, empty.sessionId, grok, UUID.randomUUID().toString(), "Hello", false))
                }
                assertTrue(chat.snapshot(empty).messages.isEmpty())
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
