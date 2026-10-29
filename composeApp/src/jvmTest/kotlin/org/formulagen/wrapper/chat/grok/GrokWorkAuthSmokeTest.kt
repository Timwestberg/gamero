package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.process.runBounded
import org.formulagen.wrapper.approvals.ApprovalDecision
import org.formulagen.wrapper.approvals.ApprovalSubject
import org.junit.Assume.assumeTrue

/** Local-config acceptance after the owner pins OIDC; model turns require a second explicit opt-in. */
class GrokWorkAuthSmokeTest {
    @Test fun installedRuntimeCancelsAnActiveCommandAndReapsItsChildren() {
        assumeTrue("Enable HOVEL_GROK_WORK_LIFECYCLE_SMOKE for a bounded temporary command",
            System.getenv("HOVEL_GROK_WORK_LIFECYCLE_SMOKE") == "1")
        probe { executable, folder, env ->
            val started = folder.resolve("started.pid")
            val finished = folder.resolve("finished.txt")
            val command = "/usr/bin/printf '%s' \"\$\$\" > $started; /bin/sleep 30; /usr/bin/printf FINISHED > $finished"
            val session = AtomicReference("")
            val granted = AtomicInteger()
            val children = mutableSetOf<ProcessHandle>()
            val rpc = GrokAcp(builder(executable, folder, env,
                listOf("--no-auto-update", "--no-subagents", "--disable-web-search", "--max-turns", "2",
                    "--tools", "GrokBuild:run_terminal_cmd", "agent", "--no-leader", "stdio")),
                GROK_ACCOUNT_METHODS + setOf("session/new", "session/prompt"),
                onRequest = { _, params, respond ->
                    val permission = GrokPermission.parse(params, session.get())
                    val subject = permission?.let { grokApprovalSubject(it.tool, folder) }
                    val allowed = subject is ApprovalSubject.Command && subject.command == command && granted.compareAndSet(0, 1)
                    respond(permission?.answer(if (allowed) ApprovalDecision.ALLOW_ONCE else ApprovalDecision.DENY) ?: cancelledGrokPermission())
                }, allowReloadResponses = true)
            try {
                val budget = GrokBudget(30.seconds)
                val initialized = rpc.call("initialize", grokInitialize(), budget.remaining())
                grokAccount(rpc, initialized, budget)
                val created = rpc.call("session/new", buildJsonObject {
                    put("cwd", folder.toString()); putJsonArray("mcpServers") {}
                }, budget.remaining())
                session.set(created["sessionId"]!!.jsonPrimitive.content)
                require(validGrokSessionId(session.get()))
                val result = CompletableFuture.supplyAsync {
                    prompt(rpc, session.get(), "This is a disposable cancellation test. Use run_terminal_cmd exactly once with is_background=false and block_until_ms=300000 to execute exactly:\n$command\nDo not change that command or run other tools. Wait for the command to finish. Stop immediately if denied.")
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while ((!Files.exists(started) || Files.size(started) == 0L) && !result.isDone && System.nanoTime() < deadline)
                    Thread.sleep(25)
                assertEquals(1, granted.get(), "The expected command was not approved")
                assertTrue(Files.exists(started), "No running command observed")
                val process = ProcessHandle.of(Files.readString(started).trim().toLong()).orElseThrow()
                children += process
                val childDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (children.none(::isSleep) && process.isAlive && System.nanoTime() < childDeadline) {
                    process.descendants().use { descendants -> descendants.forEach { children += it } }
                    if (children.none(::isSleep)) Thread.sleep(25)
                }
                assertTrue(children.any(::isSleep), "The controlled sleep process was not observed")
                rpc.cancel(session.get())
                assertEquals(JsonPrimitive("cancelled"), result.get(5, TimeUnit.SECONDS)["stopReason"])
                rpc.close(); rpc.awaitClosed()
                assertTrue(children.none { it.isAlive }, "Command descendants survived cancellation and process teardown")
                assertFalse(Files.exists(finished), "Cancelled command reached its final write")
                println("GROK_WORK_CANCEL_SMOKE: active command observed; terminal cancellation within five seconds; descendants reaped")
            } finally {
                try { rpc.close(); rpc.awaitClosed() }
                finally {
                    children.filter { it.isAlive }.forEach { it.destroyForcibly() }
                    children.forEach { runCatching { it.onExit().get(5, TimeUnit.SECONDS) } }
                }
            }
        }
    }

    /** Observe the intended long-running child rather than a transient shell helper. */
    private fun isSleep(process: ProcessHandle): Boolean = process.info().command().orElse("").endsWith("/sleep")

    @Test fun installedRuntimeHonorsOneTimeCommandApproval() = permissionProbe { folder ->
        val marker = folder.resolve("command.txt")
        val command = "/usr/bin/printf HOVEL_PERMISSION_PROBE > $marker"
        PermissionExample("GrokBuild:run_terminal_cmd", marker, "", "HOVEL_PERMISSION_PROBE",
            "Use run_terminal_cmd exactly once to run this exact command:\n$command") {
            it is ApprovalSubject.Command && it.command == command
        }
    }

    @Test fun installedRuntimeHonorsOneTimeEditApproval() = permissionProbe { folder ->
        val marker = folder.resolve("edit.txt")
        PermissionExample("GrokBuild:search_replace", marker, "BEFORE", "AFTER",
            "Use search_replace exactly once on $marker to replace the entire text BEFORE with AFTER. The file already contains exactly BEFORE.") {
            it == ApprovalSubject.Edit("Edit", listOf(marker), "Replace one matching occurrence\nBefore:\nBEFORE\nAfter:\nAFTER")
        }
    }

    /** Harmless operations are approved once, repeated explicitly, then denied to detect persistent grants. */
    private fun permissionProbe(example: (Path) -> PermissionExample) {
        assumeTrue("Enable HOVEL_GROK_WORK_PERMISSION_SMOKE for disposable-project model turns",
            System.getenv("HOVEL_GROK_WORK_PERMISSION_SMOKE") == "1")
        probe { executable, folder, env ->
            val sample = example(folder)
            Files.writeString(sample.file, sample.before)
            val session = AtomicReference("")
            val requests = AtomicInteger()
            val described = AtomicInteger()
            val decision = AtomicReference(ApprovalDecision.ALLOW_ONCE)
            val rpc = GrokAcp(builder(executable, folder, env,
                listOf("--no-auto-update", "--no-subagents", "--disable-web-search", "--max-turns", "2",
                    "--tools", sample.tool, "agent", "--no-leader", "stdio")),
                GROK_ACCOUNT_METHODS + setOf("session/new", "session/prompt"),
                onRequest = { _, params, respond ->
                    requests.incrementAndGet()
                    val permission = GrokPermission.parse(params, session.get())
                    val subject = permission?.let { grokApprovalSubject(it.tool, folder) }
                    val supported = subject != null && sample.accepts(subject)
                    if (supported) described.incrementAndGet()
                    respond(permission?.answer(if (supported) decision.get() else ApprovalDecision.DENY) ?: cancelledGrokPermission())
                }, allowReloadResponses = true)
            try {
                val budget = GrokBudget(30.seconds)
                val initialized = rpc.call("initialize", grokInitialize(), budget.remaining())
                grokAccount(rpc, initialized, budget)
                val created = rpc.call("session/new", buildJsonObject {
                    put("cwd", folder.toString()); putJsonArray("mcpServers") {}
                }, budget.remaining())
                session.set(created["sessionId"]!!.jsonPrimitive.content)
                require(validGrokSessionId(session.get()))
                val text = "This is a disposable integration test directory. ${sample.instruction} Do not access any other path or run other tools. If permission is denied, stop without retrying."
                assertEquals(JsonPrimitive("end_turn"), prompt(rpc, session.get(), text)["stopReason"])
                assertEquals(1, requests.get(), "Expected exactly one permission request")
                assertEquals(1, described.get(), "Permission details could not be safely represented")
                assertEquals(sample.after, Files.readString(sample.file), "Allowed operation did not run")
                Files.writeString(sample.file, sample.before)
                decision.set(ApprovalDecision.DENY)
                val denied = prompt(rpc, session.get(), "The test harness reset the file. Repeat the requested operation exactly once. $text")
                assertTrue(denied["stopReason"] in listOf(JsonPrimitive("end_turn"), JsonPrimitive("cancelled")))
                assertEquals(2, requests.get(), "Allow once persisted or the repeat did not request permission")
                assertEquals(2, described.get(), "Repeated operation could not be safely represented")
                assertEquals(sample.before, Files.readString(sample.file), "Denied operation executed")
                println("GROK_PERMISSION_SMOKE: ${sample.tool}; allow-once executed; repeat requested permission; deny preserved file")
            } finally { rpc.close(); rpc.awaitClosed() }
        }
    }

    /** Each fixture defines its exact observable file effect and the only subject it permits. */
    private data class PermissionExample(val tool: String, val file: Path, val before: String, val after: String,
        val instruction: String, val accepts: (ApprovalSubject) -> Boolean)

    /** A bounded explicit model turn; transport ambiguity is never retried by the test. */
    private fun prompt(rpc: GrokAcp, session: String, text: String) = rpc.call("session/prompt", buildJsonObject {
        put("sessionId", session)
        putJsonArray("prompt") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
    }, 90.seconds)

    @Test fun installedRuntimeAuthenticatesAndCreatesSessionWithUserConfiguration() = probe { executable, folder, env ->
        val requests = AtomicInteger()
        val rpc = connection(executable, folder, env, requests)
        try {
            val budget = GrokBudget(30.seconds)
            val initialized = rpc.call("initialize", grokInitialize(), budget.remaining())
            val account = grokAccount(rpc, initialized, budget)
            assertTrue(account.models.isNotEmpty())
            val created = rpc.call("session/new", buildJsonObject {
                put("cwd", folder.toString()); putJsonArray("mcpServers") {}
            }, budget.remaining())
            val id = (created["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            assertTrue(id != null && validGrokSessionId(id))
            assertEquals(0, requests.get(), "Unexpected client request")
            println("GROK_WORK_AUTH_SMOKE: personal OIDC subscription verified; work session created with original config; no prompt sent")
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    @Test fun userConfigurationRejectsMissingCredentialsWithoutInteractiveFallback() = probe { executable, folder, env ->
        val requests = AtomicInteger()
        val rpc = connection(executable, folder, env + ("GROK_AUTH_PATH" to folder.resolve("missing-auth.json").toString()), requests)
        try {
            rpc.call("initialize", grokInitialize())
            val denied = assertFailsWith<GrokRequestRejected> {
                rpc.call("authenticate", buildJsonObject {
                    put("methodId", "cached_token"); putJsonObject("_meta") { put("headless", true) }
                })
            }
            assertEquals(-32000, denied.code)
            assertEquals(0, requests.get(), "Unexpected client request")
            assertFalse(Files.exists(folder.resolve("missing-auth.json")))
            println("GROK_WORK_AUTH_SMOKE: original config rejected missing cached credentials; no client request or prompt")
        } finally { rpc.close(); rpc.awaitClosed() }
    }

    /** Preserve the real home and configuration; refuse startup integrations before this bounded probe. */
    private fun probe(action: (Path, Path, Map<String, String>) -> Unit) {
        val executable = System.getenv("HOVEL_GROK_WORK_AUTH_SMOKE")
        assumeTrue("Set HOVEL_GROK_WORK_AUTH_SMOKE only after explicitly pinning local OIDC auth", executable != null)
        val path = Path.of(requireNotNull(executable))
        require(path.isAbsolute && Files.isExecutable(path))
        val home = Path.of(requireNotNull(System.getenv("HOME")))
        val provider = Path.of(System.getenv("GROK_HOME") ?: home.resolve(".grok").toString())
        val config = provider.resolve("config.toml")
        val original = Files.readAllBytes(config)
        val folder = Files.createTempDirectory("hovel-grok-work-auth-")
        val env = mapOf("HOME" to home.toString(), "GROK_HOME" to provider.toString(),
            "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "BROWSER" to "/usr/bin/false", "GROK_DISABLE_AUTOUPDATER" to "1")
        try {
            val version = runBounded(builder(path, folder, env, listOf("--version")), 10.seconds, 256)
            assertEquals(GROK_VERIFIED_VERSION, version?.output?.trim())
            assertEquals(0, version?.exitCode)
            val inspected = runBounded(builder(path, folder, env, listOf("--no-auto-update", "inspect", "--json")), 10.seconds)
            assertEquals(0, inspected?.exitCode)
            val report = Json.parseToJsonElement(requireNotNull(inspected).output).jsonObject
            for (key in listOf("hooks", "plugins", "mcpServers", "lspServers"))
                assertTrue((report[key] as? JsonArray)?.isEmpty() == true, "Startup integration needs a separate acceptance: $key")
            for (key in listOf("configWarnings", "mcpConfigProblems"))
                assertTrue(report[key] == null || (report[key] as? JsonArray)?.isEmpty() == true, "Configuration warning")
            assertEquals(JsonPrimitive(false), report["permissions"]?.jsonObject?.get("managedSettingsExists"))
            action(path, folder, env)
        } finally {
            folder.toFile().deleteRecursively()
            assertTrue(original.contentEquals(Files.readAllBytes(config)), "Runtime changed the user's configuration during the probe")
        }
    }

    /** No shared leader, client filesystem/terminal, permission grant or model turn is available. */
    private fun connection(executable: Path, folder: Path, env: Map<String, String>, requests: AtomicInteger) = GrokAcp(
        builder(executable, folder, env, listOf("--no-auto-update", "agent", "--no-leader", "stdio")),
        GROK_ACCOUNT_METHODS + "session/new", onRequest = { _, _, respond ->
            requests.incrementAndGet(); respond(cancelledGrokPermission())
        }, allowReloadResponses = true,
    )

    /** Only explicitly selected home settings and system executables are inherited. */
    private fun builder(executable: Path, folder: Path, env: Map<String, String>, args: List<String>) =
        ProcessBuilder(listOf(executable.toString()) + args).directory(folder.toFile()).apply {
            environment().clear(); environment().putAll(env)
        }
}
