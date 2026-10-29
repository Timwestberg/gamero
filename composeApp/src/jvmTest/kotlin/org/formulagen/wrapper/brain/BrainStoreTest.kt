package org.formulagen.wrapper.brain

import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

class BrainStoreTest {
    @Test fun unicodeCapacityConfigurationsStayReadableWithFullReceiptHistory() {
        val root = Files.createTempDirectory("brain-unicode")
        val store = BrainStore(root); val companion = brainId(); val base = testConfiguration()
        val configuration = base.copy(specialists = (0 until 10).map { index ->
            base.specialists.first().copy(id = brainId(), slot = index, instructions = "界".repeat(4096))
        })
        assertTrue(BrainRules.configuration(configuration))
        repeat(34) { revision ->
            store.save("local", companion, revision.toLong(), brainId(), configuration)
            assertEquals(revision + 1L, store.read("local", companion)?.revision)
        }
        assertEquals(configuration, BrainStore(root).read("local", companion)?.configuration)
    }
    @Test fun saveIsIdempotentAndConcurrentSavesConflict() {
        val root = Files.createTempDirectory("brain-store")
        val store = BrainStore(root); val companion = brainId(); val configuration = testConfiguration()
        val mutation = brainId(); val first = store.save("local", companion, 0, mutation, configuration)
        assertEquals(first, store.save("local", companion, 0, mutation, configuration))
        assertFails { store.save("local", companion, 0, mutation, configuration.copy(coordinator = configuration.coordinator.copy(instructions = "different"))) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit<BrainSaveResult> { BrainStore(root).save("local", companion, 1, brainId(), configuration) } }
            assertEquals(setOf(BrainSaveResult.Saved::class, BrainSaveResult.Conflict::class), futures.map { it.get()::class }.toSet())
            assertEquals(2, store.read("local", companion)?.revision)
        } finally { pool.shutdownNow() }
    }
    @Test fun corruptConfigurationIsPreservedAndArtifactsAreScoped() {
        val root = Files.createTempDirectory("brain-corrupt"); val store = BrainStore(root); val companion = brainId()
        store.save("local", companion, 0, brainId(), testConfiguration())
        val file = root.resolve("local/companion-brains/$companion/configuration.json")
        Files.writeString(file, "broken")
        assertFails { store.save("local", companion, 0, brainId(), testConfiguration()) }; assertEquals("broken", Files.readString(file))
        val artifact = store.artifact("local", companion, "Input", BrainSource(BrainSourceKind.USER, messageId = brainId()), "original".toByteArray(), "text/plain")
        assertEquals("original", store.bytes("local", companion, artifact).toString(Charsets.UTF_8))
        assertFails { store.bytes("local", brainId(), artifact) }
        Files.writeString(store.artifactPath("local", companion, artifact), "modified")
        assertFails { store.bytes("local", companion, artifact) }
    }
    @Test fun settingsCanBeAdoptedButStructureCannot() {
        val before = testConfiguration(true)
        assertTrue(adoptable(before, before.copy(specialists = before.specialists.map { it.copy(instructions = "new", binding = it.binding?.copy(modelId = "other")) })))
        assertFalse(adoptable(before, before.copy(specialists = before.specialists.reversed())))
        assertFalse(adoptable(before, before.copy(workflow = before.workflow?.copy(steps = before.workflow.steps.reversed()))))
    }
    @Test fun attachmentTitlesRemainValidForWhitespaceAndLongFilenames() {
        val store = BrainStore(Files.createTempDirectory("brain-titles")); val companion = brainId()
        for (title in listOf("  plan.md  ", " ", "x".repeat(79) + "  next.md")) {
            val artifact = store.artifact("local", companion, title, BrainSource(BrainSourceKind.USER, messageId = brainId()), byteArrayOf(1), "application/octet-stream")
            assertTrue(BrainRules.label(artifact.title))
        }
    }
    /** Receipts written before they were slimmed held whole documents; those records still load and replay. */
    @Test fun receiptsWithWholeDocumentsStillLoadAndReplay() {
        val root = Files.createTempDirectory("brain-receipts")
        val store = BrainStore(root); val companion = brainId(); val configuration = testConfiguration()
        val mutation = brainId(); val saved = store.save("local", companion, 0, mutation, configuration)
        val file = root.resolve("local/companion-brains/$companion/configuration.json")
        val record = brainJson.parseToJsonElement(Files.readString(file)).jsonObject
        val receipts = record.getValue("receipts").jsonArray.map { receipt ->
            JsonObject(receipt.jsonObject - "revision" + ("document" to record.getValue("document")))
        }
        Files.writeString(file, JsonObject(record + ("receipts" to JsonArray(receipts))).toString())
        assertEquals(configuration, store.read("local", companion)?.configuration)
        assertEquals(saved, store.save("local", companion, 0, mutation, configuration))
        assertTrue(store.converted("local", companion)); assertFalse(store.converted("local", brainId()))
        store.save("local", companion, 1, brainId(), configuration)
        val rewritten = brainJson.parseToJsonElement(Files.readString(file)).jsonObject.getValue("receipts").jsonArray.map { it.jsonObject }
        assertEquals(listOf(1L, 2L), rewritten.map { assertFalse("document" in it); it.getValue("revision").jsonPrimitive.long })
    }

    /** Records written while states, phases, kinds and authors were plain strings load as the enums and write back unchanged. */
    @Test fun recordsWrittenWithStringStatesStillLoadAndRewriteIdentically() {
        val root = Files.createTempDirectory("brain-record-names"); val store = BrainStore(root); val companion = brainId()
        val configuration = brainWire(testConfiguration(workflow = true))
        val binding = """{"runtimeId":"codex","modelId":"test-model","effort":"high"}"""
        val role = """{"roleId":"${brainId()}","name":"Writer","binding":$binding,"instructions":"Write.","mode":"talk"}"""
        val (run, attempt, done, artifact) = List(4) { brainId() }
        val source = """{"kind":"attempt","messageId":null,"attemptId":"$done"}"""
        val written = """{"version":1,"messages":[
            {"id":"${brainId()}","author":"user","text":"Write it","role":null,"activity":null},
            {"id":"${brainId()}","author":"specialist","text":"Draft","role":$role,"activity":null},
            {"id":"${brainId()}","author":"coordinator","text":"Ready","role":$role,"activity":null},
            {"id":"${brainId()}","author":"system","text":"Skipped","role":null,"activity":null}],
            "run":{"summary":{"id":"$run","revision":7,"configurationRevision":1,"workflowId":null,"state":"paused","currentAttemptId":"$attempt",
              "skipped":[],"returnsUsed":0,"dispatchesUsed":2},
            "configuration":$configuration,"workspace":null,"request":"Write it","inputs":[],
            "attempts":[{"id":"$done","runId":"$run","stepId":null,"role":$role,"configurationRevision":1,"inputIds":[],"outputIds":["$artifact"],"state":"completed","outcome":"ready"},
              {"id":"$attempt","runId":"$run","stepId":null,"role":$role,"configurationRevision":1,"inputIds":[],"outputIds":[],"state":"interrupted","outcome":null}],
            "artifacts":[{"id":"$artifact","title":"Writer","digest":"${"a".repeat(64)}","source":$source,"mediaType":"text/markdown","preview":"Draft"}],
            "nextStep":0,"phase":"evaluate","task":"Write it","returnsAllowance":3,"dispatchesAllowance":60,"contexts":{},"invalidated":[]},
            "waiting":{"kind":"recovery","summary":"Work is paused.","approvalId":null,"attemptId":null},"receipts":{},"staged":[],"coordinatorContexts":{},"notice":null}"""
        val file = root.resolve("local/companion-brains/$companion/conversation.json")
        Files.createDirectories(file.parent); Files.writeString(file, written)
        val record = store.readRecord("local", companion)
        assertEquals(BrainRunState.PAUSED, record.run?.summary?.state)
        assertEquals(BrainPhase.EVALUATE, record.run?.phase)
        assertEquals(listOf(BrainAttemptState.COMPLETED, BrainAttemptState.INTERRUPTED), record.run?.attempts?.map { it.state })
        assertEquals(BrainAttemptOutcome.READY, record.run?.attempts?.first()?.outcome)
        assertEquals(listOf(BrainAuthor.USER, BrainAuthor.SPECIALIST, BrainAuthor.COORDINATOR, BrainAuthor.SYSTEM), record.messages.map { it.author })
        assertEquals(BrainWaitingKind.RECOVERY, record.waiting?.kind)
        assertEquals(BrainSourceKind.ATTEMPT, record.run?.artifacts?.single()?.source?.kind)
        store.writeRecord("local", companion, record)
        assertEquals(brainJson.parseToJsonElement(written), brainJson.parseToJsonElement(Files.readString(file)))
    }
}
