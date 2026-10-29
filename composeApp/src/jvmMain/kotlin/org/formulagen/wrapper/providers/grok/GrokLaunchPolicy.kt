package org.formulagen.wrapper.providers.grok

import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.serialization.json.*
import org.formulagen.wrapper.process.runBounded

/**
 * How one Grok agent process launches and stays acceptable: an explicit agent profile, run with exactly this
 * launch's environment. [GrokLaunch] isolates talk; [GrokWorkLaunch] works in the player's own setup.
 * Sessions and the account check depend only on this surface.
 */
internal sealed class GrokLaunchPolicy(
    private val executable: Path,
    private val environment: Map<String, String>,
    /** The agent's working folder, which its sessions also send as `cwd`. */
    val workspace: Path,
    /** This launch's agent profile; the launch removes it on [close]. */
    protected val profile: Path,
) : AutoCloseable {
    /** The accepted release, then the runtime's own inspection of this launch, within [timeout]; throws if either is refused. */
    abstract fun verify(timeout: Duration)

    /** The cheap check of this launch's accepted configuration, repeated between startup steps. */
    abstract fun recheck()

    /** A local ACP process with this launch's explicit profile, and no shared leader, subagents or web search. */
    fun agentBuilder(): ProcessBuilder = builder(listOf(GROK_NO_AUTO_UPDATE, "--no-subagents", "--disable-web-search",
        "agent", "--no-leader", "--agent-profile", profile.toString(), "stdio"))

    /**
     * Start this launch's agent, allowing only [methods]; the caller reaps it with [closeAfter] however startup ends.
     * Grok's exact internal watcher replies are accepted and never reach a caller. [onRequest] null refuses every
     * client request.
     */
    fun startAgent(methods: Set<String>, onNotification: (String, JsonObject) -> Unit = { _, _ -> },
        onRequest: ((String, JsonObject, (JsonObject?) -> Unit) -> Unit)? = null): GrokAcp =
        GrokAcp(agentBuilder(), methods, onNotification, onRequest, allowReloadResponses = true)

    /**
     * Authenticate the cached sign-in on [rpc], never interactively; [expectedAccount], when given, must be the
     * verified account. This launch is rechecked afterwards.
     */
    fun authenticate(rpc: GrokAcp, initialized: JsonObject, budget: GrokBudget, expectedAccount: String? = null): GrokAccount {
        val account = grokAccount(rpc, initialized, budget)
        check(expectedAccount == null || account.fingerprint == expectedAccount) { "Grok account changed" }
        recheck()
        return account
    }

    /** The executable in [workspace] with exactly this launch's environment: nothing else is inherited. */
    protected fun builder(arguments: List<String>): ProcessBuilder = ProcessBuilder(listOf(executable.toString()) + arguments)
        .directory(workspace.toFile()).apply { environment().clear(); environment().putAll(environment) }

    /** `grok --version` must print exactly the accepted release before [inspect] runs; both share [budget]. */
    protected fun inspectAcceptedRelease(budget: GrokBudget): JsonObject {
        val version = runBounded(builder(listOf("--version")), budget.remaining(), 256)
        check(version?.exitCode == 0 && grokVersionSupported(version.output)) { "Untested Grok version" }
        return inspect(budget)
    }

    /** The runtime's own report on this launch: `inspect --json`, with [flags] before it, or [failure] when it fails. */
    protected fun inspect(budget: GrokBudget, flags: List<String> = emptyList(),
        failure: String = "Grok inspection unavailable"): JsonObject {
        val inspected = runBounded(builder(listOf(GROK_NO_AUTO_UPDATE) + flags + listOf("inspect", "--json")), budget.remaining())
        check(inspected?.exitCode == 0) { failure }
        return Json.parseToJsonElement(inspected.output).jsonObject
    }

    /**
     * Close and reap [rpc] before removing this launch's files; an interrupted caller still waits, then keeps its
     * interrupt. A cleanup that fails is [GrokCleanupUnconfirmed]; an interruption while waiting stays an interruption.
     */
    fun closeAfter(rpc: GrokAcp?) {
        val interrupted = Thread.interrupted()
        try { rpc?.close(); rpc?.awaitClosed(); close() }
        catch (error: InterruptedException) { throw error }
        catch (error: Exception) { throw GrokCleanupUnconfirmed(error) }
        finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}

/** A Grok process or its launch files could not be confirmed gone, so nothing may start over them. */
internal class GrokCleanupUnconfirmed(cause: Exception) : IllegalStateException("Grok process cleanup was not confirmed", cause)
