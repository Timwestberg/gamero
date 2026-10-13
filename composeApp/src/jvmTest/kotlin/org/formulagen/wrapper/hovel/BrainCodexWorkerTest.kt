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
        override fun post(route: String, envelope: JsonObject): JsonObject {
            val body = envelope.getValue("input").jsonObject
            calls += route to body
            val response = when (route) {
                "brain/admit" -> {
                    admission?.let { require(it == body) }; admission = body
                    JsonObject(body + mapOf("configuration" to assignment.configuration, "accountSelections" to assignment.accountSelections,
                        "context" to JsonPrimitive(assignment.request.context), "request" to buildJsonObject {
                            put("id", assignment.request.id); put("hostId", assignment.request.hostId)
                            put("mode", "local"); put("prompt", assignment.request.prompt)
                        }))
                }
                "brain/authorize" -> buildJsonObject {
                    put("authorized", true); put("taskId", body.getValue("taskId"))
                    put("role", body.getValue("role")); put("claimId", body.getValue("claimId"))
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
        for (route in listOf("brain/admit", "brain/authorize", "brain/outcome", "brain/presentation-claim", "brain/presentation")) fixture { a, backend, execution, presentation ->
            var executions = 0; var presentations = 0
            val worker = BrainCodexWorker(execution, presentation,
                TaskProvider { executions++; ProviderResult(true, "PRIVATE EXECUTOR OUTPUT") },
                TaskProvider { presentations++; ProviderResult(true, "Final answer") }, BrainHovelRemote(backend), {}, BrainHovelRemote(backend)::authorizeLaunch)
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
            TaskProvider { presentations++; ProviderResult(presentations > 1, "Explanation") }, BrainHovelRemote(backend), {}, BrainHovelRemote(backend)::authorizeLaunch)
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
            BrainHovelRemote(backend), {}, BrainHovelRemote(backend)::authorizeLaunch)
        assertEquals(ExecutionPhase.RECONCILIATION_REQUIRED, worker.execute(a).execution.phase)
        worker.execute(a)
        assertEquals(1, executions)
    }

    @Test fun localAccountVerificationPreventsProviderInvocation() = fixture { a, backend, execution, presentation ->
        var calls = 0
        val provider = TaskProvider { calls++; ProviderResult(true, "must not execute") }
        val worker = BrainCodexWorker(execution, presentation, provider, provider, BrainHovelRemote(backend), { error("Account generation changed") }, { _, _, _ -> })
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
                { _, _, _ -> check(!revoked) { "Host revoked" } })
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
            TaskProvider { presentations++; error("Presentation process lost") }, BrainHovelRemote(backend), {}, BrainHovelRemote(backend)::authorizeLaunch)
        assertFails { worker.execute(a) }
        assertFails { worker.execute(a) }
        assertFails { backend.retry() }
        assertEquals(1, executions); assertEquals(1, presentations)
        assertNotNull(backend.claim)
        assertFalse(backend.calls.any { it.first == "brain/presentation" })
    }

    @Test fun transportDefaultsToDisabledAndOptInUsesExistingHostHeader() {
        val a = assignment()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var calls = 0
        val token = "a".repeat(64)
        server.createContext("/") { exchange ->
            calls++
            assertEquals(token, exchange.requestHeaders.getFirst("X-Hovel-Host-Token"))
            assertEquals("/native/${a.characterId}/${a.request.hostId}/brain/authorize", exchange.requestURI.path)
            val input = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject.getValue("input").jsonObject
            val response = buildJsonObject { put("data", buildJsonObject {
                put("authorized", true); put("taskId", input.getValue("taskId")); put("role", input.getValue("role")); put("claimId", input.getValue("claimId"))
            }) }.toString().toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val base = java.net.URI("http://127.0.0.1:${server.address.port}")
            val record = ExecutionRecord(a.request, id())
            assertFails { BrainHovelRemote(HovelHttpTransport(base, a.characterId, a.request.hostId, token)).authorizeLaunch(a, "execution", record) }
            assertEquals(0, calls)
            BrainHovelRemote(HovelHttpTransport(base, a.characterId, a.request.hostId, token, brainEnabled = true)).authorizeLaunch(a, "execution", record)
            assertEquals(1, calls)
        } finally { server.stop(0) }
    }

    @Test fun unsupportedBindingFailsBeforeAdmission() = fixture { a, backend, execution, presentation ->
        val binding = JsonObject(a.configuration.getValue("execution").jsonObject + ("modelId" to JsonPrimitive("unsupported")))
        val changed = a.copy(configuration = JsonObject(a.configuration + ("execution" to binding)))
        val provider = TaskProvider { error("must not execute") }
        val worker = BrainCodexWorker(execution, presentation, provider, provider, BrainHovelRemote(backend), {}, BrainHovelRemote(backend)::authorizeLaunch)
        assertFails { worker.execute(changed) }
        assertTrue(backend.calls.isEmpty())
    }
    @Test fun staleGenerationDoesNotStarveLaterWork() = rejectedWorkDoesNotStarve(false)

    @Test fun unsupportedAssignmentDoesNotStarveLaterWork() = rejectedWorkDoesNotStarve(true)

    private fun rejectedWorkDoesNotStarve(unsupported: Boolean) = fixture { a, backend, execution, presentation ->
        val rejected = a.copy(request = a.request.copy(id = id(), prompt = "Rejected task"),
            accountSelections = JsonObject(a.accountSelections.mapValues { (_, value) ->
                JsonObject(value.jsonObject + ("accountGeneration" to JsonPrimitive(2)))
            }), configuration = if (!unsupported) a.configuration else JsonObject(a.configuration +
                ("execution" to JsonObject(a.configuration.getValue("execution").jsonObject + ("modelId" to JsonPrimitive("unsupported"))))))
        val rejectedBackend = Backend(rejected)
        val remote = BrainHovelRemote(HovelTransport { route, body ->
            if (route == "brain/work") buildJsonObject { put("assignments", JsonArray(listOf(wireAssignment(rejected), wireAssignment(a)))) }
            else if (body.getValue("input").jsonObject.getValue("taskId").jsonPrimitive.content == rejected.request.id)
                rejectedBackend.post(route, body)
            else backend.post(route, body)
        })
        var launches = 0
        val host = BrainHostWork(a.request.hostId, execution, presentation,
            TaskProvider { launches++; assertNotEquals("Rejected task", it.prompt); ProviderResult(true, "Fixture reply") }, remote) {
            check(it.getValue("accountGeneration").jsonPrimitive.int == 1) { "Local generation differs" }
        }
        val failed = mutableListOf<String?>()
        host.pollOnce { task, _ -> failed += task }
        host.pollOnce { task, _ -> failed += task }
        assertEquals(listOf<String?>(rejected.request.id, rejected.request.id), failed)
        assertEquals(2, launches)
        assertEquals(ExecutionPhase.SUCCEEDED, execution.read(a.request.id)!!.phase)
        assertTrue(execution.read(rejected.request.id)?.phase in setOf(null, ExecutionPhase.PREPARED))
    }

    private fun wireAssignment(value: BrainCodexAssignment) = buildJsonObject {
        put("characterId", value.characterId); put("configurationRevision", value.configurationRevision)
        put("configuration", value.configuration); put("accountSelections", value.accountSelections)
        put("context", value.request.context)
        put("request", buildJsonObject {
            put("id", value.request.id); put("hostId", value.request.hostId); put("characterId", value.characterId)
            put("mode", "local"); put("prompt", value.request.prompt)
        })
    }

    @Test fun composedWorkerRejectsMissingStaleAndChangedIdentityAfterServerAuthorization() = fixture { a, backend, execution, _ ->
        val account = a.accountSelections.getValue("execution").jsonObject.getValue("localAccountRef").jsonPrimitive.content
        val now = System.currentTimeMillis()
        val current = BrainLocalObservation(account, 1, 1, true, now)
        val cases = listOf(null, current.copy(observedAtMillis = now - 60_000),
            current.copy(accountGeneration = 2), current.copy(available = false))
        val directory = Files.createTempDirectory("brain-source-composition-")
        try {
            var launches = 0
            val transport = HovelTransport { route, body ->
                if (route == "brain/work") buildJsonObject { put("assignments", JsonArray(listOf(wireAssignment(a)))) }
                else backend.post(route, body)
            }
            for (observation in cases) {
                val rejected = mutableListOf<String?>()
                BrainSessionOptions(enabled = true, accountSource = BrainLocalAccountSource { observation })
                    .attach(a.request.hostId, directory, execution,
                        TaskProvider { launches++; ProviderResult(true, "Must not run") }, transport)!!.use { brain ->
                        brain.pollOnce { task, _ -> rejected += task }
                    }
                assertEquals(listOf<String?>(a.request.id), rejected)
                assertEquals(0, launches)
                assertEquals(ExecutionPhase.PREPARED, execution.read(a.request.id)!!.phase)
            }
            assertEquals(cases.size, backend.calls.count { it.first == "brain/authorize" })
        } finally { directory.toFile().deleteRecursively() }
    }

}
