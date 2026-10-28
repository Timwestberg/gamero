package org.formulagen.wrapper.providers

import java.nio.file.Path
import java.time.Clock
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderDetail
import org.formulagen.wrapper.bridge.ProviderModel
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.bridge.ProviderUsage
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules

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
    /** Read subscription limits without a prompt, tool execution or a paid turn. */
    fun usage(profile: String): ProviderUsage
}

/**
 * Codex: the player's own Codex sign-in, which every profile on this Mac shares, read through one
 * account process. Hovel never signs in or out ([CodexConnection]). The model list comes from that
 * same process and answers again for a minute.
 */
internal class CodexProvider(
    private val root: Path,
    private val executable: Path?,
    private val createConnection: (CodexRuntime) -> ProviderConnection = { runtime -> CodexConnection(StdioCodexAppServer(runtime)) },
    private val clock: Clock = Clock.systemUTC(),
) : RuntimeProvider {
    override val id = RuntimeId.CODEX
    private var connection: ProviderConnection? = null
    private var models: Pair<Long, List<ProviderModel>>? = null

    override fun status(profile: String, action: ProviderAction): ProviderStatus {
        require(SharedRules.isProfileId(profile)) { "Invalid profile" }
        if (executable == null) return codex(ProviderState.UNAVAILABLE, ProviderDetail.NOT_INSTALLED, id.copy.notInstalled)
        return try {
            connection(executable).perform(action)
        } catch (error: InterruptedException) {
            drop(); throw error
        } catch (_: Exception) {
            drop(); codex(ProviderState.ERROR, ProviderDetail.UNKNOWN, "Codex status could not be checked. Try again.")
        }
    }

    override fun models(profile: String): List<ProviderModel> {
        require(SharedRules.isProfileId(profile)) { "Invalid profile" }
        val executable = executable ?: return emptyList()
        val now = clock.millis()
        models?.takeIf { now - it.first in 0 until MODELS_TTL_MILLIS }?.let { return it.second }
        // A failed call may have stopped the account process, so the next one starts a new one.
        return try { connection(executable).models().also { models = now to it } }
        catch (error: Exception) { drop(); throw error }
    }

    private fun connection(executable: Path): ProviderConnection =
        connection ?: createConnection(CodexRuntime(executable, root)).also { connection = it }

    override fun usage(profile: String): ProviderUsage {
        require(SharedRules.isProfileId(profile)) { "Invalid profile" }
        val executable = executable ?: return ProviderUsage(id, clock.millis())
        return try { connection(executable).usage() }
        catch (error: Exception) { drop(); throw error }
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

    /** The installed Claude Code's catalog, including models added after Hovel was built. */
    override fun models(profile: String): List<ProviderModel> = connection.models()

    override fun usage(profile: String): ProviderUsage = connection.usage()

    override fun close() = Unit
}

/** How long an installed runtime's model list answers again. */
internal const val MODELS_TTL_MILLIS = 60_000L
