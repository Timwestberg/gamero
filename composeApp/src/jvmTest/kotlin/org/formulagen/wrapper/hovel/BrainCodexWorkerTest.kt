package org.formulagen.wrapper.hovel

import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test
import org.formulagen.wrapper.companion.*

/** No CLI, credentials, provider process or network is used by these fixtures. */
class BrainCodexWorkerTest {
    private fun id() = UUID.randomUUID().toString()
    private fun assignment(): BrainCodexAssignment {
        val host = id()
        val account = id()
        val binding = buildJsonObject {
            put("hostId", host); put("runtimeId", "codex"); put("providerId", "openai")
            put("modelId", "runtime-default"); put("accountRef", account); put("entitlement", "subscription")
        }
        val selection = buildJsonObject {
            put("hostId", host); put("runtimeId", "codex"); put("providerId", "openai")
            put("localAccountRef", account); put("accountGeneration", 1)
        }
        return BrainCodexAssignment(id(), 1, ExecutionRequest(id(), host, "Inspect", "User-authored context"),
            buildJsonObject { put("version", 1); put("choreographer", binding); put("execution", binding) },
            buildJsonObject { put("choreographer", selection); put("execution", selection) })
    }

    private class Backend(val assignment: BrainCodexAssignment) : HovelTransport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var admission: JsonObject? = null
        var outcome: JsonObject? = null
        var record: JsonObject? = null
        var claim: String? = null
        var loseOnce: String? = null
        fun retry() {
            val old = record!!
            val result = old.getValue("result").jsonObject
            require(result.getValue("presentation").jsonObject.getValue("state").jsonPrimitive.content in setOf("failed", "cancelled"))
            record = JsonObject(old + ("revision" to JsonPrimitive(old.getValue("revision").jsonPrimitive.long + 1)) +
                ("result" to JsonObject(result + ("presentation" to buildJsonObject {
                    put("attemptId", UUID.randomUUID().toString()); put("state", "pending")
                }))))
            claim = null
        }
        override fun post(route: String, body: JsonObject): JsonObject {
            calls += route to body
            val response = when (route) {
                "brain/admit" -> {
                    admission?.let { require(it == body) }; admission = body
                    JsonObject(body + mapOf("configuration" to assignment.configuration, "accountSelections" to assignment.accountSelections))
                }
                "brain/outcome" -> {
                    outcome?.let { require(it == body) }; outcome = body
                    if (record == null) record = buildJsonObject {
                        put("revision", 1)
                        put("result", buildJsonObject {
                            put("taskId", body.getValue("taskId")); put("execution", body.getValue("execution"))
                            put("presentation", buildJsonObject { put("attemptId", UUID.randomUUID().toString()); put("state", "pending") })
                        })
                    }
                    record!!
                }
                "brain/presentation-claim" -> {
                    require(record!!.getValue("result").jsonObject.getValue("presentation").jsonObject.getValue("state").jsonPrimitive.content == "pending")
                    val proposed = body.getValue("claimId").jsonPrimitive.content
                    require(claim == null || claim == proposed); claim = proposed; record!!
                }
                "brain/presentation" -> {
                    require(claim == body.getValue("claimId").jsonPrimitive.content)
                    val old = record!!
                    val result = old.getValue("result").jsonObject
                    record = JsonObject(old + ("result" to JsonObject(result + ("presentation" to
                        JsonObject(body.getValue("result").jsonObject + ("attemptId" to body.getValue("attemptId")))))))
                    record!!
                }
                else -> error("Unexpected route")
            }
            if (loseOnce == route) { loseOnce = null; error("Committed, acknowledgement lost") }
            return response
        }
    }

    private fun fixture(test: (BrainCodexAssignment, Backend, ExecutionJournal, ExecutionJournal) -> Unit) {
        val assignment = assignment()
        val directory = Files.createTempDirectory("brain-worker")
        try {
            ExecutionJournal(directory.resolve("execution"), assignment.request.hostId).use { execution ->
                ExecutionJournal(directory.resolve("presentation"), assignment.request.hostId).use { presentation ->
                    test(assignment, Backend(assignment), execution, presentation)
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun lostAcknowledgementsNeverDuplicateEitherLaunch() {
        for (route in listOf("brain/admit", "brain/outcome", "brain/presentation-claim", "brain/presentation")) fixture { a, backend, execution, presentation ->
            var executions = 0; var presentations = 0
            val worker = BrainCodexWorker(execution, presentation,
                TaskProvider { executions++; ProviderResult(true, "PRIVATE EXECUTOR OUTPUT") },
                TaskProvider { presentations++; ProviderResult(true, "Final answer") }, BrainHovelRemote(backend), {}, { _, _ -> })
            backend.loseOnce = route
            assertFails { worker.execute(a) }
            worker.execute(a)
            worker.execute(a)
            assertEquals(1, executions, route)
            assertEquals(1, presentations, route)
            assertEquals(1, backend.calls.filter { it.first == "brain/admit" }.map { it.second }.distinct().size)
            assertFalse(backend.calls.any { "PRIVATE EXECUTOR OUTPUT" in it.second.toString() })
        }
    }

    @Test fun explicitPresentationRetryPreservesFailedExecutionAndNeverRerunsIt() = fixture { a, backend, execution, presentation ->
        var executions = 0; var presentations = 0
        val worker = BrainCodexWorker(execution, presentation,
            TaskProvider { executions++; ProviderResult(false, "Execution failed") },
            TaskProvider { presentations++; ProviderResult(presentations > 1, "Explanation") }, BrainHovelRemote(backend), {}, { _, _ -> })
        assertEquals(ExecutionPhase.FAILED, worker.execute(a).execution.phase)
        backend.retry()
        val ticket = BrainHovelRemote(backend).outcome(a, execution.read(a.request.id)!!)
        worker.present(a, ticket)
        assertEquals(1, executions); assertEquals(2, presentations)
        assertEquals(ExecutionPhase.FAILED, execution.read(a.request.id)!!.phase)
    }

    @Test fun uncertainLaunchAndAccountChangeFailClosed() = fixture { a, backend, execution, presentation ->
        var executions = 0
        val worker = BrainCodexWorker(execution, presentation,
            TaskProvider { executions++; error("Process lost") }, TaskProvider { ProviderResult(true, "Uncertain") },
            BrainHovelRemote(backend), {}, { _, _ -> })
        assertEquals(ExecutionPhase.RECONCILIATION_REQUIRED, worker.execute(a).execution.phase)
        worker.execute(a)
        assertEquals(1, executions)
    }

    @Test fun localAccountVerificationPreventsProviderInvocation() = fixture { a, backend, execution, presentation ->
        var calls = 0
        val provider = TaskProvider { calls++; ProviderResult(true, "must not execute") }
        val worker = BrainCodexWorker(execution, presentation, provider, provider, BrainHovelRemote(backend), { error("Account generation changed") }, { _, _ -> })
        assertFails { worker.execute(a) }
        assertEquals(0, calls)
        assertEquals(ExecutionPhase.PREPARED, execution.read(a.request.id)!!.phase)
    }

    @Test fun lostAcknowledgementThenRevocationPreventsNewLaunch() {
        for (route in listOf("brain/admit", "brain/presentation-claim")) fixture { a, backend, execution, presentation ->
            var revoked = false; var executions = 0; var presentations = 0
            val worker = BrainCodexWorker(execution, presentation,
                TaskProvider { executions++; ProviderResult(true, "Done") },
                TaskProvider { presentations++; ProviderResult(true, "Reply") }, BrainHovelRemote(backend), {},
                { _, _ -> check(!revoked) { "Host revoked" } })
            backend.loseOnce = route
            assertFails { worker.execute(a) }
            val before = executions
            revoked = true
            assertFails { worker.execute(a) }
            assertEquals(before, executions); assertEquals(0, presentations)
        }
    }

    @Test fun ambiguousPresentationRetainsClaimAndForbidsRetry() = fixture { a, backend, execution, presentation ->
        var executions = 0; var presentations = 0
        val worker = BrainCodexWorker(execution, presentation,
            TaskProvider { executions++; ProviderResult(true, "Done") },
            TaskProvider { presentations++; error("Presentation process lost") }, BrainHovelRemote(backend), {}, { _, _ -> })
        assertFails { worker.execute(a) }
        assertFails { worker.execute(a) }
        assertFails { backend.retry() }
        assertEquals(1, executions); assertEquals(1, presentations)
        assertNotNull(backend.claim)
        assertFalse(backend.calls.any { it.first == "brain/presentation" })
    }

    @Test fun unsupportedBindingFailsBeforeAdmission() = fixture { a, backend, execution, presentation ->
        val binding = JsonObject(a.configuration.getValue("execution").jsonObject + ("modelId" to JsonPrimitive("unsupported")))
        val changed = a.copy(configuration = JsonObject(a.configuration + ("execution" to binding)))
        val provider = TaskProvider { error("must not execute") }
        val worker = BrainCodexWorker(execution, presentation, provider, provider, BrainHovelRemote(backend), {}, { _, _ -> })
        assertFails { worker.execute(changed) }
        assertTrue(backend.calls.isEmpty())
    }
}
