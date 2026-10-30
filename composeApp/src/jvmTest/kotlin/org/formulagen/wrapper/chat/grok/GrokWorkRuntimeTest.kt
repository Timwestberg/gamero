package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.providers.GrokConnection

/** Process fixtures exercise cancellation/resume ownership without a model or real provider configuration. */
class GrokWorkRuntimeTest {
    @Test fun stopReleaseAndCloseCancelPendingApprovalsAndReapBeforeReplacement() {
        for (action in listOf("stop", "release", "close")) FakeGrokRuntime().use { fake ->
            fake.mode("approval")
            val shown = CompletableFuture<Unit>()
            val cancelled = CompletableFuture<Unit>()
            val broker = ApprovalBroker { _, cancel, visible ->
                visible(); shown.complete(Unit)
                cancel.onCancel { cancelled.complete(Unit) }.use { cancelled.get(10, TimeUnit.SECONDS) }
                ApprovalDecision.ALLOW_ONCE // A late native click must not resurrect an ended turn.
            }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker).use { runtime ->
                val events = WorkFixtureEvents()
                val handle = runtime.startTurn(workRequest(fake), events)
                shown.get(10, TimeUnit.SECONDS)
                when (action) { "stop" -> handle.stop(); "release" -> runtime.release(); else -> runtime.close() }
                cancelled.get(2, TimeUnit.SECONDS)
                awaitCondition { fake.requests().contains("session/cancel") }
                awaitCondition { Files.readAllLines(fake.root.resolve("pids")).none { ProcessHandle.of(it.toLong()).map(ProcessHandle::isAlive).orElse(false) } }
                assertFalse(events.seen.any { it == "failed" || it == "completed" })
                if (action != "close") {
                    fake.mode("normal")
                    val next = WorkFixtureEvents()
                    runtime.startTurn(workRequest(fake), next)
                    next.done.get(10, TimeUnit.SECONDS)
                    assertNull(next.failure)
                    assertEquals("Hello", next.reply)
                }
            }
        }
    }

    @Test fun trustAsksWithBothFoldersAndRequiresGrokToTrustExactlyTheConfirmedRoot() {
        FakeGrokRuntime().use { fake ->
            val repository = Files.createDirectories(fake.root.resolve("repository")).toRealPath()
            val project = Files.createDirectories(repository.resolve("game")).toRealPath()
            val asked = CopyOnWriteArrayList<Pair<java.nio.file.Path, java.nio.file.Path>>()
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            val trust = GrokWorkTrust { selected, root, _ -> asked += selected to root; true }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker, trust).use { runtime ->
                fake.untrusted(repository, rootAfterTrust = project)
                val moved = WorkFixtureEvents()
                runtime.startTurn(workRequest(fake, project), moved); moved.done.get(10, TimeUnit.SECONDS)
                assertEquals(ChatFailure.Temporary, moved.failure)
                assertEquals(listOf(project to repository), asked)
                assertFalse("session/prompt" in fake.requests())
                fake.untrusted(repository)
                val trusted = WorkFixtureEvents()
                runtime.startTurn(workRequest(fake, project), trusted); trusted.done.get(10, TimeUnit.SECONDS)
                assertNull(trusted.failure)
                assertEquals(listOf(project to repository, project to repository), asked)
                assertEquals(2, fake.requests().count { it == "trust" })
            }
        }
    }

    @Test fun aTrustRootCoveringTheHomeFolderOrTheDiskIsRefusedWithoutAsking() {
        FakeGrokRuntime().use { fake ->
            val project = Files.createDirectories(fake.root.resolve("notes")).toRealPath()
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            val trust = GrokWorkTrust { _, _, _ -> error("A trust root above the home folder must not be offered") }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker, trust).use { runtime ->
                for (root in listOf(fake.root, fake.root.parent, java.nio.file.Path.of("/"))) {
                    fake.untrusted(root)
                    val events = WorkFixtureEvents()
                    runtime.startTurn(workRequest(fake, project), events); events.done.get(10, TimeUnit.SECONDS)
                    assertEquals(ChatFailure.ProjectTrustTooBroad, events.failure, root.toString())
                }
                assertFalse("trust" in fake.requests())
                assertFalse("session/prompt" in fake.requests())
            }
        }
    }

    @Test fun theTrustDialogNamesBothFoldersAndSaysWhenGrokTrustsMore() {
        val project = java.nio.file.Path.of("/work/repository/game")
        val enclosing = grokWorkTrustDialog(project, project.parent)
        assertEquals(listOf("Project folder in Hovel: /work/repository/game", "Folder Grok will trust: /work/repository",
            "These are different folders. Grok trusts the whole folder /work/repository, including everything in it outside your project folder."),
            enclosing.lines.take(3))
        assertEquals(false, enclosing.dismiss)
        val same = grokWorkTrustDialog(project, project)
        assertEquals(listOf("Project folder in Hovel: /work/repository/game", "Folder Grok will trust: /work/repository/game"), same.lines.take(2))
        assertTrue(same.lines.none { it.startsWith("These are different folders") })
        assertEquals("Folder Grok will trust: /work/\\u202Erepo", grokWorkTrustDialog(project, java.nio.file.Path.of("/work/\u202Erepo")).lines[1])
    }

    @Test fun aRejectedWorkSessionLoadLosesOnlyThatConversationAndDropsItsBinding() {
        FakeGrokRuntime().use { fake ->
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            val root = fake.root.resolve("providers")
            GrokWorkRuntime(root, fake.env, GrokConnection(fake.env), broker).use { runtime ->
                val first = WorkFixtureEvents()
                runtime.startTurn(workRequest(fake), first)
                first.done.get(10, TimeUnit.SECONDS)
                val saved = NativeConversation(checkNotNull(first.handle), Persona.working("Ember").hash)
                val binding = root.resolve("grok-work-bindings/${saved.id}.json")
                assertTrue(Files.exists(binding))
                fake.mode("reject-load")
                val rejected = WorkFixtureEvents()
                runtime.startTurn(workRequest(fake).copy(conversation = saved), rejected)
                rejected.done.get(10, TimeUnit.SECONDS)
                assertEquals(ChatFailure.ConversationLost, rejected.failure)
                assertFalse(Files.exists(binding))
                assertEquals(1, fake.requests().count { it == "session/load" })
                assertEquals(1, fake.requests().count { it == "session/prompt" })
            }
        }
    }

    @Test fun workRunsInThePlayersEnvironmentWithTheirLoginPathAndNoInheritedGrokOrXaiSettings() {
        FakeGrokRuntime().use { fake ->
            // A Mac app started from Finder: system folders only, plus whatever the player's session exported.
            val parent = fake.env + mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "LANG" to "en_US.UTF-8", "SHELL" to "/bin/zsh",
                "TMPDIR" to fake.root.toString(), "XAI_API_KEY" to "fixture-secret", "GROK_API_KEY" to "fixture-secret",
                "GROK_ENDPOINT" to "https://example.invalid")
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            GrokWorkRuntime(fake.root.resolve("providers"), parent, GrokConnection(parent), broker,
                loginPath = { "/opt/homebrew/bin:/usr/bin:/bin" }).use { runtime ->
                val events = WorkFixtureEvents()
                runtime.startTurn(workRequest(fake), events); events.done.get(10, TimeUnit.SECONDS)
                assertNull(events.failure)
            }
            val child = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(fake.root.resolve("agent-environment.json")))
                .let { it as kotlinx.serialization.json.JsonObject }.mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
            assertEquals("${fake.root}:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin", child["PATH"])
            assertEquals("en_US.UTF-8", child["LANG"]); assertEquals("/bin/zsh", child["SHELL"]); assertEquals(fake.root.toString(), child["TMPDIR"])
            assertEquals(fake.root.resolve(".grok").toString(), child["GROK_HOME"])
            assertEquals("1", child["GROK_DISABLE_AUTOUPDATER"])
            assertFalse(child.values.any { it == "fixture-secret" })
            assertFalse("GROK_ENDPOINT" in child)
        }
    }

    @Test fun theAuthNoticeNamesTheSettingItsEffectOutsideHovelAndTheFile() {
        val notice = grokWorkAuthNotice(java.nio.file.Path.of("/Users/player/.grok/config.toml"))
        assertEquals("Grok work needs Grok Build to use only your account sign-in. Add [auth] with preferred_method = \"oidc\" " +
            "to your Grok config yourself. This also changes how Grok Build signs in outside Hovel. File: " +
            org.formulagen.wrapper.approvals.displayPath(java.nio.file.Path.of("/Users/player/.grok/config.toml")), notice)
        // An unusually long path is what gets cut, never the setting.
        val long = grokWorkAuthNotice(java.nio.file.Path.of("/" + "nested/".repeat(60) + "config.toml"))
        assertEquals(ChatLimits.NOTICE, long.length)
        assertTrue(long.startsWith(notice.substringBefore("File: ") + "File: /nested/"))
    }

    @Test fun readinessSaysExactlyWhatToAddToTheGrokConfigAndNeverWritesIt() {
        FakeGrokRuntime().use { fake ->
            val config = fake.root.resolve(".grok/config.toml")
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker).use { runtime ->
                assertEquals(Readiness.Ready, runtime.readiness(ACCOUNT))
                Files.writeString(config, "theme = 'dark'\n")
                val unpinned = runtime.readiness(ACCOUNT) as Readiness.Unavailable
                assertEquals(grokWorkAuthNotice(config), unpinned.reason)
                assertTrue(unpinned.reason.length <= ChatLimits.NOTICE)
                assertEquals("theme = 'dark'\n", Files.readString(config))
                Files.delete(config)
                assertEquals(unpinned, runtime.readiness(ACCOUNT))
                assertFalse(Files.exists(config))
                Files.writeString(config, "[auth]\npreferred_method = 'oidc'\n[endpoints]\nproxy_url = 'https://example.test'\n")
                assertEquals(Readiness.Unavailable(GROK_WORK_CONFIG_UNSUPPORTED), runtime.readiness(ACCOUNT))
            }
        }
    }

    @Test fun workBindingsCannotResumeInAnotherWorkspaceOrReplayAnAmbiguousPrompt() {
        FakeGrokRuntime().use { fake ->
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker).use { runtime ->
                val first = WorkFixtureEvents()
                val request = workRequest(fake)
                runtime.startTurn(request, first); first.done.get(10, TimeUnit.SECONDS)
                assertNull(first.failure)
                val saved = NativeConversation(checkNotNull(first.handle), request.persona.hash)
                val different = Files.createDirectory(fake.root.resolve("another-project"))
                val mismatch = WorkFixtureEvents()
                runtime.startTurn(request.copy(conversation = saved, work = request.work!!.copy(folders = ProjectFolders(different))), mismatch)
                mismatch.done.get(10, TimeUnit.SECONDS)
                assertEquals(ChatFailure.Unexpected, mismatch.failure)
                assertEquals(1, fake.requests().count { it == "session/prompt" })
                fake.mode("drop")
                val dropped = WorkFixtureEvents()
                runtime.startTurn(request.copy(conversation = saved), dropped); dropped.done.get(10, TimeUnit.SECONDS)
                assertEquals(ChatFailure.Temporary, dropped.failure)
                assertEquals(2, fake.requests().count { it == "session/prompt" })
            }
        }
    }

    @Test fun aConfigChangedWhileWorkStartsIsRefusedBeforeAuthentication() {
        FakeGrokRuntime().use { fake ->
            // The work agent's initialize rewrites the player's config away from the OIDC pin.
            fake.mode("repin")
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker).use { runtime ->
                val events = WorkFixtureEvents()
                runtime.startTurn(workRequest(fake), events); events.done.get(10, TimeUnit.SECONDS)
                assertEquals(ChatFailure.Temporary, events.failure)
                val requests = fake.workRequests()
                assertTrue("initialize" in requests)
                assertTrue(requests.none { it in setOf("_x.ai/auth/info", "_x.ai/auth/check_subscription", "authenticate") }, requests.toString())
                assertFalse("session/new" in requests)
                assertFalse(Files.exists(fake.workProfile()), "The failed launch kept its profile")
            }
        }
    }

    @Test fun anUnconfirmedCleanupAfterALostSessionForgetsTheBindingAndStopsFurtherWork() {
        FakeGrokRuntime().use { fake ->
            val broker = ApprovalBroker { _, _, _ -> error("No permission expected") }
            GrokWorkRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env), broker).use { runtime ->
                val request = workRequest(fake)
                val first = WorkFixtureEvents()
                runtime.startTurn(request, first); first.done.get(10, TimeUnit.SECONDS)
                assertNull(first.failure)
                val binding = fake.root.resolve("providers/grok-work-bindings/${first.handle}.json")
                assertTrue(Files.exists(binding))
                // Grok refuses the saved session, and the launch then cannot remove its profile.
                fake.mode("reject-load-held")
                val lost = WorkFixtureEvents()
                try {
                    runtime.startTurn(request.copy(conversation = NativeConversation(checkNotNull(first.handle), request.persona.hash)), lost)
                    lost.done.get(10, TimeUnit.SECONDS)
                    assertEquals(ChatFailure.Temporary, lost.failure)
                    assertFalse(Files.exists(binding), "The lost session's binding survived its unconfirmed cleanup")
                    assertEquals(Readiness.Unavailable("Grok work cleanup could not be confirmed. Restart Hovel before continuing."),
                        runtime.readiness(ACCOUNT))
                    assertFailsWith<IllegalStateException> { runtime.startTurn(request, WorkFixtureEvents()) }
                } finally { fake.workProfile().toFile().deleteRecursively() }
            }
        }
    }
}

private fun workRequest(fake: FakeGrokRuntime, folder: java.nio.file.Path = fake.root) = TurnRequest(ACCOUNT, "20000000-0000-4000-8000-000000000002",
    Persona.working("Ember"), null, null, "Hello",
    WorkScope(folder, "lease", "Ember", TeamMember(RuntimeId.GROK_BUILD, "runtime-default")))

private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition()) { check(System.nanoTime() < deadline) { "Condition did not complete" }; Thread.sleep(20) }
}

private class WorkFixtureEvents : TurnEvents {
    val done = CompletableFuture<Unit>()
    val seen = CopyOnWriteArrayList<String>()
    var handle: String? = null
    var reply: String? = null
    var failure: ChatFailure? = null
    override fun opened(conversation: String) { handle = conversation; seen += "opened" }
    override fun accepted() { seen += "accepted" }
    override fun partial(text: String) { reply = text; seen += "partial" }
    override fun completed(text: String, conversation: String) { reply = text; handle = conversation; seen += "completed"; done.complete(Unit) }
    override fun failed(failure: ChatFailure) { this.failure = failure; seen += "failed"; done.complete(Unit) }
}
