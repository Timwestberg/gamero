package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

class GrokReadinessTest {
    @Test fun onlyPersonalOidcSubscriptionWithExplicitNoGatePasses() {
        verifyGrokSubscription(subscription())
        for ((key, value) in listOf(
            "auth_mode" to JsonPrimitive("ApiKey"), "backend_billed" to JsonPrimitive(true),
            "is_team_principal" to JsonPrimitive(true), "is_organization_principal" to JsonPrimitive(true),
            "gate" to buildJsonObject { put("message", "Upgrade") },
            "subscription_tier" to JsonPrimitive("Free"), "subscription_tier" to JsonPrimitive(""),
        )) assertFails { verifyGrokSubscription(JsonObject(subscription() + (key to value))) }
        for (key in listOf("auth_mode", "backend_billed", "gate", "subscription_tier", "is_team_principal", "is_organization_principal"))
            assertFails { verifyGrokSubscription(JsonObject(subscription() - key)) }
    }

    @Test fun identityIsBoundedAndFingerprintedWithPrincipalAndAccountScope() {
        val info = identity()
        val fingerprint = grokAccountFingerprint(info)
        assertTrue(Regex("[0-9a-f]{64}").matches(fingerprint))
        assertEquals(fingerprint, grokAccountFingerprint(JsonObject(info + ("email" to JsonPrimitive("ignored@example.invalid")))))
        for (key in listOf("principalType", "principalId", "teamId", "organizationId"))
            assertNotEquals(fingerprint, grokAccountFingerprint(JsonObject(info + (key to JsonPrimitive("different")))))
        assertFails { grokAccountFingerprint(JsonObject(info - "principalId")) }
        assertFails { grokAccountFingerprint(JsonObject(info + ("principalId" to JsonPrimitive("x".repeat(257))))) }
        assertFails { grokAccountFingerprint(JsonObject(info + ("userBlockedReason" to JsonPrimitive("blocked")))) }
    }

    @Test fun catalogDoesNotAcceptDuplicatesUnknownDefaultsOrUnboundedIds() {
        val valid = json("""{"currentModelId":"grok-4.7","availableModels":[{"modelId":"grok-4.7","name":"Grok 4.7"}]}""")
        assertEquals("grok-4.7", grokModels(valid).first)
        assertTrue(grokModels(valid).second.single().isDefault)
        assertFails { grokModels(JsonObject(valid + ("currentModelId" to JsonPrimitive("missing")))) }
        val model = valid["availableModels"]!!.jsonArray.single()
        assertFails { grokModels(JsonObject(valid + ("availableModels" to JsonArray(listOf(model, model))))) }
        assertFails { grokModels(json("""{"currentModelId":"runtime-default","availableModels":[{"modelId":"runtime-default","name":"Default"}]}""")) }
    }

    @Test fun inspectionFailsOnAmbientCapabilitiesWarningsPoliciesOrMissingFields() {
        val clean = inspection()
        verifyGrokInspection(clean)
        for (key in listOf("projectInstructions", "hooks", "skills", "plugins", "mcpServers", "lspServers")) {
            assertFails { verifyGrokInspection(JsonObject(clean - key)) }
            assertFails { verifyGrokInspection(JsonObject(clean + (key to JsonArray(listOf(JsonPrimitive("ambient")))))) }
        }
        assertFails { verifyGrokInspection(JsonObject(clean + ("configWarnings" to JsonArray(listOf(JsonPrimitive("bad-config")))))) }
        assertFails { verifyGrokInspection(JsonObject(clean + ("configSources" to json("""{"layers":[{"role":"mdm"}]}""")))) }
        assertFails { verifyGrokInspection(JsonObject(clean + ("permissions" to json("""{"managedSettingsExists":true}""")))) }
    }

    @Test fun toolEvidenceMustArriveForTheExactSessionAndNeverRecoversFromUnsafe() {
        val evidence = GrokToolEvidence()
        evidence.observe(toolUpdate("other", emptyList()))
        assertFails { evidence.requireAllowed("current", 10.milliseconds) }
        evidence.observe(toolUpdate("current", emptyList()))
        evidence.requireAllowed("current", 10.milliseconds)
        evidence.observe(toolUpdate("other", listOf("shell")))
        assertFails { evidence.observe(toolUpdate("current", listOf("shell"))) }
        evidence.observe(toolUpdate("current", emptyList()))
        assertFails { evidence.requireAllowed("current", 10.milliseconds) }
        val early = GrokToolEvidence()
        early.observe(toolUpdate("current", listOf("shell")))
        early.observe(toolUpdate("current", emptyList()))
        assertFails { early.requireAllowed("current", 10.milliseconds) }
    }

