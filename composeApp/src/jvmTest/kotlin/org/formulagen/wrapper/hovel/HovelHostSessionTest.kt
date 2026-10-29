package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The memory lane, fed by the heartbeat the conversation runner sends. */
class HovelHostSessionTest {
    private val host = UUID.randomUUID().toString()
    private val owner = UUID.randomUUID().toString()
    private val jobId = UUID.randomUUID().toString()
    private fun job(progress: JsonObject, revision: Int, runtimeId: String = "codex") =
        HovelTestFixtures.memoryJob(host, owner, jobId, progress, revision, runtimeId = runtimeId)
    private val queued = HovelTestFixtures.QUEUED

    /** A fake server with one memory job. The worker and the test both call it, so every call is synchronized. */
    private inner class Server(private var jobItem: JsonObject? = job(queued, 0)) : HovelTransport {
        private val routes = mutableListOf<String>()
        @Volatile var failConsolidate = false

        @Synchronized override fun post(route: String, body: JsonObject): JsonObject {
            routes += route
            return when (route) {
                "heartbeat" -> buildJsonObject { putJsonArray("memoryJobs") { jobItem?.takeIf(::isOpen)?.let(::add) } }
                "memory/claim" -> job(claimed(body), 1).also { jobItem = it }
                "memory/progress" -> advance(jobItem!!, body) { progress, revision -> job(progress, revision) }.also { jobItem = it }
                "memory/consolidate" -> { check(!failConsolidate) { "offline" }; JsonObject(emptyMap()) }
                else -> error("unexpected route $route")
            }
        }

        private fun claimed(body: JsonObject) = buildJsonObject { put("state", "claimed"); put("claimId", body.getValue("claimId")) }
        private fun advance(item: JsonObject, body: JsonObject, next: (JsonObject, Int) -> JsonObject): JsonObject {
            // Like the real server, an identical report replays.
            if (body.getValue("progress") == item.getValue("progress")) return item
            assertEquals(item.getValue("revision"), body.getValue("expectedRevision"))
            return next(body.getValue("progress").jsonObject, item.getValue("revision").jsonPrimitive.int + 1)
        }

        @Synchronized fun jobState() = jobItem?.let(::stateOf)
        @Synchronized fun count(route: String) = routes.count { it == route }
        @Synchronized fun routes() = routes.toList()
    }

    private fun stateOf(item: JsonObject) = item.getValue("progress").jsonObject.getValue("state").jsonPrimitive.content

    private fun isOpen(item: JsonObject) = stateOf(item) in setOf("queued", "claimed", "running")

    private val statuses = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun session(server: Server, memoryProvider: TaskProvider) = HovelHostSession(
        hostId = host,
        memoryJournal = ExecutionJournal(Files.createTempDirectory("session-memory-journal"), host), memoryProvider = memoryProvider,
        checkReadiness = { CodexCliProvider.Readiness.READY }, transport = server,
    ) { statuses += it }

    /** One heartbeat as the runner sends it, its answer handed to the memory lane. */
    private fun HovelHostSession.beat(server: Server) = heartbeat(server.post("heartbeat", JsonObject(emptyMap())))

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(10)
        }
    }

    @Test fun aMemoryJobRunsItsInputAloneAndConsolidatesAfterItsResult() {
        val server = Server()
        val ran = mutableListOf<ExecutionRequest>()
        val memoryProvider = TaskProvider { synchronized(ran) { ran += it }; ProviderResult(true, "{\"notes\":[]}") }
        session(server, memoryProvider).use { session ->
            session.beat(server)
            eventually { server.count("memory/consolidate") == 1 }
            assertEquals("succeeded", server.jobState())
            val ranJob = synchronized(ran) { ran.single() }
            assertEquals(HovelTestFixtures.MEMORY_JOB_INPUT, ranJob.prompt)
            assertTrue(ranJob.rawPrompt)
            assertEquals(ProviderChoice("runtime-default", "low"), ranJob.choice)
        }
    }

    @Test fun aBusyLaneTakesNoSecondItem() {
        val server = Server()
        val release = CountDownLatch(1)
        var memoryLaunches = 0
        val memoryProvider = TaskProvider { memoryLaunches++; release.await(5, TimeUnit.SECONDS); ProviderResult(true, "{}") }
        session(server, memoryProvider).use { session ->
            session.beat(server)
            eventually { server.jobState() == "running" }
            session.beat(server)
            assertEquals(1, server.count("memory/claim"))
            release.countDown()
            eventually { server.count("memory/consolidate") == 1 }
            assertEquals(1, memoryLaunches)
            // running, then succeeded: a second offer would have queued a republish behind the first.
            assertEquals(2, server.count("memory/progress"))
        }
    }

    @Test fun aMemoryJobThisVersionCannotReadAsksForAnUpdate() {
        // A missing field, a renamed one, and an effort a newer server added.
        val unreadable = listOf(
            buildJsonObject { job(queued, 0).forEach { (key, value) -> if (key != "input") put(key, value) } },
            buildJsonObject {
                job(queued, 0).forEach { (key, value) -> if (key != "runtimeId") put(key, value) }
                put("providerId", "codex")
            },
            HovelTestFixtures.memoryJob(host, owner, jobId, effort = "maximal"),
            // An input longer than this version's provider limit.
            buildJsonObject {
                HovelTestFixtures.memoryJob(host, owner, jobId).forEach { (key, value) -> put(key, value) }
                put("input", "x".repeat(ExecutionRequest.MAX_PROMPT_CHARS + 1))
            },
        )
        for (item in unreadable) {
            statuses.clear()
            val server = Server(jobItem = item)
            session(server) { error("must not launch") }.use { session ->
                session.beat(server)
                assertEquals(HovelHostSession.MEMORY_JOB_UNREADABLE, statuses.last())
                assertEquals(0, server.count("memory/claim"))
            }
        }
    }

    @Test fun aJobForAnotherProviderIsLeftForAnotherCompanion() {
        val server = Server(jobItem = job(queued, 0, runtimeId = "claude"))
        session(server) { error("must not launch") }.use { session ->
            session.beat(server)
            assertEquals(listOf("heartbeat"), server.routes())
        }
    }

    @Test fun aClosedLaneTakesNoJob() {
        val server = Server()
        val session = session(server) { error("must not launch") }
        session.close()
        session.beat(server)
        assertEquals(0, server.count("memory/claim"))
    }

    @Test fun aFailedConsolidationWaitsForTheNextCall() {
        val server = Server(jobItem = null).apply { failConsolidate = true }
        session(server) { error("no job") }.use { session ->
            session.consolidate()
            assertEquals(1, server.count("memory/consolidate"))
        }
    }
}
