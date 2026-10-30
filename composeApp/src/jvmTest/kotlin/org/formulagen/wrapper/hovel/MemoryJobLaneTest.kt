package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The memory job lane, fed by the heartbeat the conversation runner sends. */
class MemoryJobLaneTest {
    private val host = UUID.randomUUID().toString()
    private val owner = UUID.randomUUID().toString()
    private val jobId = UUID.randomUUID().toString()
    private fun job(progress: JsonObject, revision: Int, runtimeId: String = "codex") =
        HovelTestFixtures.memoryJob(host, owner, jobId, progress, revision, runtimeId = runtimeId)
    private val queued = HovelTestFixtures.QUEUED

    /** A fake server with one memory job. The worker calls it, so every call is synchronized. */
    private inner class Server(private var jobItem: JsonObject? = job(queued, 0)) : HovelTransport {
        private val routes = mutableListOf<String>()
        @Volatile var failConsolidate = false

        @Synchronized override fun post(route: String, body: JsonObject): JsonObject {
            routes += route
            return when (route) {
                "heartbeat" -> buildJsonObject {
                    putJsonObject("host") { put("id", host) }
                    putJsonArray("memoryJobs") { jobItem?.takeIf(::isOpen)?.let(::add) }
                }
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

    private fun lane(server: Server, provider: TaskProvider, readiness: CodexCliProvider.Readiness = CodexCliProvider.Readiness.READY) =
        MemoryJobLane(host, ExecutionJournal(Files.createTempDirectory("memory-journal"), host), provider, { readiness }, server) { statuses += it }

    /** One heartbeat as the conversation runner sends it, its answer handed to the lane. */
    private fun MemoryJobLane.beat(server: Server) = heartbeat(server.post("heartbeat", JsonObject(emptyMap())))

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(10)
        }
    }

    @Test fun aMemoryJobRunsItsInputAndConsolidatesAfterItsResult() {
        val server = Server()
        val ran = mutableListOf<ExecutionRequest>()
        lane(server, { synchronized(ran) { ran += it }; ProviderResult(true, "{\"notes\":[]}") }).use { lane ->
            lane.beat(server)
            eventually { server.count("memory/consolidate") == 1 }
            assertEquals("succeeded", server.jobState())
            val ranJob = synchronized(ran) { ran.single() }
            assertEquals(HovelTestFixtures.MEMORY_JOB_INPUT, ranJob.prompt)
            assertEquals(ProviderChoice("runtime-default", "low"), ranJob.choice)
        }
    }

    @Test fun aBusyLaneTakesNoSecondJob() {
        val server = Server()
        val release = CountDownLatch(1)
        var launches = 0
        lane(server, { launches++; release.await(5, TimeUnit.SECONDS); ProviderResult(true, "{}") }).use { lane ->
            lane.beat(server)
            eventually { server.jobState() == "running" }
            lane.beat(server)
            assertEquals(1, server.count("memory/claim"))
            release.countDown()
            eventually { server.count("memory/consolidate") == 1 }
            assertEquals(1, launches)
            // running, then succeeded: a second offer would have queued a republish behind the first.
            assertEquals(2, server.count("memory/progress"))
        }
    }

    @Test fun aQueuedJobWaitsForCodexToBeReady() {
        val server = Server()
        lane(server, { error("must not launch") }, CodexCliProvider.Readiness.AUTH_REQUIRED).use { lane ->
            lane.beat(server)
            assertEquals(listOf("heartbeat"), server.routes())
            assertEquals("queued", server.jobState())
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
            lane(server, { error("must not launch") }).use { lane ->
                lane.beat(server)
                assertEquals(MemoryJobLane.MEMORY_JOB_UNREADABLE, statuses.last())
                assertEquals(0, server.count("memory/claim"))
            }
        }
    }

    @Test fun aJobForAnotherProviderIsLeftForAnotherCompanion() {
        val server = Server(jobItem = job(queued, 0, runtimeId = "claude"))
        lane(server, { error("must not launch") }).use { lane ->
            lane.beat(server)
            assertEquals(listOf("heartbeat"), server.routes())
        }
    }

    @Test fun aFailedConsolidationWaitsForTheNextCall() {
        val server = Server(jobItem = null).apply { failConsolidate = true }
        lane(server, { error("no job") }).use { lane ->
            lane.consolidate()
            assertEquals(1, server.count("memory/consolidate"))
        }
    }

    @Test fun theLaneDeclaresOnlyMemoryExtraction() {
        assertEquals(listOf("memory-extraction"), MemoryJobLane.FEATURES)
    }
}