    @Test fun relaunchKeepsOnlySessionDataAndCleanupNeverFollowsItsLink() {
        val root = Files.createTempDirectory("grok-persistence-test")
        try {
            val parent = mapOf("HOME" to root.toString())
            val persistent = root.resolve("persistent")
            val first = GrokLaunch.create(Path.of("/bin/false"), parent, "Persona", root.resolve("system"), persistent)
            val home = Path.of(first.agentBuilder().environment().getValue("GROK_HOME"))
            Files.writeString(home.resolve("sessions/retained.json"), "conversation")
            Files.writeString(home.resolve("downloaded-cache"), "disposable")
            first.close(); first.close()
            assertEquals("conversation", Files.readString(persistent.resolve("sessions/retained.json")))
            GrokLaunch.create(Path.of("/bin/false"), parent, "Persona", root.resolve("system"), persistent).use { next ->
                val fresh = Path.of(next.agentBuilder().environment().getValue("GROK_HOME"))
                assertFalse(Files.exists(fresh.resolve("downloaded-cache")))
                assertEquals("conversation", Files.readString(fresh.resolve("sessions/retained.json")))
                assertEquals(first.workspace, next.workspace)
                assertNotEquals(first.folder, next.folder)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun isolatedLaunchReferencesAuthButNeverCopiesCredentialsAndRefusesManagedHomes() {
        val root = Files.createTempDirectory("grok-launch-test")
        try {
            val provider = Files.createDirectory(root.resolve("provider"))
            val credential = Files.writeString(provider.resolve("auth.json"), "DO NOT READ OR COPY")
            val parent = mapOf("HOME" to root.toString(), "GROK_HOME" to provider.toString(), "XAI_API_KEY" to "secret", "GROK_ENDPOINT" to "secret")
            val launch = GrokLaunch.create(Path.of("/bin/false"), parent, "Persona instructions", root.resolve("system"))
            val folder = launch.folder
            launch.use {
                val builder = it.agentBuilder()
                assertEquals(credential.toString(), builder.environment()["GROK_AUTH_PATH"])
                assertNotEquals(root.toString(), builder.environment()["HOME"])
                assertFalse(builder.environment().containsKey("XAI_API_KEY"))
                assertFalse(builder.environment().containsKey("GROK_ENDPOINT"))
                assertFalse(Files.exists(Path.of(builder.environment().getValue("GROK_HOME")).resolve("auth.json")))
                assertEquals("DO NOT READ OR COPY", Files.readString(credential))
                Files.writeString(provider.resolve("requirements.toml"), "")
                assertFails { it.checkPolicies() }
            }
            assertFalse(Files.exists(folder))
            assertFails { GrokLaunch.create(Path.of("/bin/false"), parent, "Persona", root.resolve("system")) }
            assertEquals("DO NOT READ OR COPY", Files.readString(credential))
        } finally { root.toFile().deleteRecursively() }
    }
}

/** Explicit account opt-in: no prompts unless HOVEL_GROK_TALK_PROMPT_SMOKE is also set. */
class GrokTalkSessionSmokeTest {
    @Test fun installedRuntimeExcludesSkillsOnDiskAndRejectsEmptyStoreWithoutInteractiveAuth() {
        val executable = System.getenv("HOVEL_GROK_TALK_SMOKE")
        assumeTrue("Set HOVEL_GROK_TALK_SMOKE for the installed-runtime control checks", executable != null)
        val root = Files.createTempDirectory("grok-empty-login-smoke")
        val launch = GrokLaunch.create(Path.of(requireNotNull(executable)), System.getenv() +
            ("GROK_AUTH_PATH" to root.resolve("missing-auth.json").toString()), "Control check")
        var rpc: GrokAcp? = null
        try {
            val home = Path.of(launch.agentBuilder().environment().getValue("GROK_HOME"))
            val skill = Files.createDirectories(home.resolve("skills/hovel-isolation-fixture"))
            Files.writeString(skill.resolve("SKILL.md"), "---\nname: hovel-isolation-fixture\ndescription: Must stay excluded\n---\nUnexpected instructions")
            launch.verify()
            val transport = GrokAcp(launch.agentBuilder(), GROK_ACCOUNT_METHODS, allowReloadResponses = true)
            rpc = transport
            assertEquals(GrokAuthAvailability.SIGNED_OUT, grokHandshake(transport.call("initialize", grokInitialize())).auth)
            val error = assertFailsWith<GrokRequestRejected> {
                transport.call("authenticate", buildJsonObject { put("methodId", "cached_token"); putJsonObject("_meta") { put("headless", true) } })
            }
            assertEquals(-32000, error.code)
            println("GROK_CONTROL_SMOKE: on-disk skill excluded; empty credential store rejected without client requests")
        } finally { launch.closeAfter(rpc); root.toFile().deleteRecursively() }
    }

    @Test fun installedRuntimeEstablishesAnAuthenticatedToolFreeSession() {
        val executable = System.getenv("HOVEL_GROK_TALK_SMOKE")
        assumeTrue("Set HOVEL_GROK_TALK_SMOKE to the signed-in runtime", executable != null)
        val updates = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        GrokTalkSession.open(Path.of(requireNotNull(executable)), System.getenv(),
            "You are the Hovel integration test companion. Answer concisely.", onUpdate = { updates += it }).use { session ->
            assertTrue(session.account.models.isNotEmpty())
            assertTrue(updates.any { it["update"]?.jsonObject?.get("sessionUpdate") == JsonPrimitive("available_commands_update") })
            if (System.getenv("HOVEL_GROK_TALK_PROMPT_SMOKE") == "1") {
                val outcome = session.prompt("Reply with exactly HOVEL_GROK_OK.", 60.seconds)
                var state = GrokTurnState(session.sessionId)
                updates.forEach { state = state.update(it) }
                state = state.finish(outcome)
                assertEquals(GrokTurnEnd.COMPLETE, state.end)
                assertEquals("HOVEL_GROK_OK", state.text.trim())
                println("GROK_TALK_SMOKE: authenticated, zero tools, one successful reply")
            } else println("GROK_TALK_SMOKE: authenticated, zero tools, no prompt sent")
        }
    }

    @Test fun installedRuntimeReloadsPrivateConversationAndCancelsAnActivePrompt() {
        val executable = System.getenv("HOVEL_GROK_TALK_SMOKE")
        assumeTrue("Enable HOVEL_GROK_LIFECYCLE_SMOKE for the two-prompt reload and cancellation check",
            executable != null && System.getenv("HOVEL_GROK_LIFECYCLE_SMOKE") == "1")
        val folder = Files.createTempDirectory("grok-resume-smoke")
        val updates = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        val instructions = "You are the Hovel integration test companion. Follow short exact-output requests."
        try {
            val saved = GrokTalkSession.open(Path.of(requireNotNull(executable)), System.getenv(), instructions,
                persistentFolder = folder, onUpdate = { updates += it }).use { session ->
                assertEquals(JsonPrimitive("end_turn"), session.prompt("Remember HOVEL_ORCHID. Reply exactly SAVED.")["stopReason"])
                Triple(session.sessionId, session.account.fingerprint, session.model)
            }
            GrokTalkSession.open(Path.of(executable), System.getenv(), instructions, saved.third, persistentFolder = folder,
                resume = saved.first, expectedAccount = saved.second, onUpdate = { updates += it }).use { session ->
                updates.clear()
                val outcome = session.prompt("What word did I ask you to remember? Reply with that word only.")
                var state = GrokTurnState(session.sessionId)
                updates.forEach { state = state.update(it) }
                state = state.finish(outcome)
                assertEquals(GrokTurnEnd.COMPLETE, state.end)
                assertEquals("HOVEL_ORCHID", state.text.trim())
            }
            val active = java.util.concurrent.CountDownLatch(1)
            GrokTalkSession.open(Path.of(executable), System.getenv(), instructions, onUpdate = { params ->
                if (params["update"]?.jsonObject?.get("sessionUpdate") in listOf(JsonPrimitive("agent_message_chunk"), JsonPrimitive("agent_thought_chunk")))
                    active.countDown()
            }).use { session ->
                val reply = java.util.concurrent.CompletableFuture.supplyAsync {
                    session.prompt("Print the integers from 1 to 10000, one per line, with no omissions.")
                }
                assertTrue(active.await(30, java.util.concurrent.TimeUnit.SECONDS), "No active generation observed")
                session.cancel()
                assertEquals(JsonPrimitive("cancelled"), reply.get(5, java.util.concurrent.TimeUnit.SECONDS)["stopReason"])
            }
            println("GROK_LIFECYCLE_SMOKE: private session reloaded, context retained, active prompt cancelled within five seconds")
        } finally { folder.toFile().deleteRecursively() }
    }
}

private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
private fun subscription() = json("""{"auth_mode":"Oidc","backend_billed":false,"is_team_principal":false,"is_organization_principal":false,"team_id":null,"gate":null,"subscription_tier":"SuperGrok"}""")
private fun identity() = json("""{"methodId":"cached_token","principalType":"user","principalId":"fixture-user","teamId":null,"organizationId":null,"userBlockedReason":null,"teamBlockedReasons":[]}""")
private fun inspection() = json("""{"projectInstructions":[],"hooks":[],"skills":[],"plugins":[],"mcpServers":[],"lspServers":[],"configSources":{"layers":[{"role":"user"}]},"permissions":{"managedSettingsExists":false}}""")
private fun toolUpdate(session: String, tools: List<String>) = buildJsonObject {
    put("sessionId", session)
    putJsonObject("update") {
        put("sessionUpdate", "available_commands_update")
        putJsonObject("_meta") { put("tools", JsonArray(tools.map(::JsonPrimitive))) }
    }
}
