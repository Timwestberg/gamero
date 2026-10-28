package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.milliseconds
import org.formulagen.wrapper.bridge.ProviderState
import kotlin.test.*

class ClaudeProviderTest {
    private val subscription = ClaudeAuthStatus(loggedIn = true, authMethod = "claude.ai", subscriptionType = "max", email = "player@example.test")

    @Test fun versionsParseAndCompareByPart() {
        assertEquals(ClaudeVersion(2, 1, 304), ClaudeVersion.parse("2.1.304 (Claude Code)\n"))
        assertEquals(ClaudeVersion(10, 0, 0), ClaudeVersion.parse("10.0.0"))
        assertNull(ClaudeVersion.parse("Claude Code 2.1.304")); assertNull(ClaudeVersion.parse("2.1")); assertNull(ClaudeVersion.parse(""))
        assertTrue(ClaudeVersion(2, 1, 303) < MIN_CLAUDE_VERSION)
        assertTrue(ClaudeVersion(2, 2, 0) > MIN_CLAUDE_VERSION && ClaudeVersion(10, 0, 0) > MIN_CLAUDE_VERSION)
        assertEquals("2.1.304", MIN_CLAUDE_VERSION.toString())
    }

    @Test fun theChildEnvironmentIsAnAllowlist() {
        val executable = Path.of("/opt/homebrew/bin/claude")
        assertEquals(mapOf(
            "HOME" to System.getProperty("user.home"), "USER" to "player", "LOGNAME" to "player", "LANG" to "en_US.UTF-8",
            "PATH" to "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1", "CLAUDE_CODE_DISABLE_AUTO_MEMORY" to "1",
        ), claudeChildEnvironment(FakeClaude.LEAKY_PARENT - "TMPDIR", executable))
        assertEquals("/usr/bin:/bin:/usr/sbin:/sbin", claudeChildEnvironment(emptyMap(), Path.of("/usr/bin/claude"))["PATH"])
        assertEquals(System.getProperty("user.home"), claudeChildEnvironment(emptyMap(), executable)["HOME"])

        // The builder starts from an empty environment, not the JVM's.
        val fake = FakeClaude()
        val built = fake.runtime().builder(listOf("--version"), fake.dir).environment()
        assertEquals(claudeChildEnvironment(FakeClaude.LEAKY_PARENT, fake.executable), built.toMap())
        assertFalse(built.keys.any { it.startsWith("ANTHROPIC_") || it == "CLAUDE_CODE_ENTRYPOINT" || it == "CLAUDE_CONFIG_DIR" || it == "OPENAI_API_KEY" })
    }

