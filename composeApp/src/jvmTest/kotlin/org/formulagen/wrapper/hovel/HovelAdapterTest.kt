package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import org.formulagen.wrapper.companion.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class HovelAdapterTest {
    private val host = UUID.randomUUID().toString()
    private val owner = UUID.randomUUID().toString()
    private val jobId = UUID.randomUUID().toString()
    private fun job(progress: JsonObject, revision: Int, model: String = "runtime-default", effort: String = "low", runtimeId: String = "codex") =
        HovelTestFixtures.memoryJob(host, owner, jobId, progress, revision, model, effort, runtimeId)
    private val queued = HovelTestFixtures.QUEUED

    @Test fun aLostAcknowledgementIsDeliveredAgainWithoutLaunchingAgain() {
        var current = job(queued, 0)
        var deliveries = 0
        var launches = 0
        val transport = HovelTransport { route, body ->
            when (route) {
                "memory/claim" -> current = job(buildJsonObject { put("state", "claimed"); put("claimId", body.getValue("claimId")) }, 1)
                "memory/progress" -> {
                    assertEquals(current.getValue("revision"), body.getValue("expectedRevision"))
                    current = job(body.getValue("progress").jsonObject, current.getValue("revision").jsonPrimitive.int + 1)
                    if (body.getValue("progress").jsonObject.getValue("state").jsonPrimitive.content == "succeeded" && deliveries++ == 0) error("lost acknowledgement")
                }
            }
            current
        }
        ExecutionJournal(Files.createTempDirectory("adapter-journal"), host).use { journal ->
            val request = hovelMemoryJobRequest(current, host)
            val provider = TaskProvider { launches++; ProviderResult(true, "[]") }
            assertFails { ExecutionCoordinator(journal, provider, HovelMemoryJobRemote(transport, current)).accept(request) }
            ExecutionCoordinator(journal, provider, HovelMemoryJobRemote(transport, current)).accept(request)
            assertEquals(1, launches)
            assertEquals(2, deliveries)
            assertEquals("[]", current.getValue("progress").jsonObject.getValue("result").jsonPrimitive.content)
        }
    }

    @Test fun aMemoryJobRunsItsInputAloneOnItsModelAndEffort() {
        val request = hovelMemoryJobRequest(job(queued, 0, model = "gpt-5.1-codex-mini", effort = "high"), host)
        assertEquals(jobId, request.id)
        assertEquals(HovelTestFixtures.MEMORY_JOB_INPUT, request.prompt)
        assertEquals("", request.context)
        assertNull(request.recalledMemory)
        assertTrue(request.rawPrompt)
        assertEquals(ProviderChoice("gpt-5.1-codex-mini", "high"), request.choice)
        assertEquals(HovelTestFixtures.MEMORY_JOB_INPUT, providerPrompt(request))
    }

    @Test fun aMemoryJobThisCompanionCannotRunIsRefused() {
        assertFails { hovelMemoryJobRequest(job(queued, 0), UUID.randomUUID().toString()) }
        // A model is one name, never a flag Codex would read.
        assertFails { hovelMemoryJobRequest(job(queued, 0, model = "--config"), host) }
        assertFails { hovelMemoryJobRequest(job(queued, 0, effort = "extreme"), host) }
        assertFalse(isRunnableMemoryJob(job(queued, 0, runtimeId = "claude")))
        assertFails { hovelMemoryJobRequest(job(queued, 0, runtimeId = "claude"), host) }
        // A job missing its runtime, such as one from before a rename, is unreadable rather than someone else's.
        val unnamed = buildJsonObject { job(queued, 0).forEach { (key, value) -> if (key != "runtimeId") put(key, value) } }
        assertTrue(isRunnableMemoryJob(unnamed))
        assertFails { hovelMemoryJobRequest(unnamed, host) }
        // Refused here as the journal would refuse it, before the job is offered.
        fun jobWith(key: String, value: String) = buildJsonObject { job(queued, 0).forEach { (k, v) -> put(k, v) }; put(key, value) }
        assertFails { hovelMemoryJobRequest(jobWith("input", "x".repeat(ExecutionRequest.MAX_PROMPT_CHARS + 1)), host) }
        assertFails { hovelMemoryJobRequest(jobWith("input", "  "), host) }
        assertFails { hovelMemoryJobRequest(jobWith("id", jobId.uppercase()), host) }
        assertEquals(ExecutionRequest.MAX_PROMPT_CHARS,
            hovelMemoryJobRequest(jobWith("input", "x".repeat(ExecutionRequest.MAX_PROMPT_CHARS)), host).prompt.length)
        assertTrue(isRunnableMemoryJob(job(queued, 0)))
    }

    /** A fake server for one memory job, checking each report's revision. */
    private inner class JobServer(var reject: Boolean = false) : HovelTransport {
        var current = job(queued, 0)
        val routes = mutableListOf<String>()
        override fun post(route: String, body: JsonObject): JsonObject {
            routes += route
            assertEquals(jobId, body.getValue("jobId").jsonPrimitive.content)
            assertFalse(body.containsKey("taskId"))
            if (reject) throw HovelRejectedException()
            current = when (route) {
                "memory/claim" -> job(buildJsonObject { put("state", "claimed"); put("claimId", body.getValue("claimId")) }, 1)
                "memory/progress" -> {
                    assertEquals(current.getValue("revision"), body.getValue("expectedRevision"))
                    job(body.getValue("progress").jsonObject, current.getValue("revision").jsonPrimitive.int + 1)
                }
                else -> error("unexpected route $route")
            }
            return current
        }
        val progress get() = current.getValue("progress").jsonObject
    }

    private fun runJob(server: JobServer, output: String): ExecutionRecord =
        ExecutionJournal(Files.createTempDirectory("memory-journal"), host).use { journal ->
            val request = hovelMemoryJobRequest(server.current, host)
            ExecutionCoordinator(journal, { ProviderResult(true, output) }, HovelMemoryJobRemote(server, server.current)).accept(request)
        }

    @Test fun aMemoryJobReportsOnTheMemoryRoutes() {
        val server = JobServer()
        runJob(server, "{\"notes\":[]}")
        assertEquals(listOf("memory/claim", "memory/progress", "memory/progress"), server.routes)
        assertEquals("succeeded", server.progress.getValue("state").jsonPrimitive.content)
        assertEquals("{\"notes\":[]}", server.progress.getValue("result").jsonPrimitive.content)
    }

    @Test fun anOverlongExtractionIsReportedFailedNotCutShort() {
        val atLimit = JobServer()
        runJob(atLimit, "x".repeat(HOVEL_MEMORY_JOB_OUTPUT_CHARS))
        assertEquals(HOVEL_MEMORY_JOB_OUTPUT_CHARS, atLimit.progress.getValue("result").jsonPrimitive.content.length)
        val over = JobServer()
        runJob(over, "x".repeat(HOVEL_MEMORY_JOB_OUTPUT_CHARS + 1))
        assertEquals("failed", over.progress.getValue("state").jsonPrimitive.content)
        assertEquals("Extraction output is 65537 characters; the limit is 65536.", over.progress.getValue("error").jsonPrimitive.content)
    }

    @Test fun aReplacedJobIsNeverLaunched() {
        val server = JobServer()
        ExecutionJournal(Files.createTempDirectory("memory-journal"), host).use { journal ->
            val request = hovelMemoryJobRequest(server.current, host)
            server.reject = true
            var launches = 0
            assertFailsWith<HovelRejectedException> {
                ExecutionCoordinator(journal, { launches++; ProviderResult(true, "{}") }, HovelMemoryJobRemote(server, server.current)).accept(request)
            }
            assertEquals(0, launches)
            assertEquals(ExecutionPhase.PREPARED, journal.read(jobId)?.phase)
        }
    }

    @Test fun registrationReusesIdentityAndNeverPersistsBearerInMetadata() {
        val directory = Files.createTempDirectory("registration")
        val secrets = mutableMapOf<String, String>()
        val credentials = object : HostCredentials {
            override fun save(hostId: String, token: String) { check(!secrets.containsKey(hostId)); secrets[hostId] = token }
            override fun read(hostId: String) = secrets.getValue(hostId)
        }
        val companion = HovelCompanion("https://example.test/hovel.html", directory, directory.resolve("fixture"), credentials)
        val first = companion.prepareRegistration(owner)
        assertEquals(first, companion.prepareRegistration(owner))
        assertEquals(1, secrets.size)
        assertFalse(Files.readString(directory.resolve("registration.json")).contains(secrets.getValue(first.id)))
        assertFails { companion.prepareRegistration(UUID.randomUUID().toString()) }
        companion.close()
    }

    @Test fun navigationAndCloseInvalidateBlockedActivation() = runBlocking {
        for (close in listOf(false, true)) {
            val directory = Files.createTempDirectory("cancel-activation")
            val waiting = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            var reads = 0
            var secret = ""
            val credentials = object : HostCredentials {
                override fun save(hostId: String, token: String) { secret = token }
                override fun read(hostId: String): String {
                    if (++reads == 2) { waiting.countDown(); release.await() }
                    return secret
                }
            }
            val companion = HovelCompanion("https://example.test/hovel.html", directory, directory.resolve("fixture"), credentials)
            val prepared = async(Dispatchers.IO) { companion.dispatch("prepareConnection", buildJsonObject { put("ownerId", owner) }) }
            withTimeout(5000) { while (companion.approval == null) delay(5) }
            companion.approval!!.answer.complete(true)
            val registration = prepared.await()
            val activation = async(Dispatchers.IO) {
                runCatching { companion.dispatch("activateConnection", buildJsonObject { put("ownerId", owner); put("hostId", registration.getValue("id")) }) }
            }
            assertTrue(withContext(Dispatchers.IO) { waiting.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            if (close) companion.close() else companion.invalidatePending()
            release.countDown()
            assertTrue(activation.await().isFailure)
            Files.list(directory).use { paths ->
                assertFalse(paths.anyMatch { it.fileName.toString().let { name -> name.startsWith("journal-") || name.startsWith("memory-journal-") } })
            }
            companion.close()
        }
    }

    @Test fun aTimedOutApprovalIsClearedAndTheNextCallIsNotBusy() = runBlocking {
        val directory = Files.createTempDirectory("approval-timeout")
        val credentials = object : HostCredentials {
            override fun save(hostId: String, token: String) = Unit
            override fun read(hostId: String) = ""
        }
        val companion = HovelCompanion("https://example.test/hovel.html", directory, directory.resolve("fixture"), credentials)
        val prepare = buildJsonObject { put("ownerId", owner) }
        val timedOut = runCatching { withTimeout(500) { companion.dispatch("prepareConnection", prepare) } }
        assertIs<TimeoutCancellationException>(timedOut.exceptionOrNull())
        withContext(Dispatchers.Main) {
            assertNull(companion.approval)
            assertEquals("Connection timed out. Start again from Hovel.", companion.status)
        }
        val next = async(Dispatchers.IO) { runCatching { companion.dispatch("prepareConnection", prepare) } }
        // A leaked lock answers busy at once, so stop waiting for an approval when the call ends.
        withTimeout(5000) { while (!next.isCompleted && withContext(Dispatchers.Main) { companion.approval } == null) delay(5) }
        withContext(Dispatchers.Main) { companion.approval?.answer?.complete(false) }
        val declined = next.await().exceptionOrNull()
        assertIs<org.formulagen.wrapper.bridge.BridgeDeclinedException>(declined, "the person's cancel did not answer declined: $declined")
        // Only a call that reached its approval gets this message.
        assertEquals("Connection declined", declined.message)
        companion.close()
    }

    @Test fun stalledResponseBodyHasAnExchangeDeadline() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val release = java.util.concurrent.CountDownLatch(1)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("{\"data\":".toByteArray())
            exchange.responseBody.flush()
            release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val transport = HovelHttpTransport(java.net.URI("http://127.0.0.1:${server.address.port}"), owner, host, "0".repeat(64), 200)
            val started = System.nanoTime()
            assertFails { transport.post("heartbeat", buildJsonObject { put("providerState", "ready") }) }
            assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(2))
        } finally { release.countDown(); server.stop(0) }
    }
}
