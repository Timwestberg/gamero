package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.brain.BrainRecord
import org.formulagen.wrapper.chat.NativeConversation

class ConversationStoreTest {
    private val root = folder("conversation-store")
    private val account = id()
    private val store = ConversationStore(root, account)
    private val conversation = id()

    @Test fun aRecordIsKeptPrivatelyPerAccountAndConversation() {
        assertEquals(ConversationRecord(), store.read(conversation))
        val record = ConversationRecord(brain = BrainRecord(coordinatorContexts = mapOf("a:codex" to NativeConversation(id(), "hash"))),
            threadContexts = mapOf(id() to NativeConversation(id(), "hash")))
        store.write(conversation, record)
        assertEquals(record, store.read(conversation))
        assertEquals(record, ConversationStore(root, account).read(conversation))
        assertEquals(ConversationRecord(), ConversationStore(root, id()).read(conversation), "another account has its own")
        val folder = root.resolve(account).resolve("conversations").resolve(conversation)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.resolve("conversation.json"))))
        assertFails { ConversationStore(root, "local") }
        assertFails { store.read("../escape") }
        assertEquals(record.copy(runMessageId = "x"), store.update(conversation) { it.copy(runMessageId = "x") })
    }

    @Test fun theJournalKeepsClaimsUntilTheyAreForgotten() {
        val other = id()
        val claiming = store.journal(ClaimEntry(id(), conversation, ClaimPhase.CLAIMING))
        val launching = store.journal(ClaimEntry(id(), other, ClaimPhase.LAUNCHING, id(), 3))
        assertEquals(setOf(claiming, launching), store.pendingClaims().toSet())
        assertEquals(listOf(claiming), store.claims(conversation))
        val claimed = store.journal(claiming.copy(phase = ClaimPhase.CLAIMED, messageId = id(), revision = 2))
        assertEquals(listOf(claimed), store.claims(conversation))
        store.forget(claimed)
        assertEquals(listOf(launching), store.pendingClaims())
        Files.writeString(root.resolve(account).resolve("conversations").resolve(other).resolve("journal").resolve("${launching.claimId}.json"),
            "{\"claimId\":\"${id()}\",\"conversationId\":\"$other\",\"phase\":\"launching\"}")
        assertFails("a record in another's name is never trusted") { store.pendingClaims() }
    }

    @Test fun onlyOneHovelRunsAnAccountsConversations() {
        store.open()
        try {
            assertFails { ConversationStore(root, account).open() }
            ConversationStore(root, id()).open().close()
        } finally { store.close() }
        ConversationStore(root, account).open().close()
    }

    @Test fun artifactsAreImmutableAndCheckedOnRead() {
        val artifact = store.artifact(conversation, "  Report  ", BrainSource(BrainSourceKind.ATTEMPT, attemptId = id()), "Done".toByteArray(), "text/markdown")
        assertEquals("Report", artifact.title)
        assertEquals("Done", store.bytes(conversation, artifact).decodeToString())
        assertTrue(Files.exists(store.artifactPath(conversation, artifact)))
        val input = id()
        assertEquals(input, store.artifact(conversation, "Notes", BrainSource(BrainSourceKind.USER, messageId = id()), "Preview".toByteArray(),
            "text/plain", id = input).id, "an input keeps the server's id")
        Files.writeString(store.artifactPath(conversation, artifact), "Changed")
        assertFails { store.bytes(conversation, artifact) }
    }

    @Test fun grokPruningKeepsEveryConversationsBindings() {
        val coordinator = id(); val thread = id()
        store.write(conversation, ConversationRecord(brain = BrainRecord(coordinatorContexts = mapOf("c:grok-build" to NativeConversation(coordinator, "h"))),
            threadContexts = mapOf(id() to NativeConversation(thread, "h"))))
        store.write(id(), ConversationRecord())
        assertEquals(setOf(coordinator, thread), ConversationStore.bindingHandles(root, account))
        assertEquals(emptySet(), ConversationStore.bindingHandles(root, id()))
        assertEquals(emptySet(), ConversationStore.bindingHandles(root, "local"))
        Files.writeString(root.resolve(account).resolve("conversations").resolve(conversation).resolve("conversation.json"), "{broken")
        assertNull(ConversationStore.bindingHandles(root, account), "an unreadable record prunes nothing")
    }
}
