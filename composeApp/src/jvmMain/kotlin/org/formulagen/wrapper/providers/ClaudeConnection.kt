package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.time.Clock
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.process.runBounded

/**
 * Claude Code's one sign-in on this Mac, read with `claude auth status --json`, for the Providers
 * card and for chat readiness alike. It never runs sign-in or sign-out: Claude Code owns both, and
 * signing out from Hovel would sign the player out everywhere. [runtime] is null when Hovel found no
 * Claude Code. A status answers again for [ttl], since several views ask when the window regains focus.
 */
internal class ClaudeConnection(
    private val runtime: ClaudeRuntime?,
    private val timeout: Duration = CLAUDE_STATUS_TIMEOUT,
    private val ttl: Duration = CLAUDE_STATUS_TTL,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var cached: Pair<Long, ProviderStatus>? = null

    /** Read fresh at most once per [ttl], so an update or removal while Hovel runs shows on a later check. */
    @Synchronized fun status(): ProviderStatus {
        val now = clock.millis()
        cached?.takeIf { now - it.first in 0 until ttl.inWholeMilliseconds }?.let { return it.second }
        return read().also { cached = now to it }
    }

    private fun read(): ProviderStatus {
        val runtime = runtime?.takeIf { Files.isExecutable(it.executable) } ?: return claudeProviderStatus(false, null, null)
        val version = runtime.version(timeout) ?: return claudeProviderStatus(true, null, null)
        if (version < MIN_CLAUDE_VERSION) return claudeProviderStatus(true, version, null)
        return claudeProviderStatus(true, version, auth(runtime))
    }

    /** Exit 0 is signed in and 1 signed out; any other exit, a timeout, or output that disagrees with the exit is unknown. */
    private fun auth(runtime: ClaudeRuntime): ClaudeAuthStatus? {
        val result = runBounded(runtime.builder(listOf("auth", "status", "--json"), runtime.statusFolder()), timeout) ?: return null
        if (result.exitCode !in 0..1) return null
        return runCatching { authJson.decodeFromString<ClaudeAuthStatus>(result.output) }.getOrNull()
            ?.takeIf { it.loggedIn == (result.exitCode == 0) }
    }
}

private val authJson = Json { ignoreUnknownKeys = true }
