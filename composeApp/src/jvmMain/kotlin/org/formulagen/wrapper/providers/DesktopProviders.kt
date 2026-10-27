package org.formulagen.wrapper.providers

import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Native registry owns one active profile's account process and keeps credentials off the bridge. */
internal class DesktopProviders(
    private val root: Path,
    private val executable: Path?,
    private val openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
    private val createConnection: (CodexRuntime, (URI) -> Unit) -> ProviderConnection = { runtime, open ->
        runtime.prepare(); CodexConnection(StdioCodexAccountRpc(runtime), open)
    },
) : AutoCloseable {
    private val mutex = Mutex()
    private var activeProfile: String? = null
    private var connection: ProviderConnection? = null
    private var closed = false

    /** Validate routing before resolving any path or starting a provider. */
    suspend fun dispatch(method: String, args: JsonObject): JsonObject = mutex.withLock {
        check(!closed)
        require(method in setOf("providers.status", "providers.connect", "providers.cancel", "providers.reopen", "providers.disconnect"))
        require(args.keys == setOf("providerId", "profile") && args["providerId"] == JsonPrimitive("codex"))
        val profile = (args["profile"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected a profile")
        val home = codexProfileHome(root, profile)
        if (executable == null) return@withLock failure("unavailable", "Install Codex on this Mac, then restart Hovel to connect.")
        runInterruptible {
            try {
                if (activeProfile != profile) {
                    connection?.close(); connection = null; activeProfile = profile
                }
                val current = connection ?: createConnection(CodexRuntime(executable, home), openBrowser).also { connection = it }
                current.dispatch(method.removePrefix("providers."))
            } catch (error: InterruptedException) {
                connection?.close(); connection = null
                throw error
            } catch (_: Exception) {
                connection?.close(); connection = null
                failure("error", "Codex connection could not be verified. Check your connection and try again.")
            }
        }
    }

    /** Expose a bounded display error instead of raw provider output or credentials. */
    private fun failure(state: String, message: String) = buildJsonObject {
        put("providerId", "codex"); put("state", state); put("message", message)
    }

    /** Close the owned account process after the native bridge has stopped taking requests. */
    override fun close() = kotlinx.coroutines.runBlocking {
        mutex.withLock { closed = true; connection?.close(); connection = null }
    }
}
