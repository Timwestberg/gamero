package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.providers.grokWorkEnabled

class GrokWorkPolicyTest {
    @Test fun authPinUsesParsedTomlAndPreservesOrdinaryPreferences() {
        verifyGrokWorkConfig("theme='dark'\n[auth]\npreferred_method='oidc'\n[skills]\nignore=['vendor']")
        verifyGrokWorkConfig("auth = { preferred_method = 'oidc' }")
        for (invalid in listOf("# [auth]\n# preferred_method='oidc'", "auth.preferred_method='api_key'",
            "note=\"\"\"\n[auth]\npreferred_method='oidc'\n\"\"\"", "auth.preferred_method='oidc'\nauth.preferred_method='api_key'"))
            assertFails { verifyGrokWorkConfig(invalid) }
    }

    @Test fun ambiguousAuthModelsEndpointsAndExecutionPrefixesAreRefused() {
        for (extra in listOf("[model.custom]\nbase_url='https://example.test'", "[auth_provider.custom]\ncommand='anything'",
            "[grok_com_config]\npreferred_method='api_key'", "[[version_overrides]]\nversion='*'",
            "[relay]\nenabled=true", "[toolset.bash]\ncmd_prefix='anything'", "[endpoints]\nproxy_url='https://example.test'"))
            assertFails { verifyGrokWorkConfig("[auth]\npreferred_method='oidc'\n$extra") }
    }

    @Test fun inspectionAllowsRulesAndSkillsButNeverIgnoresConfigurationOrStartupIntegrations() {
        val config = Path.of("/home/player/.grok/config.toml")
        val report = buildJsonObject {
            put("projectTrusted", true)
            for (key in listOf("hooks", "plugins", "mcpServers", "lspServers")) putJsonArray(key) {}
            putJsonArray("skills") { add("local skill") }; putJsonArray("projectInstructions") { add("AGENTS.md") }
            putJsonObject("configSources") { putJsonArray("layers") { add(buildJsonObject { put("role", "user"); put("path", config.toString()) }) } }
            putJsonObject("permissions") { put("managedSettingsExists", false) }
        }
        verifyGrokWorkInspection(report, config)
        for (key in listOf("hooks", "plugins", "mcpServers", "lspServers", "configWarnings"))
            assertFails { verifyGrokWorkInspection(JsonObject(report + (key to buildJsonArray { add("present") })), config) }
        assertFails { verifyGrokWorkInspection(JsonObject(report - "configSources"), config) }
        assertFails { verifyGrokWorkInspection(report, Path.of("/different/config.toml")) }
    }

    @Test fun theWorkingEnvironmentIsThePlayersWithTheirLoginPathAndOnlyHovelsGrokSettings() {
        val finder = mapOf("HOME" to "/Users/player", "USER" to "player", "LOGNAME" to "player", "SHELL" to "/bin/zsh",
            "TMPDIR" to "/var/folders/xy/T/", "LANG" to "en_US.UTF-8", "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin",
            "__CFBundleIdentifier" to "com.formulagen.hovel", "XAI_API_KEY" to "secret", "XAI_BASE_URL" to "https://example.test",
            "GROK_API_KEY" to "secret", "GROK_AGENT_METADATA" to "secret", "GROK_SUBAGENTS" to "1", "GROK_HOME" to "/Users/player/.grok-work")
        val grok = Path.of("/Users/player/.grok/bin/grok")
        val env = grokWorkEnvironment(finder, "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/Users/player/.cargo/bin", grok)
        assertEquals("/Users/player/.grok/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/Users/player/.cargo/bin:/usr/sbin:/sbin", env["PATH"])
        for (key in listOf("HOME", "USER", "LOGNAME", "SHELL", "TMPDIR", "LANG", "__CFBundleIdentifier")) assertEquals(finder[key], env[key], key)
        assertEquals("/Users/player/.grok-work", env["GROK_HOME"])
        assertEquals("/Users/player/.grok-work/auth.json", env["GROK_AUTH_PATH"])
        assertEquals(mapOf("GROK_HOME" to "/Users/player/.grok-work", "GROK_AUTH_PATH" to "/Users/player/.grok-work/auth.json",
            "GROK_DISABLE_AUTOUPDATER" to "1", "GROK_SUBAGENTS" to "0", "GROK_GOAL" to "0", "GROK_WORKFLOWS" to "0"),
            env.filterKeys { it.startsWith("GROK_") })
        assertTrue(env.keys.none { it.startsWith("XAI_") })
        assertEquals("/usr/bin/false", env["BROWSER"])
        // Without a readable login shell, the app's own PATH stays, still with Grok's folder first.
        assertEquals("/Users/player/.grok/bin:/usr/bin:/bin:/usr/sbin:/sbin", grokWorkEnvironment(finder, null, grok)["PATH"])
    }

    @Test fun workRequiresBothOptInsAndNeverRunsInRelease() {
        val enabled = mapOf("HOVEL_GROK_PROVIDER" to "1", "HOVEL_GROK_WORK" to "1")
        assertTrue(grokWorkEnabled(enabled, false, true))
        assertFalse(grokWorkEnabled(enabled, true, true))
        assertFalse(grokWorkEnabled(enabled, false, false))
        for (key in enabled.keys) assertFalse(grokWorkEnabled(enabled - key, false, true))
    }

    @Test fun workActivityCorrelatesSparseUpdatesAndDoesNotLeakToolOutput() {
        val activity = GrokWorkActivity("current", Path.of("/project"))
        fun update(session: String = "current", vararg fields: Pair<String, JsonElement>) = buildJsonObject {
            put("sessionId", session); put("update", JsonObject(mapOf("sessionUpdate" to JsonPrimitive("tool_call_update"),
                "toolCallId" to JsonPrimitive("private-id")) + fields))
        }
        assertNull(activity.update(update("foreign", "status" to JsonPrimitive("completed"))))
        assertFalse(activity.state.accepted)
        assertNull(activity.update(update(fields = arrayOf("kind" to JsonPrimitive("execute"), "rawInput" to buildJsonObject { put("command", "SECRET") }))))
        assertTrue(activity.state.accepted)
        val result = activity.update(update(fields = arrayOf("status" to JsonPrimitive("completed"), "rawOutput" to JsonPrimitive("SECRET"))))
        assertEquals("Run command", result?.steps?.single()?.label)
        assertEquals(ActivityStatus.DONE, result?.steps?.single()?.status)
        assertNull(result?.steps?.single()?.detail)
        assertNull(activity.update(update(fields = arrayOf("status" to JsonPrimitive("completed")))))
        assertEquals(GrokTurnEnd.COMPLETE, activity.finish(buildJsonObject { put("stopReason", "end_turn") }).end)
    }
}
