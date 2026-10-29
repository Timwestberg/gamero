package org.formulagen.wrapper.providers.grok

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class GrokReadinessTest {
    private fun handshake(vararg ids: String) = buildJsonObject {
        put("protocolVersion", 1)
        putJsonArray("authMethods") { ids.forEach { add(buildJsonObject { put("id", it) }) } }
        putJsonObject("agentCapabilities") { put("loadSession", true) }
    }

    @Test fun anEmptyHomeAdvertisesInteractiveLoginWithoutImplyingAnUnsupportedProtocol() {
        assertEquals(GrokHandshake(GrokAuthAvailability.SIGNED_OUT, true), grokHandshake(handshake("grok.com")))
        assertEquals(GrokAuthAvailability.CACHED_CREDENTIAL, grokHandshake(handshake("cached_token", "grok.com")).auth)
        assertEquals(GrokAuthAvailability.API_KEY, grokHandshake(handshake("xai.api_key", "grok.com")).auth)
        assertEquals(GrokAuthAvailability.UNKNOWN, grokHandshake(handshake("future-auth")).auth)
        assertEquals(GrokAuthAvailability.UNKNOWN, grokHandshake(handshake()).auth)
    }

    @Test fun malformedHandshakeNeverEstablishesAUsableConnection() {
        assertFails { grokHandshake(JsonObject(handshake("grok.com") + ("protocolVersion" to JsonPrimitive(2)))) }
        assertFails { grokHandshake(handshake("cached_token", "cached_token")) }
        assertFails { grokHandshake(handshake("x".repeat(129))) }
        assertFails { grokHandshake(handshake(*Array(9) { "method-$it" })) }
        assertFails { grokHandshake(buildJsonObject { put("protocolVersion", 1) }) }
        assertFails { grokHandshake(JsonObject(handshake() + ("agentCapabilities" to buildJsonObject { put("loadSession", "true") }))) }
    }

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
        verifyGrokInspection(clean, isolatedConfig)
        for (key in listOf("projectInstructions", "hooks", "skills", "plugins", "mcpServers", "lspServers")) {
            assertFails { verifyGrokInspection(JsonObject(clean - key), isolatedConfig) }
            assertFails { verifyGrokInspection(JsonObject(clean + (key to JsonArray(listOf(JsonPrimitive("ambient"))))), isolatedConfig) }
        }
        assertFails { verifyGrokInspection(JsonObject(clean + ("configWarnings" to JsonArray(listOf(JsonPrimitive("bad-config"))))), isolatedConfig) }
        assertFails { verifyGrokInspection(JsonObject(clean + ("configSources" to json("""{"layers":[{"role":"mdm"}]}"""))), isolatedConfig) }
        assertFails { verifyGrokInspection(JsonObject(clean + ("permissions" to json("""{"managedSettingsExists":true}"""))), isolatedConfig) }
    }

    @Test fun inspectionProvesTheIsolatedConfigurationIsTheOnlyOneLoaded() {
        val user = """{"role":"user","path":"$isolatedConfig"}"""
        fun layers(vararg entries: String) = JsonObject(inspection() + ("configSources" to json("""{"layers":[${entries.joinToString(",")}]}""")))
        verifyGrokInspection(layers(user), isolatedConfig)
        verifyGrokInspection(layers("""{"role":"user","path":"/isolated/home/./.grok/config.toml"}"""), isolatedConfig)
        for (refused in listOf(
            layers(),
            layers("""{"role":"project","note":"empty"}"""),
            layers("""{"role":"user","note":"empty"}"""),
            layers(user, """{"role":"project","note":"empty"}"""),
            layers(user, user),
            layers("""{"role":"user"}"""),
            layers("""{"role":"user","path":"/Users/player/.grok/config.toml"}"""),
            layers("""{"role":"system","path":"$isolatedConfig"}"""),
            layers("""{"role":"user","path":"$isolatedConfig","note":"overridden"}"""),
            layers("""{"role":"user","path":7}"""),
        )) assertFails { verifyGrokInspection(refused, isolatedConfig) }
        assertFails { verifyGrokInspection(JsonObject(inspection() - "configSources"), isolatedConfig) }
    }

    @Test fun aConfigurationPathThroughASymbolicLinkIsTheSameFile() {
        val root = Files.createTempDirectory("grok-config-link")
        try {
            val real = Files.writeString(Files.createDirectories(root.resolve("real/.grok")).resolve("config.toml"), "")
            val link = Files.createSymbolicLink(root.resolve("linked"), root.resolve("real"))
            val report = JsonObject(inspection() + ("configSources" to json("""{"layers":[{"role":"user","path":"$real"}]}""")))
            verifyGrokInspection(report, link.resolve(".grok/config.toml"))
            assertFails { verifyGrokInspection(report, root.resolve("other/.grok/config.toml")) }
        } finally { root.toFile().deleteRecursively() }
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
                assertFails { it.recheck() }
            }
            assertFalse(Files.exists(folder))
            assertFails { GrokLaunch.create(Path.of("/bin/false"), parent, "Persona", root.resolve("system")) }
            assertEquals("DO NOT READ OR COPY", Files.readString(credential))
        } finally { root.toFile().deleteRecursively() }
    }
}

private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
private fun subscription() = json("""{"auth_mode":"Oidc","backend_billed":false,"is_team_principal":false,"is_organization_principal":false,"team_id":null,"gate":null,"subscription_tier":"SuperGrok"}""")
private fun identity() = json("""{"methodId":"cached_token","principalType":"user","principalId":"fixture-user","teamId":null,"organizationId":null,"userBlockedReason":null,"teamBlockedReasons":[]}""")
private val isolatedConfig = Path.of("/isolated/home/.grok/config.toml")
private fun inspection() = json("""{"projectInstructions":[],"hooks":[],"skills":[],"plugins":[],"mcpServers":[],"lspServers":[],"configSources":{"layers":[{"role":"user","path":"$isolatedConfig"}]},"permissions":{"managedSettingsExists":false}}""")
