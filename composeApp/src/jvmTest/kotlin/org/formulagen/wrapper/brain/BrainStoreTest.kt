package org.formulagen.wrapper.brain

import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

internal fun brainId() = UUID.randomUUID().toString()
internal fun testConfiguration(workflow: Boolean = false): BrainConfiguration {
    val binding = BrainBinding(RuntimeId.CODEX, "test-model", ModelEffort.HIGH)
    val roles = listOf("Writer", "Editor").mapIndexed { index, name -> SpecialistRole(brainId(), name, "Instructions for $name", MemberMode.TALK, binding, index) }
    val steps = roles.map { WorkflowStep(brainId(), it.name, it.id, "Do ${it.name}", "Prior artifact", "Result", false, null, null) }
    return BrainConfiguration(1, CoordinatorRole(brainId(), binding, "Help the user."), roles,
        if (workflow) BrainWorkflow(brainId(), "Writing", true, steps, emptyList()) else null)
}
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
    @Test fun coordinatorSkipReasonsMustBeTextBeforeDispatch() {
        val decision = """{"kind":"enter-workflow","entryStepId":"${brainId()}","inputIds":[],"skipped":[{"stepId":"${brainId()}","reason":false}],"reason":"Begin with the supplied plan"}"""
        assertFails { parseDecision(decision, setOf("enter-workflow")) }
    }
    @Test fun coordinatorEvidenceAcceptsItsLimitAndRejectsLongerExplanations() {
        fun decision(size: Int) = buildJsonObject {
            put("kind", "step-result"); put("attemptId", brainId()); put("outcome", "ready")
            put("explanation", "x".repeat(size))
        }.toString()
        assertEquals(BrainLimits.DESCRIPTION, parseDecision(decision(BrainLimits.DESCRIPTION), setOf("step-result")).getValue("explanation").jsonPrimitive.content.length)
        assertFails { parseDecision(decision(BrainLimits.DESCRIPTION + 1), setOf("step-result")) }
    }
}
