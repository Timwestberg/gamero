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
    private val taskId = UUID.randomUUID().toString()
    private fun task(progress: JsonObject, revision: Int) = buildJsonObject {
        put("ownerId", owner); put("characterMemory", "Ada remembers")
        put("revision", revision); put("progress", progress)
        put("request", buildJsonObject { put("id", taskId); put("hostId", host); put("mode", "local"); put("prompt", "Inspect") })
    }
    @Test fun taskPublicationRetriesWithoutLaunchingAgain() {
        var current = task(buildJsonObject { put("state", "queued") }, 0)
        var deliveries = 0
        var launches = 0
        val transport = HovelTransport { route, body ->
            when (route) {
                "claim" -> current = task(buildJsonObject { put("state", "claimed"); put("claimId", body.getValue("claimId")) }, 1)
                "progress" -> {
                    assertEquals(current.getValue("revision"), body.getValue("expectedRevision"))
                    current = task(body.getValue("progress").jsonObject, current.getValue("revision").jsonPrimitive.int + 1)
                    if (body.getValue("progress").jsonObject.getValue("state").jsonPrimitive.content == "succeeded" && deliveries++ == 0) error("lost acknowledgement")
                }
            }
            current
        }
        ExecutionJournal(Files.createTempDirectory("adapter-journal"), host).use { journal ->
            val request = hovelExecutionRequest(current, host)
            val provider = TaskProvider { launches++; ProviderResult(true, "Done") }
            assertFails { ExecutionCoordinator(journal, provider, HovelTaskRemote(transport, current)).accept(request) }
            ExecutionCoordinator(journal, provider, HovelTaskRemote(transport, current)).accept(request)
            assertEquals(1, launches)
            assertEquals(2, deliveries)
            assertEquals("Done", current.getValue("progress").jsonObject.getValue("result").jsonPrimitive.content)
        }
    }

    @Test fun rejectsTasksForAnotherHost() {
        assertFails { hovelExecutionRequest(task(buildJsonObject { put("state", "queued") }, 0), UUID.randomUUID().toString()) }
    }

    @Test fun registrationReusesIdentityAndNeverPersistsBearerInMetadata() {
        val directory = Files.createTempDirectory("registration")
        val secrets = mutableMapOf<String, String>()
        val credentials = object : HostCredentials {
            override fun save(hostId: String, token: String) { check(!secrets.containsKey(hostId)); secrets[hostId] = token }
            override fun read(hostId: String) = secrets.getValue(hostId)
        }
        val companion = HovelCompanion("https://example.test/hovel.html", directory, directory.resolve("fixture"), directory, credentials)
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
            val companion = HovelCompanion("https://example.test/hovel.html", directory, directory.resolve("fixture"), directory, credentials)
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
            Files.list(directory).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith("journal-") }) }
            companion.close()
        }
    }

    @Test fun aTimedOutApprovalIsClearedAndTheNextCallIsNotBusy() = runBlocking {
        val directory = Files.createTempDirectory("approval-timeout")
        val credentials = object : HostCredentials {
            override fun save(hostId: String, token: String) = Unit
            override fun read(hostId: String) = ""
        }
        val companion = HovelCompanion("https://example.test/hovel.html", directory, directory.resolve("fixture"), directory, credentials)
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
        assertTrue(declined is IllegalStateException && declined !is org.formulagen.wrapper.bridge.BridgeBusyException, "the next call was busy: $declined")
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

    @Test fun nativeOriginPolicyRejectsChildOpaqueAndLookalikeFrames() {
        val policy = NativeOriginPolicy("https://example.test/hovel.html")
        assertTrue(policy.permits(true, "https://example.test:443/another-path"))
        for (url in listOf("https://example.test.attacker/", "http://example.test/", "https://example.test:444/", "data:text/html,test", "about:blank", "https://user@example.test/")) {
            assertFalse(policy.permits(true, url), url)
        }
        assertFalse(policy.permits(false, "https://example.test/hovel.html"))
    }
}
