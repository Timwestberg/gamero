package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.brain.BrainRecord
import org.formulagen.wrapper.brain.RunRecord
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
    }

    @Test fun readingCreatesNothing() {
        assertEquals(ConversationRecord(), store.read(conversation))
        assertEquals(emptyList(), store.claims(conversation))
        assertFalse(Files.exists(root.resolve(account).resolve("conversations").resolve(conversation)), "a read leaves no folder behind")
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
    }

    @Test fun theJournalIsReadOnceAtOpenAndAnUnreadableEntryIsSkipped() {
        val other = id()
        val launching = store.journal(ClaimEntry(id(), other, ClaimPhase.LAUNCHING, id(), 3))
        val settling = store.journal(ClaimEntry(id(), conversation, ClaimPhase.SETTLING, id(), 4, MessageState.ANSWERED))
        val journal = root.resolve(account).resolve("conversations").resolve(other).resolve("journal")
        // One in another's name, and one that is not JSON at all: neither is trusted, and neither stops the rest.
        Files.writeString(journal.resolve("${launching.claimId}.json"), "{\"claimId\":\"${id()}\",\"conversationId\":\"$other\",\"phase\":\"launching\"}")
        Files.writeString(journal.resolve("${id()}.json"), "{broken")
        assertEquals(setOf(launching, settling), store.pendingClaims().toSet(), "the open store answers from its index")
        val reopened = ConversationStore(root, account).open()
        try {
            assertEquals(listOf(settling), reopened.pendingClaims())
        } finally { reopened.close() }
    }

    @Test fun stopMarksOlderThanAQueuedMessageLivesAreDroppedAtOpenUnlessTheirClaimIsJournaled() {
        val stale = id()
        val fresh = id()
        val claimed = id()
        listOf(stale, fresh, claimed).forEach { store.stopOnClaim(conversation, it) }
        store.open()
        store.journal(ClaimEntry(id(), conversation, ClaimPhase.CLAIMED, claimed, 1))
        store.close()
        val marks = root.resolve(account).resolve("conversations").resolve(conversation).resolve("stop-on-claim")
        val old = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - CompanionLimits.QUEUED_MESSAGE_MS - 60_000)
        Files.setLastModifiedTime(marks.resolve("$stale.json"), old)
        Files.setLastModifiedTime(marks.resolve("$claimed.json"), old)
        val reopened = ConversationStore(root, account).open()
        try {
            assertFalse(reopened.stopsOnClaim(conversation, stale), "its message expired, so no claim comes")
            assertTrue(reopened.stopsOnClaim(conversation, fresh))
            assertTrue(reopened.stopsOnClaim(conversation, claimed), "a journaled claim still needs its mark")
        } finally { reopened.close() }
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

    @Test fun grokPruningKeepsEveryBindingAConversationCanResume() {
        val coordinator = id(); val thread = id(); val run = id()
        val document = brain()
        val running = RunRecord(BrainRun(id(), 1, document.revision, null, BrainRunState.RUNNING, null), document.configuration, null, "Ship it",
            emptyList(), contexts = mapOf("writer:grok-build" to NativeConversation(run, "h")))
        store.write(conversation, ConversationRecord(brain = BrainRecord(run = running,
            coordinatorContexts = mapOf("c:grok-build" to NativeConversation(coordinator, "h"))),
            threadContexts = mapOf(id() to NativeConversation(thread, "h"))))
        store.write(id(), ConversationRecord())
        assertEquals(setOf(coordinator, thread, run), store.bindingHandles(), "a coordinator's, a thread's and a run's")
        assertEquals(emptySet(), ConversationStore(root, id()).bindingHandles(), "another account holds none of them")
        assertNull(store.bindingHandles(limit = 1), "more conversations than the limit prune nothing")
        Files.writeString(root.resolve(account).resolve("conversations").resolve(conversation).resolve("conversation.json"), "{broken")
        assertNull(store.bindingHandles(), "an unreadable record prunes nothing")
    }
}
