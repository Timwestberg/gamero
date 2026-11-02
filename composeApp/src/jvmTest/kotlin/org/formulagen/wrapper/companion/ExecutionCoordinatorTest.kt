package org.formulagen.wrapper.companion

import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import org.junit.Test

class ExecutionCoordinatorTest {
    private val host = UUID.randomUUID().toString()
    private fun request() = ExecutionRequest(UUID.randomUUID().toString(), host, "Extract memory notes")
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

    @Test fun aRecordWrittenBeforeTasksWereRemovedReadsAsTheSameRequest() {
        val dir = Files.createTempDirectory("journal")
        val prepared = request().copy(choice = ProviderChoice("gpt-5.4-mini", "low"))
        val launched = request()
        val claimIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        // As the journal wrote a memory job's record when it still ran Hovel tasks too.
        fun old(request: ExecutionRequest, claimId: String, phase: String) = buildString {
            append("""{"request":{"id":"${request.id}","hostId":"$host","prompt":"${request.prompt}","context":"",""")
            append(""""rawPrompt":true""")
            request.choice?.let { append(""","choice":{"model":"${it.model}","effort":"${it.effort}"}""") }
            append("""},"claimId":"$claimId","phase":"$phase","result":null}""")
        }
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${prepared.id}.json"), old(prepared, claimIds[0], "PREPARED"))
        Files.writeString(dir.resolve("${launched.id}.json"), old(launched, claimIds[1], "LAUNCHING"))
        ExecutionJournal(dir, host).use { journal ->
            assertEquals(ExecutionRecord(prepared, claimIds[0]), journal.read(prepared.id))
            assertEquals(claimIds[0], journal.prepare(prepared).claimId)
            val remote = Remote()
            val result = ExecutionCoordinator(journal, { error("must not launch") }, remote).accept(launched)
            assertEquals(ExecutionPhase.RECONCILIATION_REQUIRED, result.phase)
            assertEquals(claimIds[1], result.claimId)
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

    @Test fun aWholeJobInputFitsButNotOneCharacterMore() {
        // The server's limit (event-contracts HOVEL_MEMORY_LIMITS.jobInput), pinned so the constant can't drift.
        assertEquals(131_072, ExecutionRequest.MAX_PROMPT_CHARS)
        ExecutionJournal(Files.createTempDirectory("journal"), host).use { journal ->
            journal.prepare(request().copy(prompt = "x".repeat(ExecutionRequest.MAX_PROMPT_CHARS)))
            assertFails { journal.prepare(request().copy(prompt = "x".repeat(ExecutionRequest.MAX_PROMPT_CHARS + 1))) }
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

    @Test fun aRecordWithoutAChoiceLeavesItOut() {
        val dir = Files.createTempDirectory("journal")
        val job = request()
        ExecutionJournal(dir, host).use { it.prepare(job) }
        assertFalse(Files.readString(dir.resolve("${job.id}.json")).contains("choice"))
        ExecutionJournal(dir, host).use { assertEquals(job, it.read(job.id)?.request) }
    }

    @Test fun aChoiceIsOneModelNameAndAKnownEffort() {
        ExecutionJournal(Files.createTempDirectory("journal"), host).use { journal ->
            val job = journal.prepare(request().copy(choice = ProviderChoice("runtime-default", "low")))
            assertEquals(job, journal.read(job.request.id))
            assertFails { ProviderChoice("-c", "low") }
            assertFails { ProviderChoice("runtime-default", "max") }
            assertFails { ProviderChoice("m".repeat(ProviderChoice.MAX_MODEL_CHARS + 1), "low") }
            assertEquals("m".repeat(ProviderChoice.MAX_MODEL_CHARS), ProviderChoice("m".repeat(ProviderChoice.MAX_MODEL_CHARS), "xhigh").model)
        }
    }

    @Test fun aRecordWithAnInvalidChoiceFailsClosed() {
        val dir = Files.createTempDirectory("journal")
        val id = UUID.randomUUID().toString()
        val record = """{"request":{"id":"$id","hostId":"$host","prompt":"Extract","choice":{"model":"--config","effort":"low"}},"claimId":"${UUID.randomUUID()}","phase":"PREPARED","result":null}"""
        Files.writeString(dir.resolve("$id.json"), record)
        ExecutionJournal(dir, host).use { journal -> assertFails { journal.read(id) } }
    }
}
