package org.formulagen.wrapper.companion

import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import org.junit.Test

class ExecutionCoordinatorTest {
    private val host = UUID.randomUUID().toString()
    private fun request() = ExecutionRequest(UUID.randomUUID().toString(), host, "Inspect the project", "Ada")
    private class Remote : ExecutionRemote {
        var failClaim = false
        var failPublish = false
        val claims = mutableListOf<String>()
        val receipts = mutableListOf<ExecutionRecord>()
        override fun claim(record: ExecutionRecord) { claims += record.claimId; if (failClaim) error("offline") }
        override fun running(record: ExecutionRecord) = Unit
        override fun publish(record: ExecutionRecord, preview: String) { if (failPublish) error("offline"); receipts += record }
    }

    @Test fun reconnectRepublishesWithoutExecutingAgain() {
        val dir = Files.createTempDirectory("journal")
        val task = request()
        val remote = Remote().apply { failPublish = true }
        var launches = 0
        val provider = TaskProvider { launches++; ProviderResult(true, "done") }
        ExecutionJournal(dir, host).use { journal ->
            assertFails { ExecutionCoordinator(journal, provider, remote).accept(task) }
        }
        remote.failPublish = false
        ExecutionJournal(dir, host).use { journal ->
            assertEquals(ExecutionPhase.SUCCEEDED, ExecutionCoordinator(journal, provider, remote).accept(task).phase)
        }
        assertEquals(1, launches)
        assertEquals(1, remote.claims.size)
    }

    @Test fun ambiguousClaimRetriesSameClaimAndLaunchesOnce() {
        ExecutionJournal(Files.createTempDirectory("journal"), host).use { journal ->
            val remote = Remote().apply { failClaim = true }
            var launches = 0
            val coordinator = ExecutionCoordinator(journal, { launches++; ProviderResult(true, "ok") }, remote)
            val task = request()
            assertFails { coordinator.accept(task) }
            remote.failClaim = false
            coordinator.accept(task)
            assertEquals(1, launches)
            assertEquals(1, remote.claims.distinct().size)
        }
    }

    @Test fun crashedLaunchNeverExecutesAfterRestart() {
        val dir = Files.createTempDirectory("journal")
        val task = request()
        ExecutionJournal(dir, host).use { it.begin(it.prepare(task)) }
        ExecutionJournal(dir, host).use { journal ->
            val result = ExecutionCoordinator(journal, { error("must not launch") }, Remote()).accept(task)
            assertEquals(ExecutionPhase.RECONCILIATION_REQUIRED, result.phase)
        }
    }

    @Test fun providerFailureIsAmbiguousAndNeverRetried() {
        ExecutionJournal(Files.createTempDirectory("journal"), host).use { journal ->
            var launches = 0
            val coordinator = ExecutionCoordinator(journal, { launches++; error("process disappeared") }, Remote())
            val task = request()
            assertEquals(ExecutionPhase.RECONCILIATION_REQUIRED, coordinator.accept(task).phase)
            coordinator.accept(task)
            assertEquals(1, launches)
        }
    }

    @Test fun changedPayloadAndHostAreRejected() {
        ExecutionJournal(Files.createTempDirectory("journal"), host).use { journal ->
            val task = request()
            journal.prepare(task)
            assertFails { journal.prepare(task.copy(prompt = "different")) }
            assertFails { journal.prepare(task.copy(hostId = UUID.randomUUID().toString())) }
            assertFails { journal.prepare(task.copy(id = "../escape")) }
        }
    }

    @Test fun aWholeConversationFitsButNotOneCharacterMore() {
        ExecutionJournal(Files.createTempDirectory("journal"), host).use { journal ->
            journal.prepare(request().copy(prompt = "x".repeat(ExecutionRequest.MAX_PROMPT_CHARS)))
            assertFails { journal.prepare(request().copy(prompt = "x".repeat(ExecutionRequest.MAX_PROMPT_CHARS + 1))) }
            assertFails { journal.prepare(request().copy(context = "x".repeat(ExecutionRequest.MAX_CONTEXT_CHARS + 1))) }
        }
    }

    @Test fun concurrentOwnerAndCorruptionFailClosed() {
        val dir = Files.createTempDirectory("journal")
        ExecutionJournal(dir, host).use { journal ->
            assertFails { ExecutionJournal(dir, host) }
            val task = request()
            Files.writeString(dir.resolve("${task.id}.json"), "broken")
            assertFails { journal.prepare(task) }
            assertEquals("broken", Files.readString(dir.resolve("${task.id}.json")))
        }
    }

    @Test fun previewIsBoundedButFullResultSurvivesReload() {
        val dir = Files.createTempDirectory("journal")
        val task = request()
        val text = "x".repeat(100_000)
        ExecutionJournal(dir, host).use { journal ->
            val record = journal.complete(journal.begin(journal.prepare(task)), true, text)
            assertEquals(65_536, journal.preview(record).length)
            assertTrue(journal.preview(record).contains("Truncated preview"))
        }
        ExecutionJournal(dir, host).use { assertEquals(text, it.read(task.id)?.result) }
    }

    @Test fun closedJournalCannotCompeteWithNewOwner() {
        val dir = Files.createTempDirectory("journal")
        val task = request()
        val old = ExecutionJournal(dir, host)
        val prepared = old.prepare(task)
        old.close()
        old.close()
        ExecutionJournal(dir, host).use { current ->
            assertFails { ExecutionCoordinator(old, { error("stale launch") }, Remote()).accept(task) }
            assertFails { old.read(task.id) }
            assertFails { old.begin(prepared) }
            assertEquals(prepared, current.read(task.id))
            assertEquals(ExecutionPhase.SUCCEEDED,
                ExecutionCoordinator(current, { ProviderResult(true, "once") }, Remote()).accept(task).phase)
        }
    }

    @Test fun oversizedEncodedOutputPreservesLaunchReceipt() {
        ExecutionJournal(Files.createTempDirectory("journal"), host).use { journal ->
            val record = journal.begin(journal.prepare(request()))
            assertFails { journal.complete(record, true, "\u0000".repeat(6 * 1024 * 1024)) }
            assertEquals(record, journal.read(record.request.id))
        }
    }
}
