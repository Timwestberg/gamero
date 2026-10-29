package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.chat.grok.GrokAuthAvailability
import org.junit.Assume.assumeTrue

class GrokSignInTest {
    @Test fun initializeOnlyUsesProviderHomeAndDropsSecretsWithoutOpeningASession() {
        val root = Files.createTempDirectory("grok-status-test")
        try {
            val executable = root.resolve("grok")
            Files.writeString(executable, """
                #!/usr/bin/python3
                import json, os, pathlib, sys
                request = json.loads(sys.stdin.readline())
                pathlib.Path(os.environ['HOME'], 'observed.json').write_text(json.dumps({
                    'request': request, 'env': dict(os.environ), 'cwd': os.getcwd(), 'args': sys.argv[1:]}))
                print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':{
                    'protocolVersion':1,'authMethods':[{'id':'cached_token'},{'id':'grok.com'}],
                    'agentCapabilities':{'loadSession':True}}}), flush=True)
                # Any further request would violate the status-only boundary.
                for line in sys.stdin:
                    pathlib.Path(os.environ['HOME'], 'extra-request').write_text(line)
            """.trimIndent())
            executable.toFile().setExecutable(true)
            val env = mapOf("HOME" to root.toString(), "GROK_HOME" to root.resolve("provider-home").toString(),
                "XAI_API_KEY" to "fixture-secret", "GROK_API_KEY" to "fixture-secret", "GROK_AGENT_METADATA" to "fixture-secret",
                "GROK_API_BASE_URL" to "https://example.invalid", "BROWSER" to "/unexpected/browser")
            assertEquals(GrokAuthAvailability.CACHED_CREDENTIAL, readGrokSignIn(executable, env))
            val observed = Json.parseToJsonElement(Files.readString(root.resolve("observed.json"))).jsonObject
            assertEquals(JsonPrimitive("initialize"), observed["request"]!!.jsonObject["method"])
            assertEquals(JsonPrimitive(false), observed["request"]!!.jsonObject["params"]!!.jsonObject["clientCapabilities"]!!.jsonObject["terminal"])
            assertEquals(JsonArray(listOf("--no-auto-update", "agent", "--no-leader", "stdio").map(::JsonPrimitive)), observed["args"])
            val child = observed["env"]!!.jsonObject
            assertEquals(JsonPrimitive(env.getValue("GROK_HOME")), child["GROK_HOME"])
            assertEquals(JsonPrimitive("/usr/bin/false"), child["BROWSER"])
            assertEquals(JsonPrimitive("0"), child["GROK_CLAUDE_MCPS_ENABLED"])
            assertFalse(child.toString().contains("fixture-secret"))
            assertFalse(child.containsKey("GROK_API_BASE_URL"))
            val cwd = Path.of(observed["cwd"]!!.jsonPrimitive.content)
            assertNotEquals(root, cwd)
            assertFalse(Files.exists(cwd), "Temporary working folder is removed after cleanup")
            assertFalse(Files.exists(root.resolve("extra-request")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun incompatibleAndStalledRuntimesAreUnknownRatherThanSignedOut() {
        val root = Files.createTempDirectory("grok-status-failure")
        try {
            val executable = root.resolve("grok")
            for (body in listOf("echo 'not-json'", "sleep 10")) {
                Files.writeString(executable, "#!/bin/sh\n$body\n")
                executable.toFile().setExecutable(true)
                assertEquals(GrokAuthAvailability.UNKNOWN,
                    readGrokSignIn(executable, mapOf("HOME" to root.toString()), 150.milliseconds))
            }
            assertEquals(GrokAuthAvailability.UNKNOWN, readGrokSignIn(root.resolve("missing"), emptyMap()))
        } finally { root.toFile().deleteRecursively() }
    }
}

/** Explicit opt-in uses the player's signed-in runtime, without authentication or inference. */
class GrokSignInSmokeTest {
    @Test fun installedRuntimeReportsSavedSignInThroughProductionReader() {
        val executable = System.getenv("HOVEL_GROK_SIGNED_IN_SMOKE")
        assumeTrue("Set HOVEL_GROK_SIGNED_IN_SMOKE to a signed-in Grok executable", executable != null)
        assertEquals(GrokAuthAvailability.CACHED_CREDENTIAL, readGrokSignIn(Path.of(requireNotNull(executable)), System.getenv()))
        println("GROK_SIGN_IN_SMOKE: saved sign-in detected; no authentication/session/prompt sent")
    }
}
