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
            val futures = (1..2).map { pool.submit<JsonElement> { BrainStore(root).save("local", companion, 1, brainId(), configuration) } }
            assertEquals(setOf("saved", "conflict"), futures.map { it.get().jsonObject.getValue("status").jsonPrimitive.content }.toSet())
            assertEquals(2, store.read("local", companion)?.revision)
        } finally { pool.shutdownNow() }
    }
    @Test fun corruptConfigurationIsPreservedAndArtifactsAreScoped() {
        val root = Files.createTempDirectory("brain-corrupt"); val store = BrainStore(root); val companion = brainId()
        store.save("local", companion, 0, brainId(), testConfiguration())
        val file = root.resolve("local/companion-brains/$companion/configuration.json")
        Files.writeString(file, "broken")
        assertFails { store.save("local", companion, 0, brainId(), testConfiguration()) }; assertEquals("broken", Files.readString(file))
        val artifact = store.artifact("local", companion, "Input", BrainSource("user", messageId = brainId()), "original".toByteArray(), "text/plain")
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
            val artifact = store.artifact("local", companion, title, BrainSource("user", messageId = brainId()), byteArrayOf(1), "application/octet-stream")
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
}