    @Test fun discoveryPrefersConfigurationThenPathThenTheInstallerFolder() {
        val root = Files.createTempDirectory("claude-find")
        fun install(path: Path) = path.also { Files.createDirectories(it.parent); Files.writeString(it, "#!/bin/sh\n"); Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
        val configured = install(root.resolve("configured/claude"))
        val onPath = install(root.resolve("bin/claude"))
        val installer = install(root.resolve("home/.local/bin/claude"))
        val none = emptyList<String>()
        assertEquals(configured, findClaudeExecutable(mapOf("GAMERO_CLAUDE_PATH" to configured.toString(), "PATH" to onPath.parent.toString()), none))
        assertNull(findClaudeExecutable(mapOf("GAMERO_CLAUDE_PATH" to root.resolve("missing").toString(), "PATH" to onPath.parent.toString()), none))
        assertEquals(onPath, findClaudeExecutable(mapOf("PATH" to "relative:${onPath.parent}", "HOME" to root.resolve("home").toString()), none))
        assertEquals(installer, findClaudeExecutable(mapOf("PATH" to "", "HOME" to root.resolve("home").toString()), none))
        assertEquals(onPath, findClaudeExecutable(mapOf("HOME" to root.resolve("nobody").toString()), listOf(onPath.toString())))
        assertNull(findClaudeExecutable(mapOf("HOME" to root.resolve("nobody").toString()), none))
    }

    @Test fun releaseBuildsNeverOfferClaude() {
        assertTrue(claudeProviderEnabled(mapOf("HOVEL_CLAUDE_PROVIDER" to "1"), release = false))
        assertFalse(claudeProviderEnabled(mapOf("HOVEL_CLAUDE_PROVIDER" to "1"), release = true))
        assertFalse(claudeProviderEnabled(mapOf("HOVEL_CLAUDE_PROVIDER" to "true"), release = false))
        assertFalse(claudeProviderEnabled(emptyMap(), release = false))
    }

    @Test fun eachInstallAndSignInMapsToOneCardState() {
        val current = MIN_CLAUDE_VERSION
        assertEquals(ClaudeProviderStatus(ProviderState.UNAVAILABLE, message = "Install Claude Code on this Mac to use Claude."),
            claudeProviderStatus(false, null, null))
        val outdated = ClaudeProviderStatus(ProviderState.UNAVAILABLE, message = "Update Claude Code to use it here. Hovel needs version 2.1.304 or later.")
        assertEquals(outdated, claudeProviderStatus(true, ClaudeVersion(2, 1, 303), subscription))
        assertEquals(outdated, claudeProviderStatus(true, null, subscription))
        assertEquals(ClaudeProviderStatus(ProviderState.ERROR, message = "Claude status could not be checked."), claudeProviderStatus(true, current, null))
        assertEquals(ClaudeProviderStatus(ProviderState.DISCONNECTED), claudeProviderStatus(true, current, ClaudeAuthStatus(false, "none")))
        assertEquals(ClaudeProviderStatus(ProviderState.ERROR, message = "Claude Code is using an API key. Sign in with your Claude subscription to use it here."),
            claudeProviderStatus(true, current, ClaudeAuthStatus(true, "api_key")))
        assertEquals(ClaudeProviderStatus(ProviderState.CONNECTED, accountLabel = "player@example.test", plan = "Max"), claudeProviderStatus(true, current, subscription))
        assertEquals("Max 20x", claudeProviderStatus(true, current, subscription.copy(subscriptionType = "max_20x")).plan)
        val long = claudeProviderStatus(true, current, subscription.copy(email = "x".repeat(321), subscriptionType = null))
        assertEquals(ProviderState.CONNECTED, long.state); assertNull(long.accountLabel); assertNull(long.plan)
    }

    @Test fun statusReadsOnlyVersionAndAuthStatusAndNeverSignsInOrOut() {
        val fake = FakeClaude()
        val connection = ClaudeConnection(fake.runtime(), timeout = 2_000.milliseconds)
        assertEquals(ClaudeProviderStatus(ProviderState.CONNECTED, accountLabel = "player@example.test", plan = "Max"), connection.status())
        fake.scenario(auth = FakeClaude.fixture("auth-status-signed-out.json"), authExit = 1)
        assertEquals(ProviderState.DISCONNECTED, connection.status().state)
        fake.scenario(auth = FakeClaude.fixture("auth-status-api-key.json"))
        assertEquals("Claude Code is using an API key. Sign in with your Claude subscription to use it here.", connection.status().message)
        // Output that disagrees with the exit code, an odd exit, or text that is not the JSON is unknown, never "signed out".
        for ((auth, exit) in listOf(FakeClaude.fixture("auth-status-subscription.json") to 1, "Not logged in" to 1, "{}" to 0, FakeClaude.fixture("auth-status-subscription.json") to 2)) {
            fake.scenario(auth = auth, authExit = exit)
            assertEquals(ClaudeProviderStatus(ProviderState.ERROR, message = "Claude status could not be checked."), connection.status(), auth)
        }
        fake.scenario(version = "2.1.200 (Claude Code)")
        assertEquals(ProviderState.UNAVAILABLE, connection.status().state)
        val calls = fake.calls().size
        fake.scenario(version = "garbage")
        assertEquals(ProviderState.UNAVAILABLE, connection.status().state)
        assertEquals(calls + 1, fake.calls().size, "An unusable version is not asked for its sign-in")

        val started = System.nanoTime()
        fake.scenario(authSleepSeconds = 5.0)
        assertEquals(ProviderState.ERROR, ClaudeConnection(fake.runtime(), timeout = 300.milliseconds).status().state)
        assertTrue(System.nanoTime() - started < 4_000_000_000)

        val argv = fake.calls().map { it.argv() }.toSet()
        assertEquals(setOf(listOf("--version"), listOf("auth", "status", "--json")), argv)
        assertTrue(fake.calls().all { it.getValue("cwd").toString().contains("claude-code") })
        assertTrue(fake.calls().all { call -> call.env().keys.none { it.startsWith("ANTHROPIC_") } })

        Files.delete(fake.executable)
        assertEquals(ProviderState.UNAVAILABLE, connection.status().state)
        assertEquals("Install Claude Code on this Mac to use Claude.", ClaudeConnection(null).status().message)
    }
}
