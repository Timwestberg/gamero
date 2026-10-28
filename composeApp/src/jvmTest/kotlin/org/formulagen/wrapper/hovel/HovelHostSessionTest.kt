package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class HovelHostSessionTest {
    private val host = UUID.randomUUID().toString()
    private val owner = UUID.randomUUID().toString()
    private val taskId = UUID.randomUUID().toString()
    private val jobId = UUID.randomUUID().toString()
    private val input = "Extract memory notes from these exchanges."

    private fun task(progress: JsonObject, revision: Int) = buildJsonObject {
        put("ownerId", owner); put("characterMemory", "Ada remembers"); put("revision", revision); put("progress", progress)
        put("request", buildJsonObject { put("id", taskId); put("hostId", host); put("mode", "local"); put("prompt", "Inspect") })
    }

    private fun job(progress: JsonObject, revision: Int, providerId: String = "codex") = buildJsonObject {
        put("attempt", 1); put("createdAt", "2026-10-28T00:00:00.000Z"); put("day", "2026-10-27")
        put("effort", "low"); put("hostId", host); put("id", jobId); put("input", input)
        put("kind", "extract"); put("model", "runtime-default"); put("ownerId", owner); put("progress", progress)
        put("providerId", providerId); put("revision", revision); put("updatedAt", "2026-10-28T00:00:00.000Z")
    }

    private val queued = buildJsonObject { put("state", "queued") }

    /** A fake server with one task and one memory job. Both workers call it, so every call is synchronized. */
    private inner class Server(private var taskItem: JsonObject? = task(queued, 0), private var jobItem: JsonObject? = job(queued, 0)) : HovelTransport {
        private val routes = mutableListOf<String>()
        @Volatile var failConsolidate = false

        @Synchronized override fun post(route: String, body: JsonObject): JsonObject {
            routes += route
            return when (route) {
                "heartbeat" -> buildJsonObject {
                    putJsonArray("tasks") { taskItem?.let(::add) }
                    putJsonArray("memoryJobs") { jobItem?.takeIf(::isOpen)?.let(::add) }
                }
                "claim" -> task(claimed(body), 1).also { taskItem = it }
                "progress" -> advance(taskItem!!, body) { progress, revision -> task(progress, revision) }.also { taskItem = it }
                "memory/claim" -> job(claimed(body), 1).also { jobItem = it }
                "memory/progress" -> advance(jobItem!!, body) { progress, revision -> job(progress, revision) }.also { jobItem = it }
                "memory/consolidate" -> { check(!failConsolidate) { "offline" }; JsonObject(emptyMap()) }
                else -> error("unexpected route $route")
            }
        }

        private fun claimed(body: JsonObject) = buildJsonObject { put("state", "claimed"); put("claimId", body.getValue("claimId")) }
        private fun advance(item: JsonObject, body: JsonObject, next: (JsonObject, Int) -> JsonObject): JsonObject {
            assertEquals(item.getValue("revision"), body.getValue("expectedRevision"))
            return next(body.getValue("progress").jsonObject, item.getValue("revision").jsonPrimitive.int + 1)
        }

        @Synchronized fun taskState() = taskItem?.let(::stateOf)
        @Synchronized fun jobState() = jobItem?.let(::stateOf)
        @Synchronized fun count(route: String) = routes.count { it == route }
        @Synchronized fun routes() = routes.toList()
    }

    private fun stateOf(item: JsonObject) = item.getValue("progress").jsonObject.getValue("state").jsonPrimitive.content

    private fun isOpen(item: JsonObject) = stateOf(item) in setOf("queued", "claimed", "running")

    private fun session(server: Server, provider: TaskProvider, memoryProvider: TaskProvider) = HovelHostSession(
        host,
        ExecutionJournal(Files.createTempDirectory("session-journal"), host), provider,
        ExecutionJournal(Files.createTempDirectory("session-memory-journal"), host), memoryProvider,
        { CodexCliProvider.Readiness.READY }, server,
    ) {}

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(10)
        }
    }

    @Test fun aMemoryJobRunsBesideARunningTaskAndConsolidatesAfterItsResult() {
        val server = Server()
        val taskStarted = CountDownLatch(1)
        val releaseTask = CountDownLatch(1)
        val ran = mutableListOf<ExecutionRequest>()
        val provider = TaskProvider { taskStarted.countDown(); releaseTask.await(5, TimeUnit.SECONDS); ProviderResult(true, "Task done") }
        val memoryProvider = TaskProvider { synchronized(ran) { ran += it }; ProviderResult(true, "{\"notes\":[]}") }
        session(server, provider, memoryProvider).use { session ->
            session.heartbeat()
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS))
            eventually { server.count("memory/consolidate") == 1 }
            assertEquals("succeeded", server.jobState())
            assertEquals("running", server.taskState())
            assertEquals(input, synchronized(ran) { ran.single() }.prompt)
            assertEquals(ProviderChoice("runtime-default", "low"), synchronized(ran) { ran.single() }.extraction)
            releaseTask.countDown()
            eventually { server.taskState() == "succeeded" }
        }
    }

    @Test fun aBusyLaneTakesNoSecondItem() {
        val server = Server()
        val release = CountDownLatch(1)
        var memoryLaunches = 0
        val memoryProvider = TaskProvider { memoryLaunches++; release.await(5, TimeUnit.SECONDS); ProviderResult(true, "{}") }
        session(server, { ProviderResult(true, "Task done") }, memoryProvider).use { session ->
            session.heartbeat()
            eventually { server.jobState() == "running" }
            session.heartbeat()
            assertEquals(1, server.count("memory/claim"))
            release.countDown()
            eventually { server.count("memory/consolidate") == 1 }
            assertEquals(1, memoryLaunches)
        }
    }

    @Test fun aMalformedMemoryJobNeverHoldsUpATask() {
        val server = Server(jobItem = buildJsonObject {
            job(queued, 0).forEach { (key, value) -> if (key != "input") put(key, value) }
        })
        session(server, { ProviderResult(true, "Task done") }, { error("must not launch") }).use { session ->
            assertFails { session.heartbeat() }
            eventually { server.taskState() == "succeeded" }
            assertEquals(0, server.count("memory/claim"))
        }
    }

    @Test fun aMalformedTaskNeverHoldsUpAMemoryJob() {
        val server = Server(taskItem = buildJsonObject {
            task(queued, 0).forEach { (key, value) -> if (key != "characterMemory") put(key, value) }
        })
        session(server, { error("must not launch") }, { ProviderResult(true, "{}") }).use { session ->
            assertFails { session.heartbeat() }
            eventually { server.jobState() == "succeeded" }
            assertEquals(0, server.count("claim"))
        }
    }

    @Test fun aJobForAnotherProviderIsLeftForAnotherCompanion() {
        val server = Server(taskItem = null, jobItem = job(queued, 0, providerId = "claude"))
        session(server, { error("no task") }, { error("must not launch") }).use { session ->
            session.heartbeat()
            assertEquals(listOf("heartbeat"), server.routes())
        }
    }

    @Test fun aFailedConsolidationWaitsForTheNextCall() {
        val server = Server(taskItem = null, jobItem = null).apply { failConsolidate = true }
        session(server, { error("no task") }, { error("no job") }).use { session ->
            session.consolidate()
            assertEquals(1, server.count("memory/consolidate"))
        }
    }

    @Test fun tasksAndMemoryJobsNeedSeparateJournals() {
        val journal = ExecutionJournal(Files.createTempDirectory("shared-journal"), host)
        journal.use {
            assertFails {
                HovelHostSession(host, it, { error("no") }, it, { error("no") }, { CodexCliProvider.Readiness.READY }, Server()) {}
            }
        }
    }
}
