package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The Codex lanes (a Hovel task and a memory job), fed by the heartbeat the conversation runner sends. */
class HovelHostSessionTest {
    private val host = UUID.randomUUID().toString()
    private val owner = UUID.randomUUID().toString()
    private val taskId = UUID.randomUUID().toString()
    private val jobId = UUID.randomUUID().toString()
    private fun task(progress: JsonObject, revision: Int) = HovelTestFixtures.task(host, owner, taskId, progress, revision)
    private fun job(progress: JsonObject, revision: Int, runtimeId: String = "codex") =
        HovelTestFixtures.memoryJob(host, owner, jobId, progress, revision, runtimeId = runtimeId)
    private val queued = HovelTestFixtures.QUEUED

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
            // Like the real server, an identical report replays.
            if (body.getValue("progress") == item.getValue("progress")) return item
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

    private val statuses = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun session(server: Server, provider: TaskProvider, memoryProvider: TaskProvider) = HovelHostSession(
        hostId = host,
        journal = ExecutionJournal(Files.createTempDirectory("session-journal"), host), provider = provider,
        memoryJournal = ExecutionJournal(Files.createTempDirectory("session-memory-journal"), host), memoryProvider = memoryProvider,
        checkReadiness = { CodexCliProvider.Readiness.READY }, transport = server,
    ) { statuses += it }

    /** One heartbeat as the conversation runner sends it, its answer handed to the Codex lanes. */
    private fun HovelHostSession.beat(server: Server) = heartbeat(server.post("heartbeat", JsonObject(emptyMap())))

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
            session.beat(server)
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS))
            eventually { server.count("memory/consolidate") == 1 }
            assertEquals("succeeded", server.jobState())
            assertEquals("running", server.taskState())
            val ranJob = synchronized(ran) { ran.single() }
            assertEquals(HovelTestFixtures.MEMORY_JOB_INPUT, ranJob.prompt)
            assertTrue(ranJob.rawPrompt)
            assertEquals(ProviderChoice("runtime-default", "low"), ranJob.choice)
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

    @Test fun aMemoryJobThisVersionCannotReadAsksForAnUpdateAndNeverHoldsUpATask() {
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
            val server = Server(taskItem = task(queued, 0), jobItem = item)
            session(server, { ProviderResult(true, "Task done") }, { error("must not launch") }).use { session ->
                session.beat(server)
                assertEquals(HovelHostSession.MEMORY_JOB_UNREADABLE, statuses.last())
                eventually { server.taskState() == "succeeded" }
                assertEquals(0, server.count("memory/claim"))
            }
        }
    }

    @Test fun aMalformedTaskNeverHoldsUpAMemoryJob() {
        val server = Server(taskItem = buildJsonObject {
            task(queued, 0).forEach { (key, value) -> if (key != "characterMemory") put(key, value) }
        })
        session(server, { error("must not launch") }, { ProviderResult(true, "{}") }).use { session ->
            assertFails { session.beat(server) }
            eventually { server.jobState() == "succeeded" }
            assertEquals(0, server.count("claim"))
        }
    }

    @Test fun aJobForAnotherProviderIsLeftForAnotherCompanion() {
        val server = Server(taskItem = null, jobItem = job(queued, 0, runtimeId = "claude"))
        session(server, { error("no task") }, { error("must not launch") }).use { session ->
            session.beat(server)
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

    @Test fun featuresAndTheTaskProviderStateFollowTheLanes() {
        val server = Server(taskItem = null, jobItem = null)
        session(server, { error("no task") }, { error("no job") }).use { session ->
            assertEquals(listOf("provider-input", "memory-context", "memory-extraction"), session.features)
            assertEquals("ready", session.taskProviderState())
        }
        HovelHostSession(host, null, null, ExecutionJournal(Files.createTempDirectory("memory-only"), host), { error("no job") },
            { CodexCliProvider.Readiness.AUTH_REQUIRED }, server) {}.use { memoryOnly ->
            assertEquals(listOf("memory-context", "memory-extraction"), memoryOnly.features)
            assertNull(memoryOnly.taskProviderState(), "without tasks the heartbeat reports unavailable")
        }
    }
}
