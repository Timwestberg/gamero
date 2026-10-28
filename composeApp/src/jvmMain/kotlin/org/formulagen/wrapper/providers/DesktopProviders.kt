package org.formulagen.wrapper.providers

import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderCapability
import org.formulagen.wrapper.bridge.ProviderList
import org.formulagen.wrapper.bridge.ProviderModels
import org.formulagen.wrapper.bridge.ProviderScope
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules

/**
 * The `providers.*` capability: one active profile's Codex account process, with credentials kept
 * off the bridge. [onDisconnect] runs before a sign-out, so open chats stop using the account.
 * Only Codex is offered so far.
 */
internal class DesktopProviders(
    private val root: Path,
    private val executable: Path?,
    private val openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
    private val onDisconnect: () -> Unit = {},
    private val createConnection: (CodexRuntime, (URI) -> Unit) -> ProviderConnection = { runtime, open ->
        runtime.prepare(); CodexConnection(StdioCodexAppServer(runtime), open)
    },
) : ProviderCapability, AutoCloseable {
    private val mutex = Mutex()
    private var activeProfile: String? = null
    private var connection: ProviderConnection? = null
    private var closed = false

    /** The router has validated the scope; resolve the profile's folder before starting a provider. */
    override suspend fun perform(action: ProviderAction, scope: ProviderScope): ProviderStatus = mutex.withLock {
        check(!closed)
        require(scope.runtimeId == RuntimeId.CODEX) { "Only Codex is offered" }
        val profile = scope.profile
        val home = codexProfileHome(root, profile)
        if (action == ProviderAction.DISCONNECT) onDisconnect()
        if (executable == null) return@withLock failure(ProviderState.UNAVAILABLE, "Install Codex on this Mac, then restart Hovel to connect.")
        runInterruptible {
            try {
                if (activeProfile != profile) {
                    connection?.close(); connection = null; activeProfile = profile
                }
                val current = connection ?: createConnection(CodexRuntime(executable, home), openBrowser).also { connection = it }
                current.perform(action)
            } catch (error: InterruptedException) {
                connection?.close(); connection = null
                throw error
            } catch (_: Exception) {
                connection?.close(); connection = null
                failure(ProviderState.ERROR, "Codex connection could not be verified. Check your connection and try again.")
            }
        }
    }

    override suspend fun list(profile: String): ProviderList {
        require(SharedRules.isProfileId(profile)) { "Invalid profile" }
        return ProviderList(listOf(RuntimeId.CODEX))
    }

    override suspend fun models(scope: ProviderScope): ProviderModels {
        require(SharedRules.isProfileId(scope.profile)) { "Invalid profile" }
        return ProviderModels(emptyList(), scope.runtimeId)
    }

    /** Expose a bounded display error instead of raw provider output or credentials. */
    private fun failure(state: ProviderState, message: String) = ProviderStatus(RuntimeId.CODEX, state, message = message)

    /** Close the owned account process after the native bridge has stopped taking requests. */
    override fun close() = kotlinx.coroutines.runBlocking {
        mutex.withLock { closed = true; connection?.close(); connection = null }
    }
}
