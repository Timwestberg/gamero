package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*

class ProviderUsageTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private val now = 1_793_200_000_000L

    @Test fun codexChoosesTheCodexBucketAndClassifiesDurationsRatherThanPositions() {
        val usage = codexUsage(json("""{
          "rateLimits":{"primary":{"usedPercent":99,"windowDurationMins":300}},
          "rateLimitsByLimitId":{"codex":{"primary":{"usedPercent":6,"windowDurationMins":10080,"resetsAt":1793803311},
            "secondary":{"usedPercent":0,"windowDurationMins":300}},"review":{"primary":{"usedPercent":88,"windowDurationMins":300}}}}
        """), now)
        assertEquals(ProviderUsageWindow(0.0), usage.session)
        assertEquals(ProviderUsageWindow(6.0, 1793803311), usage.weekly)
        assertEquals(RuntimeId.CODEX, usage.runtimeId)
        assertEquals(now, usage.checkedAt)
    }

    @Test fun codexCanReportOnlyAWeeklyWindowAndUnknownBucketsNeverBecomeTheMainQuota() {
        val weekly = """{"limitId":"codex","primary":{"usedPercent":6,"windowDurationMins":10080},"secondary":null}"""
        val result = codexUsage(json("""{"rateLimits":$weekly}"""), now)
        assertNull(result.session); assertEquals(6.0, result.weekly?.usedPercent)
        assertNull(codexUsage(json("""{"rateLimits":$weekly,"rateLimitsByLimitId":{"review":$weekly}}"""), now).weekly)
        assertNull(codexUsage(json("""{"rateLimits":{"limitId":"review","primary":{"usedPercent":4,"windowDurationMins":300}}}"""), now).session)
        assertNull(codexUsage(json("""{"rateLimits":{"primary":{"usedPercent":4,"windowDurationMins":15}}}"""), now).session)
    }

    @Test fun claudeUsesAggregateQuotasAndNotTheSessionsCostOrModelBuckets() {
        val result = claudeUsage(json("""{"rate_limits_available":true,"session":{"total_cost_usd":99},"rate_limits":{
          "five_hour":{"utilization":42.5,"resets_at":"2026-10-29T02:00:00Z"},
          "seven_day":{"utilization":100,"resets_at":null},"seven_day_opus":{"utilization":1}}}"""), now)
        assertEquals(ProviderUsageWindow(42.5, Instant.parse("2026-10-29T02:00:00Z").epochSecond), result.session)
        assertEquals(ProviderUsageWindow(100.0), result.weekly)
        assertNull(claudeUsage(json("""{"rate_limits_available":true,"rate_limits":null}"""), now).session)
        assertNull(claudeUsage(json("""{"rate_limits_available":false,"rate_limits":{"five_hour":{"utilization":4}}}"""), now).session)
    }

    @Test fun malformedNumbersAreUnknownAndAnInvalidResetDoesNotDiscardAKnownPercent() {
        for (value in listOf("-1", "101", "null", "\"42\"", "{}")) {
            assertNull(claudeUsage(json("""{"rate_limits_available":true,"rate_limits":{"five_hour":{"utilization":$value}}}"""), now).session)
        }
        assertEquals(ProviderUsageWindow(0.0), claudeUsage(json("""{"rate_limits_available":true,"rate_limits":{"five_hour":{"utilization":0,"resets_at":"invalid"}}}"""), now).session)
    }

    @Test fun codexReadsOnlyAccountAndLimitsAndDoesNotReadQuotasWhenSignedOut() {
        val calls = mutableListOf<String>()
        var connected = false
        val rpc = object : CodexAppServer {
            override fun call(method: String, params: JsonObject): JsonObject {
                calls += method
                return when (method) {
                    "account/read" -> json(if (connected) """{"account":{"type":"chatgpt","email":"test@example.test"}}""" else """{"account":null}""")
                    "account/rateLimits/read" -> json("""{"rateLimits":{"primary":{"usedPercent":0,"windowDurationMins":300}}}""")
                    else -> error("Unexpected method")
                }
            }
            override fun close() = Unit
        }
        CodexConnection(rpc).use {
            assertNull(it.usage().session)
            assertEquals(listOf("account/read"), calls)
            connected = true
            assertEquals(0.0, it.usage().session?.usedPercent)
            assertEquals(listOf("account/read", "account/read", "account/rateLimits/read"), calls)
        }
        assertTrue("account/rateLimits/read" in StdioCodexAppServer.ACCOUNT_METHODS)
        assertFalse("turn/start" in StdioCodexAppServer.ACCOUNT_METHODS)
    }

    @Test fun unofferedOrMissingRuntimesReturnNoLimits() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hovel-usage-test")
        try {
            DesktopProviders(listOf(CodexProvider(root, null))).use { providers ->
                for (id in RuntimeId.entries) {
                    val result = providers.usage(ProviderScope(id))
                    assertEquals(id, result.runtimeId); assertNull(result.session); assertNull(result.weekly)
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun claudeControlSendsNoPromptSkipsHistoryAndStopsItsChild() {
        withClaude { runtime, root ->
            val result = readClaudeUsage(runtime)
            assertEquals(12.0, claudeUsage(result, now).session?.usedPercent)
            val requests = Files.readAllLines(root.resolve("requests")).map { json(it) }
            assertEquals(listOf("initialize", "get_usage"), requests.map { it.getValue("request").jsonObject.getValue("subtype").jsonPrimitive.content })
            assertEquals(JsonPrimitive(true), requests.last().getValue("request").jsonObject["skip_behaviors"])
            assertTrue(requests.all { it.getValue("type").jsonPrimitive.content == "control_request" })
            val args = Json.parseToJsonElement(Files.readString(root.resolve("args"))).jsonArray.map { it.jsonPrimitive.content }
            assertTrue(args.containsAll(listOf("--safe-mode", "--no-session-persistence", "--strict-mcp-config")))
            assertEquals("", args[args.indexOf("--tools") + 1])
            assertFalse(ProcessHandle.of(Files.readString(root.resolve("pid")).toLong()).map { it.isAlive }.orElse(false))
        }
    }

    @Test fun unsupportedAndHangingClaudeControlReturnUnavailable() {
        withClaude("error") { runtime, _ -> assertNull(readClaudeUsage(runtime)) }
        withClaude("hang") { runtime, root ->
            assertNull(readClaudeUsage(runtime, 300))
            assertFalse(ProcessHandle.of(Files.readString(root.resolve("pid")).toLong()).map { it.isAlive }.orElse(false))
        }
    }

    @Test fun cancellingClaudeUsageStopsItsChildAndPropagatesInterruption() {
        withClaude("hang") { runtime, root ->
            val outcome = java.util.concurrent.CompletableFuture<Throwable?>()
            val worker = Thread.ofPlatform().start {
                try { readClaudeUsage(runtime); outcome.complete(null) }
                catch (error: Throwable) { outcome.complete(error) }
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (!Files.exists(root.resolve("pid")) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(root.resolve("pid")))
            worker.interrupt()
            assertIs<InterruptedException>(outcome.get(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(ProcessHandle.of(Files.readString(root.resolve("pid")).toLong()).map { it.isAlive }.orElse(false))
        }
    }

    /** A protocol peer, with no access to a real provider account. */
    private fun withClaude(mode: String = "success", block: (ClaudeRuntime, java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("hovel-usage-control")
        val executable = root.resolve("claude")
        Files.writeString(executable, """
            #!/usr/bin/python3
            import os, sys, json, time
            root = os.path.dirname(__file__)
            open(root + '/pid', 'w').write(str(os.getpid()))
            open(root + '/args', 'w').write(json.dumps(sys.argv[1:]))
            for line in sys.stdin:
                with open(root + '/requests', 'a') as f: f.write(line)
                request = json.loads(line)
                if '$mode' == 'hang': time.sleep(30)
                response = {'subtype': '$mode', 'request_id': request['request_id'], 'response': {'rate_limits_available': True, 'rate_limits': {'five_hour': {'utilization': 12}}}}
                print(json.dumps({'type': 'control_response', 'response': response}), flush=True)
        """.trimIndent())
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        try { block(ClaudeRuntime(executable, root.resolve("providers")), root) }
        finally { root.toFile().deleteRecursively() }
    }
}
