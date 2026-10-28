package org.formulagen.wrapper.providers

import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderDetail
import org.formulagen.wrapper.bridge.ProviderModel
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.bridge.RuntimeId

/**
 * One runtime behind `providers.*`. Calls block (they may run a process), and [DesktopProviders]
 * runs one at a time per runtime, off the bridge thread.
 */
internal interface RuntimeProvider : AutoCloseable {
    val id: RuntimeId
    /** The connection's display status after [action]. */
    fun status(profile: String, action: ProviderAction): ProviderStatus
    /** The models a team member on this runtime may use. */
    fun models(profile: String): List<ProviderModel>
}

/**
 * Codex: one account process for the active profile, with credentials kept native. [onDisconnect]
 * runs before a sign-out, so open chats stop using the account. Its model list comes from that same
 * process and answers again for a minute.
 */
internal class CodexProvider(
    private val root: Path,
    private val executable: Path?,
    private val openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
    private val onDisconnect: () -> Unit = {},
    private val createConnection: (CodexRuntime, (URI) -> Unit) -> ProviderConnection = { runtime, open ->
        runtime.prepare(); CodexConnection(StdioCodexAppServer(runtime), open)
    },
    private val clock: Clock = Clock.systemUTC(),
) : RuntimeProvider {
    override val id = RuntimeId.CODEX
    private var activeProfile: String? = null
    private var connection: ProviderConnection? = null
    private val models = mutableMapOf<String, Pair<Long, List<ProviderModel>>>()

    override fun status(profile: String, action: ProviderAction): ProviderStatus {
        val home = codexProfileHome(root, profile)
        if (action == ProviderAction.DISCONNECT) onDisconnect()
        if (executable == null) return codex(ProviderState.UNAVAILABLE, ProviderDetail.NOT_INSTALLED, id.copy.notInstalled)
        return try {
            connection(profile, home, executable).perform(action).also { if (action == ProviderAction.DISCONNECT) models.remove(profile) }
        } catch (error: InterruptedException) {
            drop(); throw error
        } catch (_: Exception) {
            drop(); codex(ProviderState.ERROR, ProviderDetail.UNKNOWN, "Codex connection could not be verified. Check your connection and try again.")
        }
    }

    override fun models(profile: String): List<ProviderModel> {
        val home = codexProfileHome(root, profile)
        val executable = executable ?: return emptyList()
        val now = clock.millis()
        models[profile]?.takeIf { now - it.first in 0 until MODELS_TTL_MILLIS }?.let { return it.second }
        // A failed call may have stopped the account process, so the next one starts a new one.
        return try { connection(profile, home, executable).models().also { models[profile] = now to it } }
        catch (error: Exception) { drop(); throw error }
    }

    /** The active profile's account process; another profile's is closed first. */
    private fun connection(profile: String, home: Path, executable: Path): ProviderConnection {
        if (activeProfile != profile) { drop(); activeProfile = profile }
        return connection ?: createConnection(CodexRuntime(executable, home), openBrowser).also { connection = it }
    }

    private fun drop() { connection?.close(); connection = null }

    /** Expose a bounded display error instead of raw provider output or credentials. */
    private fun codex(state: ProviderState, detail: ProviderDetail, message: String) = ProviderStatus(id, state, message = message, detail = detail)

    override fun close() = drop()
}

/** Claude Code: its one sign-in, read through the [connection] chat readiness shares; Hovel never signs in or out. */
internal class ClaudeProvider(private val connection: ClaudeConnection) : RuntimeProvider {
    override val id = RuntimeId.CLAUDE_CODE

    /** Sign-in and sign-out happen in Claude Code, so the other actions answer the status with where to do them. */
    override fun status(profile: String, action: ProviderAction): ProviderStatus =
        connection.status().let { if (action == ProviderAction.STATUS) it else it.copy(message = "Sign in and out in Claude Code.") }

    /** Claude Code's three aliases, Sonnet first and the default. */
    override fun models(profile: String): List<ProviderModel> =
        CLAUDE_MODELS.map { ProviderModel(it.displayName, it.modelId == CLAUDE_FALLBACK_MODEL, it.modelId) }

    override fun close() = Unit
}

/** How long Codex's model list answers again for a profile. */
internal const val MODELS_TTL_MILLIS = 60_000L
