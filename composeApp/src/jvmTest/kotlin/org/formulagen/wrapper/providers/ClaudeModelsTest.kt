package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlin.time.Duration
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ProviderModel
import org.formulagen.wrapper.bridge.ProviderLimits
import org.formulagen.wrapper.bridge.ProviderState

class ClaudeModelsTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private val catalog = """{"models":[
      {"value":"default","resolvedModel":"claude-opus-5-5","displayName":"Default (recommended)"},
      {"value":"opus","resolvedModel":"claude-opus-5-5","displayName":"Opus"},
      {"value":"claude-fable-5-1[1m]","resolvedModel":"claude-fable-5-1","displayName":"Fable"},
      {"value":"sonnet","resolvedModel":"claude-sonnet-5-5","displayName":"Sonnet"}] }"""

    @Test fun discoveryPreservesFableAndFindsTheRuntimeDefaultWithoutOfferingItsDuplicate() {
        assertEquals(listOf(ProviderModel("Opus", true, "opus"), ProviderModel("Fable", false, "claude-fable-5-1[1m]"),
            ProviderModel("Sonnet", false, "sonnet")), claudeModelList(json(catalog)))
        assertTrue(claudeModelList(null).isEmpty())
        assertTrue(claudeModelList(json("""{"models":null}""")).isEmpty())
    }

    @Test fun malformedDuplicateAndOversizedCatalogEntriesCannotEscapeTheAdapter() {
        val result = claudeModelList(json("""{"models":[null,4,{"value":42},{"value":"--unsafe"},
          {"value":"claude-fable[2m]"},{"value":"claude-fable[1m]","displayName":""},
          {"value":"claude-fable[1m]","displayName":"Duplicate"},
          {"value":"sonnet","displayName":"${"x".repeat(81)}"}]}"""))
        assertEquals(listOf(ProviderModel("claude-fable[1m]", false, "claude-fable[1m]"), ProviderModel("sonnet", false, "sonnet")), result)
        val large = buildJsonObject {
            putJsonArray("models") { repeat(40) { add(buildJsonObject { put("value", "claude-model-$it") }) } }
        }
        assertEquals(ProviderLimits.MODELS, claudeModelList(large).size)
        val longId = "m".repeat(128)
        assertEquals(ProviderModel("m".repeat(80), false, longId),
            claudeModelList(json("""{"models":[{"value":"$longId"}]}""")).single())
    }

    @Test fun catalogUsesOnlyInitializeCachesRefreshesAndClearsAfterSignOut() {
        val root = Files.createTempDirectory("hovel-model-control")
        val executable = root.resolve("claude")
        Files.writeString(root.resolve("catalog"), catalog)
        Files.writeString(executable, """
            #!/usr/bin/python3
            import os, sys, json
            root = os.path.dirname(__file__)
            if sys.argv[1:] == ['--version']:
                print('2.1.304 (Claude Code)'); sys.exit(0)
            if sys.argv[1:3] == ['auth', 'status']:
                connected = not os.path.exists(root + '/signed-out')
                print(json.dumps({'loggedIn': connected, 'authMethod': 'claude.ai', 'apiProvider': 'firstParty', 'subscriptionType': 'max'}))
                sys.exit(0 if connected else 1)
            open(root + '/pid', 'w').write(str(os.getpid()))
            open(root + '/args', 'w').write(json.dumps(sys.argv[1:]))
            for line in sys.stdin:
                with open(root + '/requests', 'a') as f: f.write(line)
                request = json.loads(line)
                print(json.dumps({'type':'control_response', 'response':{'subtype':'success', 'request_id':request['request_id'],
                    'response':json.load(open(root + '/catalog'))}}), flush=True)
        """.trimIndent())
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        var now = Instant.parse("2026-10-28T12:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant() = now
        }
        try {
            val connection = ClaudeConnection(ClaudeRuntime(executable, root.resolve("providers")), ttl = Duration.ZERO, clock = clock)
            assertEquals(claudeModelList(json(catalog)), connection.models())
            assertEquals(claudeModelList(json(catalog)), connection.models())
            val requests = Files.readAllLines(root.resolve("requests")).map { json(it) }
            assertEquals(1, requests.size)
            assertEquals("control_request", requests.single()["type"]?.jsonPrimitive?.content)
            assertEquals("initialize", requests.single()["request"]?.jsonObject?.get("subtype")?.jsonPrimitive?.content)
            val args = Json.parseToJsonElement(Files.readString(root.resolve("args"))).jsonArray.map { it.jsonPrimitive.content }
            assertTrue(args.containsAll(listOf("--safe-mode", "--no-session-persistence", "--strict-mcp-config")))
            assertEquals("", args[args.indexOf("--tools") + 1])
            assertFalse(ProcessHandle.of(Files.readString(root.resolve("pid")).toLong()).map { it.isAlive }.orElse(false))
            Files.writeString(root.resolve("catalog"), """{"models":[{"value":"claude-new-model","displayName":"New"}]}""")
            now = now.plusMillis(MODELS_TTL_MILLIS)
            assertEquals(listOf(ProviderModel("New", false, "claude-new-model")), connection.models())
            Files.createFile(root.resolve("signed-out"))
            assertEquals(ProviderState.DISCONNECTED, connection.status().state)
            Files.delete(root.resolve("signed-out"))
            Files.writeString(root.resolve("catalog"), "{}")
            assertTrue(connection.models().isEmpty(), "No stale catalog after reconnect or an unsupported response")
            Files.createFile(root.resolve("signed-out"))
            assertTrue(connection.models().isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }
}
