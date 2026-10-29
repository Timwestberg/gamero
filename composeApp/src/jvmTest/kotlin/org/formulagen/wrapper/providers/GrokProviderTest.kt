package org.formulagen.wrapper.providers

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.providers.grok.GrokAuthAvailability
import org.formulagen.wrapper.providers.grok.findGrokExecutable
import kotlin.test.*

class GrokProviderTest {
    @Test fun unavailableQuotaDoesNotProbeCredentialsOrInventUsage() {
        val provider = GrokProvider(GrokConnection(emptyMap(), probe = { error("Quota must not probe authentication") }))
        val before = System.currentTimeMillis()
        val usage = provider.usage("local")
        assertEquals(RuntimeId.GROK_BUILD, usage.runtimeId)
        assertTrue(usage.checkedAt in before..System.currentTimeMillis())
        assertNull(usage.session)
        assertNull(usage.weekly)
        assertFailsWith<IllegalArgumentException> { provider.usage("../profile") }
    }

    @Test fun theContractFixtureShowsTheSignInDetectedCopyThisHostSends() {
        val fixture = kotlinx.serialization.json.Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue("success-providers-status-grok-detected.json"))
        val result = (fixture as kotlinx.serialization.json.JsonObject).getValue("value").let { it as kotlinx.serialization.json.JsonObject }
            .getValue("result") as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(GROK_SIGN_IN_DETECTED), result["message"])
    }

    @Test fun discoveryAndFreshSignInDetectionNeverClaimExecutionReadiness() {
        val root = Files.createTempDirectory("grok-discovery")
        try {
            val marker = root.resolve("invoked")
            val executable = root.resolve("grok")
            Files.writeString(executable, "#!/bin/sh\ntouch '$marker'\n")
            executable.toFile().setExecutable(true)
            var auth = GrokAuthAvailability.CACHED_CREDENTIAL
            var checks = 0
            val provider = GrokProvider(GrokConnection(mapOf("GAMERO_GROK_PATH" to executable.toString()), probe = {
                assertEquals(executable, it); checks++; GrokAccess(it, auth)
            }))
            for (action in ProviderAction.entries) {
                val status = provider.status("local", action)
                assertEquals(ProviderState.UNAVAILABLE, status.state)
                assertEquals(ProviderDetail.SIGN_IN_DETECTED, status.detail)
                assertEquals(RuntimeProviderId.XAI, status.providerId)
                assertNull(status.accountLabel)
                assertNull(status.plan)
            }
            assertEquals(ProviderAction.entries.size, checks)
            auth = GrokAuthAvailability.SIGNED_OUT
            assertEquals(ProviderDetail.SIGNED_OUT, provider.status("local", ProviderAction.STATUS).detail)
            auth = GrokAuthAvailability.API_KEY
            assertEquals(ProviderDetail.API_KEY, provider.status("local", ProviderAction.STATUS).detail)
            auth = GrokAuthAvailability.UNKNOWN
            assertEquals(ProviderState.ERROR, provider.status("local", ProviderAction.STATUS).state)
            assertEquals(ProviderDetail.UNKNOWN, provider.status("local", ProviderAction.STATUS).detail)
            assertTrue(provider.status("local", ProviderAction.STATUS).message.orEmpty().contains("supports Grok Build 1.0.70."))
            val beforeInvalid = checks
            assertFailsWith<IllegalArgumentException> { provider.status("../profile", ProviderAction.STATUS) }
            assertEquals(beforeInvalid, checks)
            assertTrue(provider.models("local").isEmpty())
            assertFalse(Files.exists(marker))
            assertNull(findGrokExecutable(mapOf("GAMERO_GROK_PATH" to "/missing/grok", "PATH" to root.toString()), emptyList()))
            assertEquals(executable, findGrokExecutable(mapOf("PATH" to root.toString()), emptyList()))
            assertNull(findGrokExecutable(mapOf("GAMERO_GROK_PATH" to "grok"), emptyList()))
            assertEquals(ProviderDetail.NOT_INSTALLED, GrokProvider(GrokConnection(mapOf("GAMERO_GROK_PATH" to "/missing/grok"), probe = {
                error("Missing executable must never be probed")
            })).status("local", ProviderAction.STATUS).detail)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun onlyOptedInClientsSeeGrokAndWorkRemainsUnavailable() = runBlocking {
        DesktopProviders(listOf(GrokProvider(mapOf("GAMERO_GROK_PATH" to "/missing/grok")))).use { providers ->
            assertEquals(ProviderList(emptyList(), emptyList()), providers.list("local"))
            assertEquals(ProviderList(listOf(RuntimeId.GROK_BUILD), emptyList()),
                providers.listForClient(ProviderListForClientArgs("local", listOf("grok-build", "future-runtime"))))
            assertEquals(ProviderList(emptyList(), emptyList()), providers.listForClient(ProviderListForClientArgs("local", listOf("codex"))))
        }
        assertTrue(grokProviderEnabled(mapOf("HOVEL_GROK_PROVIDER" to "1"), release = false, development = true))
        assertFalse(grokProviderEnabled(mapOf("HOVEL_GROK_PROVIDER" to "1"), release = true, development = true))
        assertFalse(grokProviderEnabled(mapOf("HOVEL_GROK_PROVIDER" to "1"), release = false, development = false))
        assertFalse(grokProviderEnabled(emptyMap(), release = false, development = true))
    }

    @Test fun workRequiresBothOptInsAndNeverRunsInRelease() {
        val enabled = mapOf("HOVEL_GROK_PROVIDER" to "1", "HOVEL_GROK_WORK" to "1")
        assertTrue(grokWorkEnabled(enabled, false, true))
        assertFalse(grokWorkEnabled(enabled, true, true))
        assertFalse(grokWorkEnabled(enabled, false, false))
        for (key in enabled.keys) assertFalse(grokWorkEnabled(enabled - key, false, true))
    }
}
