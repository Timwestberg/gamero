package org.formulagen.wrapper.providers

import java.nio.file.Files
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderStatus

/**
 * Claude Code's sign-in, read with `claude auth status --json`. It never runs sign-in or sign-out:
 * Claude Code owns both, and signing out from Hovel would sign the player out everywhere.
 * [runtime] is null when Hovel found no Claude Code.
 */
internal class ClaudeConnection(private val runtime: ClaudeRuntime?, private val timeout: Duration = CLAUDE_STATUS_TIMEOUT) : ProviderConnection {
    /** Sign-in and sign-out stay in Claude Code, so every other action answers the status with where to do them. */
    override fun perform(action: ProviderAction): ProviderStatus =
        status().let { if (action == ProviderAction.STATUS) it else it.copy(message = "Sign in and out in Claude Code.") }

    /** Checked fresh each time, so an update or removal while Hovel runs shows on the next check. */
    fun status(): ProviderStatus {
        val runtime = runtime?.takeIf { Files.isExecutable(it.executable) } ?: return claudeProviderStatus(false, null, null)
        val version = runtime.version(timeout)
        if (version == null || version < MIN_CLAUDE_VERSION) return claudeProviderStatus(true, version, null)
        return claudeProviderStatus(true, version, auth(runtime))
    }

    /** Exit 0 is signed in and 1 signed out; any other exit, a timeout, or output that disagrees with the exit is unknown. */
    private fun auth(runtime: ClaudeRuntime): ClaudeAuthStatus? {
        val result = runBounded(runtime.builder(listOf("auth", "status", "--json"), runtime.statusFolder()), timeout) ?: return null
        if (result.exitCode !in 0..1) return null
        return runCatching { authJson.decodeFromString<ClaudeAuthStatus>(result.output) }.getOrNull()
            ?.takeIf { it.loggedIn == (result.exitCode == 0) }
    }

    /** Each status check is its own short process; nothing stays open. */
    override fun close() = Unit
}

private val authJson = Json { ignoreUnknownKeys = true }
