package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.providers.grok.GrokSmoke

/** Opt-in acceptance of the actual work adapter, using only a disposable project and exact operations. */
class GrokWorkRuntimeSmokeTest {
    @Test fun stopReleaseAndCloseReapForegroundWorkPastTheNormalBackgroundThreshold() {
        // Ordinary work in the player's own provider home; this test never grants folder trust.
        val env = GrokSmoke.environment(GrokSmoke.turnsExecutable())
        GrokSmoke.requireWorkAuthPin()
        for (action in listOf("stop", "release", "close")) {
            val root = Files.createTempDirectory("hovel-grok-cleanup-")
            val project = Files.createDirectory(root.resolve("project")).toRealPath()
            val marker = project.resolve("started.pid")
            val finished = project.resolve("finished.txt")
            val command = "/usr/bin/printf '%s' \"\$\$\" > $marker; /bin/sleep 90; /usr/bin/printf FINISHED > $finished"
            val children = mutableSetOf<ProcessHandle>()
            val permissions = AtomicInteger()
            val broker = ApprovalBroker { request, _, shown ->
                shown()
                if ((request.subject as? ApprovalSubject.Command)?.command == command && permissions.incrementAndGet() == 1)
                    ApprovalDecision.ALLOW_ONCE else ApprovalDecision.DENY
            }
            val runtime = GrokWorkRuntime(root.resolve("providers"), env, GrokConnection(env), broker)
            try {
                val events = WorkSmokeEvents()
                val handle = runtime.startTurn(TurnRequest(ACCOUNT, "20000000-0000-4000-8000-000000000002", Persona.working("Ember"),
                    null, null, "This is a disposable process-cleanup test. Use run_terminal_cmd exactly once to execute this exact foreground command. " +
                        "Do not set block_until_ms, is_background or timeout, and do not run other tools. Stop if denied.\n$command",
                    WorkScope(project, "cleanup-lease", "Ember", TeamMember(RuntimeId.GROK_BUILD, "runtime-default"))), events)
                waitFor { Files.exists(marker) && Files.size(marker) > 0 || events.done.isDone }
                assertTrue(Files.exists(marker), "No controlled command started")
                val shell = ProcessHandle.of(Files.readString(marker).trim().toLong()).orElseThrow()
                children += shell
                waitFor {
                    shell.descendants().use { it.forEach(children::add) }
                    children.any { it.info().command().orElse("").endsWith("/sleep") }
                }
                if (action == "stop") {
                    // The unmodified runtime can detach at 15/30 seconds. This profile must keep waiting.
                    Thread.sleep(35_000)
                    assertTrue(shell.isAlive)
                    assertFalse(events.done.isDone, "Foreground work detached or completed before cancellation")
                }
                when (action) { "stop" -> handle.stop(); "release" -> runtime.release(); else -> runtime.close() }
                waitFor(10) { children.none { it.isAlive } }
                assertFalse(Files.exists(finished), "Cancelled work reached its final write")
                assertEquals(1, permissions.get())
                println("GROK_WORK_CLEANUP_SMOKE: $action; actual shell and sleep observed; children reaped; final write absent")
            } finally {
                runtime.close()
                children.filter { it.isAlive }.forEach { it.destroyForcibly() }
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test fun workAdapterEditsWithNativeBrokerAndResumesAfterRestart() {
        val executable = GrokSmoke.turnsExecutable()
        GrokSmoke.requireWorkAuthPin()
        val root = Files.createTempDirectory("hovel-grok-work-adapter-")
        val project = Files.createDirectory(root.resolve("project")).toRealPath()
        val marker = project.resolve("test.txt")
        Files.writeString(marker, "BEFORE")
        Files.writeString(project.resolve("AGENTS.md"), "For this disposable test, end your final response with PROJECT_RULE_OK.")
        // A disposable provider home keeps this test's explicit folder-trust grant out of the player's store.
        val env = GrokSmoke.disposableHomeEnvironment(root, executable)
        val requests = AtomicInteger()
        val broker = ApprovalBroker { request, _, shown ->
            shown()
            assertNull(request.sessionLabel)
            val subject = request.subject
            val exact = subject == ApprovalSubject.Edit("Edit", listOf(marker), "Replace one matching occurrence\nBefore:\nBEFORE\nAfter:\nAFTER")
            if (exact && requests.incrementAndGet() == 1) ApprovalDecision.ALLOW_ONCE else ApprovalDecision.DENY
        }
        val persona = Persona.working("Ember")
        val work = WorkScope(project, "test-lease", "Ember", TeamMember(RuntimeId.GROK_BUILD, "runtime-default"))
        val request = TurnRequest(ACCOUNT, "20000000-0000-4000-8000-000000000002", persona, null, null,
            "This is a disposable integration test. Use search_replace exactly once to replace BEFORE with AFTER in $marker. " +
                "Do not use other tools or alter other files. If denied, stop without retrying. Remember ORCHID_WORK.", work)
        val first = WorkSmokeEvents()
        try {
            GrokWorkRuntime(root.resolve("providers"), env, GrokConnection(env), broker, GrokWorkTrust { _, trusted, cancel -> trusted == project && !cancel.isCancelled }).use { runtime ->
                assertEquals(Readiness.Ready, runtime.readiness(ACCOUNT))
                runtime.startTurn(request, first); first.await()
                assertNull(first.failure)
                assertEquals("AFTER", Files.readString(marker))
                assertEquals(1, requests.get())
                assertTrue(first.reply.orEmpty().contains("PROJECT_RULE_OK"), "Project instructions were not observed")
                assertTrue(first.activities.any { it.steps.any { step -> step.kind == ActivityKind.EDIT && step.status == ActivityStatus.DONE } })
            }
            Files.writeString(marker, "BEFORE")
            GrokWorkRuntime(root.resolve("providers"), env, GrokConnection(env), broker).use { runtime ->
                val second = WorkSmokeEvents()
                runtime.startTurn(request.copy(conversation = NativeConversation(checkNotNull(first.handle), persona.hash),
                    text = "The test harness reset the file. Repeat the same edit once. If denied, stop. Include the word I asked you to remember."), second)
                second.await()
                assertNull(second.failure)
                assertEquals(2, requests.get(), "The repeated edit must ask again")
                assertEquals("BEFORE", Files.readString(marker))
                assertEquals(first.handle, second.handle)
                val recall = WorkSmokeEvents()
                runtime.startTurn(request.copy(conversation = NativeConversation(checkNotNull(second.handle), persona.hash),
                    text = "Without using tools, what word did I ask you to remember in our first message?"), recall)
                recall.await()
                assertNull(recall.failure)
                assertTrue(recall.reply.orEmpty().contains("ORCHID_WORK"), "Saved work context was not restored")
            }
            println("GROK_WORK_ADAPTER_SMOKE: native broker allow/deny; project instructions; activity; restart/resume; no persistent grant")
        } finally { root.toFile().deleteRecursively() }
    }
}

private fun waitFor(seconds: Long = 45, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    while (!condition()) { check(System.nanoTime() < deadline) { "Controlled work did not reach its expected state" }; Thread.sleep(25) }
}

private class WorkSmokeEvents : TurnEvents {
    val done = CompletableFuture<Unit>()
    val activities = CopyOnWriteArrayList<Activity>()
    var handle: String? = null
    var reply: String? = null
    var failure: ChatFailure? = null
    override fun opened(conversation: String) { handle = conversation }
    override fun accepted() = Unit
    override fun partial(text: String) { reply = text }
    override fun completed(text: String, conversation: String) { reply = text; handle = conversation; done.complete(Unit) }
    override fun failed(failure: ChatFailure) { this.failure = failure; done.complete(Unit) }
    override fun activity(activity: Activity) { activities += activity }
    fun await() { done.get(90, TimeUnit.SECONDS) }
}
