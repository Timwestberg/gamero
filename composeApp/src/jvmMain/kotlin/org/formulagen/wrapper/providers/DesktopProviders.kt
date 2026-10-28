package org.formulagen.wrapper.providers

import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderCapability
import org.formulagen.wrapper.bridge.ProviderLimits
import org.formulagen.wrapper.bridge.ProviderList
import org.formulagen.wrapper.bridge.ProviderModel
import org.formulagen.wrapper.bridge.ProviderModels
import org.formulagen.wrapper.bridge.ProviderScope
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules

/**
 * The `providers.*` capability, routed by runtime with credentials kept off the bridge. Codex has
 * one account process for the active profile; [onDisconnect] runs before its sign-out, so open
 * chats stop using the account. Claude Code is read through [claude], which is null when the
 * release gate keeps Claude off; every profile shares its one sign-in, which Hovel never changes.
 * Its status is kept for [CLAUDE_STATUS_TTL_MILLIS], since several views ask for it when the window
 * regains focus.
 */
internal class DesktopProviders(
    private val root: Path,
    private val executable: Path?,
    private val claude: ProviderConnection? = null,
    private val openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
    private val onDisconnect: () -> Unit = {},
    private val createConnection: (CodexRuntime, (URI) -> Unit) -> ProviderConnection = { runtime, open ->
        runtime.prepare(); CodexConnection(StdioCodexAppServer(runtime), open)
    },
    private val codexModels: (CodexRuntime) -> List<ProviderModel> = { runtime ->
        runtime.prepare(); StdioCodexAppServer(runtime).use(::codexModelList)
    },
    private val clock: Clock = Clock.systemUTC(),
) : ProviderCapability, AutoCloseable {
    private val mutex = Mutex()
    /** Claude's status runs its own short processes, so it never waits behind a Codex sign-in. */
    private val claudeMutex = Mutex()
    private var claudeStatus: Pair<Long, ProviderStatus>? = null
    private var activeProfile: String? = null
    private var connection: ProviderConnection? = null
    private var closed = false

    /** The router has validated the scope; resolve the profile's folder before starting a provider. */
    override suspend fun perform(action: ProviderAction, scope: ProviderScope): ProviderStatus = when (scope.runtimeId) {
        RuntimeId.CODEX -> codex(action, scope.profile)
        RuntimeId.CLAUDE_CODE -> claudeMutex.withLock {
            check(!closed)
            require(SharedRules.isProfileId(scope.profile)) { "Invalid profile" }
            val connection = claude ?: return@withLock ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.UNAVAILABLE,
                message = "Claude is not available in this version of Hovel.")
            val now = clock.millis()
            val status = claudeStatus?.takeIf { now - it.first in 0 until CLAUDE_STATUS_TTL_MILLIS }?.second
                ?: runInterruptible { connection.perform(ProviderAction.STATUS) }.also { claudeStatus = now to it }
            // Sign-in and sign-out happen in Claude Code, so the other actions answer the status with where to do them.
            if (action == ProviderAction.STATUS) status else status.copy(message = "Sign in and out in Claude Code.")
        }
    }

    /** Codex always has a card, so a Mac without it can say how to install it; Claude only when the release gate lets it. */
    override suspend fun list(profile: String): ProviderList {
        require(SharedRules.isProfileId(profile)) { "Invalid profile" }
        return ProviderList(listOfNotNull(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE.takeIf { claude != null }))
    }

    /**
     * Codex reports the models of the person's plan; Claude's are its three aliases, Sonnet first and
     * the default. Nothing known is an empty list, never an error: the page still offers the default.
     */
    override suspend fun models(scope: ProviderScope): ProviderModels = when (scope.runtimeId) {
        RuntimeId.CODEX -> {
            val home = codexProfileHome(root, scope.profile)
            val models = executable?.let {
                try { runInterruptible { codexModels(CodexRuntime(it, home)) } }
                catch (error: Exception) { if (error is InterruptedException || error is kotlinx.coroutines.CancellationException) throw error; emptyList() }
            }.orEmpty()
            ProviderModels(models, RuntimeId.CODEX)
        }
        RuntimeId.CLAUDE_CODE -> {
            require(SharedRules.isProfileId(scope.profile)) { "Invalid profile" }
            val models = if (claude == null) emptyList() else CLAUDE_MODELS.map { ProviderModel(it.displayName, it.modelId == CLAUDE_FALLBACK_MODEL, it.modelId) }
            ProviderModels(models, RuntimeId.CLAUDE_CODE)
        }
    }

    private suspend fun codex(action: ProviderAction, profile: String): ProviderStatus = mutex.withLock {
        check(!closed)
        val home = codexProfileHome(root, profile)
        if (action == ProviderAction.DISCONNECT) onDisconnect()
        if (executable == null) return@withLock codexFailure(ProviderState.UNAVAILABLE, "Install Codex on this Mac, then restart Hovel to connect.")
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
                codexFailure(ProviderState.ERROR, "Codex connection could not be verified. Check your connection and try again.")
            }
        }
    }

    /** Expose a bounded display error instead of raw provider output or credentials. */
    private fun codexFailure(state: ProviderState, message: String) = ProviderStatus(RuntimeId.CODEX, state, message = message)

    /** Close the owned account process after the native bridge has stopped taking requests. */
    override fun close() = kotlinx.coroutines.runBlocking<Unit> {
        mutex.withLock { closed = true; connection?.close(); connection = null }
        claudeMutex.withLock { claude?.close() }
    }
}

/** How long a Claude status answers again without running Claude Code. */
internal const val CLAUDE_STATUS_TTL_MILLIS = 5_000L

/**
 * The models Codex offers the signed-in plan, for a team member to choose from: visible ones whose
 * id can be a `modelId`, the runtime's default marked, at most [ProviderLimits.MODELS].
 */
internal fun codexModelList(rpc: CodexAppServer): List<ProviderModel> {
    val models = mutableListOf<ProviderModel>()
    var cursor: String? = null
    var pages = 0
    while (pages++ < 4 && models.size < ProviderLimits.MODELS) {
        val result = rpc.call("model/list", buildJsonObject { put("includeHidden", false); cursor?.let { put("cursor", it) } })
        for (item in (result["data"] as? JsonArray).orEmpty()) {
            val model = item as? JsonObject ?: continue
            if ((model["hidden"] as? JsonPrimitive)?.booleanOrNull != false) continue
            val id = (model["model"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (!SharedRules.isModelId(id) || models.any { it.modelId == id }) continue
            val name = (model["displayName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= ProviderLimits.DISPLAY_NAME } ?: id
            models += ProviderModel(name, (model["isDefault"] as? JsonPrimitive)?.booleanOrNull == true, id)
        }
        cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull ?: break
    }
    return models.take(ProviderLimits.MODELS)
}
