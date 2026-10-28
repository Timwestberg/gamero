package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.milliseconds
import org.formulagen.wrapper.bridge.ProviderDetail
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.bridge.RuntimeId
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

    @Test fun claudeIsOfferedOnlyByADevelopmentBuildThatAsksForIt() {
        val asked = mapOf("HOVEL_CLAUDE_PROVIDER" to "1")
        assertTrue(claudeProviderEnabled(asked, release = false, development = true))
        assertFalse(claudeProviderEnabled(asked, release = false, development = false), "An unmarked build fails closed")
        assertFalse(claudeProviderEnabled(asked, release = true, development = true), "A release build never offers it")
        assertFalse(claudeProviderEnabled(mapOf("HOVEL_CLAUDE_PROVIDER" to "true"), release = false, development = true))
        assertFalse(claudeProviderEnabled(emptyMap(), release = false, development = true))
    }

    @Test fun eachInstallAndSignInMapsToOneCardState() {
        val current = MIN_CLAUDE_VERSION
        fun status(state: ProviderState, detail: ProviderDetail, message: String?) = ProviderStatus(RuntimeId.CLAUDE_CODE, state, message = message, detail = detail)
        assertEquals(status(ProviderState.UNAVAILABLE, ProviderDetail.NOT_INSTALLED, "Install Claude Code on this Mac, then restart Hovel to use Claude."),
            claudeProviderStatus(false, null, null))
        assertEquals(status(ProviderState.UNAVAILABLE, ProviderDetail.OUTDATED, "Update Claude Code to use it here. Hovel needs version 2.1.304 or later."),
            claudeProviderStatus(true, ClaudeVersion(2, 1, 303), subscription))
        val unknown = status(ProviderState.ERROR, ProviderDetail.UNKNOWN, "Claude status could not be checked.")
        assertEquals(unknown, claudeProviderStatus(true, null, subscription), "An unreadable version is unknown, not outdated")
        assertEquals(unknown, claudeProviderStatus(true, current, null))
        // The page owns the sign-in help for a signed-out card.
        assertEquals(status(ProviderState.DISCONNECTED, ProviderDetail.SIGNED_OUT, null), claudeProviderStatus(true, current, ClaudeAuthStatus(false)))
        assertEquals(status(ProviderState.ERROR, ProviderDetail.API_KEY, "Claude Code is using an API key. Sign in with your Claude subscription to use it here."),
            claudeProviderStatus(true, current, ClaudeAuthStatus(true, "api_key")))
        assertEquals(ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.CONNECTED, accountLabel = "player@example.test", plan = "max"), claudeProviderStatus(true, current, subscription))
        // The page names the plan; the host passes Claude Code's own value.
        assertEquals("max_20x", claudeProviderStatus(true, current, subscription.copy(subscriptionType = "max_20x")).plan)
        val long = claudeProviderStatus(true, current, subscription.copy(email = "x".repeat(321), subscriptionType = null))
        assertEquals(ProviderState.CONNECTED, long.state); assertNull(long.accountLabel); assertNull(long.plan)
        assertEquals(ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.UNAVAILABLE, message = "Claude is not available in this version of Hovel.",
            detail = ProviderDetail.NOT_OFFERED), notOfferedStatus(RuntimeId.CLAUDE_CODE))
    }

    @Test fun oneCheckAnswersAgainForAFewSecondsAndASignedOutReplyMayLeaveOutItsMethod() {
        val fake = FakeClaude()
        var now = java.time.Instant.EPOCH
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val connection = ClaudeConnection(fake.runtime(), clock = clock)
        assertEquals(ProviderState.CONNECTED, connection.status().state)
        fake.scenario(auth = """{"loggedIn":false}""", authExit = 1)
        assertEquals(ProviderState.CONNECTED, connection.status().state)
        assertEquals(2, fake.calls().size)
        now = now.plusMillis(CLAUDE_STATUS_TTL.inWholeMilliseconds)
        assertEquals(ProviderDetail.SIGNED_OUT, connection.status().detail)
        assertEquals(4, fake.calls().size)
    }

    @Test fun statusReadsOnlyVersionAndAuthStatusAndNeverSignsInOrOut() {
        val fake = FakeClaude()
        val connection = ClaudeConnection(fake.runtime(), timeout = 2_000.milliseconds, ttl = kotlin.time.Duration.ZERO)
        assertEquals(ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.CONNECTED, accountLabel = "player@example.test", plan = "max"), connection.status())
        fake.scenario(auth = FakeClaude.fixture("auth-status-signed-out.json"), authExit = 1)
        assertEquals(ProviderState.DISCONNECTED, connection.status().state)
        fake.scenario(auth = FakeClaude.fixture("auth-status-api-key.json"))
        assertEquals("Claude Code is using an API key. Sign in with your Claude subscription to use it here.", connection.status().message)
        // Output that disagrees with the exit code, an odd exit, or text that is not the JSON is unknown, never "signed out".
        for ((auth, exit) in listOf(FakeClaude.fixture("auth-status-subscription.json") to 1, "Not logged in" to 1, "{}" to 0, FakeClaude.fixture("auth-status-subscription.json") to 2)) {
            fake.scenario(auth = auth, authExit = exit)
            assertEquals(ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.ERROR, message = "Claude status could not be checked.", detail = ProviderDetail.UNKNOWN),
                connection.status(), auth)
        }
        fake.scenario(version = "2.1.200 (Claude Code)")
        assertEquals(ProviderState.UNAVAILABLE, connection.status().state)
        val calls = fake.calls().size
        fake.scenario(version = "garbage")
        assertEquals(ProviderDetail.UNKNOWN, connection.status().detail, "An unreadable version is unknown, not outdated")
        assertEquals(calls + 1, fake.calls().size, "An unusable version is not asked for its sign-in")

        val started = System.nanoTime()
        fake.scenario(authSleepSeconds = 5.0)
        assertEquals(ProviderDetail.UNKNOWN, ClaudeConnection(fake.runtime(), timeout = 300.milliseconds).status().detail)
        assertTrue(System.nanoTime() - started < 4_000_000_000)

        val argv = fake.calls().map { it.argv() }.toSet()
        assertEquals(setOf(listOf("--version"), listOf("auth", "status", "--json")), argv)
        assertTrue(fake.calls().all { it.getValue("cwd").toString().contains("claude-code") })
        assertTrue(fake.calls().all { call -> call.env().keys.none { it.startsWith("ANTHROPIC_") } })

        Files.delete(fake.executable)
        assertEquals(ProviderState.UNAVAILABLE, connection.status().state)
        assertEquals(ProviderDetail.NOT_INSTALLED, ClaudeConnection(null).status().detail)
    }
}
